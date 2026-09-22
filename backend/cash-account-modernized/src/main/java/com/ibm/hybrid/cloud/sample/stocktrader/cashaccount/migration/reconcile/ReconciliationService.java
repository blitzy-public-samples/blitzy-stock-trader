package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.CashAccount;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.LedgerEntry;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.Money;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.OwnerNormalizer;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.ReservationState;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.LegacyExportFormat;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.export.DelimitedExportReader;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.export.LegacyCashAccountRecord;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.export.LegacyRateRecord;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.CashAccountRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.CashReservationRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.LedgerEntryRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.LegacyRateTableRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.MigrationReconciliationRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.MigrationRunRepository;

/**
 * Turns a legacy export plus the migrated database state into {@code migration_reconciliation} rows and a
 * run summary, deliberately un-profiled so the tooling wires it under whichever profile it runs and the
 * deployed web context holds it inert.
 */
@Service
public class ReconciliationService {

    // Reason tokens, held as constants so the methods that write them cannot drift and a test can assert one
    // verbatim out of a row. The convention throughout: the legacy-side reason or rendering goes in
    // legacy_value, the target-side one in migrated_value.
    private static final String NULL_IN_LEGACY = "NULL_IN_LEGACY";

    private static final String INVALID_IN_LEGACY = "INVALID_IN_LEGACY";

    private static final String NULL_RATE = "NULL_RATE";

    private static final String MISSING_IN_TARGET = "MISSING_IN_TARGET";

    private static final String MISSING_IN_LEGACY = "MISSING_IN_LEGACY";

    private static final String RESERVATIONS_OUTSTANDING = "RESERVATIONS_OUTSTANDING";

    /** The counterpart token: the side of the comparison on which the owner does exist. */
    private static final String PRESENT = "PRESENT";

    // The single authority for the accepted-currency policy is this property as application.yml declares it
    // (AAP 0.7.2's 31-code estate allowlist), never a copy in this class: a copy that drifted from the request
    // path's would stage accounts the service then refuses, and nothing would announce the divergence.
    private static final String ACCEPTED_CURRENCIES_PROPERTY = "cashaccount.fx.accepted-currencies";

    // The three-letter shape the API accepts; membership of the accepted set is the second, independent test.
    private static final Pattern CURRENCY_CODE = Pattern.compile("^[A-Z]{3}$");

    // Bounds the IN list - and so the bind-parameter count and the planner's work - of the one statement that
    // classifies a whole set of target-only owners, however many accounts the target holds.
    private static final int OWNER_CLASSIFICATION_CHUNK = 500;

    // Configuration rather than a literal because the value is an operator's trade-off between round trips and
    // resident rows, not a correctness parameter; 50 matches hibernate.jdbc.batch_size in application.yml so one
    // flush maps onto whole JDBC batches. Under cashaccount.* rather than tool.* because MigrationToolRunner's
    // typo guard admits only the tool.* options it declares, and the default below keeps a context that sets
    // nothing - the deployed web application, which never reconciles - starting unchanged.
    private static final String BATCH_CHUNK_SIZE_PROPERTY = "cashaccount.migration.batch-chunk-size";

    private static final int DEFAULT_BATCH_CHUNK_SIZE = 50;

    // The rate source in force for a run, and the default application-tool.yml ships: parity can only be judged
    // on identical inputs, so an operator who passes nothing gets the legacy-table gate.
    private static final String RATE_SOURCE_PROPERTY = "tool.rate-source";

    private static final String DEFAULT_RATE_SOURCE = "legacy-table";

    private final CashAccountRepository accounts;

    private final CashReservationRepository reservations;

    private final LedgerEntryRepository ledgerEntries;

    private final LegacyRateTableRepository legacyRates;

    private final MigrationReconciliationRepository reconciliations;

    private final MigrationRunRepository runs;

    // Constructed, not injected: DelimitedExportReader carries no Spring stereotype, holds no state and is
    // thread-safe, so a bean definition would add a wiring dependency that buys nothing.
    private final DelimitedExportReader exportReader;

    private final Set<String> acceptedCurrencies;

    // Canonicalized through MigrationRun.RateSource rather than kept as raw text, because fx/ToolExchangeRateSource
    // picks the delegate that prices a replay from the same property the same way: a raw comparison here could
    // classify a live-priced difference as one the parity gate produced.
    private final MigrationRun.RateSource rateSource;

    private final int batchChunkSize;

    // Context control only, never a data path: every row read or written here goes through a repository under
    // persistence/ (AAP 0.6.5), and this exists for the one operation no Spring Data interface exposes - clear(),
    // paired with the flush that must precede it - which is what keeps a reconcile over a production-sized target
    // from accumulating every entity it touched. @PersistenceContext injects the transaction-scoped proxy, so both
    // land in the caller's transaction and never open one of their own (AAP 0.6.3).
    @PersistenceContext
    private EntityManager entityManager;

    // Binder, never @Value, for the two scalars as well as the accepted-currency sequence: a @Value placeholder is
    // resolved and its resolved text is then handed to Spring's expression resolver, so either scalar written as
    // #{...} would execute while this service was being created. Binder resolves ${...} and converts, evaluating
    // nothing, so an unusable rate source is refused by MigrationRun.RateSource.of and an unusable chunk size by
    // the check below.
    public ReconciliationService(CashAccountRepository accounts,
                                 CashReservationRepository reservations,
                                 LedgerEntryRepository ledgerEntries,
                                 LegacyRateTableRepository legacyRates,
                                 MigrationReconciliationRepository reconciliations,
                                 MigrationRunRepository runs,
                                 Environment environment) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.ledgerEntries = Objects.requireNonNull(ledgerEntries, "ledgerEntries");
        this.legacyRates = Objects.requireNonNull(legacyRates, "legacyRates");
        this.reconciliations = Objects.requireNonNull(reconciliations, "reconciliations");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.exportReader = new DelimitedExportReader();
        this.rateSource = MigrationRun.RateSource.of(
                stringProperty(environment, RATE_SOURCE_PROPERTY, DEFAULT_RATE_SOURCE));
        int batchChunkSize = integerProperty(environment, BATCH_CHUNK_SIZE_PROPERTY, DEFAULT_BATCH_CHUNK_SIZE);
        // Refused rather than defaulted away: a zero or negative chunk size would make the compare loop below
        // advance by nothing, which is a hang in a migration window rather than a slow run.
        if (batchChunkSize < 1) {
            throw new IllegalArgumentException("cashaccount.migration.batch-chunk-size must be at least 1 row per chunk, but was "
                    + batchChunkSize);
        }
        this.batchChunkSize = batchChunkSize;
        this.acceptedCurrencies = acceptedCurrenciesFrom(environment);
    }

    /**
     * What the source validation established about one legacy export, for the loader that has to act on it.
     *
     * @param rejectedOwners   owners refused, normalized by {@link OwnerNormalizer#normalize(String)}; refusals
     *                         rather than acceptances, so this stays proportional to the findings a data owner
     *                         reviews instead of growing with a whole DB2 unload
     * @param rejectedRateKeys rate keys refused, exactly as the export's {@code currnkey} column held them,
     *                         because that is the identity the legacy join compared and a staged row is keyed by
     * @param legacyAccountCount rows read from the account export, rejected rows included
     * @param varianceCount      {@code VARIANCE} rows this validation wrote under the run
     */
    public record SourceValidation(Set<String> rejectedOwners,
                                   Set<String> rejectedRateKeys,
                                   int legacyAccountCount,
                                   int varianceCount) {
    }

    /**
     * Resolves the export's two files inside {@code inputDirectory} and validates them.
     *
     * @param run            the run the findings are recorded under
     * @param inputDirectory directory holding {@link LegacyExportFormat#CASH_ACCOUNT_FILE} and
     *                       {@link LegacyExportFormat#RATE_TABLE_FILE}
     * @return what was refused, and the counts, for the caller that has to act on it
     */
    @Transactional
    public SourceValidation validateSource(MigrationRun run, Path inputDirectory) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(inputDirectory, "inputDirectory");
        // Both children are resolved through LegacyExportFormat, which refuses a symbolic link, a
        // non-ordinary file and anything whose real path is not a child of the approved directory: a link
        // named cashaccounty.csv would otherwise be classified as legacy data and echoed back in the findings
        // it produced (AAP 0.3.2). The approval is taken once and carried into the reads, so each file is
        // opened relative to the directory that was validated rather than to its name - a directory
        // component substituted in between is then refused instead of followed. A file that is simply absent
        // is still reported by the reader that opens it, naming the file and the column shape it wanted.
        LegacyExportFormat.ApprovedDirectory approved =
                LegacyExportFormat.approveInputDirectory(inputDirectory);
        LegacyExportFormat.resolveInputFile(inputDirectory, LegacyExportFormat.CASH_ACCOUNT_FILE);
        LegacyExportFormat.resolveInputFile(inputDirectory, LegacyExportFormat.RATE_TABLE_FILE);
        return validateSource(run,
                LegacyExportFormat.ExportFile.inApprovedDirectory(approved,
                        LegacyExportFormat.CASH_ACCOUNT_FILE),
                LegacyExportFormat.ExportFile.inApprovedDirectory(approved,
                        LegacyExportFormat.RATE_TABLE_FILE));
    }

    /**
     * Classifies the named exports without holding either file, which is why both {@code load} and
     * {@code reconcile} validate through this form: a bulk load must classify a whole DB2 unload before it
     * writes its first account row, and holding that file as a list of records to do so is exactly the
     * unbounded read this form avoids.
     *
     * @param run         the run the findings are recorded under
     * @param accountFile the account export, required
     * @param rateFile    the rate-table export, or {@code null} for a run that carries accounts alone
     * @return what was refused, and the counts, for the caller that has to act on it
     */
    @Transactional
    public SourceValidation validateSource(MigrationRun run, Path accountFile, Path rateFile) {
        Objects.requireNonNull(accountFile, "accountFile");
        return validateSource(run, LegacyExportFormat.ExportFile.named(accountFile),
                rateFile == null ? null : LegacyExportFormat.ExportFile.named(rateFile));
    }

    /**
     * Classifies exports already resolved to their open policy, which is how a runbook directory's files are
     * read: relative to the approved directory rather than by a name resolved again at open time.
     *
     * @param accountFile the account export, required
     * @param rateFile    the rate-table export, or {@code null} for a run that carries accounts alone
     */
    @Transactional
    public SourceValidation validateSource(MigrationRun run,
                                           LegacyExportFormat.ExportFile accountFile,
                                           LegacyExportFormat.ExportFile rateFile) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(accountFile, "accountFile");

        SourceClassification classification = new SourceClassification();
        exportReader.streamCashAccounts(accountFile, record -> classifyAccount(run, record, classification));
        // The accounts-only shape sees no rate row at all, so nothing is staged and no rate finding is
        // recorded - the run simply has nothing to say about currencies.
        if (rateFile != null) {
            exportReader.streamRates(rateFile, rateRecord -> classifyRate(run, rateRecord, classification));
        }
        return classification.toValidation();
    }

    /**
     * Classifies rows a caller already holds, writing one {@code NULL_IN_LEGACY}, {@code INVALID_IN_LEGACY} or
     * {@code NULL_RATE} row per finding and naming what may be loaded.
     *
     * <p>Classification, never repair: five of the eight legacy columns are nullable and the program declared no
     * null indicators (DB2DDL.jcl:L48-L49, L56-L58; DCLFRANK.cpy:L19-L23), so a null in an export is a
     * legitimate legacy state only the data owner can settle, and substituting a zero or a default here would
     * erase the finding an operator has to review.</p>
     *
     * @param run            the run the findings are recorded under
     * @param accountRecords exported account rows, in file order
     * @param rateRecords    exported rate rows, empty for a run that carries accounts alone
     * @return what was refused, and the counts, for the caller that has to act on it
     */
    // @Transactional with the default REQUIRED on every public form, never REQUIRES_NEW: a load applies its whole
    // export in one transaction (AAP 0.6.3) and these join it, so a variance row can never be committed under a
    // run whose load then rolled back. The annotation is there for a caller with no transaction at all, because
    // classifying bounds the persistence context as it goes and a flush has no meaning outside one.
    @Transactional
    public SourceValidation validateSource(MigrationRun run,
                                           List<LegacyCashAccountRecord> accountRecords,
                                           List<LegacyRateRecord> rateRecords) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(accountRecords, "accountRecords");
        Objects.requireNonNull(rateRecords, "rateRecords");

        SourceClassification classification = new SourceClassification();
        for (LegacyCashAccountRecord record : accountRecords) {
            classifyAccount(run, record, classification);
        }
        for (LegacyRateRecord rateRecord : rateRecords) {
            classifyRate(run, rateRecord, classification);
        }
        return classification.toValidation();
    }

    // The only place the two conditions the legacy catalog genuinely permitted are written down: the list form
    // and the streaming form both call this, so neither can drift.
    private void classifyAccount(MigrationRun run, LegacyCashAccountRecord record,
                                 SourceClassification classification) {
        classification.legacyAccountCount++;
        boundFindings(classification);

        // An owner the legacy CHAR(32) column could not have held is a malformed export rather than a variance:
        // normalize(...) raises, the transaction aborts and the run is recorded FAILED, which is the right
        // outcome for a file that cannot be trusted at all.
        String owner = OwnerNormalizer.normalize(record.owner());

        // Selecting a NULL balance or currency into a host variable with no indicator was the SQLCODE -305 case,
        // so the legacy account was unreachable through Q/U/X/C/D while the null stood (CASH00.cbl:L136-L150,
        // L204-L211); the target's columns are NOT NULL, so the row is recorded and not loaded.
        if (record.balance() == null || LegacyExportFormat.isNull(record.currency())) {
            record(run, owner, VarianceKind.STATE, ReconciliationStatus.VARIANCE,
                    NULL_IN_LEGACY, null, null, null);
            classification.rejectedOwners.add(owner);
            classification.variances++;
            return;
        }

        // The legacy column was a nullable CHAR(8) whose rate join compared only its first five characters
        // (CASH00.cbl:L213-L219), so values the target can neither validate nor convert could be stored. Recorded
        // rather than coerced to a default currency, which would silently re-denominate real money.
        String currency = normalizeCurrency(record.currency());
        if (!isAcceptedCurrency(currency)) {
            // The offending code itself stays in the export, which is the checksummed evidence artifact the
            // row's owner joins to, so only the token goes in legacy_value.
            record(run, owner, VarianceKind.CURRENCY, ReconciliationStatus.VARIANCE,
                    INVALID_IN_LEGACY, null, null, null);
            classification.rejectedOwners.add(owner);
            classification.variances++;
            return;
        }

        // Once rejected, always rejected, whatever the file order: recording refusals rather than acceptances
        // makes that hold for free, since a later clean row for an owner named twice adds nothing and so cannot
        // reinstate a row already recorded as unloadable.
    }

    private void classifyRate(MigrationRun run, LegacyRateRecord rateRecord,
                              SourceClassification classification) {
        String rateKey = LegacyExportFormat.trimPadding(rateRecord.currnkey());
        boundFindings(classification);

        // The condition the legacy program hid: the rate SELECT raised -305, the COMPUTE ran on an uninitialized
        // RATES and the following UPDATE's SQLCODE 0 overwrote the failure before anyone saw it
        // (CASH00.cbl:L215-L231, L249-L264). Not staged, so a later C/D replay for that currency is rejected by
        // the target instead of being computed against an invented rate.
        if (rateRecord.rates() == null) {
            record(run, rateKey, VarianceKind.RATE_SOURCE, ReconciliationStatus.VARIANCE,
                    NULL_RATE, null, null, null);
            classification.rejectedRateKeys.add(rateKey);
            classification.variances++;
            return;
        }

        // A NULL currnbase or amount is deliberately not a finding: the rate SELECT fetches both columns
        // (CASH00.cbl:L215, L249) and no COMPUTE or MOVE in the program then references them, so a null in
        // either changed no balance this could be judging. They are staged as NULL for the evidence trail
        // (AAP 0.4.1).
    }

    /**
     * Compares the legacy export in {@code inputDirectory} with the migrated state, owner by owner.
     *
     * @param run            the run the findings and the summary are recorded under
     * @param inputDirectory directory holding the legacy export
     * @return the number of {@code VARIANCE} rows standing under this run, which is what makes the tool exit 2
     *         rather than 0; {@code ACCEPTED_EXCEPTION} rows are deliberately not counted
     */
    // One transaction: the findings and the run summary that reports them have to land together, or an operator
    // can read a count that its rows do not support.
    @Transactional
    public int reconcile(MigrationRun run, Path inputDirectory) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(inputDirectory, "inputDirectory");

        // Materialized and flushed before the first finding because migration_reconciliation.run_id references
        // migration_run, so a finding written under a run whose own row does not exist yet fails on the foreign
        // key - relying on the ordering hibernate.order_inserts chooses (application.yml) would not be enough.
        runs.save(run);
        entityManager.flush();

        // The target side of this comparison is the database, so LegacyExportFormat.TARGET_STATE_FILE is
        // deliberately never resolved here: that fixture is a migrated state a caller loads beforehand. Files
        // are resolved through LegacyExportFormat and opened relative to one approval taken here: only an
        // ordinary file beneath the directory that was validated is an input of this tooling, whatever that
        // directory's name leads to later (AAP 0.3.2).
        LegacyExportFormat.ApprovedDirectory approved =
                LegacyExportFormat.approveInputDirectory(inputDirectory);
        LegacyExportFormat.resolveInputFile(inputDirectory, LegacyExportFormat.CASH_ACCOUNT_FILE);
        LegacyExportFormat.resolveInputFile(inputDirectory, LegacyExportFormat.RATE_TABLE_FILE);
        LegacyExportFormat.ExportFile accountFile =
                LegacyExportFormat.ExportFile.inApprovedDirectory(approved, LegacyExportFormat.CASH_ACCOUNT_FILE);
        LegacyExportFormat.ExportFile rateFile =
                LegacyExportFormat.ExportFile.inApprovedDirectory(approved, LegacyExportFormat.RATE_TABLE_FILE);

        // First, always: the export's own unloadable rows are findings of this run, and the owners they name are
        // the ones the comparison below must leave alone.
        SourceValidation validation = validateSource(run, accountFile, rateFile);

        // One snapshot for the whole comparison, taken before the first owner is examined, so a retry load
        // committing under the same batch while the loop runs cannot move half the comparison onto a different
        // rate table (AAP 0.12.5).
        Map<String, BigDecimal> stagedRates = stagedRatesOfBatchLoad(run.batchId());

        TargetComparison comparison = new TargetComparison(stagedRates);
        exportReader.streamCashAccounts(accountFile, record -> {
            String owner = OwnerNormalizer.normalize(record.owner());

            // The owner was the legacy primary key, stored upper case (CASH00.cbl:L155), so a repeat is a
            // malformed export: the first occurrence is compared and the repeat passed over, because comparing
            // both would report one owner's single condition as two findings. Owners are joined on the
            // normalized key and never on sort order, since EBCDIC and ASCII collate differently (AAP 0.12.2).
            if (!comparison.namedByExport.add(owner)) {
                return;
            }

            // One condition, one row: the reason validateSource rejected this owner is also the reason it was
            // never loaded, so comparing it would report the same fact again as MISSING_IN_TARGET. It stays
            // counted as named by the export, so the second direction does not report MISSING_IN_LEGACY either.
            if (validation.rejectedOwners().contains(owner)) {
                return;
            }

            comparison.chunk.put(owner, record);
            if (comparison.chunk.size() >= batchChunkSize) {
                compareChunk(run, comparison);
            }
        });
        compareChunk(run, comparison);

        // The other direction, a page at a time rather than accounts.findAll(): the target is the unbounded side,
        // and this transaction inserts only migration_reconciliation rows, so a window ordered by the primary key
        // is stable while it runs.
        for (int page = 0; ; page++) {
            Page<CashAccount> targetRows = accounts.findAll(PageRequest.of(page, batchChunkSize, Sort.by("owner")));

            // Gathered before any of them is classified, because the classification is a question about
            // ledger_entry that the database answers for a whole page in one statement: a partial export against
            // a target of N accounts makes every one a candidate, so a per-owner read would be O(N) statements.
            Map<String, CashAccount> targetOnlyByOwner = new LinkedHashMap<>();
            for (CashAccount target : targetRows) {
                String owner = OwnerNormalizer.normalize(target.owner());
                if (!comparison.namedByExport.contains(owner)) {
                    targetOnlyByOwner.put(owner, target);
                }
            }

            List<String> targetOnlyOwners = new ArrayList<>(targetOnlyByOwner.size());
            for (CashAccount target : targetOnlyByOwner.values()) {
                targetOnlyOwners.add(target.owner());
            }
            Set<String> producedByMigration = ownersProducedOnlyByMigration(targetOnlyOwners);

            for (Map.Entry<String, CashAccount> entry : targetOnlyByOwner.entrySet()) {
                CashAccount target = entry.getValue();

                // Only an account the tooling itself produced is reported: if anything other than a load has
                // written to this owner's ledger, its absence from the export is ordinary post-load activity.
                if (!producedByMigration.contains(target.owner())) {
                    continue;
                }

                comparison.consideredTargetRows++;

                // Never deleted automatically (AAP 0.6.3): an omitted owner may mean the row was removed upstream
                // or that the export was partial, and only the operator can tell which - through the retail DELETE
                // endpoint, which writes ACCOUNT_DELETED. Deleting here would destroy evidence on a missing line.
                record(run, entry.getKey(), VarianceKind.STATE, ReconciliationStatus.VARIANCE,
                        MISSING_IN_LEGACY, PRESENT, null, target.availableBalance().amount());
            }
            boundPersistenceContext();
            if (!targetRows.hasNext()) {
                break;
            }
        }

        int consideredTargetRows = comparison.consideredTargetRows;

        // No VarianceKind.TRANSACTION_COUNT here: after a bulk load the target holds one MIGRATION_LOAD row per
        // account and no per-transaction history to count against, so the kind belongs to a shadow window, where
        // both sides processed the same stream (AAP 0.10.3).

        // Read back from the rows rather than accumulated in a local, so the count has one authority: only
        // VARIANCE counts, and an ACCEPTED_EXCEPTION never inflates it or changes the exit code.
        int varianceCount = Math.toIntExact(
                reconciliations.countByRunIdAndStatus(run.runId(), ReconciliationStatus.VARIANCE));

        run.setLegacyRecordCount(validation.legacyAccountCount());
        run.setMigratedRecordCount(consideredTargetRows);
        run.setVarianceCount(varianceCount);
        // finishedAt is deliberately left unset so MigrationToolRunner stays the single closer of the row.
        run.setStatus(varianceCount == 0 ? MigrationRun.Status.CLEAN : MigrationRun.Status.VARIANCE);
        // Bounding the context detached this instance, so this is a merge of a detached run - safe because
        // MigrationRun carries no @Version and nothing else writes the row inside this transaction, and required,
        // because without it the counts and the verdict would exist only in memory.
        runs.save(run);

        // A variance is a row plus an exit code, never an exception: throwing would abort the transaction and
        // destroy the findings the run exists to record. Exceptions are reserved for an unreadable export and for
        // database failures, which must abort so the run is recorded FAILED.
        return varianceCount;
    }

    // Chunking changes only how many of the export's records and the target's entities exist at once; the
    // per-owner comparison inside is the whole of the comparison and is unchanged by it.
    private void compareChunk(MigrationRun run, TargetComparison comparison) {
        if (comparison.chunk.isEmpty()) {
            return;
        }

        // findAllById, not findAll: the owner is cash_account's primary key, so this reads exactly the rows the
        // chunk asks about. Re-keyed through the normalizer the export side used, so the join cannot depend on
        // how a stored owner happens to be cased.
        Map<String, CashAccount> targetByOwner = new LinkedHashMap<>();
        for (CashAccount account : accounts.findAllById(comparison.chunk.keySet())) {
            targetByOwner.put(OwnerNormalizer.normalize(account.owner()), account);
        }

        for (Map.Entry<String, LegacyCashAccountRecord> entry : comparison.chunk.entrySet()) {
            String owner = entry.getKey();
            LegacyCashAccountRecord legacy = entry.getValue();
            CashAccount target = targetByOwner.get(owner);

            if (target == null) {
                // Recorded with the legacy balance so the operator can see what is missing; no migrated balance
                // exists to render.
                record(run, owner, VarianceKind.STATE, ReconciliationStatus.VARIANCE,
                        PRESENT, MISSING_IN_TARGET, legacy.balance(), null);
                continue;
            }

            comparison.consideredTargetRows++;

            // Tested before either comparison, because a hold moves money out of available_balance into
            // reserved_balance: the rows a comparison would then write would describe the hold rather than a
            // migration difference. Reported in the same shape as the load's refusal to overwrite such an owner
            // (AAP 0.6.3), so one fact reads as one row whichever command found it.
            if (fundsAreOnHold(target)) {
                recordReservationsOutstanding(run, target);
                continue;
            }

            // Guaranteed non-null by the invariant above: validateSource rejects exactly the rows whose balance or
            // currency it could not accept, and those were skipped.
            String legacyCurrency = normalizeCurrency(legacy.currency());
            String targetCurrency = normalizeCurrency(target.currency());
            BigDecimal legacyBalance = legacy.balance();

            // The available balance, not the total: retail balance is the available balance (AAP 0.6.2), and the
            // gate above guarantees nothing is on hold here, so the two are equal and legacy parity is exact.
            BigDecimal targetBalance = target.availableBalance().amount();

            // The currency and balance checks are independent, each writing at most one row, so an owner may
            // produce both: collapsing them would hide the second difference behind the first.
            if (!Objects.equals(legacyCurrency, targetCurrency)) {
                record(run, owner, VarianceKind.CURRENCY, ReconciliationStatus.VARIANCE,
                        legacyCurrency, targetCurrency, legacyBalance, targetBalance);
            }

            // compareTo, never equals: 12345.70 and 12345.7 are the same amount of money and differ only in scale,
            // which BigDecimal.equals reports as a difference and an operator would have to triage as one.
            if (legacyBalance.compareTo(targetBalance) != 0) {
                recordBalanceDifference(run, owner, legacyCurrency, legacyBalance, targetBalance,
                        comparison.stagedRates);
            }

            // An agreeing owner gets no row at all, not even a MATCHED one: the acceptance criteria are "zero
            // VARIANCE rows" for a matched fixture and "exactly the seeded rows" for a seeded one, and a row per
            // agreeing owner would make both depend on fixture size.
        }

        comparison.chunk.clear();
        boundPersistenceContext();
    }

    // A finding row is a managed entity until it is flushed, so an export whose every row is a finding would
    // hold one per row for the length of the validation. Counted per classified record rather than per finding,
    // so the check is reached on a clean export too, where it costs one comparison and flushes nothing.
    private void boundFindings(SourceClassification classification) {
        if (++classification.classifiedSinceFlush < batchChunkSize) {
            return;
        }
        classification.classifiedSinceFlush = 0;
        boundPersistenceContext();
    }

    // Flush before clear, always: clear() discards everything pending, so clearing first would drop the findings
    // this run exists to record. Both stay inside the one ambient transaction (AAP 0.6.3), and a flush is not a
    // commit, so no chunk of a reconcile is durable until the whole reconcile is.
    private void boundPersistenceContext() {
        entityManager.flush();
        entityManager.clear();
    }

    /**
     * Records a balance difference, reclassifying it only where the staged legacy rate proves the exchange rate
     * fully explains it.
     */
    private void recordBalanceDifference(MigrationRun run, String owner, String currency,
                                         BigDecimal legacyBalance, BigDecimal targetBalance,
                                         Map<String, BigDecimal> stagedRates) {
        // With the default tool.rate-source=legacy-table nothing is reclassified: both sides were computed from
        // the same staged RATES, so a difference cannot be a rate difference (AAP 0.12.5).
        if (rateSource.isLive() && rateExplains(stagedRates, currency, legacyBalance, targetBalance)) {
            // The two figures are rendered into the value columns because this row is no longer a BALANCE row and
            // so does not pass through MigrationReconciliation.balance(...). The variance column stays null
            // deliberately: it carries the signed difference a row leaves outstanding, and an accepted exception
            // leaves none.
            record(run, owner, VarianceKind.RATE_SOURCE, ReconciliationStatus.ACCEPTED_EXCEPTION,
                    legacyBalance.toPlainString(), targetBalance.toPlainString(),
                    legacyBalance, targetBalance);
            return;
        }
        record(run, owner, VarianceKind.BALANCE, ReconciliationStatus.VARIANCE,
                null, null, legacyBalance, targetBalance);
    }

    // Conservative by design: reconcile mode holds two absolute balances and not the transaction behind them, so
    // a rate difference is only decidable for the one shape that re-derives exactly - truncate2(RATES x target)
    // == legacy, the COMPUTE of CASH00.cbl:L222 applied from zero. Anything else keeps its BALANCE/VARIANCE row,
    // because accepting a difference no arithmetic re-derives would sign off a defect; the general rule needs the
    // replayed transaction and so belongs to shadow.ShadowComparator.
    //
    // An absent rate is an ordinary outcome and must stay one: a batch whose load has not run, a load that failed
    // and staged nothing, a currency the export never carried, and one whose exported rates column was NULL all
    // reach here as "no rate", and every one of them means the difference is not explained away.
    private boolean rateExplains(Map<String, BigDecimal> stagedRates, String currency,
                                 BigDecimal legacyBalance, BigDecimal targetBalance) {
        BigDecimal stagedRate = stagedRates.get(rateKey(currency));
        if (stagedRate == null) {
            return false;
        }
        Money rederived = LegacyBalanceCalculator.credit(BigDecimal.ZERO, stagedRate, targetBalance);
        return rederived.amount().compareTo(legacyBalance) == 0;
    }

    // Read whole rather than key by key: the legacy catalog keyed this table on CURRNKEY CHAR(5)
    // (DB2DDL.jcl:L54-L62) against an accepted set of 31 codes (AAP 0.7.2), so one row per currency costs less
    // than the per-key statements it replaces, and an immutable map is what lets every owner in the loop be
    // judged on identical inputs (AAP 0.12.5). Rows whose rates column is NULL are left out, so a
    // staged-but-unusable rate cannot be mistaken for a usable one.
    private Map<String, BigDecimal> stagedRatesOfBatchLoad(UUID batchId) {
        UUID loadRunId = latestLoadRunId(batchId);
        if (loadRunId == null) {
            return Map.of();
        }
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        for (LegacyRateTable staged : legacyRates.findByRunId(loadRunId)) {
            if (staged.rates() != null) {
                rates.put(staged.currnkey(), staged.rates());
            }
        }
        return Map.copyOf(rates);
    }

    // The batch's latest completed load is the run whose staged rates a reconcile judges on, resolved through
    // MigrationRunRepository's shared selector so this reconcile, a shadow window of the same batch and
    // fx/LegacyRateTableSource all judge on one run's rows. The selector admits only CLEAN and VARIANCE - the
    // statuses in which a load actually staged (AAP 0.6.3) - so a newer RUNNING attempt cannot mask the load this
    // batch was reconciled against. Empty stays an ordinary outcome: with no rate to explain a difference away,
    // every difference remains an outstanding BALANCE variance, which is the safe answer.
    private UUID latestLoadRunId(UUID batchId) {
        return runs.findLatestCompletedLoad(batchId)
                .map(MigrationRun::runId)
                .orElse(null);
    }

    // One query decides both conditions: an owner with no ledger row was not produced by a load, since a load
    // always writes its MIGRATION_LOAD event, and an owner with a row from any other source has been written to
    // since. Neither is reported, so the two are deliberately not distinguished.
    //
    // Set-based and chunked, never per owner: the database evaluates this for a whole set in one statement, so a
    // target of N unexported owners costs ceil(N/CHUNK) statements instead of N reads of unbounded history.
    private Set<String> ownersProducedOnlyByMigration(Collection<String> owners) {
        if (owners.isEmpty()) {
            return Set.of();
        }
        Set<String> producedByMigration = new LinkedHashSet<>();
        List<String> chunk = new ArrayList<>(Math.min(owners.size(), OWNER_CLASSIFICATION_CHUNK));
        for (String owner : owners) {
            chunk.add(owner);
            if (chunk.size() == OWNER_CLASSIFICATION_CHUNK) {
                producedByMigration.addAll(ledgerEntries.findOwnersWithEveryEntryFrom(
                        chunk, LedgerEntry.Source.MIGRATION));
                chunk.clear();
            }
        }
        if (!chunk.isEmpty()) {
            producedByMigration.addAll(ledgerEntries.findOwnersWithEveryEntryFrom(
                    chunk, LedgerEntry.Source.MIGRATION));
        }
        return producedByMigration;
    }

    /**
     * Persists one reconciliation row: the single place the row shape and the variance sign are decided, which is
     * why {@code shadow.ShadowComparator} writes its rows through here too. Every difference is a row and never a
     * log line (AAP 0.6.5), because these rows are both the runbook's sign-off evidence and the set the
     * integration tests assert over.
     *
     * @param run             the run the row belongs to
     * @param owner           the normalized owner, or the rate key for a {@code RATE_SOURCE} finding of
     *                        {@code validateSource}
     * @param kind            which difference this row records
     * @param status          whether it counts against the run
     * @param legacyValue     legacy-side reason or rendering; unused for {@link VarianceKind#BALANCE}, whose value
     *                        columns and {@code migrated - legacy} variance are computed by
     *                        {@link MigrationReconciliation#balance}, so callers pass null for that kind
     * @param migratedValue   target-side reason or rendering, under the same rule
     * @param legacyBalance   the legacy balance, or null where the row has none to show
     * @param migratedBalance the target balance, or null where the row has none to show
     * @return the saved row, carrying its generated {@code reconciliationId}
     */
    public MigrationReconciliation record(MigrationRun run,
                                          String owner,
                                          VarianceKind kind,
                                          ReconciliationStatus status,
                                          String legacyValue,
                                          String migratedValue,
                                          BigDecimal legacyBalance,
                                          BigDecimal migratedBalance) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");

        MigrationReconciliation row = kind == VarianceKind.BALANCE
                ? MigrationReconciliation.balance(run.runId(), owner, legacyBalance, migratedBalance, status)
                : MigrationReconciliation.of(run.runId(), owner, kind, status,
                        legacyValue, migratedValue, legacyBalance, migratedBalance, null);
        return reconciliations.save(row);
    }

    /**
     * Records the row that says an owner holding reserved funds was reported rather than overwritten or compared.
     *
     * @param run     the run the row belongs to
     * @param account the account row the caller has already established as holding funds - the very row a load
     *                declined to overwrite, or the row a reconcile declined to compare
     * @return the saved row, never {@code null}
     */
    // A pure recorder, because both callers already hold the answer under a lock the condition cannot change
    // beneath: LegacyLoader has the account FOR UPDATE and tested cash_reservation under it, reconcile tested the
    // row it is comparing, and a release would have to take that same cash_account lock. Only the retained target
    // balance is rendered; the legacy figure belongs to the export the caller was applying, not to this row.
    public MigrationReconciliation recordReservationsOutstanding(MigrationRun run, CashAccount account) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(account, "account");
        return record(run, account.owner(), VarianceKind.STATE, ReconciliationStatus.VARIANCE,
                PRESENT, RESERVATIONS_OUTSTANDING, null, account.availableBalance().amount());
    }

    // Neither test alone is enough. reserved_balance comes first because the row is in hand and answers for free:
    // it aggregates the owner's HELD reservations, so a zero clears every owner with nothing on hold - nearly all
    // of them - at no statement cost. cash_reservation then decides for the few that remain, because the row this
    // gate leads to names outstanding reservations and must not be written on a balance column alone.
    private boolean fundsAreOnHold(CashAccount account) {
        return !account.reservedBalance().isZero()
                && reservations.existsByOwnerAndState(account.owner(), ReservationState.HELD);
    }

    // Binder, never @Value: application.yml writes cashaccount.fx.accepted-currencies as a YAML sequence, which
    // the Environment exposes only as indexed keys and a ${...} placeholder cannot aggregate. Binder also accepts
    // the comma-separated scalar form, so relaxed binding through CASHACCOUNT_FX_ACCEPTED_CURRENCIES keeps
    // working. The Environment rather than config/CashAccountProperties because this package must not depend on
    // config (AAP 0.8.2).
    private static Set<String> acceptedCurrenciesFrom(Environment environment) {
        Objects.requireNonNull(environment, "environment");

        // Fail closed rather than substitute a set of this class's own: an Environment that cannot expose property
        // sources, an absent property and an empty one all mean the accepted-currency policy is unknown, and
        // guessing it classifies rows under a policy the running service does not enforce.
        if (!(environment instanceof ConfigurableEnvironment)) {
            throw new IllegalStateException(ACCEPTED_CURRENCIES_PROPERTY + " cannot be read from a"
                    + " non-configurable Environment; reconciliation has no accepted-currency policy to apply");
        }
        return normalizedCodes(Binder.get(environment)
                .bind(ACCEPTED_CURRENCIES_PROPERTY, Bindable.setOf(String.class))
                .orElse(null));
    }

    // The two scalar readers. A non-configurable Environment exposes no property sources, so each takes the value
    // an unset key would give - unlike the accepted-currency policy above, neither of these is a judgement the
    // reconciler would be wrong to make from its documented default.
    private static String stringProperty(Environment environment, String key, String fallback) {
        if (!(environment instanceof ConfigurableEnvironment)) {
            return fallback;
        }
        return Binder.get(environment).bind(key, Bindable.of(String.class)).orElse(fallback);
    }

    private static int integerProperty(Environment environment, String key, int fallback) {
        if (!(environment instanceof ConfigurableEnvironment)) {
            return fallback;
        }
        return Binder.get(environment).bind(key, Bindable.of(Integer.class)).orElse(fallback);
    }

    private static Set<String> normalizedCodes(Collection<String> codes) {
        Set<String> normalized = new LinkedHashSet<>();
        if (codes != null) {
            for (String code : codes) {
                String candidate = normalizeCurrency(code);
                if (candidate != null && !candidate.isEmpty()) {
                    normalized.add(candidate);
                }
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalStateException(ACCEPTED_CURRENCIES_PROPERTY + " must name at least one currency code;"
                    + " it is the single authority for which exported currencies are loadable");
        }
        return Set.copyOf(normalized);
    }

    private boolean isAcceptedCurrency(String currency) {
        return currency != null
                && CURRENCY_CODE.matcher(currency).matches()
                && acceptedCurrencies.contains(currency);
    }

    // The legacy join compared only the first RATE_KEY_LENGTH characters of the account's currency
    // (MOVE CURRENCYC TO WS-CURRENCY-KEY, CASH00.cbl:L213); the width comes from LegacyCharacterization rather
    // than a literal so the characterized parameters keep one declaration point.
    private static String rateKey(String currency) {
        if (currency == null) {
            return "";
        }
        return currency.length() <= LegacyCharacterization.RATE_KEY_LENGTH
                ? currency
                : currency.substring(0, LegacyCharacterization.RATE_KEY_LENGTH);
    }

    // trimPadding strips the legacy CHAR(8) blank padding on the right; strip() then removes anything on the left
    // that a delimited conversion may have added. Locale.ROOT, never the no-argument toUpperCase(): a Turkish
    // default locale maps "i" to a dotted capital and would fold TRY or ILS into a code no set contains.
    private static String normalizeCurrency(String raw) {
        String trimmed = LegacyExportFormat.trimPadding(raw);
        return trimmed == null ? null : trimmed.strip().toUpperCase(Locale.ROOT);
    }

    /**
     * The accumulating state of one source validation: identity keys and counters only, never the classified
     * records, so a streaming validation stays bounded by the distinct owners and rate keys rather than by the
     * export's length.
     */
    private static final class SourceClassification {

        private final Set<String> rejectedOwners = new LinkedHashSet<>();

        private final Set<String> rejectedRateKeys = new LinkedHashSet<>();

        private int legacyAccountCount;

        private int variances;

        // Records classified since the findings were last flushed out of the persistence context.
        private int classifiedSinceFlush;

        private SourceValidation toValidation() {
            return new SourceValidation(Set.copyOf(rejectedOwners), Set.copyOf(rejectedRateKeys),
                    legacyAccountCount, variances);
        }
    }

    /**
     * The state of one chunked export-versus-target comparison, whose only member that grows with the export is
     * {@code namedByExport} - one normalized owner key per row, the compact identity the second direction of the
     * comparison needs and nothing else.
     */
    private static final class TargetComparison {

        // Read once before the first chunk so every owner is judged on identical inputs (AAP 0.12.5); immutable,
        // and empty for a batch with no completed load.
        private final Map<String, BigDecimal> stagedRates;

        private final Set<String> namedByExport = new LinkedHashSet<>();

        private final Map<String, LegacyCashAccountRecord> chunk = new LinkedHashMap<>();

        private int consideredTargetRows;

        private TargetComparison(Map<String, BigDecimal> stagedRates) {
            this.stagedRates = Objects.requireNonNull(stagedRates, "stagedRates");
        }
    }
}
