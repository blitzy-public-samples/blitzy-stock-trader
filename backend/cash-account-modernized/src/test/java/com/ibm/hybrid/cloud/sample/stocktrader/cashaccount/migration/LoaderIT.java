package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.CashAccount;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.LedgerEventType;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.load.LegacyLoader;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.LegacyHistory;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.LegacyRateTable;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.MigrationRun;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.ReconciliationService;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.shadow.ShadowComparator;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.CashAccountRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.LegacyRateTableRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.MigrationReconciliationRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.MigrationRunRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.PostgresTestSupport;

/**
 * Proves {@code LegacyLoader} applies the matched fixture export, from the committed fixtures alone (AAP
 * 0.3.1-0.3.2), and that its two history shapes decode identically (AAP 0.10.3).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = "cashaccount.migration.batch-chunk-size=" + LoaderIT.FORCED_CHUNK_SIZE)
class LoaderIT extends PostgresTestSupport {

    // Several times smaller than every count this class asserts, so the six owners, three rate rows and eight
    // history rows each cross chunk boundaries and a flush that lost, duplicated or reordered a row shows up here
    // as a wrong count rather than only under a production-sized export no test may ship. Package-private so the
    // annotation above and the guard below read one declaration.
    static final String FORCED_CHUNK_SIZE = "2";

    private static final String MATCHED_FIXTURES = "/fixtures/legacy-export/matched";

    private static final Charset LEGACY_CHARSET = Charset.forName(LegacyExportFormat.DEFAULT_LEGACY_CHARSET);

    // Declared rather than defaulted silently, the CICS region's own zone being an open item (AAP 0.11.2);
    // only raw YYYYMMDD/HHMMSS text is compared below, so the choice cannot move an expected value.
    private static final ZoneId LEGACY_ZONE = ZoneId.of("UTC");

    // fixtures/legacy-export/matched/cashaccounty.csv, row for row. Every amount is built from text, so no
    // expected value on a money path ever passes through a double (AAP 0.7.1).
    private static final List<ExpectedAccount> EXPECTED_ACCOUNTS = List.of(
            new ExpectedAccount("JOHN", "1000.00", "USD"),
            new ExpectedAccount("KARRI", "12345.67", "USD"),
            new ExpectedAccount("RYAN", "23456.78", "USD"),
            new ExpectedAccount("RAUNAK", "100.00", "USD"),
            new ExpectedAccount("GREG", "123456.78", "GBP"),
            new ExpectedAccount("ERIC", "1234567.89", "EUR"));

    // fixtures/legacy-export/matched/frankfurt1.csv, staged and asserted exactly as given: the legacy SELECT
    // fetched currnbase and amount and no COMPUTE or MOVE referenced either (AAP 0.4.1), so neither may be invented.
    private static final List<ExpectedRate> EXPECTED_RATES = List.of(
            new ExpectedRate("USD", "USD", "1.00", "1.00"),
            new ExpectedRate("EUR", "USD", "1.00", "0.92"),
            new ExpectedRate("GBP", "USD", "1.00", "0.79"));

    private static final int EXPECTED_HISTORY_ROWS = 8;

    // Shared by both of JOHN's history rows on purpose: the 29-byte KSDS key is name + date + time
    // (CASH00.cbl:L47-L50; KEYS(29 0), DEFKSDS.jcl:L14), so an identical stamp leaves the raw name as the only
    // field separating the two keys and the assertion below fails if a load ever folds the casing.
    private static final String JOHN_SHARED_EVENT_DATE = "20240115";

    private static final String JOHN_SHARED_EVENT_TIME = "091500";

    // The two Q rows are staged but excluded from the count: CASH00 writes its history record after
    // END-EVALUATE unconditionally (CASH00.cbl:L102, L111-L131), so reads were recorded too, while the target
    // ledger records state changes only (AAP 0.4.6).
    private static final int EXPECTED_COUNTED_HISTORY_ROWS = 6;

    // Assembled from LegacyExportFormat's column names rather than retyped: an export whose header the reader
    // does not recognize fails on line 1, which is before any case written below could be reached.
    private static final String ACCOUNT_HEADER = String.join(",",
            LegacyExportFormat.CASH_ACCOUNT_OWNER_COLUMN,
            LegacyExportFormat.CASH_ACCOUNT_BALANCE_COLUMN,
            LegacyExportFormat.CASH_ACCOUNT_CURRENCY_COLUMN);

    private static final String HISTORY_HEADER = String.join(",",
            LegacyExportFormat.HISTORY_NAME_COLUMN,
            LegacyExportFormat.HISTORY_DATE_COLUMN,
            LegacyExportFormat.HISTORY_TIME_COLUMN,
            LegacyExportFormat.HISTORY_REQUEST_CODE_COLUMN,
            LegacyExportFormat.HISTORY_BALANCE_COLUMN,
            LegacyExportFormat.HISTORY_CURRENCY_COLUMN,
            LegacyExportFormat.HISTORY_RETCODE_COLUMN);

    private static final String RATE_HEADER = String.join(",",
            LegacyExportFormat.RATE_KEY_COLUMN,
            LegacyExportFormat.RATE_BASE_COLUMN,
            LegacyExportFormat.RATE_AMOUNT_COLUMN,
            LegacyExportFormat.RATE_RATES_COLUMN,
            LegacyExportFormat.RATE_LOAD_DATE_COLUMN);

    @Autowired
    private LegacyLoader loader;

    @Autowired
    private MigrationRunRepository migrationRuns;

    @Autowired
    private CashAccountRepository cashAccounts;

    // The staged legacy_history rows are read through JPQL below rather than through a finder on
    // persistence/LegacyHistoryRepository: nothing the service ships reads those rows back - the loader writes
    // them and reports what it staged on the run's MigrationRun summary - so a finder there would be shipped
    // data-access surface whose only caller is this test, and AAP 0.6.1 fixes the test-support file list at
    // three, so it cannot live beside the tests either.
    @Autowired
    private EntityManagerFactory entityManagers;

    @Autowired
    private LegacyRateTableRepository legacyRates;

    // The remaining collaborators a MigrationToolRunner needs. They are real beans: the only thing a
    // fault-injection case replaces is the runner's own reference to one repository.
    @Autowired
    private MigrationReconciliationRepository reconciliations;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private ShadowComparator shadowComparator;

    @Autowired
    private ConfigurableApplicationContext applicationContext;

    // The only way to read ledger_entry: LedgerEntryRepository exposes save plus two owner-scoped finders and
    // deliberately no count, findAll or delete.
    @Autowired
    private JdbcTemplate jdbc;

    // ledger_entry is absent because the ledger_entry_immutable trigger rejects UPDATE and DELETE: its rows
    // accumulate across every test sharing this JVM-wide container, which is why every ledger assertion below is
    // scoped by run_id. The delete order follows the constraints, findings before the run they reference and the
    // staging tables and cash_reservation before cash_account.
    @BeforeEach
    void resetTargetState() {
        jdbc.update("DELETE FROM migration_reconciliation");
        jdbc.update("DELETE FROM migration_run");
        jdbc.update("DELETE FROM legacy_history");
        jdbc.update("DELETE FROM legacy_rate_table");
        jdbc.update("DELETE FROM cash_reservation");
        jdbc.update("DELETE FROM cash_account");
    }

    @Test
    void loadsTheMatchedExportAndStagesBothHistoryShapesIdentically() {
        // Asserted rather than assumed: raised above the fixture counts, every number below would still pass
        // while proving nothing about a chunk boundary.
        assertThat(Integer.parseInt(FORCED_CHUNK_SIZE))
                .as("the forced chunk size must be smaller than the row counts this test asserts")
                .isLessThan(EXPECTED_HISTORY_ROWS)
                .isLessThan(EXPECTED_ACCOUNTS.size());

        Path matched = matchedFixtureDirectory();
        LegacyLoader.LoadSources resolved = LegacyLoader.LoadSources.inDirectory(matched);
        assertThat(resolved.historyTextFile())
                .as("history.csv must be on the classpath: it is one of the two shapes this test compares")
                .isNotNull();
        assertThat(resolved.historyBinaryFile())
                .as("history.cp037.bin must be on the classpath: it is the other shape")
                .isNotNull();

        UUID batchId = UUID.randomUUID();

        // The two shapes go under separate runs because both decode to the same (name, event_date, event_time)
        // keys - legacy_history's primary key - so one run_id would collide rather than compare, and readHistory
        // stages exactly one shape anyway. The explicit overload rather than load(run, directory): the directory
        // form would resolve history.cp037.bin and then demand tool.history-record-length, which the test profile
        // omits by design, and a null length is the other half of that contract.
        MigrationRun runA = migrationRuns.save(MigrationRun.start(UUID.randomUUID(), batchId,
                MigrationRun.Mode.LOAD, matched.toString(), MigrationRun.CharacterizationStatus.DRAFT));
        LegacyLoader.LoadResult resultA = loader.load(runA,
                new LegacyLoader.LoadSources(resolved.accountsFile(), resolved.rateFile(),
                        resolved.historyTextFile(), null),
                LEGACY_CHARSET, LEGACY_ZONE, null);

        // Run B names the account file only because LoadSources rejects a null accountsFile, and that costs the
        // comparison nothing: the six owners already hold these balances, so no MIGRATION_LOAD event is written
        // for a state change nothing made. The record length is declared from the fixture's documented 100-byte
        // shape (57 data bytes plus RECSZ(100 100) slack, DEFKSDS.jcl:L11), never inferred from file size
        // (AAP 0.12.1).
        MigrationRun runB = migrationRuns.save(MigrationRun.start(UUID.randomUUID(), batchId,
                MigrationRun.Mode.LOAD, matched.toString(), MigrationRun.CharacterizationStatus.DRAFT));
        LegacyLoader.LoadResult resultB = loader.load(runB,
                new LegacyLoader.LoadSources(resolved.accountsFile(), null, null,
                        resolved.historyBinaryFile()),
                LEGACY_CHARSET, LEGACY_ZONE, LegacyExportFormat.HISTORY_PADDED_RECORD_LENGTH);

        // Exactly what MigrationToolRunner does with a LoadResult, so the run row asserted below is the one an
        // operator reads after `--tool.command=load`. The runner itself is never invoked - its
        // System.exit(SpringApplication.exit(...)) would crash the Failsafe fork - so its exit code is asserted as
        // the persisted state it maps from: CLEAN with no variance is 0, variance is 2 and a failure is 1.
        runA.finish(resultA.varianceCount() == 0 ? MigrationRun.Status.CLEAN : MigrationRun.Status.VARIANCE,
                resultA.legacyRecordCount(), resultA.migratedRecordCount(), resultA.varianceCount());
        migrationRuns.save(runA);

        List<Map<String, Object>> migrationLoadRows = jdbc.queryForList(
                "SELECT owner, amount, currency, available_after, reserved_after, source"
                        + " FROM ledger_entry WHERE run_id = ? AND event_type = ? ORDER BY owner",
                runA.runId(), LedgerEventType.MIGRATION_LOAD.name());
        List<StagedHistoryFacts> historyFromText = stagedFacts(stagedHistory(runA.runId()));
        List<StagedHistoryFacts> historyFromBinary = stagedFacts(stagedHistory(runB.runId()));

        assertSoftly(softly -> {
            softly.assertThat(resultA.legacyRecordCount()).as("account rows read from the export").isEqualTo(6);
            softly.assertThat(resultA.migratedRecordCount()).as("owners applied").isEqualTo(6);
            softly.assertThat(resultA.varianceCount()).as("variance rows - matched fixtures seed none")
                    .isZero();
            softly.assertThat(resultA.stagedHistoryCount()).as("history rows staged from history.csv")
                    .isEqualTo(EXPECTED_HISTORY_ROWS);
            softly.assertThat(resultA.stagedRateCount()).as("rate rows staged from frankfurt1.csv")
                    .isEqualTo(EXPECTED_RATES.size());

            // Owners are stored upper case (AAP 0.4.2) and the loader touches reserved_balance for nobody.
            softly.assertThat(cashAccounts.count()).as("cash_account rows").isEqualTo(EXPECTED_ACCOUNTS.size());
            for (ExpectedAccount expected : EXPECTED_ACCOUNTS) {
                Optional<CashAccount> loaded = cashAccounts.findByOwner(expected.owner());
                softly.assertThat(loaded).as("account %s", expected.owner()).isPresent();
                loaded.ifPresent(account -> {
                    softly.assertThat(account.availableBalance().amount())
                            .as("available balance of %s", expected.owner())
                            .isEqualTo(expected.balance());
                    softly.assertThat(account.reservedBalance().amount())
                            .as("reserved balance of %s", expected.owner())
                            .isEqualTo(new BigDecimal("0.00"));
                    softly.assertThat(account.currency()).as("currency of %s", expected.owner())
                            .isEqualTo(expected.currency());
                });
            }

            // MIGRATION_LOAD is an absolute-set event, so its amount is the resulting available balance rather
            // than a delta (AAP 0.6.3), which is why amount and available_after agree.
            softly.assertThat(migrationLoadRows).as("MIGRATION_LOAD rows under run A")
                    .hasSize(EXPECTED_ACCOUNTS.size());
            softly.assertThat(migrationLoadRows).extracting(row -> row.get("owner"))
                    .as("owners carrying a MIGRATION_LOAD row")
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_ACCOUNTS.stream()
                            .map(ExpectedAccount::owner).toList());
            for (Map<String, Object> row : migrationLoadRows) {
                String owner = String.valueOf(row.get("owner"));
                ExpectedAccount expected = expectedAccount(owner);
                softly.assertThat((BigDecimal) row.get("amount")).as("ledger amount for %s", owner)
                        .isEqualByComparingTo(expected.balance());
                softly.assertThat((BigDecimal) row.get("available_after"))
                        .as("available_after for %s", owner).isEqualByComparingTo(expected.balance());
                softly.assertThat((BigDecimal) row.get("reserved_after")).as("reserved_after for %s", owner)
                        .isEqualByComparingTo(new BigDecimal("0.00"));
                softly.assertThat(row.get("currency")).as("ledger currency for %s", owner)
                        .isEqualTo(expected.currency());
                softly.assertThat(row.get("source")).as("ledger source for %s", owner).isEqualTo("MIGRATION");
            }

            // The rate keeps its own two decimals: NUMERIC(3,2) in the legacy catalog (DB2DDL.jcl:L58), which is
            // why no rate here can exceed 9.99.
            softly.assertThat(legacyRates.findByRunId(runA.runId())).as("staged rate rows")
                    .hasSize(EXPECTED_RATES.size());
            for (ExpectedRate expected : EXPECTED_RATES) {
                Optional<LegacyRateTable> staged =
                        legacyRates.findByRunIdAndCurrnkey(runA.runId(), expected.currnkey());
                softly.assertThat(staged).as("staged rate %s", expected.currnkey()).isPresent();
                staged.ifPresent(rate -> {
                    softly.assertThat(rate.rates()).as("rate of %s", expected.currnkey())
                            .isEqualTo(expected.rates());
                    softly.assertThat(rate.currnbase()).as("base currency of %s", expected.currnkey())
                            .isEqualTo(expected.currnbase());
                    softly.assertThat(rate.amount()).as("staged amount of %s", expected.currnkey())
                            .isEqualTo(expected.amount());
                });
            }

            // MOVE WS-NAME TO WS-VR-NAME applies no case folding (CASH00.cbl:L111), so "John"+stamp and
            // "JOHN"+stamp are two distinct valid 29-byte KSDS keys (DEFKSDS.jcl:L14) that must both survive
            // under one uppercased join key: the tuple assertion below holds only if staging kept both casings,
            // and a folded name would leave one row where the export held two.
            softly.assertThat(stagedHistoryCount(runA.runId())).as("staged history rows")
                    .isEqualTo(EXPECTED_HISTORY_ROWS);
            List<LegacyHistory> johnRows = stagedHistory(runA.runId(), "JOHN");
            softly.assertThat(johnRows).as("history rows joined to owner JOHN").hasSize(2);
            softly.assertThat(johnRows)
                    .as("JOHN's two raw names, both surviving at one identical stamp")
                    .extracting(LegacyHistory::name, LegacyHistory::eventDate, LegacyHistory::eventTime)
                    .containsExactlyInAnyOrder(
                            tuple("John", JOHN_SHARED_EVENT_DATE, JOHN_SHARED_EVENT_TIME),
                            tuple("JOHN", JOHN_SHARED_EVENT_DATE, JOHN_SHARED_EVENT_TIME));
            softly.assertThat(stagedHistoryCount(runA.runId(), LegacyExportFormat.COUNTED_REQUEST_CODES))
                    .as("history rows whose request code is a state change, the two Q reads excluded")
                    .isEqualTo(EXPECTED_COUNTED_HISTORY_ROWS);

            // The AAP 0.10.3 assertion, with scale-sensitive BigDecimal equality: it holds only because every
            // fixture balance carries exactly two decimals, the zoned 9(7)V99 field (CASH00.cbl:L43) and the
            // delimited text agreeing on scale 2.
            softly.assertThat(resultB.stagedHistoryCount()).as("history rows staged from the binary export")
                    .isEqualTo(EXPECTED_HISTORY_ROWS);
            softly.assertThat(historyFromBinary)
                    .as("the binary history export must decode to the text export, row for row")
                    .isEqualTo(historyFromText);

            // Staging is run-scoped, which is what lets the same eight keys coexist under two runs.
            softly.assertThat(stagedHistoryCount(runB.runId())).as("run B's staged history rows")
                    .isEqualTo(EXPECTED_HISTORY_ROWS);
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_history", Long.class))
                    .as("both runs' history rows coexist rather than merging")
                    .isEqualTo(2L * EXPECTED_HISTORY_ROWS);
            softly.assertThat(legacyRates.findByRunId(runB.runId()))
                    .as("run B named no rate file, so it staged no rate row").isEmpty();
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE run_id = ?",
                            Long.class, runB.runId()))
                    .as("a repeat load of unchanged balances writes no ledger event").isZero();
            softly.assertThat(resultB.varianceCount()).as("run B's variance rows").isZero();

            Optional<MigrationRun> persistedA = migrationRuns.findById(runA.runId());
            softly.assertThat(persistedA).as("run A's persisted row").isPresent();
            persistedA.ifPresent(run -> {
                softly.assertThat(run.status()).as("a load with no variance closes CLEAN - exit code 0")
                        .isEqualTo(MigrationRun.Status.CLEAN);
                softly.assertThat(run.legacyRecordCount()).as("persisted legacy_record_count").isEqualTo(6);
                softly.assertThat(run.migratedRecordCount()).as("persisted migrated_record_count").isEqualTo(6);
                softly.assertThat(run.varianceCount()).as("persisted variance_count").isZero();
                softly.assertThat(run.mode()).as("persisted mode").isEqualTo(MigrationRun.Mode.LOAD);
                softly.assertThat(run.characterizationStatus()).as("persisted characterization status")
                        .isEqualTo(MigrationRun.CharacterizationStatus.DRAFT);
                softly.assertThat(run.finishedAt()).as("finished_at is stamped by finish(...)").isNotNull();
            });
            softly.assertThat(runsOfBatch(batchId))
                    .extracting(MigrationRun::runId)
                    .as("both runs of the batch, in start order")
                    .containsExactly(runA.runId(), runB.runId());

            // 8. WHICH OF THE TWO A LATER COMMAND OF THIS BATCH WOULD READ. Run A was closed CLEAN above and run
            //    B was never closed, so it is still RUNNING - the state an interrupted or in-flight attempt
            //    leaves behind. The selector every consumer of staged rows resolves through
            //    (reconcile/ReconciliationService, shadow/ShadowComparator, fx/LegacyRateTableSource) must
            //    therefore answer run A, although run B is the more recent load and staged history rows of its
            //    own: RUNNING is not a completed load, because a load applies its whole export in one
            //    transaction and ends CLEAN or VARIANCE only when every row is in (AAP 0.6.3).
            softly.assertThat(migrationRuns.findLatestCompletedLoad(batchId).map(MigrationRun::runId))
                    .as("the batch's completed load, not its newer RUNNING attempt")
                    .contains(runA.runId());
        });

        deterministicallyResolvesTwoCompletedLoadsStampedAtTheSameInstant();
    }

    /**
     * Two completed loads of one batch stamped identically: the selector must still name one of them, always
     * the same one.
     */
    // A phase of the load scenario rather than a test of its own, because AAP 0.7.6 fixes this file's
    // allocation and the condition is a property of the same selection the phase above exercises. It cannot be
    // reached by creating two runs normally - MigrationRun.start stamps started_at from the clock - so the tie
    // is forced through SQL, which is also the only way an operator's concurrent retries would produce it.
    //
    // The run ids are fixed rather than random so the expected winner is unambiguous in every ordering: the
    // database sorts uuid by its sixteen bytes, and 0xff... outranks 0x00... there as plainly as it does in the
    // canonical text form. With a random pair the assertion would have to recompute the database's own
    // collation to know what to expect.
    private void deterministicallyResolvesTwoCompletedLoadsStampedAtTheSameInstant() {
        UUID batchId = UUID.randomUUID();
        UUID lowerRunId = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID higherRunId = UUID.fromString("ffffffff-ffff-4fff-bfff-fffffffffffe");
        OffsetDateTime sharedStamp = OffsetDateTime.of(2024, 1, 15, 9, 15, 0, 0, ZoneOffset.UTC);

        for (UUID runId : List.of(lowerRunId, higherRunId)) {
            MigrationRun tied = MigrationRun.start(runId, batchId, MigrationRun.Mode.LOAD,
                    "started_at tie", MigrationRun.CharacterizationStatus.DRAFT);
            tied.finish(MigrationRun.Status.CLEAN, 0, 0, 0);
            migrationRuns.save(tied);
        }
        jdbc.update("UPDATE migration_run SET started_at = ? WHERE batch_id = ?", sharedStamp, batchId);

        // Compared as instants, because a TIMESTAMPTZ comes back at the reading JVM's offset and OffsetDateTime
        // equality is offset-sensitive: the assertion is that both rows name one moment, not one rendering.
        assertThat(runsOfBatch(batchId))
                .as("the tie is real: both rows carry one started_at")
                .extracting(run -> run.startedAt().toInstant())
                .containsOnly(sharedStamp.toInstant());
        assertThat(migrationRuns.findLatestCompletedLoad(batchId).map(MigrationRun::runId))
                .as("a started_at tie is broken by run_id descending, so the answer cannot depend on the order"
                        + " the rows happen to come back in")
                .contains(higherRunId);
    }

    // The malformed export is written here rather than committed, because the committed fixtures are the
    // acceptance evidence of AAP 0.10.3 and a corrupt file beside them would read as legacy data that looks like
    // this. Two lines are the minimum that proves both halves of the contract: a legacy NULL balance, recorded as
    // a finding before the second line is reached, and characters after a closing quote, where RFC 4180 admits
    // only a delimiter, a record end or end of file (AAP 0.12.1).
    @Test
    void rejectsAnAccountExportCarryingCharactersAfterAClosingQuote(@TempDir Path exportDirectory)
            throws IOException {
        Path accountsFile = exportDirectory.resolve(LegacyExportFormat.CASH_ACCOUNT_FILE);
        Files.writeString(accountsFile, String.join("\n",
                "owner,balance,currencyc",
                "NULLBAL,,USD",
                "\"JOHN\"X,1000.00,USD") + "\n");

        MigrationRun run = migrationRuns.save(MigrationRun.start(UUID.randomUUID(), UUID.randomUUID(),
                MigrationRun.Mode.LOAD, accountsFile.toString(), MigrationRun.CharacterizationStatus.DRAFT));

        // The failure has to name the file, the 1-based line an operator can open and the rule the line breaks:
        // a load that fails without saying where is a load nobody can fix.
        assertThatThrownBy(() -> loader.load(run, LegacyLoader.LoadSources.accountsOnly(accountsFile),
                LEGACY_CHARSET, LEGACY_ZONE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(accountsFile.toString())
                .hasMessageContaining("line 3")
                .hasMessageContaining("RFC 4180");

        assertSoftly(softly -> {
            // The single-transaction contract of AAP 0.6.3: NULLBAL's finding row was written before the
            // malformed line was reached, so a load that left anything behind would show it here, and all three
            // tables are checked together because "nothing was applied" is a statement about all of them.
            softly.assertThat(cashAccounts.count()).as("cash_account rows after a rejected export").isZero();
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE run_id = ?",
                            Long.class, run.runId()))
                    .as("ledger rows written under a rejected export").isZero();
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM migration_reconciliation WHERE run_id = ?",
                            Long.class, run.runId()))
                    .as("finding rows surviving a rejected export").isZero();
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_history", Long.class))
                    .as("staged history rows after a rejected export").isZero();
        });
    }

    // The tooling reaches ordinary files beneath the directory named by tool.input and nothing else (AAP 0.3.2):
    // a symbolic link named cashaccounty.csv would otherwise be any file this process can open, staged as legacy
    // data and echoed in failure messages (CWE-22, CWE-59). The directory overload is used deliberately, so the
    // resolution runs inside the loader where an operator's invocation runs it.
    @Test
    void refusesAnExportChildThatIsASymbolicLink(@TempDir Path exportDirectory, @TempDir Path outsideDirectory)
            throws IOException {
        // A VALID export, placed outside the input directory: the refusal must rest on the link and not on
        // anything wrong with the file it names, which is what makes this a containment assertion.
        Path outsideExport = outsideDirectory.resolve("approved-elsewhere.csv");
        Files.writeString(outsideExport, String.join("\n",
                "owner,balance,currencyc",
                "MALLORY,1000.00,USD") + "\n");
        Files.createSymbolicLink(exportDirectory.resolve(LegacyExportFormat.CASH_ACCOUNT_FILE), outsideExport);

        MigrationRun run = migrationRuns.save(MigrationRun.start(UUID.randomUUID(), UUID.randomUUID(),
                MigrationRun.Mode.LOAD, exportDirectory.toString(), MigrationRun.CharacterizationStatus.DRAFT));

        // One vocabulary for an operator and for this test: the words "symbolic link" and the offending path.
        // The path is taken through toRealPath because that is the trusted root the loader measures children
        // against - it is the link that must not be followed, never the directory the operator named.
        Path refusedChild = exportDirectory.toRealPath().resolve(LegacyExportFormat.CASH_ACCOUNT_FILE);
        assertThatThrownBy(() -> loader.load(run, exportDirectory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbolic link")
                .hasMessageContaining(refusedChild.toString());

        assertSoftly(softly -> {
            softly.assertThat(cashAccounts.count()).as("cash_account rows after a refused symbolic link")
                    .isZero();
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE run_id = ?",
                            Long.class, run.runId()))
                    .as("ledger rows written under a refused symbolic link").isZero();
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM migration_reconciliation WHERE run_id = ?",
                            Long.class, run.runId()))
                    .as("finding rows written under a refused symbolic link").isZero();
        });
    }

    // Resolving a child and later opening it by pathname are two resolutions of one name, so a directory
    // component renamed and replaced by a symbolic link in between redirects the read, and NOFOLLOW_LINKS cannot
    // see it because it refuses only a link in the final component (CWE-22, CWE-59). The substitution is
    // performed deliberately rather than raced, standing in for the interval every load spends between resolving
    // its sources and reading them; what refuses it is the directory identity captured at resolution (AAP 0.3.2).
    @Test
    void refusesAnExportWhoseApprovedDirectoryWasSubstitutedAfterResolution(@TempDir Path workspace)
            throws IOException {
        Path approvedDirectory = Files.createDirectory(workspace.resolve("approved"));
        Files.writeString(approvedDirectory.resolve(LegacyExportFormat.CASH_ACCOUNT_FILE), String.join("\n",
                "owner,balance,currencyc",
                "JOHN,1000.00,USD") + "\n");
        Path elsewhere = Files.createDirectory(workspace.resolve("elsewhere"));
        Files.writeString(elsewhere.resolve(LegacyExportFormat.CASH_ACCOUNT_FILE), String.join("\n",
                "owner,balance,currencyc",
                "MALLORY,9999.99,USD") + "\n");

        // Resolved while the approved directory is still the approved directory, which is what a load does
        // before it reads a byte.
        LegacyLoader.LoadSources sources = LegacyLoader.LoadSources.inDirectory(approvedDirectory);

        Files.move(approvedDirectory, workspace.resolve("approved-moved-away"));
        Files.createSymbolicLink(approvedDirectory, elsewhere);
        // The substitution is real, not notional: the same pathname now leads to another directory holding an
        // ordinary file of the declared name, so every pathname-based guard would read it.
        assertThat(Files.readString(approvedDirectory.resolve(LegacyExportFormat.CASH_ACCOUNT_FILE)))
                .as("the substituted directory must be readable by pathname, or this test proves nothing")
                .contains("MALLORY");

        MigrationRun run = migrationRuns.save(MigrationRun.start(UUID.randomUUID(), UUID.randomUUID(),
                MigrationRun.Mode.LOAD, approvedDirectory.toString(),
                MigrationRun.CharacterizationStatus.DRAFT));

        assertThatThrownBy(() -> loader.load(run, sources, LEGACY_CHARSET, LEGACY_ZONE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no longer the directory that was approved");

        assertSoftly(softly -> {
            // The substituted export must never have been read, which is a statement about the row it would
            // have produced as much as about the counts.
            softly.assertThat(cashAccounts.findByOwner("MALLORY"))
                    .as("the account the substituted directory held").isEmpty();
            softly.assertThat(cashAccounts.count()).as("cash_account rows after a substituted directory")
                    .isZero();
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE run_id = ?",
                            Long.class, run.runId()))
                    .as("ledger rows written under a substituted directory").isZero();
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM migration_reconciliation WHERE run_id = ?",
                            Long.class, run.runId()))
                    .as("finding rows written under a substituted directory").isZero();
        });
    }

    // The parser honours RFC 4180 quoting, so an unclosed quote or a wrong delimiter turns a malformed export
    // into a single unbounded field that would exhaust the tool JVM instead of naming the line to open
    // (CWE-400). The width is built from the constant so the test moves with the limit rather than pinning a copy.
    @Test
    void refusesAnAccountExportFieldWiderThanTheDeclaredLimit(@TempDir Path exportDirectory) throws IOException {
        String tooWideOwner = "A".repeat(LegacyExportFormat.MAX_FIELD_CHARACTERS + 1);
        Path accountsFile = exportDirectory.resolve(LegacyExportFormat.CASH_ACCOUNT_FILE);
        Files.writeString(accountsFile, String.join("\n",
                "owner,balance,currencyc",
                tooWideOwner + ",1000.00,USD") + "\n");

        MigrationRun run = migrationRuns.save(MigrationRun.start(UUID.randomUUID(), UUID.randomUUID(),
                MigrationRun.Mode.LOAD, accountsFile.toString(), MigrationRun.CharacterizationStatus.DRAFT));

        assertThatThrownBy(() -> loader.load(run, LegacyLoader.LoadSources.accountsOnly(accountsFile),
                LEGACY_CHARSET, LEGACY_ZONE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(accountsFile.toString())
                .hasMessageContaining("line 2")
                .hasMessageContaining(String.valueOf(LegacyExportFormat.MAX_FIELD_CHARACTERS));

        assertSoftly(softly -> {
            softly.assertThat(cashAccounts.count()).as("cash_account rows after an over-wide field").isZero();
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE run_id = ?",
                            Long.class, run.runId()))
                    .as("ledger rows written under an over-wide field").isZero();
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM migration_reconciliation WHERE run_id = ?",
                            Long.class, run.runId()))
                    .as("finding rows written under an over-wide field").isZero();
        });
    }

    // The positive half of AAP 0.6.3, and the only place the runner's own boundary is exercised on a run that
    // succeeds: "every row of the export is applied AND the run row is CLEAN" is one commit, so the applied rows
    // and the terminal row are asserted together after a real invocation rather than after this class has played
    // the runner's part by hand, as the test above does.
    @Test
    void closesTheRunCleanAndAppliesTheWholeExportWhenNothingFails() {
        int exitCode = runnerLoading(matchedFixtureDirectory(), migrationRuns, reconciliations)
                .execute(new DefaultApplicationArguments());

        List<MigrationRun> runRows = migrationRuns.findAll();
        assertThat(runRows).as("one invocation, one run row").hasSize(1);
        MigrationRun closed = runRows.get(0);

        assertSoftly(softly -> {
            softly.assertThat(exitCode).as("a clean run exits 0").isZero();
            softly.assertThat(closed.status()).as("the verdict that committed with the work")
                    .isEqualTo(MigrationRun.Status.CLEAN);
            softly.assertThat(closed.finishedAt()).as("finished_at is stamped by the close").isNotNull();
            softly.assertThat(closed.legacyRecordCount()).as("account rows read").isEqualTo(6);
            softly.assertThat(closed.migratedRecordCount()).as("owners applied").isEqualTo(6);
            softly.assertThat(closed.varianceCount()).as("the matched export seeds no variance").isZero();

            softly.assertThat(cashAccounts.count()).as("every exported owner is present")
                    .isEqualTo(EXPECTED_ACCOUNTS.size());
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE run_id = ? AND"
                                    + " event_type = ?", Long.class, closed.runId(),
                            LedgerEventType.MIGRATION_LOAD.name()))
                    .as("one MIGRATION_LOAD event per applied owner, under this run")
                    .isEqualTo((long) EXPECTED_ACCOUNTS.size());
            // The directory carries history.cp037.bin, so the binary shape is the one a load stages - which is
            // why the runner is given the fixture's declared 100-byte record length.
            softly.assertThat(stagedHistoryCount(closed.runId())).as("staged history rows")
                    .isEqualTo(EXPECTED_HISTORY_ROWS);
            softly.assertThat(legacyRates.findByRunId(closed.runId())).as("staged rate rows")
                    .hasSize(EXPECTED_RATES.size());
        });
    }

    /*
     * The failure half of the same invariant, which only a fault after the load can reach. The rule leaves one
     * state that must be impossible: an applied load recorded under a FAILED row. A malformed export cannot
     * produce it - that aborts before anything is applied - so the two cases below let the load succeed in full
     * and then fail the two steps that follow it inside the same transaction: the read of the persisted
     * findings, and the save that closes the run. Both assert the same thing, because both are the same
     * invariant.
     */
    @Test
    void recordsTheRunFailedAndAppliesNothingWhenTheVarianceCountCannotBeReadAfterTheLoad() {
        MigrationReconciliationRepository failingOnTheFirstCount = faultOnCall(
                MigrationReconciliationRepository.class, reconciliations, "countByRunIdAndStatus", 1);

        int exitCode = runnerLoading(matchedFixtureDirectory(), migrationRuns, failingOnTheFirstCount)
                .execute(new DefaultApplicationArguments());

        // The second count is the failure path's own read of the findings, and it must succeed: that is how the
        // FAILED row takes its variance count from the evidence that committed rather than from the lost
        // instance.
        assertNothingAppliedUnderAFailedRun(exitCode);
    }

    @Test
    void recordsTheRunFailedAndAppliesNothingWhenTheTerminalRunSaveFails() {
        // Save #1 opens the RUNNING row and must succeed - it is the evidence of the attempt, and it commits
        // before the command's transaction exists. Save #2 is the close that would have written CLEAN, and it
        // is the one that fails. Save #3 is the FAILED close on the failure path, which must succeed or the
        // row would be left RUNNING, which no runbook gate can sign off.
        MigrationRunRepository failingOnTheClose =
                faultOnCall(MigrationRunRepository.class, migrationRuns, "save", 2);

        int exitCode = runnerLoading(matchedFixtureDirectory(), failingOnTheClose, reconciliations)
                .execute(new DefaultApplicationArguments());

        assertNothingAppliedUnderAFailedRun(exitCode);
    }

    /*
     * A repeated key is diagnosed as one in both shapes it arrives in. Two rows carrying one 29-byte KSDS key
     * inside a chunk collide in the persistence context before any statement is sent, while rows in different
     * chunks collide at the insert and arrive carrying SQLState 23505; the forced chunk size of 2 is what puts
     * the second file's pair in different chunks. Staging has to be lossless, so either is malformed input
     * (CASH00.cbl:L47-L50, DEFKSDS.jcl:L14) - and the operator has to be told which record to look for.
     */
    @Test
    void reportsARepeatedStagingKeyAsARepeatedKey(@TempDir Path exportDirectory) throws IOException {
        Path insideOneChunk = historyExportIn(exportDirectory.resolve("inside-one-chunk"),
                "JOHN,20240115,091500,A,1000.00,USD,000000000",
                "JOHN,20240115,091500,Q,1000.00,USD,000000000");
        Path acrossChunks = historyExportIn(exportDirectory.resolve("across-chunks"),
                "JOHN,20240115,091500,A,1000.00,USD,000000000",
                "KARRI,20240115,091501,A,12345.67,USD,000000000",
                "JOHN,20240115,091500,Q,1000.00,USD,000000000");

        assertSoftly(softly -> {
            for (Path history : List.of(insideOneChunk, acrossChunks)) {
                softly.assertThatThrownBy(() -> loadHistoryOnly(history))
                        .as("the diagnosis of the repeated key in %s", history)
                        .isInstanceOf(CashAccountException.class)
                        .hasMessageContaining(history.toString())
                        .hasMessageContaining("repeats a key")
                        .hasMessageContaining("The database reports:");
            }
        });
    }

    /*
     * The same catch receives failures that are not repeated keys, and must not call them one. legacy_rate_table
     * stages rates at NUMERIC(3,2), the width of the legacy DECIMAL(3,2) column (DB2DDL.jcl:L58), so a rate of
     * 12.34 cannot be staged as exported - and the export readers trim padding and parse text rather than judge
     * a staging column's width, so the value reaches the database. Reported as a repeated key, it would send the
     * operator of a migration window hunting for a duplicate record in a file that has none.
     */
    @Test
    void reportsAStagingValueThatDoesNotFitAsSuchRatherThanAsARepeatedKey(@TempDir Path exportDirectory)
            throws IOException {
        Path accountsFile = exportDirectory.resolve(LegacyExportFormat.CASH_ACCOUNT_FILE);
        Files.writeString(accountsFile, ACCOUNT_HEADER + "\nJOHN,1000.00,USD\n");
        Path rateFile = exportDirectory.resolve(LegacyExportFormat.RATE_TABLE_FILE);
        Files.writeString(rateFile, RATE_HEADER + "\nUSD,USD,1.00,12.34,2024-01-15\n");

        MigrationRun run = migrationRuns.save(MigrationRun.start(UUID.randomUUID(), UUID.randomUUID(),
                MigrationRun.Mode.LOAD, exportDirectory.toString(),
                MigrationRun.CharacterizationStatus.DRAFT));

        assertThatThrownBy(() -> loader.load(run,
                new LegacyLoader.LoadSources(accountsFile, rateFile, null, null),
                LEGACY_CHARSET, LEGACY_ZONE, null))
                .isInstanceOf(CashAccountException.class)
                .hasMessageContaining(rateFile.toString())
                .hasMessageContaining("does not fit the staging column")
                .hasMessageContaining("The database reports:")
                .hasMessageNotContaining("repeats a key");

        assertThat(legacyRates.count()).as("no rate row survives a rejected staging pass").isZero();
    }

    /*
     * Constructed rather than taken from the context, and that is forced: MigrationToolRunner carries
     * @Profile("tool") and closes a command with System.exit (see this class's header), so no context may be
     * allowed to instantiate it. Held in one place so the runner's constructor is named once. The declared
     * record length is the fixture's documented 100-byte shape, which the matched directory's history.cp037.bin
     * requires of a load.
     */
    private MigrationToolRunner runnerLoading(Path inputDirectory,
                                              MigrationRunRepository runRepository,
                                              MigrationReconciliationRepository reconciliationRepository) {
        return new MigrationToolRunner(loader, reconciliationService, shadowComparator, runRepository,
                reconciliationRepository, applicationContext, "load", inputDirectory.toString(),
                UUID.randomUUID().toString(), MigrationRun.RateSource.LEGACY_TABLE.token(),
                LegacyExportFormat.DEFAULT_LEGACY_CHARSET, LEGACY_ZONE.getId(),
                LegacyExportFormat.HISTORY_PADDED_RECORD_LENGTH);
    }

    /*
     * The failure half of the contract, asserted as one statement because it is one: the run row an operator
     * reads says FAILED and still carries the progress the lost command reported, and not a single row of the
     * load survived - accounts, ledger events, findings and both staging tables. The exit code is the tool's
     * error code and never 2, which asserts a completed run whose verdict is recorded.
     */
    private void assertNothingAppliedUnderAFailedRun(int exitCode) {
        List<MigrationRun> runRows = migrationRuns.findAll();
        assertThat(runRows)
                .as("the RUNNING row opened before the command's transaction survives that transaction's"
                        + " rollback, so exactly one run row remains")
                .hasSize(1);
        MigrationRun failed = runRows.get(0);

        assertSoftly(softly -> {
            softly.assertThat(exitCode).as("a failed command exits 1, never the variance code 2").isEqualTo(1);
            softly.assertThat(failed.status()).as("the run an operator reads").isEqualTo(MigrationRun.Status.FAILED);
            softly.assertThat(failed.finishedAt()).as("a FAILED run is closed, not left open").isNotNull();
            softly.assertThat(failed.legacyRecordCount())
                    .as("the progress the lost command reported is kept, never zeroed").isEqualTo(6);
            softly.assertThat(failed.migratedRecordCount())
                    .as("the progress the lost command reported is kept, never zeroed").isEqualTo(6);
            softly.assertThat(failed.varianceCount())
                    .as("the matched export seeds no variance, and the findings rolled back with the work")
                    .isZero();

            softly.assertThat(cashAccounts.count())
                    .as("FAILED must mean nothing was applied: the six exported owners are absent").isZero();
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE run_id = ?",
                            Long.class, failed.runId()))
                    .as("no MIGRATION_LOAD event survives the rolled-back load").isZero();
            softly.assertThat(jdbc.queryForObject(
                            "SELECT COUNT(*) FROM migration_reconciliation WHERE run_id = ?",
                            Long.class, failed.runId()))
                    .as("no finding row survives the rolled-back load").isZero();
            softly.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_history", Long.class))
                    .as("no staged history row survives the rolled-back load").isZero();
            softly.assertThat(legacyRates.count())
                    .as("no staged rate row survives the rolled-back load").isZero();
        });
    }

    /*
     * A real repository with one call of one method made to fail, which is how a failure that can only happen
     * between two committed steps is reached at all: no mocking framework is on this classpath (AAP 0.7.6), and
     * every other call - including the failure path's own - goes to the real bean. A reflective proxy rather
     * than a hand-written implementation because the contract is a Spring Data interface: implementing its
     * inherited methods would add thirty bodies that assert nothing.
     */
    private static <T> T faultOnCall(Class<T> contract, T delegate, String method, int failingCall) {
        AtomicInteger calls = new AtomicInteger();
        return contract.cast(Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[] {contract},
                (proxy, invoked, arguments) -> {
                    if (invoked.getName().equals(method) && calls.incrementAndGet() == failingCall) {
                        // A datastore failure rather than a contrived exception type: it is what the runner has
                        // to survive in a migration window, and it is unchecked, so Spring rolls the
                        // transaction back exactly as it would in production.
                        throw new DataAccessResourceFailureException(
                                "Injected fault on call " + failingCall + " of " + method);
                    }
                    try {
                        return invoked.invoke(delegate, arguments);
                    } catch (InvocationTargetException thrownByTheDelegate) {
                        throw thrownByTheDelegate.getTargetException();
                    }
                }));
    }

    /** An export directory holding one account row and the given history lines, for the staging cases. */
    private static Path historyExportIn(Path exportDirectory, String... historyLines) throws IOException {
        Files.createDirectories(exportDirectory);
        Files.writeString(exportDirectory.resolve(LegacyExportFormat.CASH_ACCOUNT_FILE),
                ACCOUNT_HEADER + "\nJOHN,1000.00,USD\n");
        Path history = exportDirectory.resolve(LegacyExportFormat.HISTORY_TEXT_FILE);
        Files.writeString(history, HISTORY_HEADER + "\n" + String.join("\n", historyLines) + "\n");
        return history;
    }

    private void loadHistoryOnly(Path historyFile) {
        MigrationRun run = migrationRuns.save(MigrationRun.start(UUID.randomUUID(), UUID.randomUUID(),
                MigrationRun.Mode.LOAD, historyFile.getParent().toString(),
                MigrationRun.CharacterizationStatus.DRAFT));
        loader.load(run,
                new LegacyLoader.LoadSources(
                        historyFile.resolveSibling(LegacyExportFormat.CASH_ACCOUNT_FILE), null, historyFile,
                        null),
                LEGACY_CHARSET, LEGACY_ZONE, null);
    }

    // Resolved from the test classpath rather than a project-relative path: Maven copies these fixtures into
    // target/test-classes unfiltered, which is what keeps history.cp037.bin byte-intact, and the forked JVM's
    // working directory is no reliable base. A missing directory fails and never skips.
    private static Path matchedFixtureDirectory() {
        URL directory = LoaderIT.class.getResource(MATCHED_FIXTURES);
        if (directory == null) {
            return fail("The matched legacy-export fixtures are absent from the test classpath at "
                    + MATCHED_FIXTURES + "; they are a committed deliverable, so this is a build problem");
        }
        try {
            return Paths.get(directory.toURI());
        } catch (URISyntaxException malformed) {
            return fail("The fixture resource " + directory + " is not addressable as a file path", malformed);
        }
    }

    /** Every {@code legacy_history} row one load staged, read outside the loader's own transaction. */
    // A short-lived EntityManager so the read sees what the load committed rather than what a test transaction
    // still holds; the real entity is returned so the assertions judge the persisted values field by field.
    private List<LegacyHistory> stagedHistory(UUID runId) {
        EntityManager entityManager = entityManagers.createEntityManager();
        try {
            return entityManager
                    .createQuery("select staged from LegacyHistory staged where staged.runId = :runId",
                            LegacyHistory.class)
                    .setParameter("runId", runId)
                    .getResultList();
        } finally {
            entityManager.close();
        }
    }

    /** One load's staged rows for an owner, joined on the uppercased key rather than on the raw name. */
    // MOVE WS-NAME TO WS-VR-NAME applies no case folding (CASH00.cbl:L111, L119), so "John" and "JOHN" under
    // one stamp were two legitimate 29-byte KSDS keys while the account table stores owners upper case (L155);
    // EBCDIC also collates differently from UTF-8 (AAP 0.12.2), so rows are matched on this key, never on order.
    private List<LegacyHistory> stagedHistory(UUID runId, String ownerKey) {
        EntityManager entityManager = entityManagers.createEntityManager();
        try {
            return entityManager
                    .createQuery("select staged from LegacyHistory staged where staged.runId = :runId"
                            + " and staged.ownerKey = :ownerKey", LegacyHistory.class)
                    .setParameter("runId", runId)
                    .setParameter("ownerKey", ownerKey)
                    .getResultList();
        } finally {
            entityManager.close();
        }
    }

    private long stagedHistoryCount(UUID runId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM legacy_history WHERE run_id = ?", Long.class, runId);
    }

    /** How many staged rows carry a request code that counts as a transaction. */
    // The code set is the caller's to pass, not a literal here: CASH00 writes its history record after
    // END-EVALUATE unconditionally (CASH00.cbl:L102, L111-L131), so reads and unrecognized codes are staged
    // too, and which codes count is fixed once in LegacyExportFormat (AAP 0.12.1).
    private long stagedHistoryCount(UUID runId, Collection<String> requestCodes) {
        EntityManager entityManager = entityManagers.createEntityManager();
        try {
            return entityManager
                    .createQuery("select count(staged) from LegacyHistory staged where staged.runId = :runId"
                            + " and staged.requestCode in :requestCodes", Long.class)
                    .setParameter("runId", runId)
                    .setParameter("requestCodes", requestCodes)
                    .getSingleResult();
        } finally {
            entityManager.close();
        }
    }

    /** Every run of a batch, oldest first, as the evidence of one runbook step. */
    // Read here rather than through the repository because MigrationRunRepository publishes one selector - the
    // batch's completed load - and no broad batch list whose only callers would be these tests. run_id breaks
    // a started_at tie for the same reason the selector does, so this listing cannot flake.
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

    // Sorted on the key's own time and name halves so the comparison judges content, never the order two
    // independent queries happened to return rows in.
    private static List<StagedHistoryFacts> stagedFacts(List<LegacyHistory> staged) {
        return staged.stream()
                .map(row -> new StagedHistoryFacts(row.name(), row.ownerKey(), row.eventDate(),
                        row.eventTime(), row.requestCode(), row.balance(), row.currency(), row.retcode()))
                .sorted(Comparator.comparing(StagedHistoryFacts::eventDate)
                        .thenComparing(StagedHistoryFacts::eventTime)
                        .thenComparing(StagedHistoryFacts::name))
                .toList();
    }

    private static ExpectedAccount expectedAccount(String owner) {
        return EXPECTED_ACCOUNTS.stream()
                .filter(expected -> expected.owner().equals(owner))
                .findFirst()
                .orElseGet(() -> fail("The load wrote a ledger row for owner '" + owner
                        + "', which the matched export does not name"));
    }

    /** One expected {@code cash_account} row, its balance carried as text so no money value touches a double. */
    private record ExpectedAccount(String owner, BigDecimal balance, String currency) {

        private ExpectedAccount(String owner, String balance, String currency) {
            this(owner, new BigDecimal(balance), currency);
        }
    }

    /** One expected {@code legacy_rate_table} row, including the two columns the legacy program never read. */
    private record ExpectedRate(String currnkey, String currnbase, BigDecimal amount, BigDecimal rates) {

        private ExpectedRate(String currnkey, String currnbase, String amount, String rates) {
            this(currnkey, currnbase, new BigDecimal(amount), new BigDecimal(rates));
        }
    }

    /** Every staged history field except {@code run_id}, which is what makes the two shapes comparable. */
    private record StagedHistoryFacts(String name,
                                      String ownerKey,
                                      String eventDate,
                                      String eventTime,
                                      String requestCode,
                                      BigDecimal balance,
                                      String currency,
                                      String retcode) {
    }
}
