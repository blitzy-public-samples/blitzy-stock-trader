package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.load;

import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
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
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
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

/** Applies a parsed legacy export to the target: cash_account rows, their MIGRATION_LOAD ledger rows and the two run-scoped staging tables. */
@Service
public class LegacyLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(LegacyLoader.class);

    // The code page default comes from LegacyExportFormat's constant so the assumed region charset keeps one
    // declaration.
    private static final String LEGACY_CHARSET_PROPERTY = "tool.legacy-charset";

    // No default for either: the zone falls back to ZoneOffset.UTC in the constructor, and the record length
    // has deliberately no default, because it is the operator's answer to an open item.
    private static final String LEGACY_TIMEZONE_PROPERTY = "tool.legacy-timezone";

    private static final String HISTORY_RECORD_LENGTH_PROPERTY = "tool.history-record-length";

    // Configurable because the value trades round trips against resident rows and decides nothing about
    // correctness, and 50 because it matches hibernate.jdbc.batch_size, so one flush maps onto whole JDBC
    // batches. Under cashaccount.* because the runner rejects any tool.* option outside the seven it
    // declares, which would leave an operator sizing a migration window unable to pass this one.
    private static final String BATCH_CHUNK_SIZE_PROPERTY = "cashaccount.migration.batch-chunk-size";

    private static final int DEFAULT_BATCH_CHUNK_SIZE = 50;

    // SQLState rather than a driver error code or a message substring: the states below are the ones the
    // standard assigns, so the staging diagnosis reads the same whatever driver or message locale is in play.
    private static final String SQL_STATE_UNIQUE_VIOLATION = "23505";

    private static final String SQL_STATE_CLASS_INTEGRITY_CONSTRAINT = "23";

    private static final String SQL_STATE_CLASS_DATA_EXCEPTION = "22";

    private static final String SQL_STATE_CLASS_CONNECTION_EXCEPTION = "08";

    private final CashAccountRepository accounts;

    private final CashReservationRepository reservations;

    private final LegacyHistoryRepository legacyHistory;

    private final LegacyRateTableRepository legacyRates;

    private final MigrationRunRepository runs;

    private final LedgerService ledgerService;

    private final ReconciliationService reconciliationService;

    // Constructed, not injected: DelimitedExportReader carries no Spring stereotype, holds no state and is
    // thread-safe.
    private final DelimitedExportReader exportReader;

    // Both held as inert text and resolved on use, never in the constructor: an unusable tool.legacy-charset or
    // tool.legacy-timezone must reach an operator as MigrationToolRunner's one-line argument error, and resolving
    // them during bean creation made that same message the innermost Caused-by of an 80-line refresh failure.
    // The runner's dispatch passes its own validated values to the explicit load(...) below, so these two are
    // read only by the convenience overload, whose caller declared no values of its own.
    private final String configuredLegacyCharset;

    private final String configuredLegacyTimeZone;

    private final Integer historyRecordLength;

    private final int batchChunkSize;

    // Context control only, never a data path: every read and write here goes through a repository, and this
    // reference exists for the one operation no Spring Data interface exposes - clear(), which bounds the
    // persistence context across a bulk load - paired with the flush that must precede it.
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Container constructor.
     *
     * @param accounts              the target account rows
     * @param reservations          consulted to leave an owner with a held reservation untouched
     * @param legacyHistory         the history staging rows
     * @param legacyRates           the rate-table staging rows
     * @param runs                  {@code migration_run} rows
     * @param ledgerService         appends the {@code MIGRATION_LOAD} row inside this load's transaction
     * @param reconciliationService validates the export before a row of it is applied
     * @param environment           source of the two {@code tool.*} decoding values, the record length and the
     *                              chunk size, all read through {@link Binder}
     * @throws CashAccountException when the chunk size is below one row
     */
    // BINDER, NEVER @Value, FOR ALL FOUR. A @Value placeholder is resolved and its RESOLVED TEXT is then handed to
    // Spring's expression resolver, so a code page, zone, record length or chunk size written as #{...} would
    // execute while this loader was being created. Binder resolves ${...} and converts, evaluating nothing, so a
    // wrong value fails its own conversion, the chunk-size check below, or - for the code page and the zone - the
    // invocation that would have decoded with it. It also drops the two inline #{null} defaults these parameters
    // used to carry, since an unbound Integer is already the absent case the record-length rule needs.
    public LegacyLoader(CashAccountRepository accounts,
                        CashReservationRepository reservations,
                        LegacyHistoryRepository legacyHistory,
                        LegacyRateTableRepository legacyRates,
                        MigrationRunRepository runs,
                        LedgerService ledgerService,
                        ReconciliationService reconciliationService,
                        Environment environment) {
        int batchChunkSize = integerProperty(environment, BATCH_CHUNK_SIZE_PROPERTY, DEFAULT_BATCH_CHUNK_SIZE);

        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.legacyHistory = Objects.requireNonNull(legacyHistory, "legacyHistory");
        this.legacyRates = Objects.requireNonNull(legacyRates, "legacyRates");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.ledgerService = Objects.requireNonNull(ledgerService, "ledgerService");
        this.reconciliationService = Objects.requireNonNull(reconciliationService, "reconciliationService");
        this.exportReader = new DelimitedExportReader();
        this.configuredLegacyCharset =
                toolProperty(environment, LEGACY_CHARSET_PROPERTY, LegacyExportFormat.DEFAULT_LEGACY_CHARSET);
        this.configuredLegacyTimeZone = toolProperty(environment, LEGACY_TIMEZONE_PROPERTY, null);
        this.historyRecordLength = integerProperty(environment, HISTORY_RECORD_LENGTH_PROPERTY);
        // Refused rather than defaulted away: a chunk size below one describes a batch that cannot exist,
        // which an operator must see at start-up and not halfway through a migration window.
        if (batchChunkSize < 1) {
            throw CashAccountException.of(CashAccountErrorCode.INTERNAL,
                    "cashaccount.migration.batch-chunk-size must be at least 1 row per chunk, but was " + batchChunkSize);
        }
        this.batchChunkSize = batchChunkSize;
    }

    /**
     * The files one load reads, each resolved by the caller so a run can stage exactly the shapes it has.
     *
     * @param accountsFile      the account export, the one required file
     * @param rateFile          the rate export, or {@code null} for a load that carries accounts alone
     * @param historyTextFile   the delimited history export, or {@code null}
     * @param historyBinaryFile the raw EBCDIC history export, or {@code null}; it wins where both shapes
     *                          are named, because staging both under one run would collide on the key
     */
    // The approved directory travels with the paths because a path is re-resolved on every use, so a directory
    // component renamed and replaced by a symbolic link between this resolution and the reads below would
    // silently redirect them - and NOFOLLOW_LINKS on the open cannot see it, because it refuses only a link
    // in the final component. Carrying LegacyExportFormat.ApprovedDirectory lets every read open its file
    // relative to the directory that was actually validated, so a substitution is refused instead of
    // followed (AAP 0.3.2). It is null for the accounts-only and explicitly-named shapes, where there is no
    // approved directory for containment to mean anything and only link refusal applies.
    public record LoadSources(Path accountsFile,
                              Path rateFile,
                              Path historyTextFile,
                              Path historyBinaryFile,
                              LegacyExportFormat.ApprovedDirectory approvedDirectory) {

        public LoadSources {
            Objects.requireNonNull(accountsFile,
                    "An account export path is required; a load with no accounts file would apply nothing");
        }

        /** Files a caller names outright, with no approved directory to anchor their opens to. */
        public LoadSources(Path accountsFile, Path rateFile, Path historyTextFile, Path historyBinaryFile) {
            this(accountsFile, rateFile, historyTextFile, historyBinaryFile, null);
        }

        /** One export file of this load, opened the way this load's sources were resolved. */
        LegacyExportFormat.ExportFile exportFile(Path file) {
            return approvedDirectory == null
                    ? LegacyExportFormat.ExportFile.named(file)
                    : LegacyExportFormat.ExportFile.inApprovedDirectory(approvedDirectory,
                            file.getFileName().toString());
        }

        /**
         * Resolves a runbook export directory, naming the account and rate files whether or not they exist
         * so that a missing one is reported by the reader that knows the column shape it wanted.
         *
         * @param inputDirectory the directory the runbook step wrote its export to
         * @return the sources it holds; a directory carrying neither history shape stages no history
         */
        // RESOLVED THROUGH LegacyExportFormat, NEVER BY Path.resolve. A fixed child name resolved against an
        // unverified directory and tested with a link-following Files.isRegularFile accepts a symbolic link
        // named cashaccounty.csv, whose target is any file this process may read - and a load would then stage
        // that content into the legacy_* tables as legacy data. The helper refuses a link, a non-ordinary file
        // and anything whose real path is not a child of the approved directory, and the two history shapes
        // are tested with NOFOLLOW_LINKS so a link cannot answer "this shape is present" either (AAP 0.3.2).
        public static LoadSources inDirectory(Path inputDirectory) {
            Objects.requireNonNull(inputDirectory, "inputDirectory");
            LegacyExportFormat.ApprovedDirectory approved =
                    LegacyExportFormat.approveInputDirectory(inputDirectory);
            // Resolved through the containment rules as well as opened through the approval: the resolution
            // refuses a link or a non-ordinary file here, naming the child an operator can look at, while the
            // approval is what keeps the later opens inside this very directory.
            Path historyText =
                    LegacyExportFormat.resolveInputFile(inputDirectory, LegacyExportFormat.HISTORY_TEXT_FILE);
            Path historyBinary =
                    LegacyExportFormat.resolveInputFile(inputDirectory, LegacyExportFormat.HISTORY_BINARY_FILE);
            return new LoadSources(
                    LegacyExportFormat.resolveInputFile(inputDirectory, LegacyExportFormat.CASH_ACCOUNT_FILE),
                    LegacyExportFormat.resolveInputFile(inputDirectory, LegacyExportFormat.RATE_TABLE_FILE),
                    // Presence is judged through the approved directory, so a link cannot answer "this shape
                    // is present" and a substituted directory cannot answer at all.
                    LegacyExportFormat.isExportFilePresent(approved, LegacyExportFormat.HISTORY_TEXT_FILE)
                            ? historyText : null,
                    LegacyExportFormat.isExportFilePresent(approved, LegacyExportFormat.HISTORY_BINARY_FILE)
                            ? historyBinary : null,
                    approved);
        }

        /**
         * One account file and nothing else, the shape a target-state load needs: with no rate file the
         * validation sees an empty rate list, so no rate row is staged and no rate finding recorded.
         *
         * @param accountsFile the account export to apply
         * @return sources naming that file alone
         */
        public static LoadSources accountsOnly(Path accountsFile) {
            return new LoadSources(accountsFile, null, null, null, null);
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
    // One transaction per load, and never a second one: either every applicable row lands or none does, so a
    // failed load leaves the target exactly as it was and a retry is a new run_id under the same batch_id.
    // LedgerService's append methods propagate MANDATORY, so they require this ambient transaction, and no
    // exception may be caught to continue with the next owner - partial application is a defect. When
    // MigrationToolRunner is the caller it opens the transaction this load joins and writes CLEAN/VARIANCE
    // inside it, so the applied rows and the verdict that describes them commit together or not at all.
    @Transactional
    public LoadResult load(MigrationRun run, Path inputDirectory) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(inputDirectory, "inputDirectory");
        // Resolved here rather than at construction, so a code page or zone this JVM cannot use is refused by
        // the invocation that would have decoded with it and not by the context that merely held it.
        return load(run, LoadSources.inDirectory(inputDirectory), charsetOf(configuredLegacyCharset),
                zoneOf(configuredLegacyTimeZone), historyRecordLength);
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
        // Flushed so the run row exists before the source validation writes this run's first finding:
        // migration_reconciliation.run_id references migration_run, and because run_id is a plain column
        // rather than an association, Hibernate's insert ordering knows nothing of that dependency.
        entityManager.flush();

        // Fail closed before a byte is read rather than when the history file is reached: a run that lacks
        // the declared record length cannot frame the binary export at all.
        requireHistoryRecordLength(sources, historyRecordLength);

        // The whole export is classified before the first account row is written, so which owners may be
        // applied is settled up front; inventing a value for a legacy NULL or an out-of-set currency instead
        // would erase the finding an operator reviews. It is a pass and never a copy, so no file is held.
        ReconciliationService.SourceValidation validation =
                reconciliationService.validateSource(activeRun, sources.exportFile(sources.accountsFile()),
                        sources.rateFile() == null ? null : sources.exportFile(sources.rateFile()));

        // Rows reach the applying pass one at a time, so the loader holds one exported record and one chunk
        // of accounts rather than the file.
        LoadTally tally = new LoadTally(validation.varianceCount());
        exportReader.streamCashAccounts(sources.exportFile(sources.accountsFile()),
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

    // Materialized only when the row does not exist yet, because migration_reconciliation.run_id references
    // migration_run and the validation may write this run's first finding before the runner's row is
    // visible. No field of the run is set here: its verdict and counts belong to MigrationToolRunner, which
    // writes them once this load returns - in this same transaction when the runner is the caller.
    private MigrationRun activeRun(MigrationRun run) {
        return runs.findById(run.runId()).orElseGet(() -> runs.save(run));
    }

    /** Applies one exported account row: inserted, overwritten, or left alone with a recorded reason. */
    private void applyAccount(MigrationRun run,
                              LoadSources sources,
                              ReconciliationService.SourceValidation validation,
                              LegacyCashAccountRecord record,
                              LoadTally tally) {
        String owner = OwnerNormalizer.normalize(record.owner());

        // A repeated owner is malformed input rather than a last-write-wins case: the legacy primary key was
        // the owner itself, stored upper case (CASH00.cbl:L155), so a well-formed unload cannot produce one
        // and collapsing it would breach the one-MIGRATION_LOAD-row-per-owner-per-run rule that the ledger's
        // partial unique index enforces.
        if (!tally.seenOwners.add(owner)) {
            throw CashAccountException.forOwner(CashAccountErrorCode.INTERNAL, owner,
                    "The account export " + sources.accountsFile() + " names owner '" + owner
                            + "' more than once; a load applies each owner exactly once per run");
        }

        // Rejection, not membership: the validation returns the owners it refused, so this pass carries
        // state proportional to the refusals rather than to every owner in the export.
        if (validation.rejectedOwners().contains(owner)) {
            return;
        }

        Money balance = Money.of(record.balance());
        Optional<CashAccount> existing = accounts.findByOwnerForUpdate(owner);

        if (existing.isEmpty()) {
            insertAccount(run, owner, record.currency(), balance);
            tally.migrated++;
        } else if (reservations.existsByOwnerAndState(owner, ReservationState.HELD)) {
            // An owner with a HELD reservation is not touched: overwriting an absolute balance while funds
            // sit in reserved_balance would hand held money back to the available side and leave a
            // reservation its settlement can no longer honour, so the load records a RESERVATIONS_OUTSTANDING
            // state variance instead. The condition is established once, under the row lock taken above, and
            // the locked row is handed to the recorder, because a release must take that same lock.
            reconciliationService.recordReservationsOutstanding(run, existing.get());
            tally.variances++;
        } else {
            overwriteAccount(run, existing.get(), record.currency(), balance);
            tally.migrated++;
        }

        // Applied rows leave the persistence context at the chunk boundary, so the pass costs one chunk of
        // entities however many owners the export names. The row locks it has taken are unaffected: they
        // belong to the transaction and are held until it ends.
        tally.appliedSinceFlush++;
        if (tally.appliedSinceFlush >= batchChunkSize) {
            tally.appliedSinceFlush = 0;
            boundPersistenceContext();
        }
    }

    // Flush before clear, always: clear() discards whatever is pending, so clearing without flushing would
    // drop the rows just applied. Neither is a commit - both run inside the one ambient transaction - so a
    // failure at any chunk still leaves the target exactly as it was.
    private void boundPersistenceContext() {
        entityManager.flush();
        entityManager.clear();
    }

    // CashAccount.open stamps a fresh incarnation_id, which is what scopes an owner's reservations and
    // idempotency keys to this life of the account, so a key replayed against an owner that was deleted and
    // loaded again cannot resurrect a reservation over funds the current account never held. The ledger row
    // follows the balance, never precedes it: MIGRATION_LOAD's amount is the resulting available balance.
    private void insertAccount(MigrationRun run, String owner, String exportedCurrency, Money balance) {
        CashAccount opened = accounts.save(CashAccount.open(owner, exportedCurrency, balance));
        ledgerService.appendMigrationLoad(opened, run.runId());
    }

    // An unchanged owner writes no ledger row: a rehearsal followed by a final load must leave an audit
    // trail of what actually changed, and an event recording the balance already stored would assert a
    // state change nothing made. The owner still counts as migrated. compareTo, never equals: 1000.0 and
    // 1000.00 are the same money, and a delimited export's scale is the producer's choice.
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
        if (sources.rateFile() == null) {
            return 0;
        }

        StagingTally tally = new StagingTally();
        stagingRun(sources.rateFile(), () -> exportReader.streamRates(sources.exportFile(sources.rateFile()),
                record -> {
                    // The same key expression the validation used, so the two decisions line up exactly
                    // rather than approximately.
                    String rateKey = LegacyExportFormat.trimPadding(record.currnkey());
                    if (validation.rejectedRateKeys().contains(rateKey)) {
                        return;
                    }
                    // A null currnbase or amount is staged as null: the program fetched both columns and
                    // referenced neither, so neither is a value this loader may invent.
                    stage(legacyRates.save(LegacyRateTable.staged(run.runId(), record)), tally);
                }));

        return tally.staged;
    }

    // The binary export wins wherever both shapes are named, because they decode to the same
    // (name, event_date, event_time) keys and staging both under one run_id would collide on
    // legacy_history's primary key; comparing the two shapes is therefore two runs rather than one.
    private int stageHistory(MigrationRun run,
                             LoadSources sources,
                             Charset legacyCharset,
                             ZoneId legacyTimeZone,
                             Integer historyRecordLength) {
        StagingTally tally = new StagingTally();
        // Both shapes stage through one consumer, so the text and binary paths cannot diverge in what they
        // write - the property LoaderIT asserts row for row.
        Consumer<VsamHistoryRecord> staging = record -> stageHistoryRecord(run, record, legacyTimeZone, tally);

        if (sources.historyBinaryFile() != null) {
            stagingRun(sources.historyBinaryFile(), () -> new VsamHistoryRecordDecoder(legacyCharset,
                    historyRecordLength).streamAll(sources.exportFile(sources.historyBinaryFile()), staging));
        } else if (sources.historyTextFile() != null) {
            stagingRun(sources.historyTextFile(),
                    () -> exportReader.streamHistory(sources.exportFile(sources.historyTextFile()), staging));
        }

        return tally.staged;
    }

    private void stageHistoryRecord(MigrationRun run,
                                    VsamHistoryRecord record,
                                    ZoneId legacyTimeZone,
                                    StagingTally tally) {
        // The row keeps the decoded name and carries the uppercased key beside it, because the legacy write
        // folded no case (CASH00.cbl:L111) while the account table stored upper case, so "John"+stamp and
        // "JOHN"+stamp were distinct 29-byte keys (DEFKSDS.jcl:L14) that a fold here would merge. A blank
        // name propagates as INVALID_OWNER: staging is lossless, so it is a file to fix, not a row to lose.
        stage(legacyHistory.save(LegacyHistory.staged(run.runId(), record,
                OwnerNormalizer.normalize(record.name()), eventAt(record, legacyTimeZone))), tally);
    }

    // The duplicate-key check is the database's, not a set in memory: the staging tables declare the legacy
    // keys within a run as their primary keys, so detecting a repeat there costs nothing per row and keeps
    // the loader's footprint independent of the export's length. The driver also names the offending key
    // values, which is what an operator needs to find the line. Which failure the pass actually met is
    // decided by classify(...) below, because a repeated key is only one of the things this catch receives.
    private void stagingRun(Path exportFile, Runnable stagingPass) {
        try {
            stagingPass.run();
            // The trailing partial chunk leaves the context inside the translation below, so a repeated key
            // among the last few rows of a file is reported exactly like one in any other chunk.
            boundPersistenceContext();
        } catch (DataAccessException | PersistenceException rejected) {
            // Two exception families because a staging failure has two origins: one raised by a repository call
            // arrives translated as a DataAccessException, while one raised by the flush above arrives as the
            // provider's own PersistenceException, which nothing translates because a flush is not a repository
            // call.
            throw CashAccountException.of(CashAccountErrorCode.INTERNAL,
                    stagingFailureMessage(exportFile, rejected), rejected);
        }
    }

    /**
     * Names what actually rejected a staging pass, so the first line an operator reads is the true diagnosis.
     */
    // Classified rather than named because the catch above receives every way a staging insert can fail, and a
    // repeated legacy key is only one of them: a value too wide or too precise for its column, a NOT NULL or
    // CHECK rejection, a lost connection and a provider fault all arrive here too. Calling each a duplicate key
    // sends the operator to look for a repeated record in an export that has none. The load aborts either way
    // (one transaction, AAP 0.6.3), so what this decides is the diagnosis and nothing else.
    private static String stagingFailureMessage(Path exportFile, RuntimeException rejected) {
        String diagnosis = switch (classify(rejected)) {
            case DUPLICATE_KEY -> "a record repeats a key its legacy primary key could not hold twice, so"
                    + " staging cannot be lossless.";
            case VALUE_REJECTED -> "a value does not fit the staging column it was written to - width,"
                    + " precision or format - so the row cannot be staged exactly as exported. This is not a"
                    + " repeated key: the legacy column widths are the ones in"
                    + " src/main/resources/schema/cash-account-schema.sql.";
            case CONSTRAINT_VIOLATION -> "a row was rejected by a constraint on the staging table that is not"
                    + " its primary key - a NOT NULL or CHECK rule. This is not a repeated key.";
            case CONNECTION_LOST -> "the database connection failed during the pass, so nothing about the"
                    + " export itself is implicated and the pass can be repeated once the datastore is"
                    + " reachable.";
            case UNCLASSIFIED -> "the database or the persistence provider rejected the pass. The cause below"
                    + " is the failure to act on, and it is not a repeated key.";
        };
        return "The export " + exportFile + " could not be staged: " + diagnosis
                + " The database reports: " + rootCause(rejected);
    }

    /** What rejected a staging pass, as far as the exception chain states it. */
    private enum StagingRejection {
        DUPLICATE_KEY,
        VALUE_REJECTED,
        CONSTRAINT_VIOLATION,
        CONNECTION_LOST,
        UNCLASSIFIED
    }

    // The chain is walked, and two kinds of evidence count, because the same duplicate reaches this class in two
    // shapes and only one of them carries an SQLState: a repeat inside one chunk is raised by the provider
    // before any statement is sent (Hibernate's non-unique-object signal, which Spring translates to
    // DuplicateKeyException) while a repeat across chunks is raised by the insert and carries SQLState 23505.
    // Keyed on the standard SQLState classes rather than on driver text so the classification holds for any
    // message locale: 23505 unique violation, class 23 other integrity constraint, class 22 data exception
    // (string right truncation, numeric overflow, bad datetime format), class 08 connection exception.
    private static StagingRejection classify(Throwable rejected) {
        StagingRejection fromSqlState = null;
        for (Throwable cause = rejected; cause != null; cause = nextCause(cause)) {
            if (cause instanceof DuplicateKeyException || cause instanceof EntityExistsException) {
                return StagingRejection.DUPLICATE_KEY;
            }
            if (cause instanceof DataAccessResourceFailureException && fromSqlState == null) {
                fromSqlState = StagingRejection.CONNECTION_LOST;
            }
            if (cause instanceof SQLException failed) {
                StagingRejection classified = classifySqlState(failed.getSQLState());
                if (classified == StagingRejection.DUPLICATE_KEY) {
                    return classified;
                }
                // The outermost SQLState wins over a deeper one only when nothing above it said anything: a
                // batch failure wraps the driver's own exception, and both carry the same state.
                if (classified != null && fromSqlState == null) {
                    fromSqlState = classified;
                }
            }
        }
        return fromSqlState == null ? StagingRejection.UNCLASSIFIED : fromSqlState;
    }

    private static StagingRejection classifySqlState(String sqlState) {
        if (sqlState == null || sqlState.length() < 2) {
            return null;
        }
        if (SQL_STATE_UNIQUE_VIOLATION.equals(sqlState)) {
            return StagingRejection.DUPLICATE_KEY;
        }
        return switch (sqlState.substring(0, 2)) {
            case SQL_STATE_CLASS_INTEGRITY_CONSTRAINT -> StagingRejection.CONSTRAINT_VIOLATION;
            case SQL_STATE_CLASS_DATA_EXCEPTION -> StagingRejection.VALUE_REJECTED;
            case SQL_STATE_CLASS_CONNECTION_EXCEPTION -> StagingRejection.CONNECTION_LOST;
            default -> null;
        };
    }

    // A cause that points at itself would loop; the JDK makes no promise against one.
    private static Throwable nextCause(Throwable thrown) {
        Throwable cause = thrown.getCause();
        return cause == thrown ? null : cause;
    }

    // The driver's own message, which carries the offending key or value; Spring's wrapper text names only the
    // statement. Walked to the deepest cause so the detail is not buried under two layers of translation.
    private static String rootCause(Throwable thrown) {
        Throwable cause = thrown;
        while (nextCause(cause) != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message.strip();
    }

    // save rather than saveAll, which would add nothing but a list of every row: both staging entities
    // declare their own newness, so each save is an insert with no existence SELECT for its assigned key.
    // The chunk boundary matches hibernate.jdbc.batch_size, so a flush is whole JDBC batches rather than
    // one statement per row.
    private void stage(Object stagedRow, StagingTally tally) {
        Objects.requireNonNull(stagedRow, "A staged row is required");
        tally.staged++;
        if (tally.staged % batchChunkSize == 0) {
            boundPersistenceContext();
        }
    }

    // The one field where a malformed value is tolerated, deliberately: the raw text is stored in the key
    // columns either way, so nothing is lost, while guessing an instant would shift a shadow window's
    // boundaries with every individual row still looking plausible.
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

    // Fail closed on a binary export with no declared length: the program writes a 57-byte record
    // (CASH00.cbl:L38-L45, LENGTH OF at L128) into a cluster whose RECSZ is 100 (DEFKSDS.jcl:L11), and which
    // width a REPRO yields depends on a CICS FILE definition this repository does not hold. A guessed length
    // divides many files cleanly and then shifts every field of every record - a wrong load, not a failed one.
    private static void requireHistoryRecordLength(LoadSources sources, Integer historyRecordLength) {
        if (sources.historyBinaryFile() != null && historyRecordLength == null) {
            throw CashAccountException.of(CashAccountErrorCode.INTERNAL,
                    "tool.history-record-length is mandatory whenever a binary history export ("
                            + sources.historyBinaryFile() + ") is loaded: the record length is declared"
                            + " from the CICS FILE definition, never inferred from the file");
        }
    }

    // Locale.ROOT, never the no-argument toUpperCase(): a Turkish default locale maps "i" to U+0130, which
    // would make an unchanged currency compare unequal on one pod and equal on another.
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
    // and the value stays correctable by configuration mid-migration.
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

    // The three readers the container constructor uses. Each guards a non-configurable Environment, which exposes
    // no property sources at all, by taking the same value an unset key would give.
    private static String toolProperty(Environment environment, String key, String fallback) {
        if (!(environment instanceof ConfigurableEnvironment)) {
            return fallback;
        }
        return Binder.get(environment).bind(key, Bindable.of(String.class)).orElse(fallback);
    }

    private static Integer integerProperty(Environment environment, String key) {
        if (!(environment instanceof ConfigurableEnvironment)) {
            return null;
        }
        return Binder.get(environment).bind(key, Bindable.of(Integer.class)).orElse(null);
    }

    private static int integerProperty(Environment environment, String key, int fallback) {
        Integer bound = integerProperty(environment, key);
        return bound == null ? fallback : bound;
    }

    /** What the account pass has applied so far, carried across its chunks. */
    private static final class LoadTally {

        // The one member that grows with the export, and a normalized owner key per row rather than a
        // record: it cannot be dropped in favour of the ledger's partial unique index, which could report
        // only that some row was a duplicate and not which exported line wrote it.
        private final Set<String> seenOwners = new LinkedHashSet<>();

        private int migrated;

        private int variances;

        private int appliedSinceFlush;

        private LoadTally(int variancesAlreadyRecorded) {
            this.variances = variancesAlreadyRecorded;
        }
    }

    /** How many rows one staging pass has written. */
    private static final class StagingTally {

        private int staged;
    }
}
