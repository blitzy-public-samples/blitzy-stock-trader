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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.load;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.audit.LedgerService;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.CashAccount;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.Money;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.OwnerNormalizer;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.ReservationState;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.LegacyExportFormat;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.export.DelimitedExportReader;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.export.LegacyCashAccountRecord;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.export.LegacyRateRecord;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.export.VsamHistoryRecord;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.export.VsamHistoryRecordDecoder;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.LegacyHistory;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.LegacyRateTable;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.MigrationRun;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.ReconciliationService;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.CashAccountRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.CashReservationRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.LegacyHistoryRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.LegacyRateTableRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.MigrationRunRepository;

/*
 * NEVER POINTED AT THE LIVE LEGACY SYSTEM (AAP 0.3.2). Every input is an ordinary file path under
 * tool.input: this class opens no socket, names no DB2 for z/OS table and no VSAM data set, and reads no
 * deployment configuration value. Its only proof is src/test/resources/fixtures/legacy-export.
 *
 * WHY ONE TRANSACTION AROUND THE WHOLE LOAD (AAP 0.6.3). Either every applicable row of the export lands
 * or none does, so a failure anywhere leaves the target exactly as it was and a retry is a new run_id under
 * the same batch_id. That single ambient transaction is also the mechanism LedgerService depends on: its
 * append methods are @Transactional(propagation = MANDATORY) and would fail outright with no transaction
 * open, and a nested or separate one here would let a ledger row or a variance row commit for work that was
 * then rolled back. Nothing in this class may therefore open a second transaction, and no exception may be
 * caught in order to carry on with the next owner or record - partial application is a defect, not a
 * degraded mode. MigrationToolRunner owns the run row's verdict and writes it outside this boundary, which
 * is why a rolled-back load can still leave a FAILED run behind.
 */
/** Applies a parsed legacy export to the target: cash_account rows, their MIGRATION_LOAD ledger rows and the two run-scoped staging tables. */
// NO @Profile, mirroring reconcile/ReconciliationService's recorded decision: MigrationToolRunner carries
// @Profile("tool") while LoaderIT and ReconciliationIT run outside it, and an un-profiled bean is injectable
// in both. Its presence in the deployed web context is inert - no request mapping, no scheduled work, no
// constructor side effect, no ExchangeRateSource bean, and nothing on the request path injects it.
@Service
public class LegacyLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(LegacyLoader.class);

    // Assembled from LegacyExportFormat's constant rather than restating the code page here, so the module
    // keeps one declaration of the assumed region charset. Both operands are compile-time String constants,
    // which is what makes the concatenation legal as an annotation value.
    private static final String LEGACY_CHARSET_PROPERTY =
            "${tool.legacy-charset:" + LegacyExportFormat.DEFAULT_LEGACY_CHARSET + "}";

    // No textual default for either of these: the zone falls back to ZoneOffset.UTC in the constructor, and
    // the record length has no default at all, which IS the requirement (application-tool.yml).
    private static final String LEGACY_TIMEZONE_PROPERTY = "${tool.legacy-timezone:#{null}}";

    private static final String HISTORY_RECORD_LENGTH_PROPERTY = "${tool.history-record-length:#{null}}";

    // How many rows are applied or staged before the persistence context is flushed and cleared. A property
    // because the value trades round trips against resident rows and decides nothing about correctness, and 50
    // because it matches hibernate.jdbc.batch_size in application.yml, so one flush maps onto whole JDBC
    // batches rather than straddling them. Under cashaccount.* rather than tool.*: MigrationToolRunner rejects
    // any tool.* command-line option outside the seven it declares, which would leave an operator sizing a
    // migration window unable to pass this one. The textual default keeps a context that sets nothing - the
    // deployed web application, which never loads - starting exactly as before.
    private static final String BATCH_CHUNK_SIZE_PROPERTY = "${cashaccount.migration.batch-chunk-size:50}";

    private final CashAccountRepository accounts;

    private final CashReservationRepository reservations;

    private final LegacyHistoryRepository legacyHistory;

    private final LegacyRateTableRepository legacyRates;

    private final MigrationRunRepository runs;

    private final LedgerService ledgerService;

    private final ReconciliationService reconciliationService;

    // Constructed, not injected: DelimitedExportReader carries no Spring stereotype, holds no state and is
    // thread-safe, exactly as ReconciliationService constructs its own.
    private final DelimitedExportReader exportReader;

    private final Charset legacyCharset;

    private final ZoneId legacyTimeZone;

    private final Integer historyRecordLength;

    private final int batchChunkSize;

    // CONTEXT CONTROL ONLY, NEVER A DATA PATH. Every read and every write in this class goes through a
    // repository under persistence/ (AAP 0.6.5); this reference exists for the single operation no Spring Data
    // interface exposes - clear(), which is what bounds the persistence context across a bulk load - paired
    // with the flush that must precede it. No row is persisted, merged, removed or queried through it.
    // @PersistenceContext injects the shared transaction-scoped proxy, so it acts inside the caller's single
    // transaction (AAP 0.6.3) and never opens one of its own.
    @PersistenceContext
    private EntityManager entityManager;

    public LegacyLoader(CashAccountRepository accounts,
                        CashReservationRepository reservations,
                        LegacyHistoryRepository legacyHistory,
                        LegacyRateTableRepository legacyRates,
                        MigrationRunRepository runs,
                        LedgerService ledgerService,
                        ReconciliationService reconciliationService,
                        @Value(LEGACY_CHARSET_PROPERTY) String legacyCharset,
                        @Value(LEGACY_TIMEZONE_PROPERTY) String legacyTimeZone,
                        @Value(HISTORY_RECORD_LENGTH_PROPERTY) Integer historyRecordLength,
                        @Value(BATCH_CHUNK_SIZE_PROPERTY) int batchChunkSize) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.legacyHistory = Objects.requireNonNull(legacyHistory, "legacyHistory");
        this.legacyRates = Objects.requireNonNull(legacyRates, "legacyRates");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.ledgerService = Objects.requireNonNull(ledgerService, "ledgerService");
        this.reconciliationService = Objects.requireNonNull(reconciliationService, "reconciliationService");
        this.exportReader = new DelimitedExportReader();
        this.legacyCharset = charsetOf(legacyCharset);
        this.legacyTimeZone = zoneOf(legacyTimeZone);
        this.historyRecordLength = historyRecordLength;
        // Refused rather than defaulted away: a chunk size below one would stage a row and flush on every row
        // at best, and at worst describe a batch that cannot exist - a misconfiguration an operator must see
        // at start-up rather than halfway through a migration window.
        if (batchChunkSize < 1) {
            throw CashAccountException.of(CashAccountErrorCode.INTERNAL,
                    "cashaccount.migration.batch-chunk-size must be at least 1 row per chunk, but was " + batchChunkSize);
        }
        this.batchChunkSize = batchChunkSize;
    }

    /**
     * The files one load reads, each resolved by the caller so a run can stage exactly the shapes it has.
     *
     * <p>Only {@code accountsFile} is required. {@code rateFile} may be null - a target-state load carries
     * accounts alone - and the two history components are the alternative shapes of the same data, of which
     * a run stages exactly one: the binary file when it is present, otherwise the text file.</p>
     */
    public record LoadSources(Path accountsFile, Path rateFile, Path historyTextFile, Path historyBinaryFile) {

        public LoadSources {
            Objects.requireNonNull(accountsFile,
                    "An account export path is required; a load with no accounts file would apply nothing");
        }

        /**
         * Resolves a runbook export directory: both mandatory files unconditionally, each history shape only
         * when it is actually there.
         *
         * <p>The account and rate files are resolved whether or not they exist, so a missing one is reported
         * by the reader that names the file and the column shape it wanted. History is optional, so a
         * directory that carries neither shape stages no history rather than failing.</p>
         */
        public static LoadSources inDirectory(Path inputDirectory) {
            Objects.requireNonNull(inputDirectory, "inputDirectory");
            Path historyText = inputDirectory.resolve(LegacyExportFormat.HISTORY_TEXT_FILE);
            Path historyBinary = inputDirectory.resolve(LegacyExportFormat.HISTORY_BINARY_FILE);
            return new LoadSources(inputDirectory.resolve(LegacyExportFormat.CASH_ACCOUNT_FILE),
                    inputDirectory.resolve(LegacyExportFormat.RATE_TABLE_FILE),
                    Files.isRegularFile(historyText) ? historyText : null,
                    Files.isRegularFile(historyBinary) ? historyBinary : null);
        }

        /**
         * One account file and nothing else, the shape a target-state load needs.
         *
         * <p>With no rate file the validation sees an empty rate list, so no rate row is staged and no rate
         * finding is recorded - the file simply has nothing to say about currencies.</p>
         */
        public static LoadSources accountsOnly(Path accountsFile) {
            return new LoadSources(accountsFile, null, null, null);
        }
    }

    /**
     * What one load did, for the runner that has to close the run row and choose an exit code.
     *
     * @param legacyRecordCount  account rows read from the export, rejected rows included
     * @param migratedRecordCount owners whose target state matches the export after this run: inserted,
     *                            overwritten, or already identical - never a rejected or skipped owner
     * @param varianceCount      rows recorded under this run by the source validation plus every owner this
     *                           load left untouched because funds are held
     * @param stagedHistoryCount rows written to {@code legacy_history}
     * @param stagedRateCount    rows written to {@code legacy_rate_table}
     */
    public record LoadResult(int legacyRecordCount,
                            int migratedRecordCount,
                            int varianceCount,
                            int stagedHistoryCount,
                            int stagedRateCount) {
    }

    /**
     * Loads the export held in {@code inputDirectory}, resolving its files by their documented names.
     *
     * @param run            the open run this load records itself under; its verdict stays the runner's to write
     * @param inputDirectory a directory holding the account and rate exports, and optionally either history shape
     * @return what the load applied and staged
     * @throws CashAccountException when the directory carries a binary history export and
     *         {@code tool.history-record-length} is not configured, or when the export is malformed
     */
    @Transactional
    public LoadResult load(MigrationRun run, Path inputDirectory) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(inputDirectory, "inputDirectory");
        return load(run, LoadSources.inDirectory(inputDirectory), legacyCharset, legacyTimeZone,
                historyRecordLength);
    }

    /**
     * Loads the named files, with the code page, zone and record length the caller declares.
     *
     * @param run                 the open run this load records itself under
     * @param sources             the files to apply and stage
     * @param legacyCharset       the code page a binary history export is decoded with
     * @param legacyTimeZone      the zone the raw {@code YYYYMMDD}/{@code HHMMSS} stamps are resolved at
     * @param historyRecordLength the declared binary record length, required only for a binary history export
     * @return what the load applied and staged
     * @throws CashAccountException when a binary history export is named without a record length, when the
     *         export names one owner or one history key twice, or when an exported balance cannot be held
     */
    @Transactional
    public LoadResult load(MigrationRun run,
                           LoadSources sources,
                           Charset legacyCharset,
                           ZoneId legacyTimeZone,
                           Integer historyRecordLength) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(legacyCharset, "legacyCharset");
        Objects.requireNonNull(legacyTimeZone, "legacyTimeZone");

        MigrationRun activeRun = activeRun(run);
        // Flushed here so the run row is in the database before the source validation writes this run's first
        // finding: migration_reconciliation.run_id references migration_run, and run_id is a plain UUID column
        // rather than an association, so the insert ordering hibernate.order_inserts performs (application.yml)
        // knows nothing of that dependency.
        entityManager.flush();

        // Fail closed before a byte is read, not when the history file is reached: the declared record length is
        // the operator's answer to an open item and a run that lacks it cannot frame the binary export at all.
        requireHistoryRecordLength(sources, historyRecordLength);

        // WHY THE WHOLE EXPORT IS CLASSIFIED BEFORE THE FIRST ACCOUNT ROW IS WRITTEN. The source validation is a
        // complete first pass over the account and rate exports: which owners may be applied at all, and whether
        // the files parse, are settled before this load touches an account row - and inventing a value for a
        // legacy NULL or an out-of-set currency instead would erase the finding an operator reviews. It is a pass
        // and never a copy, so neither file is held; and what makes a failure anywhere in the load - in the
        // second pass, in staging, in the database - leave nothing behind is the single ambient transaction
        // (AAP 0.6.3) rather than the order these passes run in.
        ReconciliationService.SourceValidation validation =
                reconciliationService.validateSource(activeRun, sources.accountsFile(), sources.rateFile());

        // The second pass applies. Rows reach it one at a time, so the loader holds one exported record and one
        // chunk of accounts rather than the file.
        LoadTally tally = new LoadTally(validation.varianceCount());
        exportReader.streamCashAccounts(sources.accountsFile(),
                record -> applyAccount(activeRun, sources, validation, record, tally));
        boundPersistenceContext();

        int stagedRates = stageRates(activeRun, validation, sources);
        int stagedHistory = stageHistory(activeRun, sources, legacyCharset, legacyTimeZone, historyRecordLength);

        LoadResult result = new LoadResult(validation.legacyAccountCount(), tally.migrated, tally.variances,
                stagedHistory, stagedRates);

        LOGGER.info("Load run {} read {} account rows, applied {}, recorded {} variance rows, staged {} history"
                        + " and {} rate rows", activeRun.runId(), result.legacyRecordCount(),
                result.migratedRecordCount(), result.varianceCount(), result.stagedHistoryCount(),
                result.stagedRateCount());

        return result;
    }

    /**
     * Re-reads the run row inside this transaction so a detached instance is managed here.
     *
     * <p>It is materialized only when the row does not exist yet, because
     * {@code migration_reconciliation.run_id} references {@code migration_run} and the source validation may
     * write this run's first finding before the runner's row is visible. No field of the run is set here: its
     * status, counts and {@code finishedAt} belong to {@code MigrationToolRunner}, outside this boundary.</p>
     */
    private MigrationRun activeRun(MigrationRun run) {
        return runs.findById(run.runId()).orElseGet(() -> runs.save(run));
    }

    /**
     * Applies one exported account row: inserted, overwritten, or left alone with a recorded reason.
     *
     * <p>Called once per row of the second pass, so nothing it holds outlives the row except the tally.</p>
     */
    private void applyAccount(MigrationRun run,
                              LoadSources sources,
                              ReconciliationService.SourceValidation validation,
                              LegacyCashAccountRecord record,
                              LoadTally tally) {
        String owner = OwnerNormalizer.normalize(record.owner());

        // A repeated owner is malformed input rather than a last-write-wins case: the legacy primary key
        // was the owner itself and storage was upper case (CASH00.cbl:L155), so a well-formed unload
        // cannot produce one, and collapsing it silently would breach the one-load-event-per-owner rule
        // that the partial index uq_ledger_entry_migration_load enforces.
        if (!tally.seenOwners.add(owner)) {
            throw CashAccountException.forOwner(CashAccountErrorCode.INTERNAL, owner,
                    "The account export " + sources.accountsFile() + " names owner '" + owner
                            + "' more than once; a load applies each owner exactly once per run");
        }

        // REJECTION, NOT MEMBERSHIP. The source validation returns the owners it refused rather than the
        // owners it accepted, because this pass is already reading the export and every owner it sees was named
        // by the file: asking "was this one refused?" needs state proportional to the refusals, while asking
        // "is this one on the accepted list?" would need a set holding every owner in the export.
        if (validation.rejectedOwners().contains(owner)) {
            return;
        }

        Money balance = Money.of(record.balance());
        Optional<CashAccount> existing = accounts.findByOwnerForUpdate(owner);

        if (existing.isEmpty()) {
            insertAccount(run, owner, record.currency(), balance);
            tally.migrated++;
        } else if (reservations.existsByOwnerAndState(owner, ReservationState.HELD)) {
            // WHY A HELD RESERVATION MAKES THE OWNER UNTOUCHABLE. Overwriting an absolute balance while funds
            // sit in reserved_balance would hand an institutional caller's held money back to the available
            // side, leaving a HELD cash_reservation row that its settlement can no longer honour - the
            // conservation the reservation state machine guarantees is only true while nothing rewrites the
            // account behind it (schema/cash-account-schema.sql, cash_reservation; AAP 0.6.3). The condition is
            // therefore tested before any mutation, and the answer is a recorded row for the operator rather
            // than a balance this load decided on its own.
            //
            // The condition is established ONCE, here, under that lock, and the locked row is handed to the
            // recorder: a release has to take this same cash_account row lock, so nothing can free the funds
            // between the test and the row that reports them, and re-testing inside the recorder would only
            // repeat a statement whose answer this transaction owns.
            reconciliationService.recordReservationsOutstanding(run, existing.get());
            tally.variances++;
        } else {
            overwriteAccount(run, existing.get(), record.currency(), balance);
            tally.migrated++;
        }

        // The applied rows and their ledger events leave the persistence context at the chunk boundary, so the
        // second pass costs one chunk of entities however many owners the export names. The account row locks
        // this pass has taken are unaffected: they belong to the transaction and are held until it ends.
        tally.appliedSinceFlush++;
        if (tally.appliedSinceFlush >= batchChunkSize) {
            tally.appliedSinceFlush = 0;
            boundPersistenceContext();
        }
    }

    // FLUSH BEFORE CLEAR, ALWAYS. clear() discards whatever is pending, so a clear without a flush would drop
    // the very rows the load has just applied. Neither is a commit: both run inside the one ambient transaction
    // (AAP 0.6.3), so nothing this load writes becomes durable before the whole load does, and a failure at any
    // chunk still leaves the target exactly as it was.
    private void boundPersistenceContext() {
        entityManager.flush();
        entityManager.clear();
    }

    // WHY A FRESH incarnation_id ON EVERY CREATE. CashAccount.open stamps a new one, and it is what scopes an
    // owner's reservations and idempotency keys to this life of the account: cash_reservation carries
    // UNIQUE (incarnation_id, idempotency_key) and cash_account UNIQUE (incarnation_id) in
    // schema/cash-account-schema.sql, so a key replayed against an owner that was deleted and loaded again can
    // never resurrect a reservation that reserved funds the current account never held.
    //
    // The ledger row is appended after the balance is set, never before: MIGRATION_LOAD is an absolute-set
    // event whose amount is the resulting available balance, which LedgerService reads off the account.
    private void insertAccount(MigrationRun run, String owner, String exportedCurrency, Money balance) {
        CashAccount opened = accounts.save(CashAccount.open(owner, exportedCurrency, balance));
        ledgerService.appendMigrationLoad(opened, run.runId());
    }

    // WHY AN UNCHANGED OWNER WRITES NO LEDGER ROW. Re-running a load - the runbook's rehearsal, then its final
    // load - must leave the audit trail describing what actually changed, and an event recording a balance
    // that is already the balance would assert a state change nothing made. The target state still matches the
    // export, so the owner counts as migrated; only the row is absent. compareTo, never equals: 1000.0 and
    // 1000.00 are the same money and different BigDecimal values, and a delimited export's scale is the
    // producer's choice.
    private void overwriteAccount(MigrationRun run, CashAccount account, String exportedCurrency, Money balance) {
        boolean sameBalance = account.availableBalance().amount().compareTo(balance.amount()) == 0;
        boolean sameCurrency = account.currency().equals(canonicalCurrency(exportedCurrency));
        if (sameBalance && sameCurrency) {
            return;
        }

        account.overwriteAvailableBalance(balance);
        account.changeCurrency(exportedCurrency);
        accounts.save(account);
        ledgerService.appendMigrationLoad(account, run.runId());
    }

    private int stageRates(MigrationRun run,
                           ReconciliationService.SourceValidation validation,
                           LoadSources sources) {
        // No rate file is the accounts-only shape: nothing is read and nothing is staged, exactly as a load
        // that saw an empty rate list did.
        if (sources.rateFile() == null) {
            return 0;
        }

        StagingTally tally = new StagingTally();
        stagingRun(sources.rateFile(), () -> exportReader.streamRates(sources.rateFile(), record -> {
            // The same key expression the validation used, so the two decisions line up exactly rather than
            // approximately; the accepted spellings of the base-currency column are the reader's business.
            String rateKey = LegacyExportFormat.trimPadding(record.currnkey());
            if (validation.rejectedRateKeys().contains(rateKey)) {
                return;
            }
            // A null currnbase or amount is staged as null: the program fetched both columns and referenced
            // neither, so neither is a value this loader may invent.
            stage(legacyRates.save(LegacyRateTable.staged(run.runId(), record)), tally);
        }));

        return tally.staged;
    }

    /**
     * Stages the one history shape this run carries, or nothing when the sources name neither.
     *
     * <p>The binary export wins whenever it is named, because both shapes decode to the same
     * {@code (name, event_date, event_time)} keys and staging both under one {@code run_id} would collide on
     * {@code legacy_history}'s primary key. A run that means to stage the text shape names the text file
     * alone, which is how a comparison of the two shapes is two runs rather than one.</p>
     */
    // WHY legacy_history.name KEEPS THE CALLER'S CASING. CASH00 moved WS-NAME into the record with no case
    // folding (CASH00.cbl:L111) while the account table stored upper case, so "John"+stamp and "JOHN"+stamp
    // were two distinct, equally valid 29-byte KSDS keys (DEFKSDS.jcl:L14) - and on a Q request WS-NAME had
    // already been replaced by the database's upper-case OWNER (CASH00.cbl:L144), so one physical owner
    // legitimately appears under two casings. Folding the name would merge real records; the uppercased join
    // key is carried beside it instead, as legacy_history.owner_key.
    private int stageHistory(MigrationRun run,
                             LoadSources sources,
                             Charset legacyCharset,
                             ZoneId legacyTimeZone,
                             Integer historyRecordLength) {
        StagingTally tally = new StagingTally();
        // Both shapes stage through one consumer, so the text and binary paths cannot diverge in what they
        // write - which is the property AAP 0.10.3 has LoaderIT assert row for row.
        Consumer<VsamHistoryRecord> staging = record -> stageHistoryRecord(run, record, legacyTimeZone, tally);

        if (sources.historyBinaryFile() != null) {
            stagingRun(sources.historyBinaryFile(), () -> new VsamHistoryRecordDecoder(legacyCharset,
                    historyRecordLength).streamAll(sources.historyBinaryFile(), staging));
        } else if (sources.historyTextFile() != null) {
            stagingRun(sources.historyTextFile(),
                    () -> exportReader.streamHistory(sources.historyTextFile(), staging));
        }

        return tally.staged;
    }

    private void stageHistoryRecord(MigrationRun run,
                                    VsamHistoryRecord record,
                                    ZoneId legacyTimeZone,
                                    StagingTally tally) {
        // A blank name propagates as INVALID_OWNER rather than being dropped: staging is lossless, and a
        // record whose owner cannot be derived is a file to fix, not a row to lose.
        stage(legacyHistory.save(LegacyHistory.staged(run.runId(), record,
                OwnerNormalizer.normalize(record.name()), eventAt(record, legacyTimeZone))), tally);
    }

    // WHERE THE DUPLICATE-KEY CHECK LIVES, AND WHY IT IS NOT A SET IN MEMORY. A staged row's identity is its
    // legacy primary key within this run - (run_id, currnkey) and the raw 29-byte KSDS key (CASH00.cbl:L47-L50,
    // DEFKSDS.jcl:L14) - and the staging tables declare exactly those as primary keys, so the run-scoped
    // uniqueness the export must satisfy is already held by the database. Detecting a repeat there instead of
    // in a set costs nothing per row and keeps the loader's footprint independent of the export's length, which
    // a set of every key it has seen could not. The abort is unchanged: the whole load is one transaction
    // (AAP 0.6.3), so a repeated key leaves nothing behind either way - and the driver names the offending key
    // values, which is what an operator needs to find the line.
    //
    // Both moments are covered. Two rows with one key in the same chunk collide in the persistence context, and
    // rows in different chunks collide at the insert; Spring's exception translation renders each as a
    // DataAccessException, which is why the translation below catches the family rather than one type.
    private void stagingRun(Path exportFile, Runnable stagingPass) {
        try {
            stagingPass.run();
            // The trailing partial chunk leaves the context here, inside the translation below, so a repeated
            // key among the last few rows of a file is reported exactly like one in any other chunk.
            boundPersistenceContext();
        } catch (DataAccessException | PersistenceException rejected) {
            // Two exception families because the failure has two origins: a repeat inside one chunk is raised by
            // the repository's save and arrives translated as a DataAccessException, while a repeat across chunks
            // is raised by the flush and arrives as the provider's own PersistenceException, which nothing
            // translates because the flush is not a repository call.
            throw CashAccountException.of(CashAccountErrorCode.INTERNAL,
                    "The export " + exportFile + " could not be staged: a record repeats a key its legacy"
                            + " primary key could not hold twice, so staging cannot be lossless."
                            + " The database reports: " + rootCause(rejected), rejected);
        }
    }

    // The driver's own message, which carries the duplicate key's values; Spring's wrapper text names only the
    // statement. Walked to the deepest cause so the detail is not buried under two layers of translation.
    private static String rootCause(Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message.strip();
    }

    // WHY save AND NOT saveAll, AND WHY THE ROW LEAVES THE CONTEXT AT A CHUNK BOUNDARY. save() on a staging
    // entity is an insert and nothing more: both staging entities declare their own newness (Persistable on
    // reconcile/LegacyHistory and reconcile/LegacyRateTable), so Spring Data persists rather than merges and no
    // existence SELECT is issued for an assigned composite key. saveAll would add nothing but a list of every
    // row. The chunk boundary is where the accumulated inserts leave the context; its size equals
    // hibernate.jdbc.batch_size (application.yml), so a flush is whole JDBC batches rather than one statement
    // per row - the default Hibernate would otherwise apply, which makes a bulk staging pass N round trips.
    private void stage(Object stagedRow, StagingTally tally) {
        Objects.requireNonNull(stagedRow, "A staged row is required");
        tally.staged++;
        if (tally.staged % batchChunkSize == 0) {
            boundPersistenceContext();
        }
    }

    /**
     * Resolves a history record's raw stamp at the configured zone, or {@code null} when it will not parse.
     *
     * <p>The one field where a malformed value is tolerated, and deliberately: nothing is lost, because the
     * raw text is stored in the key columns either way, and the zone itself is an unconfirmed assumption the
     * mainframe team has yet to answer. Guessing an instant would silently shift a shadow window's boundaries
     * while every individual row still looked plausible.</p>
     */
    private static OffsetDateTime eventAt(VsamHistoryRecord record, ZoneId legacyTimeZone) {
        String date = record.eventDate();
        String time = record.eventTime();
        if (date == null || time == null) {
            return null;
        }
        try {
            LocalDate day = LocalDate.parse(date.strip(), LegacyExportFormat.HISTORY_DATE_FORMAT);
            LocalTime moment = LocalTime.parse(time.strip(), LegacyExportFormat.HISTORY_TIME_FORMAT);
            return day.atTime(moment).atZone(legacyTimeZone).toOffsetDateTime();
        } catch (DateTimeParseException unresolvable) {
            return null;
        }
    }

    /** Requires the declared record length whenever this run's sources name a binary history export. */
    // Fail closed on a binary export with no declared length. CASH00 writes a record whose width is the
    // accumulated width of WS-VSAM-RECORD (CASH00.cbl:L38-L45, passed as LENGTH OF at L128) into a cluster
    // whose RECSZ is wider (DEFKSDS.jcl:L11), and which of the two a REPRO yields depends on a CICS FILE
    // definition that is not in this repository (AAP 0.11.2); LegacyExportFormat holds both accepted widths.
    // A guessed length divides many files cleanly and then shifts every field of every record - a wrong load
    // rather than a failed one. Checked at the top of the load rather than where the file is staged, so a run
    // that cannot decode its history never reaches the database at all.
    private static void requireHistoryRecordLength(LoadSources sources, Integer historyRecordLength) {
        if (sources.historyBinaryFile() != null && historyRecordLength == null) {
            throw CashAccountException.of(CashAccountErrorCode.INTERNAL,
                    "tool.history-record-length is mandatory whenever a binary history export ("
                            + sources.historyBinaryFile() + ") is loaded: the record length is declared"
                            + " from the CICS FILE definition, never inferred from the file");
        }
    }

    // Locale.ROOT, never the no-argument toUpperCase(): a Turkish default locale maps "i" to U+0130, which
    // would make an unchanged currency compare unequal on one pod and equal on another. Shape and membership
    // of the accepted set were already judged by the source validation, so this only renders the form the
    // entity stores for the comparison above.
    private static String canonicalCurrency(String exportedCurrency) {
        return exportedCurrency == null ? null : exportedCurrency.strip().toUpperCase(Locale.ROOT);
    }

    private static Charset charsetOf(String legacyCharset) {
        String candidate = legacyCharset == null ? null : legacyCharset.strip();
        if (candidate == null || candidate.isEmpty()) {
            return Charset.forName(LegacyExportFormat.DEFAULT_LEGACY_CHARSET);
        }
        try {
            return Charset.forName(candidate);
        } catch (IllegalArgumentException unsupported) {
            throw CashAccountException.of(CashAccountErrorCode.INTERNAL,
                    "tool.legacy-charset names '" + candidate + "', which this JVM cannot decode with;"
                            + " the assumed legacy code page is " + LegacyExportFormat.DEFAULT_LEGACY_CHARSET,
                    unsupported);
        }
    }

    // ZoneOffset.UTC as the fallback rather than a string default, so the assumed zone is a typed constant
    // and the value stays correctable by configuration mid-migration (AAP 0.11.2).
    private static ZoneId zoneOf(String legacyTimeZone) {
        String candidate = legacyTimeZone == null ? null : legacyTimeZone.strip();
        if (candidate == null || candidate.isEmpty()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(candidate);
        } catch (RuntimeException unresolvable) {
            throw CashAccountException.of(CashAccountErrorCode.INTERNAL,
                    "tool.legacy-timezone names '" + candidate + "', which is not a zone this JVM knows",
                    unresolvable);
        }
    }

    /** What the account pass has applied so far, carried across its chunks. */
    // seenOwners is the one member that grows with the export, and it holds a normalized owner key per row -
    // never a record and never an entity. It cannot be dropped in favour of the database's own constraint:
    // losslessness requires naming a file that lists one owner twice, and the partial index
    // uq_ledger_entry_migration_load could only report that some row was a duplicate, not which line wrote it.
    private static final class LoadTally {

        private final Set<String> seenOwners = new LinkedHashSet<>();

        private int migrated;

        private int variances;

        private int appliedSinceFlush;

        private LoadTally(int variancesAlreadyRecorded) {
            this.variances = variancesAlreadyRecorded;
        }
    }

    /** How many rows one staging pass has written. */
    // A counter and nothing else. The keys a staging pass has already seen are held by the staging tables'
    // own run-scoped primary keys rather than by this class, so the pass costs the same whether it stages six
    // rows or six million (see stagingRun).
    private static final class StagingTally {

        private int staged;
    }
}
