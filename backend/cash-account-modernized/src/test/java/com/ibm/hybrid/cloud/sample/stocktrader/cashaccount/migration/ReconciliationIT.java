package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.tuple;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

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
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.MigrationReconciliationRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.MigrationRunRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.PostgresTestSupport;

/**
 * Proves reconciliation over the real database from the committed fixtures alone (AAP 0.3.2): the matched export
 * yields no finding, the seeded one exactly five.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        // These properties rather than the tool profile, which would run MigrationToolRunner: it ends in
        // System.exit(SpringApplication.exit(...)) and would take the Failsafe JVM with it, so this class plays the
        // runner's part by hand. legacy-table is AAP 0.12.5's parity mode - both sides judged on identical staged
        // rates - stated here so the mode this evidence was produced under is visible, and the record length comes
        // from the CICS FILE definition (DEFKSDS.jcl RECSZ(100 100); AAP 0.11.2) rather than from the file's size.
        properties = { "tool.rate-source=legacy-table", "tool.history-record-length=100",
                // Smaller than every count asserted below, so those row sets are also the evidence that flushing
                // and clearing the persistence context between chunks loses, duplicates and reorders nothing.
                "cashaccount.migration.batch-chunk-size=2",
                // Indexed keys, because application.yml declares cashaccount.fx.accepted-currencies as a YAML
                // sequence that reaches the Environment only as [0..n], and a Binder takes a collection wholly from
                // the highest-priority source carrying it: exactly these four codes are in force, three of them
                // every currency the fixtures use and XTS in no shipped list.
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

    // Named once so the decorator below and the assertion that reads its count cannot drift apart; it is
    // MigrationRunRepository's shared default method, which a JDK proxy intercepts exactly as it does a derived one.
    private static final String BATCH_LOOKUP_METHOD = "findLatestCompletedLoad";

    // Static because the @TestConfiguration that increments it is static, and reset immediately before the call it
    // measures, so a context reused across tests cannot carry a stale count into one.
    private static final AtomicInteger batchLookups = new AtomicInteger();

    // The width of cash_reservation.request_hash, CHAR(64) for a hex-encoded SHA-256 of the canonical hold payload
    // (AAP 0.7.3). No replay is exercised here, so a literal keeps it plain that nothing depends on the digest.
    private static final String TEST_REQUEST_HASH = "0".repeat(64);

    /* Ordered so no foreign key objects: findings before the runs they reference. ledger_entry is deliberately
     * absent - the ledger_entry_immutable trigger rejects UPDATE and DELETE - so ledger rows accumulate across the
     * suite and no assertion here may depend on the database being otherwise empty; the container is shared
     * JVM-wide (support/PostgresTestSupport) and every run identifier below is a fresh UUID. */
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

    // The findings are read back through JPQL below rather than through a finder on
    // persistence/MigrationReconciliationRepository, whose shipped read is a COUNT: nothing the service ships
    // reads a finding row back - the tooling writes one and consumes only its count - and AAP 0.6.1 fixes the
    // test-support file list at three, so the reads live with the assertions that make them. The production
    // repository is injected beside it for that shipped count.
    @Autowired
    private EntityManagerFactory entityManagers;

    @Autowired
    private MigrationReconciliationRepository reconciliationCounts;

    @Autowired
    private CashAccountRepository cashAccounts;

    @Autowired
    private JdbcTemplate jdbc;

    // The drift guard of AAP 0.10.1: the arithmetic asserted here is only trustworthy while the characterization
    // document is present and still cites the COBOL lines it was derived from, so a missing or uncited document
    // fails this class before it asserts anything about a balance.
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
        assertThat(findingsOfRun(loadRunId))
                .as("a consistent export gives its load nothing to record")
                .isEmpty();

        UUID reconcileRunId = UUID.randomUUID();
        MigrationRun reconcileRun = MigrationRun.start(reconcileRunId, batchId, MigrationRun.Mode.RECONCILE,
                matchedDirectory.toString(), MigrationRun.CharacterizationStatus.DRAFT);
        migrationRuns.save(reconcileRun);

        assertThat(reconciliationService.reconcile(reconcileRun, matchedDirectory))
                .as("VARIANCE rows the matched export produces")
                .isZero();

        // An agreeing owner gets no row at all, not even a MATCHED one: MATCHED is the value an operator sets when
        // signing a reviewed finding off, and a row per agreeing owner would bury the seeded ones.
        assertThat(findingsOfRun(reconcileRunId))
                .as("the matched case is zero rows, not six MATCHED ones")
                .isEmpty();
        assertThat(reconciliationCounts.countByRunIdAndStatus(reconcileRunId, ReconciliationStatus.VARIANCE))
                .isZero();

        // Closing the row is the runner's responsibility alone, so the command leaves finishedAt unset.
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

        assertThat(runsOfBatch(entityManagers, batchId))
                .as("the batch is how a reconcile names the load it judges")
                .extracting(MigrationRun::runId, MigrationRun::mode)
                .containsExactly(tuple(loadRunId, MigrationRun.Mode.LOAD),
                        tuple(reconcileRunId, MigrationRun.Mode.RECONCILE));
    }

    @Test
    void seededExportProducesExactlyTheFiveSeededVariances() {
        Path seededDirectory = fixtureDirectory(SEEDED_FIXTURES);
        UUID batchId = UUID.randomUUID();

        // target-state.csv is loaded as the migrated state, as an earlier faulty load would have left it: reconcile
        // compares the export against the database, so the divergence has to be in the database first. Accounts
        // only, because staging the seeded rate export here would record the ZZZ null-rate finding under this run
        // instead of the reconcile's, and all five expected rows belong to the reconcile - which is also why both
        // runs share one batch_id, the batch being how a reconcile names the load it judges.
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
        assertThat(findingsOfRun(targetRunId))
                .as("every target-state row is non-null with an accepted currency, so this load records nothing")
                .isEmpty();

        UUID reconcileRunId = UUID.randomUUID();
        MigrationRun reconcileRun = MigrationRun.start(reconcileRunId, batchId, MigrationRun.Mode.RECONCILE,
                seededDirectory.toString(), MigrationRun.CharacterizationStatus.DRAFT);
        migrationRuns.save(reconcileRun);

        assertThat(reconciliationService.reconcile(reconcileRun, seededDirectory))
                .as("VARIANCE rows the seeded export produces")
                .isEqualTo(5);

        List<MigrationReconciliation> rows = findingsOfRun(reconcileRunId);

        // Asserted over the run's whole row list rather than a status-filtered subset, because this set is the
        // acceptance evidence of AAP 0.10.3: a sixth row must fail here rather than pass silently, and a mismatch
        // is a finding to report and never something to accommodate by relaxing the assertion or editing a fixture.
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

        // KARRI's digit transposition, read through the sign convention MigrationReconciliation declares module-wide
        // (variance = migrated - legacy) so it reads +0.09; every expected decimal is built from text because a
        // double cannot hold a two-decimal balance exactly.
        MigrationReconciliation karri = rowFor(rows, "KARRI");
        assertThat(karri.legacyBalance()).isEqualByComparingTo(new BigDecimal("12345.67"));
        assertThat(karri.migratedBalance()).isEqualByComparingTo(new BigDecimal("12345.76"));
        assertThat(karri.variance()).isEqualByComparingTo(new BigDecimal("0.09"));
        assertThat(karri.variance().scale()).as("the money scale of every balance column").isEqualTo(2);
        assertThat(karri.legacyValue()).isEqualTo("12345.67");
        assertThat(karri.migratedValue()).isEqualTo("12345.76");

        // ERIC's currency changed while both balances stayed identical: the currency and balance checks are
        // independent and each writes at most one row, so this owner produces exactly one.
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

        // GREG is in the export and absent from the migrated state: recorded with no migrated balance, since 0.00
        // would assert a balance the target never held and only an operator can tell a removed row from a partial
        // export.
        MigrationReconciliation greg = rowFor(rows, "GREG");
        assertThat(greg.migratedValue()).isEqualTo("MISSING_IN_TARGET");
        assertThat(greg.legacyValue()).isEqualTo("PRESENT");
        assertThat(greg.legacyBalance()).isEqualByComparingTo(new BigDecimal("123456.78"));
        assertThat(greg.migratedBalance()).isNull();
        assertThat(greg.variance()).as("no signed difference from a balance the target never held").isNull();

        // NULLBAL's empty unquoted balance field is a nullable legacy column (DB2DDL.jcl:L48) recorded by
        // validateSource as it reads the export: the account is not loaded and no value is invented for it.
        MigrationReconciliation nullBalance = rowFor(rows, "NULLBAL");
        assertThat(nullBalance.legacyValue()).isEqualTo("NULL_IN_LEGACY");
        assertThat(nullBalance.legacyBalance()).isNull();
        assertThat(nullBalance.migratedBalance()).isNull();

        // ZZZ's empty rates field, also from validateSource, is what proves the seeded frankfurt1.csv parsed at all:
        // it heads the base-currency column cyrrnbase (DB2DDL.jcl:L56) where matched/ heads it currnbase
        // (DCLFRANK.cpy:L10). Its own empty cyrrnbase and amount produce no finding, because the legacy program
        // fetched both columns and referenced neither in any COMPUTE or MOVE (CASH00.cbl:L215, L249; AAP 0.4.1).
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
        assertThat(findingsOfRun(reconcileRunId, ReconciliationStatus.VARIANCE))
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

        // Written as data rather than arranged through the institutional endpoints, which would make a
        // reconciliation assertion depend on that surface's shape: the reconciler reads exactly cash_account and
        // cash_reservation, so a hold placed this way is indistinguishable to it.
        holdFundsAgainstLoadedAccount(HOLD_OWNER, "250.00", "750.00");

        UUID reconcileRunId = UUID.randomUUID();
        MigrationRun reconcileRun = MigrationRun.start(reconcileRunId, batchId, MigrationRun.Mode.RECONCILE,
                matchedDirectory.toString(), MigrationRun.CharacterizationStatus.DRAFT);
        migrationRuns.save(reconcileRun);

        assertThat(reconciliationService.reconcile(reconcileRun, matchedDirectory))
                .as("one finding for the hold, and nothing else for the five owners that match")
                .isEqualTo(1);

        List<MigrationReconciliation> rows = findingsOfRun(reconcileRunId);
        assertThat(rows).as("the hold is the run's only finding").hasSize(1);

        // The shape the load itself uses when it declines to overwrite such an owner (AAP 0.6.3): no legacy figure
        // and no signed variance, because the difference a hold creates is not money the migration lost.
        MigrationReconciliation onHold = rowFor(rows, HOLD_OWNER);
        assertThat(onHold.varianceKind()).isEqualTo(VarianceKind.STATE);
        assertThat(onHold.status()).isEqualTo(ReconciliationStatus.VARIANCE);
        assertThat(onHold.legacyValue()).isEqualTo("PRESENT");
        assertThat(onHold.migratedValue()).isEqualTo("RESERVATIONS_OUTSTANDING");
        assertThat(onHold.migratedBalance()).isEqualByComparingTo(new BigDecimal("750.00"));
        assertThat(onHold.legacyBalance()).isNull();
        assertThat(onHold.variance()).isNull();

        // The export carries 1000.00 for this owner while the target's available balance reads 750.00, so a
        // comparison that ran anyway would report a 250.00 BALANCE variance describing the hold rather than a
        // migration difference.
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

        List<MigrationReconciliation> rows = findingsOfRun(reconcileRunId);
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

    private UUID insertAccountAbsentFromTheExport(String owner, String availableBalance) {
        UUID incarnation = UUID.randomUUID();
        jdbc.update("INSERT INTO cash_account (owner, incarnation_id, currency, available_balance,"
                        + " reserved_balance) VALUES (?, ?, 'USD', ?, 0.00)",
                owner, incarnation, new BigDecimal(availableBalance));
        return incarnation;
    }

    // Written through SQL because ledger_entry is append-only: LedgerEntryRepository publishes no findAll and no
    // delete, and these rows are a precondition about who wrote an owner's history rather than an event the
    // service produced.
    private void appendLedgerRow(String owner, UUID incarnation, String eventType, String source,
                                 String amount) {
        jdbc.update("INSERT INTO ledger_entry (owner, incarnation_id, event_type, amount, currency,"
                        + " available_after, reserved_after, source) VALUES (?, ?, ?, ?, 'USD', ?, 0.00, ?)",
                owner, incarnation, eventType, new BigDecimal(amount), new BigDecimal(amount), source);
    }

    // Two-sided, because one side alone proves nothing: XTS is accepted although no shipped list contains it, so
    // the configured value must have reached the reconciler, and CHF is rejected although the estate allowlist in
    // application.yml contains it, so no set of the class's own can still be in force. validateSource is driven
    // with two synthetic rows rather than through a fixture because the fixtures use only the three codes this
    // override keeps accepted, and their row sets are the AAP 0.10.3 acceptance evidence, which must not move to
    // accommodate a test.
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

        assertThat(findingsOfRun(runId))
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

    /** Every finding one run recorded, in insertion order. */
    // Ordered by the generated identity because row order here is evidence order: the acceptance criteria are
    // "zero rows" for a matched fixture and "exactly the seeded rows" for a seeded one, and a set can only be
    // read as exact when the query fixes both its scope and its sequence - PostgreSQL is free to return rows in
    // any order without an ORDER BY. The scope is the run rather than the batch because a reconcile writes its
    // findings, source-validation rows included, under its own run_id.
    //
    // Static and factory-parameterized so the nested live-rate context can use it with its own factory. A
    // short-lived EntityManager, closed whatever happens, keeps the read outside any transaction of the code
    // under test while returning the real entity, so every assertion judges the persisted values.
    private static List<MigrationReconciliation> findingsOfRun(EntityManagerFactory entityManagers, UUID runId) {
        EntityManager entityManager = entityManagers.createEntityManager();
        try {
            return entityManager
                    .createQuery("select finding from MigrationReconciliation finding"
                            + " where finding.runId = :runId order by finding.reconciliationId asc",
                            MigrationReconciliation.class)
                    .setParameter("runId", runId)
                    .getResultList();
        } finally {
            entityManager.close();
        }
    }

    /** Every run of a batch, oldest first, as the evidence of one runbook step. */
    // Read here rather than through the repository because MigrationRunRepository publishes one selector - the
    // batch's completed load - and no broad batch list, which is what stopped its three production callers from
    // each filtering such a list into a rule of their own. run_id breaks a started_at tie exactly as the
    // selector does, so this listing cannot depend on the order two identically stamped rows come back in.
    private static List<MigrationRun> runsOfBatch(EntityManagerFactory entityManagers, UUID batchId) {
        EntityManager entityManager = entityManagers.createEntityManager();
        try {
            return entityManager
                    .createQuery("select run from MigrationRun run where run.batchId = :batchId"
                            + " order by run.startedAt asc, run.runId asc", MigrationRun.class)
                    .setParameter("batchId", batchId)
                    .getResultList();
        } finally {
            entityManager.close();
        }
    }

    private List<MigrationReconciliation> findingsOfRun(UUID runId) {
        return findingsOfRun(entityManagers, runId);
    }

    private List<MigrationReconciliation> findingsOfRun(UUID runId, ReconciliationStatus status) {
        EntityManager entityManager = entityManagers.createEntityManager();
        try {
            return entityManager
                    .createQuery("select finding from MigrationReconciliation finding"
                            + " where finding.runId = :runId and finding.status = :status"
                            + " order by finding.reconciliationId asc", MigrationReconciliation.class)
                    .setParameter("runId", runId)
                    .setParameter("status", status)
                    .getResultList();
        } finally {
            entityManager.close();
        }
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
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
            // A context of its own because ReconciliationService's constructor binds tool.rate-source, fixing the
            // mode when the bean is built, and both properties are restated in full so this context configures
            // itself whatever nested-configuration inheritance does later.
            properties = { "tool.rate-source=live", "tool.history-record-length=100" })
    @ActiveProfiles("test")
    class LiveRateSourceMode extends PostgresTestSupport {

        /** The matched fixture's EUR owner: 1234567.89 EUR against a staged EUR rate of 0.92. */
        private static final String RATE_OWNER = "ERIC";

        private static final String LEGACY_BALANCE = "1234567.89";

        // truncate2(0.92 x 1341921.62) = truncate2(1234567.8904) = 1234567.89, the legacy COMPUTE of
        // CASH00.cbl:L222 applied from zero, so the staged rate re-derives the legacy balance to the cent. It has
        // to be exact: a neighbouring cent would re-derive 1234567.88 and stay a genuine BALANCE variance.
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

        // This context's own factory: @Nested carries a Spring context of its own, so the enclosing class's
        // injected factory would read through a different datasource pool than the reconcile under test wrote to.
        @Autowired
        private EntityManagerFactory liveEntityManagers;

        @Autowired
        private JdbcTemplate liveJdbc;

        @Test
        void differenceTheBatchesStagedRateExplainsIsAnAcceptedExceptionAndRaisesNoVarianceCount() {
            Path matchedDirectory = fixtureDirectory(MATCHED_FIXTURES);
            UUID batchId = UUID.randomUUID();

            // The load stages STOCKTRD.FRANKFURT1 under its own run id, which is the point of this test: the
            // reconcile runs under a different run id of the same batch, so a lookup keyed on the reconcile's own
            // run id would find no rate and could never reclassify anything.
            MigrationRun loadRun = MigrationRun.start(UUID.randomUUID(), batchId, MigrationRun.Mode.LOAD,
                    matchedDirectory.toString(), MigrationRun.CharacterizationStatus.DRAFT);
            liveMigrationRuns.save(loadRun);
            LegacyLoader.LoadResult loaded = liveLegacyLoader.load(loadRun, matchedDirectory);
            assertThat(loaded.stagedRateCount()).as("the rate rows the reclassification is judged on").isEqualTo(3);

            // Closed exactly as MigrationToolRunner closes a load with no variance, because that is what makes
            // this run the batch's COMPLETED load: a load's staged rows may be read only once its single
            // transaction has landed and the row says so (AAP 0.6.3), and the reconcile below resolves the
            // batch's rates through that status.
            loadRun.finish(MigrationRun.Status.CLEAN, loaded.legacyRecordCount(), loaded.migratedRecordCount(),
                    loaded.varianceCount());
            liveMigrationRuns.save(loadRun);

            liveJdbc.update("UPDATE cash_account SET available_balance = ? WHERE owner = ?",
                    new BigDecimal(TARGET_BALANCE), RATE_OWNER);
            liveJdbc.update("UPDATE cash_account SET available_balance = ? WHERE owner = ?",
                    new BigDecimal(SECOND_TARGET_BALANCE), SECOND_RATE_OWNER);

            // A later load of the same batch that failed, staging nothing, since a load applies its whole export in
            // one transaction or none of it (AAP 0.6.3). It is the most recent load in the batch, so a resolution
            // that ignored status would take its empty staging and reclassify nothing at all.
            MigrationRun failedRetry = MigrationRun.start(UUID.randomUUID(), batchId, MigrationRun.Mode.LOAD,
                    matchedDirectory.toString(), MigrationRun.CharacterizationStatus.DRAFT);
            failedRetry.finish(MigrationRun.Status.FAILED, 0, 0, 0);
            liveMigrationRuns.save(failedRetry);

            // And a later one still RUNNING, the newest load of the batch. This is the state an in-flight
            // attempt has, and the state an interrupted one is left in when its JVM dies before the row can be
            // closed: its single transaction has staged nothing that a reader may see, and its rows will either
            // arrive on commit or vanish on rollback. Excluding FAILED alone is therefore not enough - a
            // resolution that took "not FAILED" for "completed" would pick this row over the CLEAN load below
            // it, find no staged rate, and turn both authorized differences into outstanding BALANCE variances.
            // The assertions that follow hold only because the completed load is the one resolved.
            MigrationRun inFlightRetry = MigrationRun.start(UUID.randomUUID(), batchId, MigrationRun.Mode.LOAD,
                    matchedDirectory.toString(), MigrationRun.CharacterizationStatus.DRAFT);
            liveMigrationRuns.save(inFlightRetry);
            assertThat(persistedRun(inFlightRetry.runId()).status())
                    .as("the newest load of the batch is RUNNING when the reconcile below resolves its rates")
                    .isEqualTo(MigrationRun.Status.RUNNING);

            UUID reconcileRunId = UUID.randomUUID();
            MigrationRun reconcileRun = MigrationRun.start(reconcileRunId, batchId, MigrationRun.Mode.RECONCILE,
                    matchedDirectory.toString(), MigrationRun.CharacterizationStatus.DRAFT);
            liveMigrationRuns.save(reconcileRun);

            batchLookups.set(0);
            assertThat(liveReconciliationService.reconcile(reconcileRun, matchedDirectory))
                    .as("two authorized differences must not raise the count that drives exit code 2")
                    .isZero();

            // One resolution per comparison, whatever the number of differing owners: the staged table is resolved
            // before the owner loop, so every owner is judged on the same snapshot.
            assertThat(batchLookups.get())
                    .as("migration_run lookups by batch during one reconcile of two differing owners")
                    .isEqualTo(1);

            List<MigrationReconciliation> rows = findingsOfRun(liveEntityManagers, reconcileRunId);
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

            // The same divergence must stay a VARIANCE under a batch that staged nothing: the rate is resolved from
            // the batch's own completed load, and a resolution that leaked across batches would silence this run.
            UUID unstagedBatchId = UUID.randomUUID();
            UUID unstagedRunId = UUID.randomUUID();
            MigrationRun unstagedRun = MigrationRun.start(unstagedRunId, unstagedBatchId,
                    MigrationRun.Mode.RECONCILE, matchedDirectory.toString(),
                    MigrationRun.CharacterizationStatus.DRAFT);
            liveMigrationRuns.save(unstagedRun);

            assertThat(liveReconciliationService.reconcile(unstagedRun, matchedDirectory))
                    .as("nothing staged under this batch, so neither difference is explained away")
                    .isEqualTo(2);

            List<MigrationReconciliation> unstagedRows = findingsOfRun(liveEntityManagers, unstagedRunId);
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
        @TestConfiguration
        static class CountingRunRepositoryConfiguration {

            // A JDK proxy delegating every call to the Spring Data bean, not a double: behaviour stays the real
            // thing and only the call is counted, so no mocking framework is needed - Mockito is excluded from the
            // test stack (AAP 0.7.6). @Primary rather than a replacement leaves that bean as the delegate while
            // every injection point in this context, the service under test included, sees the counting view.
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

    // Resolved from the test classpath rather than a module-relative path, which is what makes it correct for
    // Failsafe, an IDE runner and a rerun from another working directory alike. Fails and never skips: an absent
    // fixture means the acceptance evidence of AAP 0.10.3 cannot be produced, and a skipped test reports success.
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
