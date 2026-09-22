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
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.load.LegacyLoader;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.MigrationReconciliation;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.MigrationRun;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.ReconciliationService;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.ReconciliationStatus;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.VarianceKind;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.CashAccountRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.MigrationReconciliationRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.MigrationRunRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.PostgresTestSupport;

/*
 * WHY THE "tool" PROFILE IS NEVER ACTIVATED HERE, AND MigrationToolRunner NEVER INVOKED. The runner is an
 * ApplicationRunner, @SpringBootTest executes runners as part of starting the context, and the runner's last
 * act is System.exit(SpringApplication.exit(...)) - which would terminate the Failsafe JVM mid-suite and take
 * every remaining *IT with it. This class therefore plays the runner's role by hand, in the runner's own order
 * and with the runner's own arithmetic: open the run, call the command, read the VARIANCE rows back, then
 * close the row with finish(...). The exit code is asserted as its persisted equivalent - CLEAN with zero
 * variance rows is exit 0, VARIANCE with at least one is exit 2, and a failure would be exit 1 - because the
 * code itself is a process-level value this JVM cannot produce without ending.
 *
 * WHY THE EXPECTED ROW SETS ARE ASSERTED EXACTLY AND NEVER LOOSENED. They are the acceptance evidence of AAP
 * 0.10.3, restated in each fixture's MANIFEST.md: a matched export yields zero rows and a seeded one exactly
 * five. An assertion on the FULL row list of the run, not on a status-filtered subset, is what makes a sixth,
 * unexpected row a failure rather than a silent pass - so if a row is missing or extra here, the finding is
 * reported, never accommodated by relaxing the assertion or editing a fixture.
 *
 * WHY MATCHED MEANS ZERO ROWS AND NOT SIX. ReconciliationService writes no row for an agreeing owner, not even
 * a MATCHED one; ReconciliationStatus.MATCHED is the value an operator sets when signing a reviewed finding
 * off. A row per agreeing owner would bury the seeded rows and make both assertions depend on fixture size.
 *
 * WHY EVERY SEEDED VARIANCE CARRIES THE RECONCILE RUN ID. The target-state load is a run of its own and writes
 * no finding at all: all five of its rows are non-null and carry an accepted currency, so its source
 * validation rejects nothing. Every row the seeded case expects is therefore written by the reconcile, under
 * the reconcile's run_id, which is also why both runs share one batch_id - a reconcile names the load it
 * judges through the batch, never through a run identifier.
 *
 * WHY ONLY VARIANCE FEEDS THE COUNT. An ACCEPTED_EXCEPTION row records a difference that has already been
 * authorized, so it must not raise migration_run.variance_count or move the exit code. No fixture row reaches
 * that status - reconcile mode with the legacy rate table cannot reclassify a balance difference (AAP 0.12.5)
 * - which is exactly why the seeded KARRI row stays BALANCE/VARIANCE.
 *
 * Fixtures only, always: no connection to DB2 for z/OS or to the VSAM history is made or attempted here, and
 * nothing this class proves may be read as evidence that real legacy data reconciled (AAP 0.3.2, 0.10.6).
 */

/** Proves reconciliation over the real database: the matched export yields no finding, the seeded one exactly five. */
// The property pair, not the profile: ReconciliationService binds tool.rate-source and LegacyLoader binds
// tool.history-record-length with @Value, while application-test.yml deliberately owns no tool.* key.
// legacy-table is AAP 0.12.5's parity mode - both sides judged on identical staged rates - stated here rather
// than inherited from the code default so the mode this evidence was produced under is visible in the test.
// The record length is mandatory because matched/ ships the binary history shape, which LoadSources.inDirectory
// prefers whenever it is present, and the length is declared from the CICS FILE definition, never inferred
// from the file (DEFKSDS.jcl RECSZ(100 100); the open item of AAP 0.11.2).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = { "tool.rate-source=legacy-table", "tool.history-record-length=100" })
// Spring Boot's test support disables metrics export unless this is present, and config/MetricsScrapeController
// is a component-scanned bean that requires PrometheusMeterRegistry, so without it the whole context fails.
@AutoConfigureObservability
class ReconciliationIT extends PostgresTestSupport {

    private static final String MATCHED_FIXTURES = "/fixtures/legacy-export/matched";

    private static final String SEEDED_FIXTURES = "/fixtures/legacy-export/seeded-mismatch";

    /** Tables this class owns, in an order no foreign key objects to: findings before the runs they reference. */
    // ledger_entry is deliberately absent and must stay absent: the ledger_entry_immutable trigger rejects both
    // UPDATE and DELETE, so a cleanup that touched it would fail the test rather than clean anything. Ledger
    // rows therefore accumulate across the suite, which is harmless because every run identifier below is a
    // fresh UUID and every assertion is scoped to a run - the container is shared JVM-wide with the sibling *IT
    // classes (support/PostgresTestSupport), so nothing here may depend on the database being otherwise empty.
    private static final List<String> TABLES_TO_CLEAR = List.of(
            "migration_reconciliation",
            "migration_run",
            "legacy_history",
            "legacy_rate_table",
            "cash_reservation",
            "cash_account");

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private LegacyLoader legacyLoader;

    @Autowired
    private MigrationRunRepository migrationRuns;

    @Autowired
    private MigrationReconciliationRepository reconciliations;

    @Autowired
    private CashAccountRepository cashAccounts;

    @Autowired
    private JdbcTemplate jdbc;

    // The drift guard of AAP 0.10.1: reconciliation arithmetic is only trustworthy while the document it was
    // derived from is present and still cites the COBOL lines it was derived from. It observes the ordering
    // checkpoint rather than proving it - nothing at run time can establish that the document was written
    // first - so a missing or uncited document fails this class before it asserts anything about a balance.
    @BeforeAll
    static void characterizationDocumentIsPresent() {
        CharacterizationDocPresentTest.requireCharacterizationDocument();
    }

    @BeforeEach
    void clearMigrationState() {
        TABLES_TO_CLEAR.forEach(table -> jdbc.update("DELETE FROM " + table));
    }

    @Test
    void matchedExportReconcilesWithZeroVariance() {
        Path matchedDirectory = fixtureDirectory(MATCHED_FIXTURES);
        UUID batchId = UUID.randomUUID();

        UUID loadRunId = UUID.randomUUID();
        MigrationRun loadRun = MigrationRun.start(loadRunId, batchId, MigrationRun.Mode.LOAD,
                matchedDirectory.toString(), MigrationRun.CharacterizationStatus.DRAFT);
        migrationRuns.save(loadRun);

        LegacyLoader.LoadResult loaded = legacyLoader.load(loadRun, matchedDirectory);

        assertThat(loaded.legacyRecordCount())
                .as("account rows read from %s", LegacyExportFormat.CASH_ACCOUNT_FILE)
                .isEqualTo(6);
        assertThat(loaded.migratedRecordCount()).as("owners applied by the load").isEqualTo(6);
        assertThat(loaded.varianceCount()).as("findings a consistent export must not produce").isZero();
        assertThat(cashAccounts.count()).as("cash_account rows after the load").isEqualTo(6);
        // The copybook's spelling of the rate table's base-currency column (DCLFRANK.cpy:L10 CURRNBASE); the
        // shipped DDL spells it cyrrnbase (DB2DDL.jcl:L56) and the seeded fixture carries that spelling, so the
        // export reader accepts either and neither file's header is a load failure - the open item of AAP 0.11.2.
        assertThat(loaded.stagedRateCount()).as("rate rows staged from a currnbase-headed export").isEqualTo(3);
        assertThat(reconciliations.findByRunIdOrderByReconciliationIdAsc(loadRunId))
                .as("a consistent export gives its load nothing to record")
                .isEmpty();

        UUID reconcileRunId = UUID.randomUUID();
        MigrationRun reconcileRun = MigrationRun.start(reconcileRunId, batchId, MigrationRun.Mode.RECONCILE,
                matchedDirectory.toString(), MigrationRun.CharacterizationStatus.DRAFT);
        migrationRuns.save(reconcileRun);

        assertThat(reconciliationService.reconcile(reconcileRun, matchedDirectory))
                .as("VARIANCE rows the matched export produces")
                .isZero();

        assertThat(reconciliations.findByRunIdOrderByReconciliationIdAsc(reconcileRunId))
                .as("the matched case is zero rows, not six MATCHED ones")
                .isEmpty();
        assertThat(reconciliations.countByRunIdAndStatus(reconcileRunId, ReconciliationStatus.VARIANCE)).isZero();

        // The state the command itself leaves behind: counts and verdict written, finishedAt still unset,
        // because closing the row is the runner's single responsibility.
        MigrationRun afterReconcile = persistedRun(reconcileRunId);
        assertThat(afterReconcile.legacyRecordCount()).isEqualTo(6);
        assertThat(afterReconcile.migratedRecordCount()).isEqualTo(6);
        assertThat(afterReconcile.varianceCount()).isZero();
        assertThat(afterReconcile.status()).isEqualTo(MigrationRun.Status.CLEAN);
        assertThat(afterReconcile.finishedAt()).as("only the runner closes a run").isNull();

        reconcileRun.finish(MigrationRun.Status.CLEAN, 6, 6, 0);
        migrationRuns.save(reconcileRun);

        // CLEAN with a zero variance count is MigrationToolRunner's exit code 0: the runbook's gate reads this
        // row, so the row and the code it would have produced have to agree.
        MigrationRun closedRun = persistedRun(reconcileRunId);
        assertThat(closedRun.varianceCount()).isZero();
        assertThat(closedRun.status()).isEqualTo(MigrationRun.Status.CLEAN);
        assertThat(closedRun.legacyRecordCount()).isEqualTo(6);
        assertThat(closedRun.migratedRecordCount()).isEqualTo(6);
        assertThat(closedRun.finishedAt()).as("finished_at of a closed run").isNotNull();

        assertThat(migrationRuns.findByBatchIdOrderByStartedAtAsc(batchId))
                .as("the batch is how a reconcile names the load it judges")
                .extracting(MigrationRun::runId, MigrationRun::mode)
                .containsExactly(tuple(loadRunId, MigrationRun.Mode.LOAD),
                        tuple(reconcileRunId, MigrationRun.Mode.RECONCILE));
    }

    @Test
    void seededExportProducesExactlyTheFiveSeededVariances() {
        Path seededDirectory = fixtureDirectory(SEEDED_FIXTURES);
        UUID batchId = UUID.randomUUID();

        // target-state.csv is loaded as THE MIGRATED STATE, as if an earlier faulty load had produced it:
        // reconcile compares the legacy export against the database, so the divergence has to be in the
        // database before the comparison runs. Accounts only - staging the seeded rate export here would record
        // the ZZZ null-rate finding under this run instead of the reconcile's, and the five expected rows all
        // belong to the reconcile.
        UUID targetRunId = UUID.randomUUID();
        MigrationRun targetRun = MigrationRun.start(targetRunId, batchId, MigrationRun.Mode.LOAD,
                seededDirectory.resolve(LegacyExportFormat.TARGET_STATE_FILE).toString(),
                MigrationRun.CharacterizationStatus.DRAFT);
        migrationRuns.save(targetRun);

        LegacyLoader.LoadResult targetLoad = legacyLoader.load(targetRun,
                LegacyLoader.LoadSources.accountsOnly(
                        seededDirectory.resolve(LegacyExportFormat.TARGET_STATE_FILE)),
                // Neither is consulted by an accounts-only load, which names no history file; the characterized
                // region code page and UTC are passed so the call states the same assumptions the tool profile
                // defaults to rather than inventing different ones (AAP 0.11.2).
                Charset.forName(LegacyExportFormat.DEFAULT_LEGACY_CHARSET), ZoneOffset.UTC, null);

        assertThat(targetLoad.migratedRecordCount()).as("target-state rows applied").isEqualTo(5);
        assertThat(cashAccounts.count()).as("cash_account rows standing as the migrated state").isEqualTo(5);
        assertThat(reconciliations.findByRunIdOrderByReconciliationIdAsc(targetRunId))
                .as("every target-state row is non-null with an accepted currency, so this load records nothing")
                .isEmpty();

        UUID reconcileRunId = UUID.randomUUID();
        MigrationRun reconcileRun = MigrationRun.start(reconcileRunId, batchId, MigrationRun.Mode.RECONCILE,
                seededDirectory.toString(), MigrationRun.CharacterizationStatus.DRAFT);
        migrationRuns.save(reconcileRun);

        assertThat(reconciliationService.reconcile(reconcileRun, seededDirectory))
                .as("VARIANCE rows the seeded export produces")
                .isEqualTo(5);

        List<MigrationReconciliation> rows =
                reconciliations.findByRunIdOrderByReconciliationIdAsc(reconcileRunId);

        assertThat(rows).as("exactly the five seeded findings and nothing else").hasSize(5);
        assertThat(rows)
                .extracting(MigrationReconciliation::owner,
                        MigrationReconciliation::varianceKind,
                        MigrationReconciliation::status)
                .containsExactlyInAnyOrder(
                        tuple("KARRI", VarianceKind.BALANCE, ReconciliationStatus.VARIANCE),
                        tuple("ERIC", VarianceKind.CURRENCY, ReconciliationStatus.VARIANCE),
                        tuple("GREG", VarianceKind.STATE, ReconciliationStatus.VARIANCE),
                        tuple("NULLBAL", VarianceKind.STATE, ReconciliationStatus.VARIANCE),
                        tuple("ZZZ", VarianceKind.RATE_SOURCE, ReconciliationStatus.VARIANCE));

        // KARRI: the digit transposition. The sign convention is the module-wide one declared in
        // MigrationReconciliation - variance = migrated - legacy - so the transposition reads +0.09, and every
        // expected decimal is built from a String because a double cannot hold a two-decimal balance exactly.
        MigrationReconciliation karri = rowFor(rows, "KARRI");
        assertThat(karri.legacyBalance()).isEqualByComparingTo(new BigDecimal("12345.67"));
        assertThat(karri.migratedBalance()).isEqualByComparingTo(new BigDecimal("12345.76"));
        assertThat(karri.variance()).isEqualByComparingTo(new BigDecimal("0.09"));
        assertThat(karri.variance().scale()).as("the money scale of every balance column").isEqualTo(2);
        assertThat(karri.legacyValue()).isEqualTo("12345.67");
        assertThat(karri.migratedValue()).isEqualTo("12345.76");

        // ERIC: the currency change, with identical balances on both sides. The currency and balance checks are
        // independent and each writes at most one row, which is why one owner produces exactly one row here.
        MigrationReconciliation eric = rowFor(rows, "ERIC");
        assertThat(eric.legacyValue()).isEqualTo("EUR");
        assertThat(eric.migratedValue()).isEqualTo("USD");
        assertThat(eric.legacyBalance()).isEqualByComparingTo(new BigDecimal("1234567.89"));
        assertThat(eric.migratedBalance()).isEqualByComparingTo(new BigDecimal("1234567.89"));
        assertThat(rows)
                .as("ERIC's balances are deliberately identical, so no BALANCE row may exist for it")
                .filteredOn(row -> row.varianceKind() == VarianceKind.BALANCE)
                .extracting(MigrationReconciliation::owner)
                .containsExactly("KARRI");

        // GREG: present in the legacy export, absent from the migrated state. Recorded with the legacy balance
        // and no migrated one - a 0.00 there would assert a balance the target never held - and never deleted
        // or invented, because only the operator can tell a removed row from a partial export.
        MigrationReconciliation greg = rowFor(rows, "GREG");
        assertThat(greg.migratedValue()).isEqualTo("MISSING_IN_TARGET");
        assertThat(greg.legacyValue()).isEqualTo("PRESENT");
        assertThat(greg.legacyBalance()).isEqualByComparingTo(new BigDecimal("123456.78"));
        assertThat(greg.migratedBalance()).isNull();
        assertThat(greg.variance()).as("no signed difference from a balance the target never held").isNull();

        // NULLBAL: the empty unquoted balance field of a nullable legacy column (DB2DDL.jcl:L48). Written by
        // ReconciliationService.validateSource() as it reads the export; the account is not loaded and no value
        // is invented for it, so both balance columns stay null.
        MigrationReconciliation nullBalance = rowFor(rows, "NULLBAL");
        assertThat(nullBalance.legacyValue()).isEqualTo("NULL_IN_LEGACY");
        assertThat(nullBalance.legacyBalance()).isNull();
        assertThat(nullBalance.migratedBalance()).isNull();

        // ZZZ: the empty rates field, also from validateSource(). Its presence is what proves the seeded
        // frankfurt1.csv parsed at all: that file heads the base-currency column cyrrnbase (DB2DDL.jcl:L56)
        // while matched/frankfurt1.csv heads it currnbase (DCLFRANK.cpy:L10), and the reader accepts either.
        // The row's own empty cyrrnbase and amount produce NO finding, because the legacy program fetched both
        // columns and referenced neither in any COMPUTE or MOVE (CASH00.cbl:L215, L249; AAP 0.4.1).
        MigrationReconciliation nullRate = rowFor(rows, "ZZZ");
        assertThat(nullRate.legacyValue()).isEqualTo("NULL_RATE");

        assertThat(rows)
                .extracting(MigrationReconciliation::owner)
                .as("JOHN, RYAN and RAUNAK match on both balance and currency, so they get no row")
                .doesNotContain("JOHN", "RYAN", "RAUNAK");
        assertThat(rows)
                .extracting(MigrationReconciliation::varianceKind)
                .as("reconcile mode holds one MIGRATION_LOAD row per account and no per-transaction history,"
                        + " so there is nothing for a TRANSACTION_COUNT row to count")
                .doesNotContain(VarianceKind.TRANSACTION_COUNT);
        assertThat(reconciliations.findByRunIdAndStatus(reconcileRunId, ReconciliationStatus.VARIANCE))
                .as("no row of any other status stands under this run")
                .hasSize(5);

        MigrationRun afterReconcile = persistedRun(reconcileRunId);
        assertThat(afterReconcile.legacyRecordCount())
                .as("the seeded export's seven account rows, rejected ones included")
                .isEqualTo(7);
        assertThat(afterReconcile.migratedRecordCount())
                .as("the five migrated owners the comparison could consider")
                .isEqualTo(5);
        assertThat(afterReconcile.varianceCount()).isEqualTo(5);
        assertThat(afterReconcile.status()).isEqualTo(MigrationRun.Status.VARIANCE);
        assertThat(afterReconcile.finishedAt()).as("only the runner closes a run").isNull();

        reconcileRun.finish(MigrationRun.Status.VARIANCE, 7, 5, 5);
        migrationRuns.save(reconcileRun);

        // VARIANCE with five rows standing is MigrationToolRunner's exit code 2, and the runbook's Step 1
        // sign-off is refused until each of those rows is resolved or reclassified with a written reason.
        MigrationRun closedRun = persistedRun(reconcileRunId);
        assertThat(closedRun.legacyRecordCount()).isEqualTo(7);
        assertThat(closedRun.migratedRecordCount()).isEqualTo(5);
        assertThat(closedRun.varianceCount()).isEqualTo(5);
        assertThat(closedRun.status()).isEqualTo(MigrationRun.Status.VARIANCE);
        assertThat(closedRun.finishedAt()).as("finished_at of a closed run").isNotNull();
    }

    /** Re-reads a run row so an assertion reads what the database holds rather than the in-memory instance. */
    private MigrationRun persistedRun(UUID runId) {
        return migrationRuns.findById(runId)
                .orElseGet(() -> fail("migration_run %s was expected to exist".formatted(runId)));
    }

    /** The single row a seeded owner is expected to have, asserted as single before its values are read. */
    private static MigrationReconciliation rowFor(List<MigrationReconciliation> rows, String owner) {
        List<MigrationReconciliation> matching = rows.stream()
                .filter(row -> owner.equals(row.owner()))
                .toList();
        assertThat(matching).as("rows recorded for %s", owner).hasSize(1);
        return matching.get(0);
    }

    // Resolved from the test classpath rather than from a module-relative path: these fixtures are copied to
    // target/test-classes by the build, so the classpath is the one location that is correct for Failsafe, for
    // an IDE runner and for a rerun from a different working directory alike. It FAILS and never skips, naming
    // the resource: an absent fixture directory means the acceptance evidence of AAP 0.10.3 cannot be produced
    // at all, which is a failure to report rather than a test to quietly pass over.
    private static Path fixtureDirectory(String classpathLocation) {
        URL resource = ReconciliationIT.class.getResource(classpathLocation);
        if (resource == null) {
            return fail("the fixture directory %s is absent from the test classpath; the reconciliation"
                    + " evidence of AAP 0.10.3 cannot be produced without it".formatted(classpathLocation));
        }
        try {
            return Path.of(resource.toURI());
        } catch (URISyntaxException e) {
            return fail("the fixture directory %s resolved to a URL that is not a usable path: %s"
                    .formatted(classpathLocation, resource), e);
        }
    }
}
