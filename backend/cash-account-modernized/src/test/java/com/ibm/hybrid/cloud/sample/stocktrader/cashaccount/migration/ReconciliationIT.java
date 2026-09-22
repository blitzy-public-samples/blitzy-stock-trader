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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.export.LegacyCashAccountRecord;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.load.LegacyLoader;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.MigrationReconciliation;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.MigrationRun;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.ReconciliationService;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.ReconciliationStatus;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.VarianceKind;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.CashAccountRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.MigrationRunRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.MigrationReconciliationTestQueries;
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
//
// The accepted-currency list is overridden here in INDEXED-KEY form, and the form is the whole point:
// application.yml declares cashaccount.fx.accepted-currencies as a YAML sequence, which reaches the
// Environment only as cashaccount.fx.accepted-currencies[0..n], so this is the shape a ${...} placeholder
// cannot bind and a Binder can. Binder takes a collection entirely from the highest-priority source that
// carries it rather than merging sources, so the set in force below is exactly these four codes. Three of
// them are every currency the fixtures use (USD, EUR, GBP), which is what leaves the matched and seeded row
// sets identical to their AAP 0.10.3 expectations; XTS is ISO 4217's reserved test code, in force here and in
// no shipped list, and acceptedCurrencySetComesFromConfigurationNotFromCode() is what it exists for.
//
// cashaccount.migration.batch-chunk-size is forced to 2, which is both the chunk of exported owners a reconcile compares at a
// time and the page of target rows it walks the other direction in. The seven-row export and the five-row
// migrated state therefore cross several chunk and several page boundaries, so the row sets asserted below -
// zero for matched, exactly the five seeded ones - are also the evidence that flushing and clearing the
// persistence context between chunks loses, duplicates and reorders nothing.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = { "tool.rate-source=legacy-table", "tool.history-record-length=100",
                "cashaccount.migration.batch-chunk-size=2",
                "cashaccount.fx.accepted-currencies[0]=USD", "cashaccount.fx.accepted-currencies[1]=EUR",
                "cashaccount.fx.accepted-currencies[2]=GBP", "cashaccount.fx.accepted-currencies[3]=XTS" })
class ReconciliationIT extends PostgresTestSupport {

    private static final String MATCHED_FIXTURES = "/fixtures/legacy-export/matched";

    private static final String SEEDED_FIXTURES = "/fixtures/legacy-export/seeded-mismatch";

    /** The matched fixture's 1000.00 USD owner, used as the one whose funds go on hold. */
    private static final String HOLD_OWNER = "JOHN";

    // Owners the fixture export does not name, one per branch of the target-only classification rule. Named
    // apart from the estate's stub names so a reader cannot mistake one for a fixture row.
    private static final String MIGRATION_ONLY_OWNER = "LOADEDONLY";

    private static final String RETAIL_TOUCHED_OWNER = "RETAILTOUCHED";

    private static final String NO_LEDGER_OWNER = "NOLEDGERROWS";

    // The derived finder a reconcile uses to resolve the batch's load run, named once so the decorator below
    // and the assertion that reads its count cannot drift apart.
    private static final String BATCH_LOOKUP_METHOD = "findByBatchIdOrderByStartedAtAsc";

    // Static because the @TestConfiguration that increments it is static, and reset by the test immediately
    // before the call it measures, so a context reused across tests cannot carry a stale count into one.
    private static final AtomicInteger batchLookups = new AtomicInteger();

    // cash_reservation.request_hash is CHAR(64), the width of a hex-encoded SHA-256 of the canonical hold
    // payload (AAP 0.7.3). No replay is exercised here, so the value only has to be of the column's width -
    // a literal keeps it obvious that nothing in this test depends on the digest itself.
    private static final String TEST_REQUEST_HASH = "0".repeat(64);

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
    private MigrationReconciliationTestQueries reconciliations;

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

    @Test
    void ownerWithFundsOnHoldIsRecordedAsStateRatherThanComparedAsABalanceDifference() {
        Path matchedDirectory = fixtureDirectory(MATCHED_FIXTURES);
        UUID batchId = UUID.randomUUID();

        MigrationRun loadRun = MigrationRun.start(UUID.randomUUID(), batchId, MigrationRun.Mode.LOAD,
                matchedDirectory.toString(), MigrationRun.CharacterizationStatus.DRAFT);
        migrationRuns.save(loadRun);
        legacyLoader.load(loadRun, matchedDirectory);

        // The precondition is a DATA condition - reserved funds standing against an account - so it is written
        // as data: available_balance and reserved_balance summing to the loaded 1000.00, with the HELD
        // cash_reservation row that accounts for the reserved half. Reaching for the institutional endpoints to
        // arrange it would make this test depend on that surface's shape to assert something about
        // reconciliation, and a hold placed this way is indistinguishable to the reconciler, which reads exactly
        // these two tables.
        holdFundsAgainstLoadedAccount(HOLD_OWNER, "250.00", "750.00");

        UUID reconcileRunId = UUID.randomUUID();
        MigrationRun reconcileRun = MigrationRun.start(reconcileRunId, batchId, MigrationRun.Mode.RECONCILE,
                matchedDirectory.toString(), MigrationRun.CharacterizationStatus.DRAFT);
        migrationRuns.save(reconcileRun);

        assertThat(reconciliationService.reconcile(reconcileRun, matchedDirectory))
                .as("one finding for the hold, and nothing else for the five owners that match")
                .isEqualTo(1);

        List<MigrationReconciliation> rows =
                reconciliations.findByRunIdOrderByReconciliationIdAsc(reconcileRunId);
        assertThat(rows).as("the hold is the run's only finding").hasSize(1);

        // The row the load writes when it declines to overwrite such an owner (AAP 0.6.3), in the same shape:
        // the retained available balance on the target side, no legacy figure, and no signed variance, because
        // the difference the hold creates is not money the migration lost.
        MigrationReconciliation onHold = rowFor(rows, HOLD_OWNER);
        assertThat(onHold.varianceKind()).isEqualTo(VarianceKind.STATE);
        assertThat(onHold.status()).isEqualTo(ReconciliationStatus.VARIANCE);
        assertThat(onHold.legacyValue()).isEqualTo("PRESENT");
        assertThat(onHold.migratedValue()).isEqualTo("RESERVATIONS_OUTSTANDING");
        assertThat(onHold.migratedBalance()).isEqualByComparingTo(new BigDecimal("750.00"));
        assertThat(onHold.legacyBalance()).isNull();
        assertThat(onHold.variance()).isNull();

        // What must NOT be here: the legacy export carries 1000.00 for this owner and the target's available
        // balance now reads 750.00, so a comparison that ran anyway would report a 250.00 BALANCE variance
        // describing the hold rather than a migration difference.
        assertThat(rows)
                .extracting(MigrationReconciliation::varianceKind)
                .as("the held funds must not be reported a second time as a balance or currency difference")
                .doesNotContain(VarianceKind.BALANCE, VarianceKind.CURRENCY);

        MigrationRun afterReconcile = persistedRun(reconcileRunId);
        assertThat(afterReconcile.migratedRecordCount())
                .as("the held owner's row was read and classified, so it counts as considered")
                .isEqualTo(6);
        assertThat(afterReconcile.varianceCount()).isEqualTo(1);
        assertThat(afterReconcile.status()).isEqualTo(MigrationRun.Status.VARIANCE);
    }

    @Test
    void targetOwnersTheExportDoesNotNameAreClassifiedByTheirLedgerSource() {
        Path matchedDirectory = fixtureDirectory(MATCHED_FIXTURES);
        UUID batchId = UUID.randomUUID();

        MigrationRun loadRun = MigrationRun.start(UUID.randomUUID(), batchId, MigrationRun.Mode.LOAD,
                matchedDirectory.toString(), MigrationRun.CharacterizationStatus.DRAFT);
        migrationRuns.save(loadRun);
        legacyLoader.load(loadRun, matchedDirectory);

        // Three owners the export does not name, one per branch of the rule: produced by a load and untouched
        // since; produced by a load but written to afterwards through the retail seam; and an account with no
        // ledger row at all, which no load can have produced because a load always writes its MIGRATION_LOAD
        // event. Only the first is a migration variance.
        UUID loadedOnly = insertAccountAbsentFromTheExport(MIGRATION_ONLY_OWNER, "42.00");
        appendLedgerRow(MIGRATION_ONLY_OWNER, loadedOnly, "MIGRATION_LOAD", "MIGRATION", "42.00");

        UUID touched = insertAccountAbsentFromTheExport(RETAIL_TOUCHED_OWNER, "17.00");
        appendLedgerRow(RETAIL_TOUCHED_OWNER, touched, "MIGRATION_LOAD", "MIGRATION", "10.00");
        appendLedgerRow(RETAIL_TOUCHED_OWNER, touched, "CREDIT", "RETAIL", "7.00");

        insertAccountAbsentFromTheExport(NO_LEDGER_OWNER, "5.00");

        UUID reconcileRunId = UUID.randomUUID();
        MigrationRun reconcileRun = MigrationRun.start(reconcileRunId, batchId, MigrationRun.Mode.RECONCILE,
                matchedDirectory.toString(), MigrationRun.CharacterizationStatus.DRAFT);
        migrationRuns.save(reconcileRun);

        assertThat(reconciliationService.reconcile(reconcileRun, matchedDirectory))
                .as("only the owner the migration produced and nothing else touched")
                .isEqualTo(1);

        List<MigrationReconciliation> rows =
                reconciliations.findByRunIdOrderByReconciliationIdAsc(reconcileRunId);
        assertThat(rows).hasSize(1);

        // Recorded, never deleted: a delta export that omits an owner may equally mean the row was removed
        // upstream or that the export was partial, and only the operator can tell which (AAP 0.6.3).
        MigrationReconciliation missing = rowFor(rows, MIGRATION_ONLY_OWNER);
        assertThat(missing.varianceKind()).isEqualTo(VarianceKind.STATE);
        assertThat(missing.status()).isEqualTo(ReconciliationStatus.VARIANCE);
        assertThat(missing.legacyValue()).isEqualTo("MISSING_IN_LEGACY");
        assertThat(missing.migratedValue()).isEqualTo("PRESENT");
        assertThat(missing.migratedBalance()).isEqualByComparingTo(new BigDecimal("42.00"));
        assertThat(missing.legacyBalance()).as("no balance the legacy export ever held").isNull();

        assertThat(rows)
                .extracting(MigrationReconciliation::owner)
                .as("post-load retail activity and an account no load produced are not migration variances")
                .doesNotContain(RETAIL_TOUCHED_OWNER, NO_LEDGER_OWNER);

        assertThat(cashAccounts.existsByOwner(MIGRATION_ONLY_OWNER))
                .as("a reconcile records and never removes a target row")
                .isTrue();

        MigrationRun afterReconcile = persistedRun(reconcileRunId);
        assertThat(afterReconcile.migratedRecordCount())
                .as("the six exported owners plus the one target-only owner the run reported")
                .isEqualTo(7);
        assertThat(afterReconcile.varianceCount()).isEqualTo(1);
    }

    /**
     * Puts part of a loaded owner's balance on hold, as a settled hold would leave it: available and reserved
     * summing to what the load applied, with the {@code HELD} reservation row that accounts for the reserved half.
     */
    private void holdFundsAgainstLoadedAccount(String owner, String heldAmount, String remainingAvailable) {
        UUID incarnation = jdbc.queryForObject(
                "SELECT incarnation_id FROM cash_account WHERE owner = ?", UUID.class, owner);

        jdbc.update("INSERT INTO cash_reservation (reservation_id, owner, incarnation_id, order_reference,"
                        + " amount, currency, state, idempotency_key, request_hash, expires_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'USD', 'HELD', ?, ?, ?)",
                UUID.randomUUID(), owner, incarnation, "ORDER-" + owner, new BigDecimal(heldAmount),
                "idempotency-" + owner, TEST_REQUEST_HASH, OffsetDateTime.now(ZoneOffset.UTC).plusDays(1));

        jdbc.update("UPDATE cash_account SET available_balance = ?, reserved_balance = ? WHERE owner = ?",
                new BigDecimal(remainingAvailable), new BigDecimal(heldAmount), owner);
    }

    /** Inserts a target account the legacy export does not name, returning its incarnation. */
    private UUID insertAccountAbsentFromTheExport(String owner, String availableBalance) {
        UUID incarnation = UUID.randomUUID();
        jdbc.update("INSERT INTO cash_account (owner, incarnation_id, currency, available_balance,"
                        + " reserved_balance) VALUES (?, ?, 'USD', ?, 0.00)",
                owner, incarnation, new BigDecimal(availableBalance));
        return incarnation;
    }

    // Written through SQL because ledger_entry is append-only by design: persistence/LedgerEntryRepository
    // publishes no findAll and no delete, the ledger_entry_immutable trigger rejects UPDATE and DELETE, and the
    // rows a test needs here are a precondition about who wrote an owner's history rather than an event the
    // service produced.
    private void appendLedgerRow(String owner, UUID incarnation, String eventType, String source,
                                 String amount) {
        jdbc.update("INSERT INTO ledger_entry (owner, incarnation_id, event_type, amount, currency,"
                        + " available_after, reserved_after, source) VALUES (?, ?, ?, ?, 'USD', ?, 0.00, ?)",
                owner, incarnation, eventType, new BigDecimal(amount), new BigDecimal(amount), source);
    }

    /** The accepted-currency set the reconciler judges an export against is the configured one, not a compiled-in one. */
    // Two-sided on purpose, because one side alone proves nothing. XTS is accepted although no shipped list
    // contains it, so the configured value must have reached the reconciler; CHF is rejected although the
    // estate allowlist in application.yml contains it, so a set of the class's own cannot still be in force.
    // Before the fix this class documents, the accepted set arrived through
    // @Value("${cashaccount.fx.accepted-currencies:AUD,BGN,...,ZAR}"), which cannot aggregate the indexed keys a
    // YAML sequence or an indexed override produces: the placeholder fell back to its own 31-code literal, so
    // application.yml's list was inert and every list-form override was ignored - silently, since a currency
    // policy has no other observable symptom until an account is refused or staged wrongly.
    //
    // validateSource is driven directly with two synthetic rows rather than through a fixture, because the
    // fixtures deliberately use only the three codes this override keeps accepted (their row sets are the AAP
    // 0.10.3 acceptance evidence and must not move to accommodate a test).
    @Test
    void acceptedCurrencySetComesFromConfigurationNotFromCode() {
        UUID runId = UUID.randomUUID();
        MigrationRun run = MigrationRun.start(runId, UUID.randomUUID(), MigrationRun.Mode.RECONCILE,
                "accepted-currency binding", MigrationRun.CharacterizationStatus.DRAFT);
        migrationRuns.save(run);

        ReconciliationService.SourceValidation validation = reconciliationService.validateSource(run,
                List.of(new LegacyCashAccountRecord("INSET", new BigDecimal("10.00"), "XTS"),
                        new LegacyCashAccountRecord("OUTSET", new BigDecimal("20.00"), "CHF")),
                List.of());

        // The validation names what it refused and never what it accepted, so the accepted side is proven as
        // the absence of a refusal: both rows were read, and only the CHF one was turned away.
        assertThat(validation.legacyAccountCount()).isEqualTo(2);
        assertThat(validation.rejectedOwners())
                .as("CHF belongs to the shipped estate allowlist and not to this context's list;"
                        + " XTS is in force only because the configured list is, so it must not be refused")
                .containsExactly("OUTSET");
        assertThat(validation.varianceCount()).isEqualTo(1);

        assertThat(reconciliations.findByRunIdOrderByReconciliationIdAsc(runId))
                .extracting(MigrationReconciliation::owner,
                        MigrationReconciliation::varianceKind,
                        MigrationReconciliation::status,
                        MigrationReconciliation::legacyValue)
                .as("the out-of-set currency is recorded as a finding, never coerced to a default")
                .containsExactly(tuple("OUTSET", VarianceKind.CURRENCY, ReconciliationStatus.VARIANCE,
                        "INVALID_IN_LEGACY"));
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

    /** Live-rate mode: the one configuration in which a balance difference may be reclassified (AAP 0.12.5). */
    // A context of its own because tool.rate-source is bound by ReconciliationService's constructor, so the mode
    // is fixed when the bean is built and cannot be varied within one context. The record length is declared for
    // the same reason the enclosing class declares it - the matched fixtures ship the binary history shape - and
    // the properties are stated here in full so this context configures itself whatever nested-configuration
    // inheritance does later, exactly as security/RoleEnforcementIT's strict-mode context does.
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
            properties = { "tool.rate-source=live", "tool.history-record-length=100" })
    @ActiveProfiles("test")
    class LiveRateSourceMode extends PostgresTestSupport {

        /** The matched fixture's EUR owner: 1234567.89 EUR against a staged EUR rate of 0.92. */
        private static final String RATE_OWNER = "ERIC";

        private static final String LEGACY_BALANCE = "1234567.89";

        // The unconverted figure the staged rate re-derives the legacy balance from, to the cent:
        // truncate2(0.92 x 1341921.62) = truncate2(1234567.8904) = 1234567.89, the legacy COMPUTE of
        // CASH00.cbl:L222 applied from zero. Chosen as text, and one of the two values in this test that have
        // to be exact - a neighbouring cent would re-derive 1234567.88 and stay a genuine BALANCE variance.
        private static final String TARGET_BALANCE = "1341921.62";

        /** The matched fixture's GBP owner: a second differing owner, judged on the same snapshot. */
        private static final String SECOND_RATE_OWNER = "GREG";

        private static final String SECOND_LEGACY_BALANCE = "123456.78";

        // truncate2(0.79 x 156274.41) = truncate2(123456.7839) = 123456.78, the staged GBP rate applied the
        // same way. Two owners rather than one is what proves the snapshot serves the whole loop.
        private static final String SECOND_TARGET_BALANCE = "156274.41";

        @Autowired
        private ReconciliationService liveReconciliationService;

        @Autowired
        private LegacyLoader liveLegacyLoader;

        @Autowired
        private MigrationRunRepository liveMigrationRuns;

        @Autowired
        private MigrationReconciliationTestQueries liveReconciliations;

        @Autowired
        private JdbcTemplate liveJdbc;

        @Test
        void differenceTheBatchesStagedRateExplainsIsAnAcceptedExceptionAndRaisesNoVarianceCount() {
            Path matchedDirectory = fixtureDirectory(MATCHED_FIXTURES);
            UUID batchId = UUID.randomUUID();

            // The load is what stages STOCKTRD.FRANKFURT1 - under ITS run id, which is the whole point of this
            // test: the reconcile below runs under a different run id of the same batch, so a lookup keyed on
            // the reconcile's own run id would find no rate and could never reclassify anything.
            MigrationRun loadRun = MigrationRun.start(UUID.randomUUID(), batchId, MigrationRun.Mode.LOAD,
                    matchedDirectory.toString(), MigrationRun.CharacterizationStatus.DRAFT);
            liveMigrationRuns.save(loadRun);
            LegacyLoader.LoadResult loaded = liveLegacyLoader.load(loadRun, matchedDirectory);
            assertThat(loaded.stagedRateCount()).as("the rate rows the reclassification is judged on").isEqualTo(3);

            liveJdbc.update("UPDATE cash_account SET available_balance = ? WHERE owner = ?",
                    new BigDecimal(TARGET_BALANCE), RATE_OWNER);
            liveJdbc.update("UPDATE cash_account SET available_balance = ? WHERE owner = ?",
                    new BigDecimal(SECOND_TARGET_BALANCE), SECOND_RATE_OWNER);

            // A LATER LOAD OF THE SAME BATCH THAT FAILED, staging nothing - which is what a failed load leaves
            // behind, since it applies its whole export in one transaction or none of it (AAP 0.6.3). It is the
            // most recent load in the batch, so a resolution that ignored status would take its empty staging
            // and reclassify nothing at all.
            MigrationRun failedRetry = MigrationRun.start(UUID.randomUUID(), batchId, MigrationRun.Mode.LOAD,
                    matchedDirectory.toString(), MigrationRun.CharacterizationStatus.DRAFT);
            failedRetry.finish(MigrationRun.Status.FAILED, 0, 0, 0);
            liveMigrationRuns.save(failedRetry);

            UUID reconcileRunId = UUID.randomUUID();
            MigrationRun reconcileRun = MigrationRun.start(reconcileRunId, batchId, MigrationRun.Mode.RECONCILE,
                    matchedDirectory.toString(), MigrationRun.CharacterizationStatus.DRAFT);
            liveMigrationRuns.save(reconcileRun);

            batchLookups.set(0);
            assertThat(liveReconciliationService.reconcile(reconcileRun, matchedDirectory))
                    .as("two authorized differences must not raise the count that drives exit code 2")
                    .isZero();

            // ONE resolution for the whole comparison, whatever the number of differing owners: the staged
            // table is resolved before the owner loop, so the batch's run list is read once and every owner is
            // judged on the same snapshot. Counted by the decorator this context installs over the real
            // repository, so the number is observed rather than argued.
            assertThat(batchLookups.get())
                    .as("migration_run lookups by batch during one reconcile of two differing owners")
                    .isEqualTo(1);

            List<MigrationReconciliation> rows =
                    liveReconciliations.findByRunIdOrderByReconciliationIdAsc(reconcileRunId);
            assertThat(rows).as("both reclassified differences are rows, and the run's only ones").hasSize(2);
            assertThat(rows)
                    .extracting(MigrationReconciliation::owner, MigrationReconciliation::varianceKind,
                            MigrationReconciliation::status)
                    .as("each owner's difference is explained by the rate its own staged row carries")
                    .containsExactlyInAnyOrder(
                            tuple(RATE_OWNER, VarianceKind.RATE_SOURCE, ReconciliationStatus.ACCEPTED_EXCEPTION),
                            tuple(SECOND_RATE_OWNER, VarianceKind.RATE_SOURCE,
                                    ReconciliationStatus.ACCEPTED_EXCEPTION));

            MigrationReconciliation reclassified = rowFor(rows, RATE_OWNER);
            assertThat(reclassified.legacyValue()).isEqualTo(LEGACY_BALANCE);
            assertThat(reclassified.migratedValue()).isEqualTo(TARGET_BALANCE);
            assertThat(reclassified.legacyBalance()).isEqualByComparingTo(new BigDecimal(LEGACY_BALANCE));
            assertThat(reclassified.migratedBalance()).isEqualByComparingTo(new BigDecimal(TARGET_BALANCE));
            assertThat(reclassified.variance())
                    .as("an accepted exception leaves no signed difference outstanding")
                    .isNull();

            MigrationReconciliation second = rowFor(rows, SECOND_RATE_OWNER);
            assertThat(second.legacyBalance()).isEqualByComparingTo(new BigDecimal(SECOND_LEGACY_BALANCE));
            assertThat(second.migratedBalance())
                    .isEqualByComparingTo(new BigDecimal(SECOND_TARGET_BALANCE));
            assertThat(second.variance()).isNull();

            MigrationRun afterReconcile = persistedRun(reconcileRunId);
            assertThat(afterReconcile.varianceCount()).isZero();
            assertThat(afterReconcile.status())
                    .as("a run carrying only accepted exceptions is CLEAN - exit code 0")
                    .isEqualTo(MigrationRun.Status.CLEAN);

            // THE SCOPE CHECK. The same divergence, reconciled under a batch that has staged nothing, must stay
            // a VARIANCE: the rate is resolved from the batch's own completed load, so a reconcile of a batch
            // with no load has no rate to explain anything away with - and a resolution that leaked across
            // batches would silence this second run too.
            UUID unstagedBatchId = UUID.randomUUID();
            UUID unstagedRunId = UUID.randomUUID();
            MigrationRun unstagedRun = MigrationRun.start(unstagedRunId, unstagedBatchId,
                    MigrationRun.Mode.RECONCILE, matchedDirectory.toString(),
                    MigrationRun.CharacterizationStatus.DRAFT);
            liveMigrationRuns.save(unstagedRun);

            assertThat(liveReconciliationService.reconcile(unstagedRun, matchedDirectory))
                    .as("nothing staged under this batch, so neither difference is explained away")
                    .isEqualTo(2);

            List<MigrationReconciliation> unstagedRows =
                    liveReconciliations.findByRunIdOrderByReconciliationIdAsc(unstagedRunId);
            assertThat(unstagedRows)
                    .extracting(MigrationReconciliation::owner, MigrationReconciliation::varianceKind,
                            MigrationReconciliation::status)
                    .containsExactlyInAnyOrder(
                            tuple(RATE_OWNER, VarianceKind.BALANCE, ReconciliationStatus.VARIANCE),
                            tuple(SECOND_RATE_OWNER, VarianceKind.BALANCE, ReconciliationStatus.VARIANCE));
            assertThat(rowFor(unstagedRows, RATE_OWNER).variance())
                    .as("the signed difference the row leaves outstanding, migrated minus legacy")
                    .isEqualByComparingTo(new BigDecimal("107353.73"));
        }

        /** Re-reads a run row from this context's own datasource. */
        private MigrationRun persistedRun(UUID runId) {
            return liveMigrationRuns.findById(runId)
                    .orElseGet(() -> fail("migration_run %s was expected to exist".formatted(runId)));
        }

        /**
         * Installs a counting decorator over {@code MigrationRunRepository} so a test can observe how often one
         * reconcile resolves the batch's load run.
         */
        // A DECORATOR OVER THE REAL REPOSITORY, NOT A DOUBLE. Every call is delegated to the Spring Data bean,
        // so behaviour is the real thing and only the call is counted - which is why this needs no mocking
        // framework, and must not have one: no Java module in the estate uses one and Mockito is excluded from
        // the test stack (AAP 0.7.6). A JDK dynamic proxy is the whole mechanism.
        //
        // @Primary rather than a replacement, so the interface's own bean stays the delegate and every
        // injection point in this context - the service under test included - receives the counting view.
        @TestConfiguration
        static class CountingRunRepositoryConfiguration {

            @Bean
            @Primary
            MigrationRunRepository countingMigrationRuns(
                    @Qualifier("migrationRunRepository") MigrationRunRepository delegate) {
                return (MigrationRunRepository) Proxy.newProxyInstance(
                        MigrationRunRepository.class.getClassLoader(),
                        new Class<?>[] { MigrationRunRepository.class },
                        (proxy, method, args) -> {
                            if (BATCH_LOOKUP_METHOD.equals(method.getName())) {
                                batchLookups.incrementAndGet();
                            }
                            try {
                                return method.invoke(delegate, args);
                            } catch (InvocationTargetException invoked) {
                                // Unwrapped so the caller sees the repository's own exception rather than the
                                // proxy's reflective wrapper, which would change what a failure looks like.
                                throw invoked.getCause();
                            }
                        });
            }
        }
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
