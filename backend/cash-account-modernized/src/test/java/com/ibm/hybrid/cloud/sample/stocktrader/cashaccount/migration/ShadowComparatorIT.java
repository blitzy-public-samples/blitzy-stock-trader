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
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.fx.ExchangeRateSource;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.fx.LegacyRateTableSource;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.load.LegacyLoader;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.MigrationReconciliation;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.MigrationRun;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.ReconciliationService;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.ReconciliationStatus;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.VarianceKind;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.shadow.ShadowComparator;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.shadow.ShadowLegacyResponse;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.shadow.ShadowTransaction;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.CashAccountRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.LegacyRateTableRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.MigrationReconciliationRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.MigrationRunRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.MigrationReconciliationTestQueries;
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
 * cases, parameterized matrices and redundant variants are not to be generated. Two evidence-integrity regressions
 * found in code review are asserted as further phases INSIDE the seeded scenario rather than as tests of their own,
 * because neither is reachable from a committed fixture - the fixture contract requires every success line to carry
 * a balance, and no fixture can make a window fail while recording a finding - and the allocation is frozen.
 */
/** Proves the dual-run comparator on the committed shadow captures, and that its evidence survives a lost window. */
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

    /** A zero return code, which is what the legacy status channel rendered for a successful request. */
    private static final String SUCCESS_RETCODE = "000000000";

    /** {@code migration_run.source_path} for the one window built in memory rather than read from a fixture. */
    private static final String IN_MEMORY_WINDOW = "in-memory capture (no fixture shape carries it)";

    @Autowired
    private ShadowComparator shadowComparator;

    @Autowired
    private LegacyLoader legacyLoader;

    @Autowired
    private MigrationRunRepository migrationRuns;

    // Two views of one table: the production repository is what the runner is built with, because its failure
    // accounting reads the rows a command left behind; the test-only finders below are how this class asserts
    // over them, so no query that exists only for a test sits on the production interface.
    @Autowired
    private MigrationReconciliationRepository reconciliationRepository;

    @Autowired
    private MigrationReconciliationTestQueries reconciliations;

    @Autowired
    private CashAccountRepository accounts;

    @Autowired
    private LegacyRateTableRepository legacyRates;

    @Autowired
    private ReconciliationService reconciliationService;

    // The context the runner is handed. It uses it only in run(...), which this class never calls, but the
    // constructor requires it - and a @SpringBootTest context IS a ConfigurableApplicationContext.
    @Autowired
    private ConfigurableApplicationContext applicationContext;

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

    /**
     * The seeded window's evidence, in three phases: the three seeded rows and nothing else, then the two
     * conditions a committed fixture cannot express - a capture line with no balance on the legacy side, and a
     * window lost while recording a finding.
     */
    // Phases rather than separate tests because AAP 0.7.6 allocates this file two, and each phase takes its own
    // fresh run id, so no phase's row set can disturb another's.
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

        malformedCaptureIsRecordedWithAnExplicitAbsenceToken();
        lostWindowKeepsItsProgressAndItsCommittedFindings();
    }

    /**
     * A success reply that carried no balance: the row must name the absent side rather than leave it blank.
     */
    // Part of the seeded scenario rather than a test of its own, because AAP 0.7.6 allocates this file two tests
    // and this shape cannot come from a fixture: the fixture contract requires every success line to carry a
    // balance, so the capture is built in memory through the comparator's own in-memory entry point. Its run id is
    // fresh, so the row set asserted above is unaffected.
    private void malformedCaptureIsRecordedWithAnExplicitAbsenceToken() {
        UUID runId = UUID.randomUUID();
        MigrationRun run = migrationRuns.save(MigrationRun.start(runId, UUID.randomUUID(),
                MigrationRun.Mode.SHADOW, IN_MEMORY_WINDOW, MigrationRun.CharacterizationStatus.DRAFT));

        // A Q read of an owner the corpus holds, paired with a reply that claims success (a zero retcode,
        // CASH00.cbl:L104) and states no balance at all - so the target answers with the account's balance and the
        // legacy side offers nothing to compare it against.
        BigDecimal targetBalance = balanceOf("JOHN");
        int variances = shadowComparator.compare(run,
                List.of(new ShadowTransaction(1L, "JOHN", "Q", null, null)),
                List.of(new ShadowLegacyResponse(1L, "JOHN", SUCCESS_RETCODE, null)));

        assertThat(variances)
                .as("a comparison with nothing on the legacy side fails closed as one outstanding variance")
                .isEqualTo(1);

        List<MigrationReconciliation> rows = reconciliations.findByRunIdOrderByReconciliationIdAsc(runId);
        assertThat(rows)
                .as("one row for the malformed line, and no TRANSACTION_COUNT row: Q changed no state, so it is"
                        + " counted on neither side")
                .hasSize(1);

        MigrationReconciliation absent = row(rows, "JOHN", VarianceKind.BALANCE);
        assertThat(absent.status()).isEqualTo(ReconciliationStatus.VARIANCE);
        // The point of the row: an absent side is NAMED. Without the token the finding would carry a null
        // legacy_value beside a null legacy_balance and a null variance, which is indistinguishable from a
        // comparison the tooling failed to complete - and an operator reviewing the window's rows could neither
        // triage it nor trace it back to the capture that is malformed.
        assertThat(absent.legacyValue()).isEqualTo(MigrationReconciliation.ABSENT_IN_CAPTURE);
        assertThat(absent.legacyBalance())
                .as("no balance may be invented for a side that stated none")
                .isNull();
        assertThat(absent.migratedValue()).isEqualTo(targetBalance.toPlainString());
        assertThat(absent.migratedBalance()).isEqualByComparingTo(targetBalance);
        assertThat(absent.variance())
                .as("the difference from a balance that was never stated is unknown, not zero")
                .isNull();
    }

    /**
     * A window lost while recording a finding: the FAILED row must state the progress and the findings that stand.
     */
    // DRIVEN THROUGH MigrationToolRunner.execute, the production recovery path, rather than by reproducing it here:
    // the row's closure is the runner's own logic, and asserting a hand-rolled copy of it would leave the real path
    // unexercised. execute(...) is the package-private entry point that returns the exit code instead of forcing
    // it; run(...) - the one that ends in System.exit - is never called, which is why the tool profile can stay off
    // (see the class note) while the runner is still exercised. @Profile("tool") is not evaluated for an instance
    // constructed explicitly, the same reason the staged rate source below can reuse LegacyRateTableSource.
    //
    // The injected failure is on the REJECTED_BY_TARGET insert, which the seeded window writes at seq 2 (RAUNAK)
    // AFTER seq 1 (JOHN) has been replayed and its BALANCE finding has committed, and at the moment seq 2's own
    // target refusal has already happened. That one fault point covers both halves of the accounting: a finding
    // that committed must be counted, and a line whose evidence insert failed must still appear in the progress.
    private void lostWindowKeepsItsProgressAndItsCommittedFindings() {
        Path window = fixtureDirectory(SEEDED_SHADOW_FIXTURES);
        UUID batchId = UUID.randomUUID();

        int exitCode;
        injectRejectionRowInsertFailure();
        try {
            exitCode = shadowCompareRunner(window, batchId).execute(new DefaultApplicationArguments());
        } finally {
            // Dropped whatever happened above: the PostgreSQL container is JVM-wide and shared with the sibling
            // ITs, so a trigger left behind would fail an unrelated class.
            removeRejectionRowInsertFailure();
        }

        assertThat(exitCode)
                .as("a datastore failure while recording a finding is an error (1), never a completed run that"
                        + " recorded variances (2)")
                .isEqualTo(1);

        MigrationRun lost = onlyRunOfBatch(batchId);
        // A FAILED row that states what the attempt actually did. Closed with zeroed counters it would tell the
        // operator who signs off runbook Step 2 that the window found nothing, with its finding sitting under the
        // same run id; left RUNNING it could not be signed off at all.
        assertThat(lost.status()).isEqualTo(MigrationRun.Status.FAILED);
        assertThat(lost.legacyRecordCount())
                .as("all seven captured replies were read and joined before the window was lost")
                .isEqualTo(7);
        assertThat(lost.migratedRecordCount())
                .as("two lines were taken up: seq 1, whose finding committed, and seq 2, whose target refusal"
                        + " happened and whose finding insert then failed")
                .isEqualTo(2);
        assertThat(lost.varianceCount())
                .as("read back from the finding that committed, not from the instance that died")
                .isEqualTo(1);
        assertThat(lost.finishedAt())
                .as("a closed run is stamped, whatever its verdict")
                .isNotNull();

        assertThat(reconciliations.findByRunIdOrderByReconciliationIdAsc(lost.runId()))
                .as("the finding that reached the table survives the failure that followed it")
                .extracting(MigrationReconciliation::owner, MigrationReconciliation::varianceKind,
                        MigrationReconciliation::status)
                .containsExactly(tuple("JOHN", VarianceKind.BALANCE, ReconciliationStatus.VARIANCE));
    }

    /**
     * The runner as the runbook's Step 2 command line configures it, for one shadow window.
     */
    private MigrationToolRunner shadowCompareRunner(Path window, UUID batchId) {
        return new MigrationToolRunner(legacyLoader, reconciliationService, shadowComparator, migrationRuns,
                reconciliationRepository, applicationContext, "shadow-compare", window.toString(), batchId.toString(),
                "legacy-table", LegacyExportFormat.DEFAULT_LEGACY_CHARSET, "UTC", null);
    }

    /** The one run the runner opened for a batch, failing rather than guessing when the batch holds another. */
    private MigrationRun onlyRunOfBatch(UUID batchId) {
        List<MigrationRun> runsOfBatch = migrationRuns.findByBatchIdOrderByStartedAtAsc(batchId);
        if (runsOfBatch.size() != 1) {
            return fail("the runner must open exactly one run for batch %s, but the batch holds %s"
                    .formatted(batchId, runsOfBatch));
        }
        return runsOfBatch.get(0);
    }

    /**
     * Makes the next {@code REJECTED_BY_TARGET} insert fail, so a window dies while recording a finding.
     */
    // plpgsql with a WHEN condition, both available on the PostgreSQL 12 floor this module is written to, so the
    // injection is as narrow as the scenario: no other row kind, table or test is affected. Nothing in the
    // comparator, the runner, the fixtures or the schema is altered - the trigger is created and dropped here.
    private void injectRejectionRowInsertFailure() {
        jdbcTemplate.execute("""
                CREATE FUNCTION shadow_it_reject_rejection_row() RETURNS trigger AS $fn$
                BEGIN
                    RAISE EXCEPTION 'ShadowComparatorIT fault injection: the window is lost while recording a finding';
                END
                $fn$ LANGUAGE plpgsql""");
        jdbcTemplate.execute("""
                CREATE TRIGGER shadow_it_reject_rejection_row
                BEFORE INSERT ON migration_reconciliation
                FOR EACH ROW WHEN (NEW.variance_kind = 'REJECTED_BY_TARGET')
                EXECUTE FUNCTION shadow_it_reject_rejection_row()""");
    }

    private void removeRejectionRowInsertFailure() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS shadow_it_reject_rejection_row"
                + " ON migration_reconciliation");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS shadow_it_reject_rejection_row()");
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
