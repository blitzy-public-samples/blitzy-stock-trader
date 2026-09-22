/*
       Copyright 2025 Kyndryl, All Rights Reserved

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.fx.ExchangeRateSource;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.fx.LegacyRateTableSource;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.load.LegacyLoader;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.MigrationReconciliation;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.MigrationRun;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.ReconciliationStatus;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.VarianceKind;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.shadow.ShadowComparator;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.CashAccountRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.LegacyRateTableRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.MigrationReconciliationRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.MigrationRunRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.PostgresTestSupport;

/*
 * FIXTURES ONLY, AND THAT IS A PROHIBITION RATHER THAN A CONVENIENCE (AAP 0.3.1-0.3.2, 0.10.6). "Do not run the
 * dual-run mechanism against the live legacy system": this class replays the two committed synthetic captures under
 * src/test/resources/fixtures/shadow and nothing else. It opens no connection to DB2 for z/OS, reads no VSAM data
 * set, speaks to no CICS region, and its passing proves nothing about production traffic. A live shadow window needs
 * capture infrastructure, sign-off and a rollback plan that belong to docs/operational-runbook.md step 2, which this
 * deliverable documents and deliberately does not execute.
 *
 * WHY THE "tool" PROFILE STAYS OFF even though this exercises the tooling. migration/MigrationToolRunner is an
 * ApplicationRunner carrying @Profile("tool"), @SpringBootTest executes runners, and the runner ends in
 * System.exit(SpringApplication.exit(...)) - so activating the profile would terminate the Failsafe JVM mid-suite
 * and report as an infrastructure failure rather than a test result. ShadowComparator and LegacyLoader are
 * deliberately un-profiled for exactly this reason, so the comparator is driven directly and the run row's verdict
 * is written here, the way the runner writes it.
 *
 * WHY EXACTLY TWO TESTS. AAP 0.7.6 caps the reconciliation-against-characterization group at six tests across four
 * classes and allocates this file two of them: matched -> zero rows, seeded -> exactly the seeded rows. Exploratory
 * cases, parameterized matrices and redundant variants are not to be generated.
 */
/** Proves the dual-run comparator on the committed shadow captures: zero rows for matched, exactly three for seeded. */
// NONE, not MOCK: the comparator replays through the service layer and never over HTTP (AAP 0.8.2), so a servlet
// context and a mock dispatcher would add moving parts to a comparison that does not involve them.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // MANDATORY here, not inherited: ShadowComparator binds tool.rate-source with @Value, and
                // application-test.yml owns no tool.* key (application-tool.yml does, and the tool profile is off
                // above). legacy-table is the AAP 0.12.5 parity gate - both sides priced from the same staged RATES -
                // so a balance difference cannot be a rate difference and no RATE_SOURCE row can arise.
                "tool.rate-source=legacy-table"
        })
class ShadowComparatorIT extends PostgresTestSupport {

    private static final String MATCHED_SHADOW_FIXTURES = "fixtures/shadow/matched";

    private static final String SEEDED_SHADOW_FIXTURES = "fixtures/shadow/seeded-mismatch";

    /** The six-account corpus and the three rates every replay in this class starts from. */
    private static final String LEGACY_EXPORT_FIXTURES = "fixtures/legacy-export/matched";

    private static final int CORPUS_ACCOUNTS = 6;

    private static final int CORPUS_RATES = 3;

    @Autowired
    private ShadowComparator shadowComparator;

    @Autowired
    private LegacyLoader legacyLoader;

    @Autowired
    private MigrationRunRepository migrationRuns;

    @Autowired
    private MigrationReconciliationRepository reconciliations;

    @Autowired
    private CashAccountRepository accounts;

    @Autowired
    private LegacyRateTableRepository legacyRates;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StagedLegacyRateSource stagedRates;

    // The drift guard AAP 0.10.1 requires of the reconciliation classes: the fixtures' expected values are derived
    // from the characterization document's formula, so a run against a missing or gutted document would be asserting
    // arithmetic whose authority no longer exists.
    @BeforeAll
    static void characterizationDocumentIsPresent() {
        CharacterizationDocPresentTest.requireCharacterizationDocument();
    }

    /**
     * Restores the corpus, the staged rates and an empty findings table before each replay.
     */
    // ledger_entry IS DELIBERATELY ABSENT FROM THIS RESET. The trigger ledger_entry_immutable rejects every UPDATE
    // and DELETE (schema/cash-account-schema.sql), which is the audit guarantee of AAP 0.7.4 and not an obstacle to
    // work around - so the replay's CREDIT/DEBIT/ACCOUNT_* rows accumulate across tests and across the sibling *IT
    // classes sharing this JVM-wide container. That is harmless because every assertion below is scoped by run_id or
    // by owner and none counts ledger rows. Each reset therefore takes a FRESH run id, which also satisfies the
    // partial unique index uq_ledger_entry_migration_load on (run_id, owner).
    @BeforeEach
    void restoreStartingState() {
        // FK-safe order: migration_reconciliation.run_id references migration_run
        // (fk_migration_reconciliation_run); nothing else here is referenced by anything.
        jdbcTemplate.update("DELETE FROM migration_reconciliation");
        jdbcTemplate.update("DELETE FROM migration_run");
        jdbcTemplate.update("DELETE FROM legacy_history");
        jdbcTemplate.update("DELETE FROM legacy_rate_table");
        jdbcTemplate.update("DELETE FROM cash_reservation");
        jdbcTemplate.update("DELETE FROM cash_account");

        Path corpus = fixtureDirectory(LEGACY_EXPORT_FIXTURES);
        MigrationRun corpusLoad = migrationRuns.save(MigrationRun.start(UUID.randomUUID(), UUID.randomUUID(),
                MigrationRun.Mode.LOAD, corpus.toString(), MigrationRun.CharacterizationStatus.DRAFT));

        // The accounts and rates are named explicitly rather than through LoadSources.inDirectory, which would also
        // resolve the history.cp037.bin this fixture directory carries and then demand tool.history-record-length -
        // a property no shadow window needs, since a capture stream is what this class replays rather than the
        // legacy audit file. The charset and zone are consequently inert, and are the tool's own defaults.
        LegacyLoader.LoadResult loaded = legacyLoader.load(corpusLoad,
                new LegacyLoader.LoadSources(corpus.resolve(LegacyExportFormat.CASH_ACCOUNT_FILE),
                        corpus.resolve(LegacyExportFormat.RATE_TABLE_FILE), null, null),
                Charset.forName(LegacyExportFormat.DEFAULT_LEGACY_CHARSET), ZoneOffset.UTC, null);

        assertThat(loaded.migratedRecordCount())
                .as("the corpus load must apply all %d accounts of %s before a replay begins",
                        CORPUS_ACCOUNTS, LEGACY_EXPORT_FIXTURES)
                .isEqualTo(CORPUS_ACCOUNTS);
        assertThat(loaded.varianceCount())
                .as("the matched corpus carries no NULL or out-of-set value, so its load records no finding")
                .isZero();
        assertThat(legacyRates.findByRunId(corpusLoad.runId()))
                .as("the staged USD/EUR/GBP rates are what the cross-currency replays are priced from")
                .hasSize(CORPUS_RATES);

        // WHY A STAGED RATE SOURCE IS WIRED IN AT ALL. RetailCashAccountService injects ExchangeRateSource, and in
        // profile test the only bean is the live FrankfurterExchangeRateClient pointed at the refused port
        // 127.0.0.1:1 (application-test.yml) - so the GREG (GBP) and ERIC (EUR) replays would come back
        // 503 EXCHANGE_RATE_UNAVAILABLE and the matched window would report rejections instead of parity. Parity
        // also has to be judged on the very RATES the legacy arithmetic used (AAP 0.12.5), which is these staged
        // rows, and no test may reach the public exchange-rate API. The run is handed over per reset because the
        // rows belong to this load alone.
        stagedRates.useStagingRun(corpusLoad.runId());
    }

    @Test
    void matchedStreamReplaysWithZeroVariance() {
        Path window = fixtureDirectory(MATCHED_SHADOW_FIXTURES);
        UUID runId = UUID.randomUUID();
        MigrationRun run = migrationRuns.save(MigrationRun.start(runId, UUID.randomUUID(),
                MigrationRun.Mode.SHADOW, window.toString(), MigrationRun.CharacterizationStatus.DRAFT));

        int variances = shadowComparator.compare(run, window);

        assertThat(variances)
                .as("every one of the ten captured replies in %s agrees with the target's replay, across all six "
                        + "legacy request codes A/Q/U/X/C/D (CASH00.cbl:L89-L102)", MATCHED_SHADOW_FIXTURES)
                .isZero();

        // The whole row set for the run, not just its VARIANCE rows: an agreeing capture line gets no row at all -
        // not even a MATCHED one - so "no rows" is the entire assertion and an invented row of any status fails it.
        assertThat(reconciliations.findByRunIdOrderByReconciliationIdAsc(runId))
                .as("a clean window records nothing")
                .isEmpty();
        assertThat(reconciliations.countByRunIdAndStatus(runId, ReconciliationStatus.VARIANCE)).isZero();

        // The comparator sets the three counts through setters and deliberately neither saves nor finishes the run;
        // closing it is the runner's job, reproduced here so the persisted verdict is asserted rather than assumed.
        assertThat(run.legacyRecordCount()).isEqualTo(10);
        assertThat(run.migratedRecordCount()).isEqualTo(10);
        assertThat(run.varianceCount()).isZero();

        run.finish(MigrationRun.Status.CLEAN, run.legacyRecordCount(), run.migratedRecordCount(),
                run.varianceCount());
        migrationRuns.save(run);

        MigrationRun closed = requireRun(runId);
        // CLEAN with variance_count 0 is what MigrationToolRunner turns into exit code 0.
        assertThat(closed.status()).isEqualTo(MigrationRun.Status.CLEAN);
        assertThat(closed.varianceCount()).isZero();

        // Target state moved, rather than the comparator merely declining to find fault: KARRI's debit, GREG's
        // cross-rate credit at the staged 0.79 and ERIC's sub-cent debit at 0.92 all landed, and RAUNAK survived the
        // X-then-A pair at seq 8/9 - ordered that way in the fixture because RAUNAK is already in the corpus, so a
        // create before the delete would collide (409/-803) and destroy the zero-variance guarantee.
        assertThat(balanceOf("KARRI")).isEqualByComparingTo(new BigDecimal("12000.00"));
        assertThat(balanceOf("GREG")).isEqualByComparingTo(new BigDecimal("123535.78"));
        assertThat(balanceOf("ERIC")).isEqualByComparingTo(new BigDecimal("1234567.61"));
        assertThat(balanceOf("RAUNAK")).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(currencyOf("GREG")).isEqualTo("GBP");
        assertThat(currencyOf("ERIC")).isEqualTo("EUR");
    }

    @Test
    void seededStreamProducesExactlyTheThreeSeededRows() {
        Path window = fixtureDirectory(SEEDED_SHADOW_FIXTURES);
        UUID runId = UUID.randomUUID();
        MigrationRun run = migrationRuns.save(MigrationRun.start(runId, UUID.randomUUID(),
                MigrationRun.Mode.SHADOW, window.toString(), MigrationRun.CharacterizationStatus.DRAFT));

        int variances = shadowComparator.compare(run, window);

        // Two, not three: RAUNAK's row is an ACCEPTED_EXCEPTION, and only VARIANCE feeds variance_count and the
        // exit code - collapsing the two would make every characterized legacy quirk look like a migration defect.
        assertThat(variances)
                .as("the seeded window's two outstanding differences are JOHN's balance and RYAN's count")
                .isEqualTo(2);

        List<MigrationReconciliation> rows = reconciliations.findByRunIdOrderByReconciliationIdAsc(runId);

        // Size first, over the FULL row set for the run: a missing row means the comparator has a hole in it and a
        // fourth means it invented one, and AAP 0.10.3 fixes this set at exactly three.
        assertThat(rows)
                .as("exactly the three seeded rows of %s/MANIFEST.md, and nothing else", SEEDED_SHADOW_FIXTURES)
                .hasSize(3);
        assertThat(rows)
                .extracting(MigrationReconciliation::owner, MigrationReconciliation::varianceKind,
                        MigrationReconciliation::status)
                .containsExactlyInAnyOrder(
                        tuple("JOHN", VarianceKind.BALANCE, ReconciliationStatus.VARIANCE),
                        tuple("RAUNAK", VarianceKind.REJECTED_BY_TARGET, ReconciliationStatus.ACCEPTED_EXCEPTION),
                        tuple("RYAN", VarianceKind.TRANSACTION_COUNT, ReconciliationStatus.VARIANCE));

        // JOHN: the request is byte-identical to the matched window's, and only the captured reply differs - the
        // cleanest possible balance seed. variance = migrated - legacy is the module-wide convention, so the target
        // holding a dime less than the reply claimed is -0.10 and not +0.10.
        MigrationReconciliation john = row(rows, "JOHN", VarianceKind.BALANCE);
        assertThat(john.legacyBalance()).isEqualByComparingTo(new BigDecimal("1250.60"));
        assertThat(john.migratedBalance()).isEqualByComparingTo(new BigDecimal("1250.50"));
        assertThat(john.variance()).isEqualByComparingTo(new BigDecimal("-0.10"));
        assertThat(john.variance().scale()).isEqualTo(2);
        assertThat(john.legacyValue()).isEqualTo("1250.60");
        assertThat(john.migratedValue()).isEqualTo("1250.50");

        // RAUNAK: 100.00 - 1.00 x 150.00 is raw -50.00, which the unsigned WS-CALC PIC 9(7)V99 stored as its
        // absolute value 50.00 (CASH00.cbl:L17 field, L255-L256 COMPUTE). The target refuses the over-debit with
        // 422 INSUFFICIENT_FUNDS - the deliberate behavioural improvement the user authorized (AAP 0.4.6, 0.14.2) -
        // so the row is evidence of an authorized deviation, which is why it is an ACCEPTED_EXCEPTION and not a
        // defect. Nothing is asserted about a migrated balance: the target wrote none.
        MigrationReconciliation raunak = row(rows, "RAUNAK", VarianceKind.REJECTED_BY_TARGET);
        assertThat(raunak.legacyValue()).isEqualTo("50.00");
        assertThat(raunak.legacyBalance()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(raunak.migratedValue()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(raunak.migratedBalance()).isNull();
        assertThat(raunak.variance()).isNull();
        assertThat(balanceOf("RAUNAK"))
                .as("a refused debit leaves the balance exactly as the corpus load left it")
                .isEqualByComparingTo(new BigDecimal("100.00"));

        // RYAN: the seed is the reply at seq 7 that no transaction pairs with. The comparator joins on seq plus the
        // normalized owner rather than on file order - EBCDIC and UTF-8 collate differently (AAP 0.12.2), so an
        // ordinal pairing would compare unrelated lines - which is what lets an unpaired successful reply count
        // toward the legacy total without being replayed: five counted replies against four replayed transactions.
        // A target shortfall cannot be explained by the DUPREC lower bound (CASH00.cbl:L124), hence VARIANCE.
        MigrationReconciliation ryan = row(rows, "RYAN", VarianceKind.TRANSACTION_COUNT);
        assertThat(ryan.legacyValue()).isEqualTo("5");
        assertThat(ryan.migratedValue()).isEqualTo("4");
        // A count is not money: rendering 0.00 on this row would read as monetary agreement.
        assertThat(ryan.legacyBalance()).isNull();
        assertThat(ryan.migratedBalance()).isNull();
        assertThat(ryan.variance()).isNull();

        // The absences the row set encodes, asserted rather than inferred from the size alone: KARRI, GREG and ERIC
        // are untouched by this stream; RAUNAK's refused line still counts as processed, so its counts agree at 1/1
        // and it gets no TRANSACTION_COUNT row; and no RATE_SOURCE row can arise under tool.rate-source=legacy-table.
        assertThat(rows).extracting(MigrationReconciliation::owner)
                .doesNotContain("KARRI", "GREG", "ERIC");
        assertThat(rows)
                .filteredOn(candidate -> candidate.varianceKind() == VarianceKind.TRANSACTION_COUNT)
                .extracting(MigrationReconciliation::owner)
                .containsExactly("RYAN");
        assertThat(rows).extracting(MigrationReconciliation::varianceKind)
                .doesNotContain(VarianceKind.RATE_SOURCE, VarianceKind.STATE, VarianceKind.CURRENCY);

        assertThat(run.legacyRecordCount())
                .as("seven captured replies, one of them unpaired")
                .isEqualTo(7);
        assertThat(run.migratedRecordCount())
                .as("six replayed transactions, counted as attempted rather than accepted")
                .isEqualTo(6);
        assertThat(run.varianceCount()).isEqualTo(2);

        run.finish(MigrationRun.Status.VARIANCE, run.legacyRecordCount(), run.migratedRecordCount(),
                run.varianceCount());
        migrationRuns.save(run);

        MigrationRun closed = requireRun(runId);
        // VARIANCE with variance_count 2 is what MigrationToolRunner turns into exit code 2. The third row raises
        // neither, deliberately: an accepted exception is recorded for the evidence trail and never counted.
        assertThat(closed.status()).isEqualTo(MigrationRun.Status.VARIANCE);
        assertThat(closed.varianceCount()).isEqualTo(2);
        assertThat(reconciliations.countByRunIdAndStatus(runId, ReconciliationStatus.ACCEPTED_EXCEPTION))
                .isEqualTo(1);
    }

    private MigrationRun requireRun(UUID runId) {
        return migrationRuns.findById(runId)
                .orElseGet(() -> fail("the run row %s must be readable back after it was closed".formatted(runId)));
    }

    // The account's type is never named, so this class imports nothing from the domain package: the repository's
    // declared return type is the whole contract these two helpers need.
    private BigDecimal balanceOf(String owner) {
        return accounts.findByOwner(owner)
                .map(account -> account.availableBalance().amount())
                .orElseGet(() -> fail("the replay must leave account %s in place".formatted(owner)));
    }

    private String currencyOf(String owner) {
        return accounts.findByOwner(owner)
                .map(account -> account.currency())
                .orElseGet(() -> fail("the replay must leave account %s in place".formatted(owner)));
    }

    /**
     * The one row of a kind for an owner, failing rather than returning empty when the set does not hold it.
     */
    private static MigrationReconciliation row(List<MigrationReconciliation> rows, String owner, VarianceKind kind) {
        return rows.stream()
                .filter(candidate -> owner.equals(candidate.owner()) && candidate.varianceKind() == kind)
                .reduce((first, second) -> fail(
                        "exactly one %s row is expected for %s, and the run recorded more".formatted(kind, owner)))
                .orElseGet(() -> fail("the run recorded no %s row for %s; rows were %s".formatted(kind, owner, rows)));
    }

    /**
     * Resolves a test-classpath fixture directory to a filesystem path.
     *
     * <p>Fails rather than skips when a fixture is absent: a comparison with nothing to replay proves nothing, and
     * a skipped test reports as success.
     */
    private static Path fixtureDirectory(String resource) {
        URL location = ShadowComparatorIT.class.getClassLoader().getResource(resource);
        if (location == null) {
            return fail(("the fixture directory %s is not on the test classpath; it is required by this test and is"
                    + " expected under src/test/resources").formatted(resource));
        }
        Path directory;
        try {
            directory = Path.of(location.toURI());
        } catch (URISyntaxException | FileSystemNotFoundException | IllegalArgumentException e) {
            return fail("the fixture %s resolved to %s, which is not a readable filesystem path"
                    .formatted(resource, location), e);
        }
        if (!Files.isDirectory(directory)) {
            return fail("the fixture %s resolved to %s, which is not a directory".formatted(resource, directory));
        }
        return directory;
    }

    /**
     * Wires the production staged-rate lookup in as the replay's rate source.
     */
    // A nested @TestConfiguration, so it applies to this class alone and fx/ExchangeRateSourceWiringTest still sees
    // the deployed wiring - exactly one ExchangeRateSource bean, the live client - in the default profile.
    @TestConfiguration
    static class StagedRateSourceConfiguration {

        // @Profile("tool") on LegacyRateTableSource is a containment boundary for COMPONENT SCANNING and is not
        // evaluated when the class is instantiated explicitly here, so the production implementation is reused
        // verbatim rather than reimplemented: the five-character key truncation of CASH00.cbl:L213/L247, the
        // fail-closed answer to an absent or null RATES row, and the unrescaled return that leaves domain.Money
        // holding the single truncation point are all its behaviour, not this test's. The batch id is empty because
        // the run-explicit overload it is driven through never consults it.
        @Bean
        @Primary
        StagedLegacyRateSource stagedLegacyRateSource(LegacyRateTableRepository legacyRates,
                                                      MigrationRunRepository migrationRuns) {
            return new StagedLegacyRateSource(new LegacyRateTableSource(legacyRates, migrationRuns, ""));
        }
    }

    /**
     * The staged rate table as an {@link ExchangeRateSource}, scoped to the load the current test staged.
     */
    // A hand-written delegate, never a mock - Mockito is excluded from spring-boot-starter-test (AAP 0.7.6) and must
    // not be added. It exists only because LegacyRateTableSource's interface method resolves its staging run from
    // tool.batch-id once and caches it, while this class re-stages the corpus under a fresh run id before every
    // test; the run-explicit overload it delegates to takes that run as an argument, so no rate value, key rule or
    // failure mode is reimplemented here.
    static final class StagedLegacyRateSource implements ExchangeRateSource {

        private final LegacyRateTableSource delegate;

        private volatile UUID stagingRunId;

        StagedLegacyRateSource(LegacyRateTableSource delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        void useStagingRun(UUID stagingRunId) {
            this.stagingRunId = Objects.requireNonNull(stagingRunId, "stagingRunId");
        }

        @Override
        public BigDecimal rate(String base, String quote) {
            UUID staged = stagingRunId;
            if (staged == null) {
                // IllegalStateException and deliberately NOT the module's rate-unavailable exception: an
                // undeclared staging run is a fault in this harness, and the service layer would translate a
                // rate outage into 503 EXCHANGE_RATE_UNAVAILABLE, which the comparator classifies as an
                // authorized deviation - so a misconfigured test would record a quiet ACCEPTED_EXCEPTION row
                // instead of failing. An unexpected runtime failure becomes an outstanding VARIANCE row, which
                // breaks both assertions loudly.
                throw new IllegalStateException(
                        "no staging run has been declared for this test; the corpus load must run first");
            }
            return delegate.rate(staged, base, quote);
        }
    }
}
