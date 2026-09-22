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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.CashAccount;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.LedgerEventType;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.load.LegacyLoader;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.LegacyHistory;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.LegacyRateTable;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.MigrationRun;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.CashAccountRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.LegacyRateTableRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.MigrationRunRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.LegacyHistoryTestQueries;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.PostgresTestSupport;

/** Proves {@code LegacyLoader} applies the matched fixture export and that its two history shapes decode identically (AAP 0.10.3). */
/*
 * FIXTURES ONLY, AND THAT IS A PROHIBITION RATHER THAN A CONVENIENCE (AAP 0.3.1-0.3.2). Every input below is
 * a file under src/test/resources/fixtures/legacy-export/matched: no DB2 for z/OS connection, no VSAM data
 * set and no deployment credential appears here or may be added, whatever access the executing environment
 * happens to hold. Bulk migration against the real mainframe is runbook step 1, a handoff this test proves
 * the mechanism for and never performs.
 *
 * WHY THE tool PROFILE IS NEVER ACTIVATED. MigrationToolRunner carries @Profile("tool") and is an
 * ApplicationRunner, which @SpringBootTest executes as part of context start-up, and it closes a command
 * with System.exit(SpringApplication.exit(...)) - under Failsafe that terminates the forked JVM mid-suite and
 * reports as a crashed fork rather than as a failed test. This class therefore plays the runner's part
 * itself - open a run, load, close the run - and asserts the exit code as the persisted state the runner maps
 * to it (CLEAN with no variance is exit 0; variance is 2 and a failure is 1), never by invoking the runner.
 */
// No web layer is needed: this test drives the service layer directly.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
// The chunk size a load applies and stages by, forced to a value several times smaller than every count this
// class asserts. That is what makes the assertions below evidence of bounded-ness as well as of correctness: the
// six owners, the three rate rows and the eight history rows each cross several chunk boundaries, so a flush and
// clear that lost, duplicated or reordered a row would show up as a wrong count or a missing account here rather
// than only under a production-sized export no test can ship (AAP 0.10.3 fixtures only).
@TestPropertySource(properties = "cashaccount.migration.batch-chunk-size=" + LoaderIT.FORCED_CHUNK_SIZE)
class LoaderIT extends PostgresTestSupport {

    /** Package-private so the annotation above and the guard below read one declaration of the forced size. */
    static final String FORCED_CHUNK_SIZE = "2";

    private static final String MATCHED_FIXTURES = "/fixtures/legacy-export/matched";

    private static final Charset LEGACY_CHARSET = Charset.forName(LegacyExportFormat.DEFAULT_LEGACY_CHARSET);

    // The zone is declared, never defaulted silently: the CICS region's own zone is an open item
    // (AAP 0.11.2), and only the raw YYYYMMDD/HHMMSS text is compared below, so the choice cannot move an
    // expected value.
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

    // fixtures/legacy-export/matched/frankfurt1.csv. currnbase and amount are staged exactly as given and
    // asserted as given: the legacy SELECT fetched both and no COMPUTE or MOVE referenced either
    // (AAP 0.4.1), so neither is a value this test or the loader may invent.
    private static final List<ExpectedRate> EXPECTED_RATES = List.of(
            new ExpectedRate("USD", "USD", "1.00", "1.00"),
            new ExpectedRate("EUR", "USD", "1.00", "0.92"),
            new ExpectedRate("GBP", "USD", "1.00", "0.79"));

    private static final int EXPECTED_HISTORY_ROWS = 8;

    // The single stamp the fixture gives BOTH of JOHN's history rows, date and time alike. It is shared on
    // purpose: the 29-byte KSDS key is name + date + time (CASH00.cbl:L47-L50; KEYS(29 0), DEFKSDS.jcl:L14),
    // so holding the stamp identical leaves the raw name as the only field separating the two keys, and the
    // assertion below then fails if a load ever folds the casing. Two different stamps would have kept the
    // rows apart by themselves and proven nothing about the name.
    private static final String JOHN_SHARED_EVENT_DATE = "20240115";

    private static final String JOHN_SHARED_EVENT_TIME = "091500";

    // Six of the eight history rows carry a counted request code. The two Q rows are staged and excluded
    // from the count on purpose: CASH00 writes its history record after END-EVALUATE unconditionally
    // (CASH00.cbl:L102, L111-L131), so reads were recorded too, while the target ledger records state
    // changes only (AAP 0.4.6) - counting them would make every transaction-count comparison wrong by the
    // number of reads in the window.
    private static final int EXPECTED_COUNTED_HISTORY_ROWS = 6;

    @Autowired
    private LegacyLoader loader;

    @Autowired
    private MigrationRunRepository migrationRuns;

    @Autowired
    private CashAccountRepository cashAccounts;

    @Autowired
    private LegacyHistoryTestQueries legacyHistory;

    @Autowired
    private LegacyRateTableRepository legacyRates;

    // The only way to read ledger_entry: persistence/LedgerEntryRepository extends the bare Repository
    // interface and exposes save plus two owner-scoped finders, deliberately offering no count, no findAll
    // and no delete.
    @Autowired
    private JdbcTemplate jdbc;

    /*
     * WHY ledger_entry IS ABSENT FROM THIS LIST. The schema trigger ledger_entry_immutable rejects both
     * UPDATE and DELETE, so its rows accumulate across every test sharing this JVM-wide container and there
     * is no cleanup to perform - which is why every ledger assertion below is scoped by run_id, and why the
     * SQL that reads it is read-only. The order of the six deletes follows the constraints:
     * migration_reconciliation references migration_run, and the two staging tables and cash_reservation go
     * before cash_account.
     */
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
        // The forced chunk size is asserted rather than assumed: raise it above the fixture counts and every
        // number below would still pass while proving nothing about a chunk boundary.
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

        /*
         * WHY THE TWO HISTORY SHAPES ARE LOADED UNDER SEPARATE RUNS. Both decode to the same
         * (name, event_date, event_time) keys, which is legacy_history's primary key, so staging them under
         * one run_id would collide rather than compare - LegacyLoader.readHistory consequently stages
         * exactly one shape, preferring the binary file whenever it is named. Proving them identical is
         * therefore two runs of one batch: this one names the text file alone.
         *
         * The explicit overload, not load(run, directory): the directory form resolves history.cp037.bin
         * because it is there and then demands tool.history-record-length, which the test profile omits by
         * design (application-test.yml: "tool.* - owned by application-tool.yml"). Passing null for the
         * record length here is the second half of that contract - the declared length is required for the
         * binary shape only.
         */
        MigrationRun runA = migrationRuns.save(MigrationRun.start(UUID.randomUUID(), batchId,
                MigrationRun.Mode.LOAD, matched.toString(), MigrationRun.CharacterizationStatus.DRAFT));
        LegacyLoader.LoadResult resultA = loader.load(runA,
                new LegacyLoader.LoadSources(resolved.accountsFile(), resolved.rateFile(),
                        resolved.historyTextFile(), null),
                LEGACY_CHARSET, LEGACY_ZONE, null);

        /*
         * Run B stages the binary shape and nothing else that matters. It names the account file because
         * LoadSources rejects a null accountsFile - a load with no accounts would apply nothing - and that
         * costs this comparison nothing: the six owners already hold exactly these balances and currencies,
         * so LegacyLoader leaves each row untouched and writes no MIGRATION_LOAD event for a state change
         * nothing made. Asserted below as zero ledger rows under run B. The record length is declared from
         * the fixture's documented 100-byte shape (57 data bytes plus RECSZ(100 100) slack,
         * DEFKSDS.jcl:L11), never inferred from the file size (AAP 0.12.1).
         */
        MigrationRun runB = migrationRuns.save(MigrationRun.start(UUID.randomUUID(), batchId,
                MigrationRun.Mode.LOAD, matched.toString(), MigrationRun.CharacterizationStatus.DRAFT));
        LegacyLoader.LoadResult resultB = loader.load(runB,
                new LegacyLoader.LoadSources(resolved.accountsFile(), null, null,
                        resolved.historyBinaryFile()),
                LEGACY_CHARSET, LEGACY_ZONE, LegacyExportFormat.HISTORY_PADDED_RECORD_LENGTH);

        // Exactly what MigrationToolRunner does with a LoadResult, so the run row this test asserts is the
        // one an operator would read after `--tool.command=load`.
        runA.finish(resultA.varianceCount() == 0 ? MigrationRun.Status.CLEAN : MigrationRun.Status.VARIANCE,
                resultA.legacyRecordCount(), resultA.migratedRecordCount(), resultA.varianceCount());
        migrationRuns.save(runA);

        List<Map<String, Object>> migrationLoadRows = jdbc.queryForList(
                "SELECT owner, amount, currency, available_after, reserved_after, source"
                        + " FROM ledger_entry WHERE run_id = ? AND event_type = ? ORDER BY owner",
                runA.runId(), LedgerEventType.MIGRATION_LOAD.name());
        List<StagedHistoryFacts> historyFromText = stagedFacts(legacyHistory.findByRunId(runA.runId()));
        List<StagedHistoryFacts> historyFromBinary = stagedFacts(legacyHistory.findByRunId(runB.runId()));

        assertSoftly(softly -> {
            // 1. What the load reported: six account rows read and applied, no variance, the whole history
            //    file staged, all three rate rows staged.
            softly.assertThat(resultA.legacyRecordCount()).as("account rows read from the export").isEqualTo(6);
            softly.assertThat(resultA.migratedRecordCount()).as("owners applied").isEqualTo(6);
            softly.assertThat(resultA.varianceCount()).as("variance rows - matched fixtures seed none")
                    .isZero();
            softly.assertThat(resultA.stagedHistoryCount()).as("history rows staged from history.csv")
                    .isEqualTo(EXPECTED_HISTORY_ROWS);
            softly.assertThat(resultA.stagedRateCount()).as("rate rows staged from frankfurt1.csv")
                    .isEqualTo(EXPECTED_RATES.size());

            // 2. The accounts themselves, owner by owner. Owners are stored upper case (AAP 0.4.2) and the
            //    loader touches reserved_balance for nobody, so every reservation column stays at zero.
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

            // 3. One MIGRATION_LOAD ledger row per owner, carrying run A's id. MIGRATION_LOAD is an
            //    absolute-set event, so its amount is the resulting available balance rather than a delta
            //    (AAP 0.6.3), which is why amount and available_after agree.
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

            // 4. Rate staging, under run A's id and with the rate's own two decimals - NUMERIC(3,2) in the
            //    legacy catalog (DB2DDL.jcl:L58), which is why no rate here can exceed 9.99.
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

            // 5. History staging keeps every row and every casing. MOVE WS-NAME TO WS-VR-NAME applies no
            //    case folding (CASH00.cbl:L111), so "John"+stamp and "JOHN"+stamp are two distinct valid
            //    29-byte KSDS keys (DEFKSDS.jcl:L14) that must both survive under one uppercased join key.
            //    The fixture's two JOHN rows carry the SAME stamp, so the tuple assertion below is the whole
            //    proof that the raw name is part of the key: it holds only if staging kept both casings, and
            //    a name folded to upper case would leave one row where the export held two. The dedupe guard
            //    keyed on the raw name and legacy_history's primary key are what make the pair legal.
            softly.assertThat(legacyHistory.countByRunId(runA.runId())).as("staged history rows")
                    .isEqualTo(EXPECTED_HISTORY_ROWS);
            List<LegacyHistory> johnRows = legacyHistory.findByRunIdAndOwnerKey(runA.runId(), "JOHN");
            softly.assertThat(johnRows).as("history rows joined to owner JOHN").hasSize(2);
            softly.assertThat(johnRows)
                    .as("JOHN's two raw names, both surviving at one identical stamp")
                    .extracting(LegacyHistory::name, LegacyHistory::eventDate, LegacyHistory::eventTime)
                    .containsExactlyInAnyOrder(
                            tuple("John", JOHN_SHARED_EVENT_DATE, JOHN_SHARED_EVENT_TIME),
                            tuple("JOHN", JOHN_SHARED_EVENT_DATE, JOHN_SHARED_EVENT_TIME));
            softly.assertThat(legacyHistory.countByRunIdAndRequestCodeIn(runA.runId(),
                            LegacyExportFormat.COUNTED_REQUEST_CODES))
                    .as("history rows whose request code is a state change, the two Q reads excluded")
                    .isEqualTo(EXPECTED_COUNTED_HISTORY_ROWS);

            // 6. The AAP 0.10.3 assertion: history.cp037.bin decodes to exactly what history.csv decodes to.
            //    Compared field for field ignoring run_id, and with scale-sensitive BigDecimal equality -
            //    which holds only because every fixture balance carries exactly two decimals, the zoned
            //    9(7)V99 field (CASH00.cbl:L44) and the delimited text agreeing on scale 2.
            softly.assertThat(resultB.stagedHistoryCount()).as("history rows staged from the binary export")
                    .isEqualTo(EXPECTED_HISTORY_ROWS);
            softly.assertThat(historyFromBinary)
                    .as("the binary history export must decode to the text export, row for row")
                    .isEqualTo(historyFromText);

            // Staging is run-scoped, which is what lets the same eight keys coexist: two runs, sixteen rows,
            // and run B staged no rate row because it named no rate file.
            softly.assertThat(legacyHistory.countByRunId(runB.runId())).as("run B's staged history rows")
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

            // 7. The run row an operator reads, and the batch that ties the two commands together.
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
            softly.assertThat(migrationRuns.findByBatchIdOrderByStartedAtAsc(batchId))
                    .extracting(MigrationRun::runId)
                    .as("both runs of the batch, in start order")
                    .containsExactly(runA.runId(), runB.runId());
        });
    }

    /*
     * WHY A MALFORMED EXPORT IS WRITTEN HERE RATHER THAN COMMITTED AS A FIXTURE. The committed fixtures are the
     * acceptance evidence of AAP 0.10.3 and every one of them is well formed; a deliberately corrupt file beside
     * them would be read as legacy data that looks like this. The two lines below are the minimum that proves
     * both halves of the contract: the first is a legacy NULL balance, which the source validation records as a
     * finding before the second line is reached, and the second carries characters after a closing quote, where
     * RFC 4180 admits only a delimiter, a record end or end of file (AAP 0.12.1). Before this was rejected,
     * "JOHN"X loaded as the owner JOHNX - a value no producer wrote and no operator could trace.
     */
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

        // The failure has to name the file and the 1-based line an operator can open, and say which rule the
        // line breaks - a load that fails without saying where is a load nobody can fix.
        assertThatThrownBy(() -> loader.load(run, LegacyLoader.LoadSources.accountsOnly(accountsFile),
                LEGACY_CHARSET, LEGACY_ZONE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(accountsFile.toString())
                .hasMessageContaining("line 3")
                .hasMessageContaining("RFC 4180");

        assertSoftly(softly -> {
            // THE SINGLE-TRANSACTION CONTRACT (AAP 0.6.3). NULLBAL's finding row was written before the
            // malformed line was reached, so a load that left anything at all behind would show it here - and
            // the accounts, ledger and finding tables are checked together because "nothing was applied" is a
            // statement about all three.
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

    /*
     * Resolved from the test classpath rather than from a project-relative path: Maven copies these fixtures
     * into target/test-classes unfiltered, which is what keeps history.cp037.bin byte-intact, and the forked
     * test JVM's working directory is not a reliable base. A missing directory fails - never skips - because
     * an integration test that silently stopped asserting is worse than a red one.
     */
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

    // Sorted on the key's own time and name halves so the comparison judges content and never the order two
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
