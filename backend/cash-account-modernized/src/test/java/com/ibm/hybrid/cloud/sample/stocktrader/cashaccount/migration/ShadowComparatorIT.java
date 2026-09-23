package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.tuple;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
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
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
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
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.retail.RetailCashAccountService;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.PostgresTestSupport;

/**
 * Proves the dual-run comparator on the committed shadow captures alone (AAP 0.3.1-0.3.2, 0.10.6), and that its
 * evidence survives a lost window.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // Mandatory here rather than inherited: ShadowComparator binds tool.rate-source with @Value and
                // application-test.yml owns no tool.* key, the tool profile staying off because
                // MigrationToolRunner's System.exit(SpringApplication.exit(...)) would end the Failsafe JVM.
                // legacy-table is the AAP 0.12.5 parity gate - both sides priced from the same staged RATES - so a
                // balance difference cannot be a rate difference and no RATE_SOURCE row can arise.
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

    /** {@code migration_run.source_path} for the windows built in memory rather than read from a fixture. */
    private static final String IN_MEMORY_WINDOW = "in-memory capture (no fixture shape carries it)";

    // The GBP rate the live-mode phase stages under the batch's COMPLETED load, deliberately different from the
    // corpus rate of 0.79 the replay is priced at, so the two sides differ by an amount only this row
    // re-derives. Two decimals because RATES is DECIMAL(3,2) (DB2DDL.jcl:L58; DCLFRANK.cpy:L12).
    private static final BigDecimal COMPLETED_LOAD_GBP_RATE = new BigDecimal("0.50");

    // credit(123456.78, 0.50, 100.00) = 123506.78, the legacy COMPUTE of CASH00.cbl:L222 applied to GREG's
    // corpus balance at the rate above. Stated as text because a double cannot hold it exactly (AAP 0.7.1).
    private static final BigDecimal CAPTURED_GBP_BALANCE = new BigDecimal("123506.78");

    /** GREG's balance after the same credit priced at the corpus rate of 0.79, which is what the target replays. */
    private static final BigDecimal TARGET_GBP_BALANCE = new BigDecimal("123535.78");

    // Two more GBP rates for the staging-resolution phase, distinct from each other, from the corpus rate and
    // from the live phase's, so an assertion naming one of them can only be satisfied by the load that staged it.
    private static final BigDecimal OLDER_LOAD_GBP_RATE = new BigDecimal("0.30");

    private static final BigDecimal NEWER_LOAD_GBP_RATE = new BigDecimal("0.40");

    @Autowired
    private ShadowComparator shadowComparator;

    @Autowired
    private LegacyLoader legacyLoader;

    @Autowired
    private MigrationRunRepository migrationRuns;

    // The runner is built with the production repository because its failure accounting reads the rows a command
    // left behind, and its shipped read is a COUNT; the row-returning reads this class asserts over are JPQL below,
    // so no query that exists only for a test sits on the production interface or beside it (AAP 0.6.1 fixes the
    // test-support file list at three).
    @Autowired
    private MigrationReconciliationRepository reconciliationRepository;

    @Autowired
    private EntityManagerFactory entityManagers;

    @Autowired
    private CashAccountRepository accounts;

    @Autowired
    private LegacyRateTableRepository legacyRates;

    @Autowired
    private ReconciliationService reconciliationService;

    // Injected only so the live-mode phase below can build a comparator of its own; every other replay in this
    // class goes through the context's comparator.
    @Autowired
    private RetailCashAccountService retailService;

    // Handed to the runner because its constructor requires it, though only run(...) - never called here - uses it.
    @Autowired
    private ConfigurableApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StagedLegacyRateSource stagedRates;

    // The run the current test's corpus load staged its rates under, so a phase that repoints the staged source
    // can hand it back and leave the reset's state for whatever runs after it.
    private UUID corpusStagingRunId;

    // The drift guard of AAP 0.10.1: the fixtures' expected values come from the characterization document's
    // formula, so a run against a missing or gutted document would assert arithmetic with no authority behind it.
    @BeforeAll
    static void characterizationDocumentIsPresent() {
        CharacterizationDocPresentTest.requireCharacterizationDocument();
    }

    // ledger_entry is deliberately absent from this reset: the ledger_entry_immutable trigger rejects every UPDATE
    // and DELETE (schema/cash-account-schema.sql), the audit guarantee of AAP 0.7.4 rather than an obstacle to work
    // around, so replay rows accumulate across the classes sharing this JVM-wide container. Harmless because every
    // assertion below is scoped by run id or owner, and each reset takes a fresh run id, which also satisfies the
    // partial unique index uq_ledger_entry_migration_load on (run_id, owner).
    @BeforeEach
    void restoreStartingState() {
        // FK-safe order: migration_reconciliation.run_id references migration_run.
        jdbcTemplate.update("DELETE FROM migration_reconciliation");
        jdbcTemplate.update("DELETE FROM migration_run");
        jdbcTemplate.update("DELETE FROM legacy_history");
        jdbcTemplate.update("DELETE FROM legacy_rate_table");
        jdbcTemplate.update("DELETE FROM cash_reservation");
        jdbcTemplate.update("DELETE FROM cash_account");

        Path corpus = fixtureDirectory(LEGACY_EXPORT_FIXTURES);
        MigrationRun corpusLoad = migrationRuns.save(MigrationRun.start(UUID.randomUUID(), UUID.randomUUID(),
                MigrationRun.Mode.LOAD, corpus.toString(), MigrationRun.CharacterizationStatus.DRAFT));

        // Named explicitly rather than through LoadSources.inDirectory, which would resolve the history.cp037.bin
        // this directory carries and then demand tool.history-record-length - a property no shadow window needs,
        // since what this class replays is a capture stream and not the legacy audit file.
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

        this.corpusStagingRunId = corpusLoad.runId();

        // A staged rate source is wired in because profile test's only ExchangeRateSource bean is the live client
        // pointed at the refused port 127.0.0.1:1 (application-test.yml), so the GREG (GBP) and ERIC (EUR) replays
        // would return 503 EXCHANGE_RATE_UNAVAILABLE and the matched window would report rejections instead of
        // parity - and parity must anyway be judged on the very RATES the legacy arithmetic used (AAP 0.12.5),
        // with no test reaching the public API. The run is handed over per reset, the rows belonging to this load.
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

        // The whole row set, not just its VARIANCE rows: an agreeing capture line gets no row at all, not even a
        // MATCHED one, so an invented row of any status fails this.
        assertThat(findingsOfRun(runId))
                .as("a clean window records nothing")
                .isEmpty();
        assertThat(reconciliationRepository.countByRunIdAndStatus(runId, ReconciliationStatus.VARIANCE)).isZero();

        // The comparator neither saves nor finishes the run - closing it is the runner's job, reproduced here so
        // the persisted verdict is asserted rather than assumed.
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

        // Target state moved, rather than the comparator merely declining to find fault. RAUNAK's X-then-A pair at
        // seq 8/9 is ordered that way in the fixture because RAUNAK is already in the corpus, so a create before
        // the delete would collide (409/-803) and destroy the zero-variance guarantee.
        assertThat(balanceOf("KARRI")).isEqualByComparingTo(new BigDecimal("12000.00"));
        assertThat(balanceOf("GREG")).isEqualByComparingTo(new BigDecimal("123535.78"));
        assertThat(balanceOf("ERIC")).isEqualByComparingTo(new BigDecimal("1234567.61"));
        assertThat(balanceOf("RAUNAK")).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(currencyOf("GREG")).isEqualTo("GBP");
        assertThat(currencyOf("ERIC")).isEqualTo("EUR");
    }

    /**
     * A captured window larger than the declared limit: refused naming the limit, with no evidence row written.
     */
    // compare(run, directory) reads both captures into lists, which is right for a window an operator reviews row
    // by row at runbook Step 2 and wrong for anything larger: an unbounded read of a mis-transferred bulk export
    // would exhaust the tool JVM instead of saying which file to split (CWE-400). A test of its own rather than a
    // phase of the seeded scenario because no committed fixture may carry an oversized window. The row count is
    // taken from LegacyExportFormat.MAX_WINDOW_RECORDS so the test moves with the limit.
    @Test
    void refusesACapturedWindowLargerThanTheDeclaredLimit(@TempDir Path window) throws IOException {
        Path transactionsFile = window.resolve(LegacyExportFormat.SHADOW_TRANSACTIONS_FILE);
        try (BufferedWriter capture = Files.newBufferedWriter(transactionsFile, StandardCharsets.UTF_8)) {
            capture.write(String.join(",", LegacyExportFormat.SHADOW_TRANSACTION_COLUMNS));
            capture.write("\n");
            // One line past the limit, so the refusal is the limit itself rather than any property of a row: every
            // line is a well-formed Q read of an owner the corpus holds, with no amount and no currency.
            for (int seq = 1; seq <= LegacyExportFormat.MAX_WINDOW_RECORDS + 1; seq++) {
                capture.write(seq + ",JOHN,Q,,\n");
            }
        }

        // Required by the window's contract and deliberately header-only: the transactions capture is read first,
        // so no reply of this file is ever reached and inventing content for them would assert nothing.
        Files.writeString(window.resolve(LegacyExportFormat.SHADOW_LEGACY_RESPONSES_FILE),
                String.join(",", LegacyExportFormat.SHADOW_LEGACY_RESPONSE_COLUMNS) + "\n");

        UUID runId = UUID.randomUUID();
        MigrationRun run = migrationRuns.save(MigrationRun.start(runId, UUID.randomUUID(),
                MigrationRun.Mode.SHADOW, window.toString(), MigrationRun.CharacterizationStatus.DRAFT));

        // The real path, because that is the contained path the comparator resolves the capture to and names.
        Path refusedCapture = window.toRealPath().resolve(LegacyExportFormat.SHADOW_TRANSACTIONS_FILE);
        assertThatThrownBy(() -> shadowComparator.compare(run, window))
                .as("an oversized capture is an input error that names the file and the limit, never a variance")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(refusedCapture.toString())
                .hasMessageContaining(String.valueOf(LegacyExportFormat.MAX_WINDOW_RECORDS));

        assertThat(findingsOfRun(runId))
                .as("a window refused before its first replay records nothing under the run")
                .isEmpty();
    }

    /**
     * The seeded window's evidence in three phases - the seeded rows, then the two conditions no committed fixture
     * can express - phased rather than split because AAP 0.7.6 allocates this file two tests.
     */
    @Test
    void seededStreamProducesExactlyTheThreeSeededRows() {
        Path window = fixtureDirectory(SEEDED_SHADOW_FIXTURES);
        UUID runId = UUID.randomUUID();
        MigrationRun run = migrationRuns.save(MigrationRun.start(runId, UUID.randomUUID(),
                MigrationRun.Mode.SHADOW, window.toString(), MigrationRun.CharacterizationStatus.DRAFT));

        int variances = shadowComparator.compare(run, window);

        // Two, not three: RAUNAK's row is an ACCEPTED_EXCEPTION, and only VARIANCE feeds variance_count and the
        // exit code, because collapsing the two would make a characterized legacy quirk look like a defect.
        assertThat(variances)
                .as("the seeded window's two outstanding differences are JOHN's balance and RYAN's count")
                .isEqualTo(2);

        List<MigrationReconciliation> rows = findingsOfRun(runId);

        // Over the run's full row set, which AAP 0.10.3 fixes at exactly three: a missing row is a hole in the
        // comparator and a fourth is one it invented.
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

        // JOHN's request is byte-identical to the matched window's and only the captured reply differs. Under the
        // module-wide convention variance = migrated - legacy, a target holding a dime less reads -0.10.
        MigrationReconciliation john = row(rows, "JOHN", VarianceKind.BALANCE);
        assertThat(john.legacyBalance()).isEqualByComparingTo(new BigDecimal("1250.60"));
        assertThat(john.migratedBalance()).isEqualByComparingTo(new BigDecimal("1250.50"));
        assertThat(john.variance()).isEqualByComparingTo(new BigDecimal("-0.10"));
        assertThat(john.variance().scale()).isEqualTo(2);
        assertThat(john.legacyValue()).isEqualTo("1250.60");
        assertThat(john.migratedValue()).isEqualTo("1250.50");

        // RAUNAK's 100.00 - 1.00 x 150.00 is raw -50.00, which the unsigned WS-CALC PIC 9(7)V99 stored as its
        // absolute value 50.00 (CASH00.cbl:L17 field, L255-L256 COMPUTE). The target refuses the over-debit with
        // 422 INSUFFICIENT_FUNDS, the authorized behavioural deviation of AAP 0.4.6 and 0.14.2, so the row is an
        // ACCEPTED_EXCEPTION rather than a defect and the target wrote no migrated balance to assert.
        MigrationReconciliation raunak = row(rows, "RAUNAK", VarianceKind.REJECTED_BY_TARGET);
        assertThat(raunak.legacyValue()).isEqualTo("50.00");
        assertThat(raunak.legacyBalance()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(raunak.migratedValue()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(raunak.migratedBalance()).isNull();
        assertThat(raunak.variance()).isNull();
        assertThat(balanceOf("RAUNAK"))
                .as("a refused debit leaves the balance exactly as the corpus load left it")
                .isEqualByComparingTo(new BigDecimal("100.00"));

        // RYAN's seed is the reply at seq 7 that no transaction pairs with. The comparator joins on seq plus the
        // normalized owner rather than file order - EBCDIC and UTF-8 collate differently (AAP 0.12.2) - which is
        // what lets an unpaired successful reply count toward the legacy total without being replayed. A target
        // shortfall cannot be explained by the DUPREC lower bound (CASH00.cbl:L124), hence VARIANCE.
        MigrationReconciliation ryan = row(rows, "RYAN", VarianceKind.TRANSACTION_COUNT);
        assertThat(ryan.legacyValue()).isEqualTo("5");
        assertThat(ryan.migratedValue()).isEqualTo("4");
        // A count is not money: rendering 0.00 on this row would read as monetary agreement.
        assertThat(ryan.legacyBalance()).isNull();
        assertThat(ryan.migratedBalance()).isNull();
        assertThat(ryan.variance()).isNull();

        // The absences asserted rather than inferred from the size: RAUNAK's refused line still counts as
        // processed, so its counts agree at 1/1 and it gets no TRANSACTION_COUNT row, and no RATE_SOURCE row can
        // arise under tool.rate-source=legacy-table.
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
        // VARIANCE with variance_count 2 is what MigrationToolRunner turns into exit code 2; the third row raises
        // neither, an accepted exception being recorded for the evidence trail and never counted.
        assertThat(closed.status()).isEqualTo(MigrationRun.Status.VARIANCE);
        assertThat(closed.varianceCount()).isEqualTo(2);
        assertThat(reconciliationRepository.countByRunIdAndStatus(runId, ReconciliationStatus.ACCEPTED_EXCEPTION))
                .isEqualTo(1);

        malformedCaptureIsRecordedWithAnExplicitAbsenceToken();
        stagedRateLookupFallsBackToTheSchemasLatestCompletedLoad();
        anUnpriceableCrossCurrencyLineStaysAnOutstandingVariance();
        lostWindowKeepsItsProgressAndItsCommittedFindings();
        liveExplanationResolvesTheBatchsCompletedLoadAndNotANewerRunningOne();
    }

    /**
     * A window whose own batch holds no load prices from the schema's most recent completed load, while a batch
     * that does hold one still resolves to it.
     */
    // The dual-run step replays one window per invocation, each with its own --tool.batch-id (AAP 0.3.3 Step 2),
    // so the staged rates a cross-currency replay must be priced from were staged by the migration step's load
    // under a different batch. Resolving the batch alone made every such window fail for want of a rate, and the
    // window then closed clean having priced nothing. Driven through the production LegacyRateTableSource's
    // interface method - the one the replay reaches through ToolExchangeRateSource - because that method's own
    // resolution is what changed; the run-explicit overload the class's test double uses never consults a batch.
    // started_at is pinned rather than left to two consecutive clock reads, so "most recent" is a fact of the
    // rows and not of the clock's granularity.
    private void stagedRateLookupFallsBackToTheSchemasLatestCompletedLoad() {
        UUID olderBatch = UUID.randomUUID();
        UUID newerBatch = UUID.randomUUID();
        UUID olderLoad = completedLoadStagingGbpRate(olderBatch, OLDER_LOAD_GBP_RATE, 120);
        UUID newerLoad = completedLoadStagingGbpRate(newerBatch, NEWER_LOAD_GBP_RATE, 60);

        assertThat(migrationRuns.findLatestCompletedLoadInSchema().map(MigrationRun::runId))
                .as("the schema's most recent completed load is the one started later")
                .contains(newerLoad);

        // A window's own batch, holding no load at all: the documented shape, and the one that used to price
        // nothing. It resolves the newer of the two, which is the state a Step 1 load leaves behind.
        assertThat(rateSourceForBatch(UUID.randomUUID()).rate("USD", "GBP"))
                .as("a window whose batch holds no load prices from the schema's latest completed load")
                .isEqualByComparingTo(NEWER_LOAD_GBP_RATE);

        // And the batch-scoped resolution is untouched: naming the older batch prices from ITS load even though
        // a newer completed load exists, which is what keeps a reconcile judging the load it names (AAP 0.6.3).
        assertThat(rateSourceForBatch(olderBatch).rate("USD", "GBP"))
                .as("a batch that holds a completed load still resolves to it, newer loads notwithstanding")
                .isEqualByComparingTo(OLDER_LOAD_GBP_RATE);
        assertThat(olderLoad).isNotEqualTo(newerLoad);
    }

    /**
     * A cross-currency line the target could not price: recorded as an outstanding {@code VARIANCE}, so the
     * window's variance count and exit code both move.
     */
    // The dual-run gate is "zero VARIANCE rows for N consecutive windows" (AAP 0.3.3 Step 2), and
    // EXCHANGE_RATE_UNAVAILABLE used to be classified as an authorized deviation - which made a window in which
    // every cross-currency transaction went unpriced indistinguishable from one in which every balance agreed.
    // A captured legacy reply that succeeded proves the legacy HAD its rate, so a target that cannot price the
    // same line is reporting its own environment and not a characterized difference in behaviour.
    private void anUnpriceableCrossCurrencyLineStaysAnOutstandingVariance() {
        BigDecimal beforeReplay = balanceOf("GREG");

        // A staging run that staged nothing, which is what an unresolvable rate looks like from the service's
        // side: LegacyRateTableSource finds no FRANKFURT1 row and the replay is refused 503, balance untouched.
        stagedRates.useStagingRun(UUID.randomUUID());

        UUID runId = UUID.randomUUID();
        MigrationRun run = migrationRuns.save(MigrationRun.start(runId, UUID.randomUUID(),
                MigrationRun.Mode.SHADOW, IN_MEMORY_WINDOW, MigrationRun.CharacterizationStatus.DRAFT));

        int variances = shadowComparator.compare(run,
                List.of(new ShadowTransaction(1L, "GREG", "C", new BigDecimal("100.00"), "GBP")),
                List.of(new ShadowLegacyResponse(1L, "GREG", SUCCESS_RETCODE, TARGET_GBP_BALANCE)));

        assertThat(variances)
                .as("an unpriced cross-currency line must raise the window's variance count, and with it the"
                        + " exit code the Step 2 gate reads")
                .isEqualTo(1);

        List<MigrationReconciliation> rows = findingsOfRun(runId);
        assertThat(rows).as("the window's only finding").hasSize(1);

        MigrationReconciliation refused = row(rows, "GREG", VarianceKind.REJECTED_BY_TARGET);
        assertThat(refused.status())
                .as("a rate the target could not obtain is an outstanding difference, not an accepted one")
                .isEqualTo(ReconciliationStatus.VARIANCE);
        assertThat(refused.migratedValue()).isEqualTo(CashAccountErrorCode.EXCHANGE_RATE_UNAVAILABLE.name());
        assertThat(refused.legacyBalance()).isEqualByComparingTo(TARGET_GBP_BALANCE);
        assertThat(refused.migratedBalance())
                .as("the target produced no balance to compare")
                .isNull();
        assertThat(balanceOf("GREG"))
                .as("a refused credit leaves the balance exactly as it was")
                .isEqualByComparingTo(beforeReplay);

        // Handed back so any phase after this one prices from the corpus again, exactly as the reset left it.
        stagedRates.useStagingRun(corpusStagingRunId);
    }

    /**
     * Saves a completed {@code LOAD} run with one staged GBP rate, at a pinned start time.
     *
     * @param batchId         the batch the load belongs to
     * @param gbpRate         the {@code RATES} value staged under it
     * @param secondsBackdated how far before now its {@code started_at} is pinned
     * @return the run id of the completed load
     */
    // The rate row is inserted rather than loaded from a file for the reason the live-mode phase gives: no
    // committed fixture may carry a rate that contradicts the corpus it ships beside. started_at is pinned in the
    // same statement because the selector under test orders by it.
    private UUID completedLoadStagingGbpRate(UUID batchId, BigDecimal gbpRate, int secondsBackdated) {
        MigrationRun load = MigrationRun.start(UUID.randomUUID(), batchId, MigrationRun.Mode.LOAD,
                IN_MEMORY_WINDOW, MigrationRun.CharacterizationStatus.DRAFT);
        load.finish(MigrationRun.Status.CLEAN, 1, 1, 0);
        migrationRuns.save(load);
        jdbcTemplate.update("UPDATE migration_run SET started_at = now() - make_interval(secs => ?)"
                + " WHERE run_id = ?", secondsBackdated, load.runId());
        jdbcTemplate.update("INSERT INTO legacy_rate_table (run_id, currnkey, currnbase, amount, rates)"
                + " VALUES (?, 'GBP', 'USD', 1.00, ?)", load.runId(), gbpRate);
        return load.runId();
    }

    // The production source as a shadow window reaches it: bound to one --tool.batch-id through an Environment,
    // exactly as the container binds it, so the resolution under test is its own and not this test's.
    private LegacyRateTableSource rateSourceForBatch(UUID batchId) {
        return new LegacyRateTableSource(legacyRates, migrationRuns, batchId.toString());
    }

    /**
     * A success reply that carried no balance: the row must name the absent side rather than leave it blank, a
     * shape no fixture can carry because the fixture contract requires every success line to carry a balance.
     */
    private void malformedCaptureIsRecordedWithAnExplicitAbsenceToken() {
        UUID runId = UUID.randomUUID();
        MigrationRun run = migrationRuns.save(MigrationRun.start(runId, UUID.randomUUID(),
                MigrationRun.Mode.SHADOW, IN_MEMORY_WINDOW, MigrationRun.CharacterizationStatus.DRAFT));

        // A Q read of an owner the corpus holds, paired with a reply claiming success (a zero retcode,
        // CASH00.cbl:L104) that states no balance, so the legacy side offers nothing to compare against.
        BigDecimal targetBalance = balanceOf("JOHN");
        int variances = shadowComparator.compare(run,
                List.of(new ShadowTransaction(1L, "JOHN", "Q", null, null)),
                List.of(new ShadowLegacyResponse(1L, "JOHN", SUCCESS_RETCODE, null)));

        assertThat(variances)
                .as("a comparison with nothing on the legacy side fails closed as one outstanding variance")
                .isEqualTo(1);

        List<MigrationReconciliation> rows = findingsOfRun(runId);
        assertThat(rows)
                .as("one row for the malformed line, and no TRANSACTION_COUNT row: Q changed no state, so it is"
                        + " counted on neither side")
                .hasSize(1);

        MigrationReconciliation absent = row(rows, "JOHN", VarianceKind.BALANCE);
        assertThat(absent.status()).isEqualTo(ReconciliationStatus.VARIANCE);
        // The absent side is named: without the token the finding would carry a null legacy_value beside a null
        // balance and a null variance, indistinguishable from a comparison the tooling failed to complete.
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
     * A live-mode difference is attributed to the rate of the batch's completed load, never to a newer load
     * still running.
     */
    // A phase of the seeded scenario rather than a test of its own: AAP 0.7.6 allocates this file two tests, and
    // no committed fixture can express a batch holding two loads in different states with a staged rate that
    // differs from the replay's price. A comparator is built here because tool.rate-source reaches
    // ShadowComparator through its constructor, so live mode cannot be selected inside the parity-gate context;
    // the collaborators handed to it are the proxied beans, so every transaction boundary inside the replay is
    // the production one. The staged rate belongs to the CLEAN load alone and the RUNNING load staged nothing
    // (AAP 0.6.3), so the ACCEPTED_EXCEPTION asserted below is reachable only while the completed load is the
    // one resolved.
    private void liveExplanationResolvesTheBatchsCompletedLoadAndNotANewerRunningOne() {
        UUID batchId = UUID.randomUUID();

        MigrationRun completedLoad = MigrationRun.start(UUID.randomUUID(), batchId, MigrationRun.Mode.LOAD,
                IN_MEMORY_WINDOW, MigrationRun.CharacterizationStatus.DRAFT);
        completedLoad.finish(MigrationRun.Status.CLEAN, 1, 1, 0);
        migrationRuns.save(completedLoad);

        // Inserted rather than loaded from a file: the row's rate must differ from the corpus rate the replay is
        // priced at, and no committed fixture may carry a rate that contradicts the corpus it ships beside (the
        // fixtures are the AAP 0.10.3 acceptance evidence).
        jdbcTemplate.update("INSERT INTO legacy_rate_table (run_id, currnkey, currnbase, amount, rates)"
                        + " VALUES (?, 'GBP', 'USD', 1.00, ?)",
                completedLoad.runId(), COMPLETED_LOAD_GBP_RATE);

        MigrationRun inFlightRetry = MigrationRun.start(UUID.randomUUID(), batchId, MigrationRun.Mode.LOAD,
                IN_MEMORY_WINDOW, MigrationRun.CharacterizationStatus.DRAFT);
        migrationRuns.save(inFlightRetry);
        assertThat(requireRun(inFlightRetry.runId()).status())
                .as("the batch's newest load is RUNNING, and it has staged no rate row")
                .isEqualTo(MigrationRun.Status.RUNNING);
        assertThat(legacyRates.findByRunId(inFlightRetry.runId()))
                .as("a load still running has staged nothing a reader may price an explanation from")
                .isEmpty();

        // Bound off an Environment exactly as the container binds it, so the phase exercises the same inert
        // tool.rate-source intake a live shadow-compare command line reaches.
        ShadowComparator liveRateComparator = new ShadowComparator(retailService, reconciliationService,
                reconciliationRepository, migrationRuns, legacyRates, accounts,
                new MockEnvironment().withProperty("tool.rate-source", "live"));

        UUID runId = UUID.randomUUID();
        MigrationRun run = migrationRuns.save(MigrationRun.start(runId, batchId, MigrationRun.Mode.SHADOW,
                IN_MEMORY_WINDOW, MigrationRun.CharacterizationStatus.DRAFT));

        int variances = liveRateComparator.compare(run,
                List.of(new ShadowTransaction(1L, "GREG", "C", new BigDecimal("100.00"), "GBP")),
                List.of(new ShadowLegacyResponse(1L, "GREG", SUCCESS_RETCODE, CAPTURED_GBP_BALANCE)));

        assertThat(variances)
                .as("a difference the completed load's staged rate re-derives exactly is authorized, so it"
                        + " raises neither the variance count nor the exit code")
                .isZero();

        List<MigrationReconciliation> rows = findingsOfRun(runId);
        assertThat(rows).as("the window's only finding").hasSize(1);

        MigrationReconciliation explained = row(rows, "GREG", VarianceKind.RATE_SOURCE);
        assertThat(explained.status()).isEqualTo(ReconciliationStatus.ACCEPTED_EXCEPTION);
        assertThat(explained.legacyBalance()).isEqualByComparingTo(CAPTURED_GBP_BALANCE);
        assertThat(explained.migratedBalance()).isEqualByComparingTo(TARGET_GBP_BALANCE);
        assertThat(explained.variance())
                .as("an accepted exception leaves no signed difference outstanding")
                .isNull();
        assertThat(balanceOf("GREG"))
                .as("the replay itself landed, priced at the corpus rate the target holds")
                .isEqualByComparingTo(TARGET_GBP_BALANCE);
    }

    /** A window lost while recording a finding: the FAILED row must state the progress and the findings that stand. */
    // Driven through MigrationToolRunner.execute, the production recovery path: closing that row is the runner's
    // own logic, and execute(...) returns the exit code where run(...) forces it through System.exit. The fault is
    // injected on the REJECTED_BY_TARGET insert the seeded window writes at seq 2, after seq 1's finding has
    // committed and after seq 2's own target refusal, so one point covers both halves of the accounting.
    private void lostWindowKeepsItsProgressAndItsCommittedFindings() {
        Path window = fixtureDirectory(SEEDED_SHADOW_FIXTURES);
        UUID batchId = UUID.randomUUID();

        int exitCode;
        injectRejectionRowInsertFailure();
        try {
            exitCode = shadowCompareRunner(window, batchId).execute(new DefaultApplicationArguments());
        } finally {
            // Dropped whatever happened above: the container is JVM-wide, so a trigger left behind would fail an
            // unrelated class.
            removeRejectionRowInsertFailure();
        }

        assertThat(exitCode)
                .as("a datastore failure while recording a finding is an error (1), never a completed run that"
                        + " recorded variances (2)")
                .isEqualTo(1);

        MigrationRun lost = onlyRunOfBatch(batchId);
        // Closed with zeroed counters, the row would tell the operator signing off runbook Step 2 that the window
        // found nothing while its finding sat under the same run id; left RUNNING it could not be signed off.
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

        assertThat(findingsOfRun(lost.runId()))
                .as("the finding that reached the table survives the failure that followed it")
                .extracting(MigrationReconciliation::owner, MigrationReconciliation::varianceKind,
                        MigrationReconciliation::status)
                .containsExactly(tuple("JOHN", VarianceKind.BALANCE, ReconciliationStatus.VARIANCE));
    }

    /** The runner as the runbook's Step 2 command line configures it, for one shadow window. */
    private MigrationToolRunner shadowCompareRunner(Path window, UUID batchId) {
        return new MigrationToolRunner(legacyLoader, reconciliationService, shadowComparator, migrationRuns,
                reconciliationRepository, applicationContext, "shadow-compare", window.toString(), batchId.toString(),
                "legacy-table", LegacyExportFormat.DEFAULT_LEGACY_CHARSET, "UTC", null);
    }

    /** The one run the runner opened for a batch, failing rather than guessing when the batch holds another. */
    private MigrationRun onlyRunOfBatch(UUID batchId) {
        List<MigrationRun> runsOfBatch = runsOfBatch(batchId);
        if (runsOfBatch.size() != 1) {
            return fail("the runner must open exactly one run for batch %s, but the batch holds %s"
                    .formatted(batchId, runsOfBatch));
        }
        return runsOfBatch.get(0);
    }

    /** Makes the next {@code REJECTED_BY_TARGET} insert fail, so a window dies while recording a finding. */
    // plpgsql with a WHEN condition, both available on the PostgreSQL 12 floor this module is written to, so no
    // other row kind, table or test is affected and nothing outside this method is altered.
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

    /** Every finding one window recorded, in insertion order. */
    // Ordered by the generated identity because row order here is evidence order: AAP 0.10.3 fixes the row sets
    // at "none" and "exactly three", and PostgreSQL returns rows in any order without an ORDER BY. A short-lived
    // EntityManager keeps the read outside any transaction of the comparator under test, so each assertion
    // judges what committed.
    private List<MigrationReconciliation> findingsOfRun(UUID runId) {
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

    /** Every run of a batch, oldest first. */
    // Read here rather than through the repository because MigrationRunRepository publishes one selector - the
    // batch's completed load - and no broad batch list; run_id breaks a started_at tie exactly as the selector
    // does, so a batch's runs cannot come back in one order here and another order there.
    private List<MigrationRun> runsOfBatch(UUID batchId) {
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

    // The account's type is never named, so this class imports nothing from the domain package.
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

    /** The one row of a kind for an owner, failing rather than returning empty when the set does not hold it. */
    private static MigrationReconciliation row(List<MigrationReconciliation> rows, String owner, VarianceKind kind) {
        return rows.stream()
                .filter(candidate -> owner.equals(candidate.owner()) && candidate.varianceKind() == kind)
                .reduce((first, second) -> fail(
                        "exactly one %s row is expected for %s, and the run recorded more".formatted(kind, owner)))
                .orElseGet(() -> fail("the run recorded no %s row for %s; rows were %s".formatted(kind, owner, rows)));
    }

    // Fails rather than skips when a fixture is absent: a comparison with nothing to replay proves nothing, and a
    // skipped test reports as success.
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

    /** Wires the production staged-rate lookup in as the replay's rate source, for this class alone. */
    @TestConfiguration
    static class StagedRateSourceConfiguration {

        // @Profile("tool") bounds component scanning and is not evaluated for an explicitly instantiated class, so
        // the production implementation is reused verbatim: the five-character key truncation of
        // CASH00.cbl:L213/L247, the fail-closed answer to an absent or null RATES row and the unrescaled return
        // that leaves domain.Money holding the single truncation point are all its behaviour, not this test's. The
        // batch id is empty because the run-explicit overload never consults it.
        @Bean
        @Primary
        StagedLegacyRateSource stagedLegacyRateSource(LegacyRateTableRepository legacyRates,
                                                      MigrationRunRepository migrationRuns) {
            return new StagedLegacyRateSource(new LegacyRateTableSource(legacyRates, migrationRuns, ""));
        }
    }

    /** The staged rate table as an {@link ExchangeRateSource}, scoped to the load the current test staged. */
    static final class StagedLegacyRateSource implements ExchangeRateSource {

        private final LegacyRateTableSource delegate;

        private volatile UUID stagingRunId;

        // A hand-written delegate rather than a mock, Mockito being excluded from the test stack (AAP 0.7.6). It
        // exists because LegacyRateTableSource's interface method resolves its staging run from tool.batch-id once
        // and caches it, while this class re-stages the corpus under a fresh run id before every test; the
        // run-explicit overload it delegates to takes that run as an argument, so no rate value, key rule or
        // failure mode is reimplemented here.
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
                // Deliberately not the module's rate-unavailable exception: the service layer would translate a
                // rate outage into 503 EXCHANGE_RATE_UNAVAILABLE, which the comparator records as a finding of
                // the window under test, so a harness that had simply not declared its staging run would report
                // as a product variance instead of failing loudly here.
                throw new IllegalStateException(
                        "no staging run has been declared for this test; the corpus load must run first");
            }
            return delegate.rate(staged, base, quote);
        }
    }
}
