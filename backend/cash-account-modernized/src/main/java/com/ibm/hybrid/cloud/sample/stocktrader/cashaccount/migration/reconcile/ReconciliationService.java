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

import org.springframework.beans.factory.annotation.Value;
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

/** Turns a legacy export plus the migrated database state into {@code migration_reconciliation} rows and a run summary. */
// NO @Profile("tool"), deliberately. migration.load.LegacyLoader calls validateSource() and
// migration.MigrationToolRunner calls reconcile(); a bean without a profile is visible in every profile, so
// leaving this one un-profiled makes it injectable whichever profile those two carry and removes a way for the
// tooling to fail to wire. Its presence in the deployed web context is inert: no request mapping, no @Scheduled
// work, no constructor side effect, and nothing on the request path injects it.
//
// Every difference is a ROW, never a log line (AAP 0.6.5): migration_reconciliation is the evidence the runbook's
// sign-off reads and the deterministic set the integration tests assert over, so a finding that existed only in
// output would be invisible to both.
@Service
public class ReconciliationService {

    // Reason tokens. Held as constants so the two methods that write them cannot drift, and so a test can assert
    // one verbatim out of the row's value columns. The convention throughout: the legacy-side rendering or reason
    // goes in legacy_value, the target-side rendering or reason in migrated_value.
    private static final String NULL_IN_LEGACY = "NULL_IN_LEGACY";

    private static final String INVALID_IN_LEGACY = "INVALID_IN_LEGACY";

    private static final String NULL_RATE = "NULL_RATE";

    private static final String MISSING_IN_TARGET = "MISSING_IN_TARGET";

    private static final String MISSING_IN_LEGACY = "MISSING_IN_LEGACY";

    private static final String RESERVATIONS_OUTSTANDING = "RESERVATIONS_OUTSTANDING";

    /** The counterpart token: the side of the comparison on which the owner does exist. */
    private static final String PRESENT = "PRESENT";

    // The accepted-currency policy has ONE authority, and it is this property as application.yml declares it
    // (AAP 0.7.2's 31-code estate allowlist). No copy of that set lives in this class: the reconciler decides
    // whether a legacy export row is loadable, so a second copy here that drifted from the request path's would
    // stage accounts the service then refuses, or reject accounts it would have accepted - a divergence nothing
    // would announce. Bound with Binder rather than @Value because application.yml writes the property as a YAML
    // sequence, which reaches the Environment only as indexed keys: a ${...} placeholder cannot aggregate those,
    // so it silently resolved to its own literal default and ignored both the file and every list-form override.
    private static final String ACCEPTED_CURRENCIES_PROPERTY = "cashaccount.fx.accepted-currencies";

    // The three-letter shape the API accepts; membership of the accepted set is the second, independent test.
    private static final Pattern CURRENCY_CODE = Pattern.compile("^[A-Z]{3}$");

    // How many owners one target-only classification query names at a time. The question "was every ledger row
    // of this owner written by a load?" is answered for a whole set of owners in ONE statement rather than by
    // reading any owner's history, and the chunk keeps the IN list - and therefore the bind-parameter count and
    // the planner's work - bounded however many accounts the target holds.
    private static final int OWNER_CLASSIFICATION_CHUNK = 500;

    // How many exported owners are compared, how many target rows are paged, and how many classified records
    // pass before the persistence context is flushed and cleared. A property because the value is an operator's
    // trade-off between round trips and resident rows and not a correctness parameter, and 50 because it matches
    // hibernate.jdbc.batch_size in application.yml, so one flush maps onto whole JDBC batches. Under
    // cashaccount.* rather than tool.* because MigrationToolRunner's typo guard admits only the seven tool.*
    // options it declares; the textual default keeps a context that sets nothing - the deployed web application,
    // which never reconciles - starting exactly as before.
    private static final String BATCH_CHUNK_SIZE_PROPERTY = "${cashaccount.migration.batch-chunk-size:50}";

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

    // Resolved through MigrationRun.RateSource, never held as raw text: fx/ToolExchangeRateSource picks the
    // delegate that prices a replay from the same property after canonicalizing it, so a raw comparison here
    // could classify a live-priced difference as one the parity gate produced - the run's rows would then
    // contradict the arithmetic behind them.
    private final MigrationRun.RateSource rateSource;

    private final int batchChunkSize;

    // WHY AN EntityManager SITS BESIDE THE REPOSITORIES RATHER THAN REPLACING THEM. The repositories remain the
    // whole query path - nothing below issues JPQL or SQL of its own - and this exists for one thing the Spring
    // Data interfaces cannot express: bounding the persistence context with flush() and clear() so a reconcile
    // over a production-sized target does not accumulate every entity it has touched. @PersistenceContext
    // injects the shared transaction-scoped proxy, so the flush and clear land in the caller's transaction and
    // never open one of their own (AAP 0.6.3).
    // CONTEXT CONTROL ONLY, NEVER A DATA PATH. Every row this class reads or writes goes through a repository
    // under persistence/ (AAP 0.6.5); this reference exists for the one operation no Spring Data interface
    // exposes - clear(), which bounds the persistence context across a bulk reconcile - paired with the flush
    // that must precede it. Nothing is persisted, merged, removed or queried through it.
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Container constructor.
     *
     * @param environment source of the accepted-currency set, read through {@link Binder} because
     *                    {@code cashaccount.fx.accepted-currencies} is written as a YAML sequence; a
     *                    {@code @Value} placeholder cannot bind one
     * @param rateSource  {@code tool.rate-source}, a scalar, so a placeholder binds it correctly
     * @param batchChunkSize {@code cashaccount.migration.batch-chunk-size}, the rows compared and the findings
     *                    held between two bounds of the persistence context; refused below 1
     */
    public ReconciliationService(CashAccountRepository accounts,
                                 CashReservationRepository reservations,
                                 LedgerEntryRepository ledgerEntries,
                                 LegacyRateTableRepository legacyRates,
                                 MigrationReconciliationRepository reconciliations,
                                 MigrationRunRepository runs,
                                 Environment environment,
                                 @Value("${tool.rate-source:legacy-table}") String rateSource,
                                 @Value(BATCH_CHUNK_SIZE_PROPERTY) int batchChunkSize) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.ledgerEntries = Objects.requireNonNull(ledgerEntries, "ledgerEntries");
        this.legacyRates = Objects.requireNonNull(legacyRates, "legacyRates");
        this.reconciliations = Objects.requireNonNull(reconciliations, "reconciliations");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.exportReader = new DelimitedExportReader();
        this.rateSource = MigrationRun.RateSource.of(rateSource);
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
     * <p>{@code rejectedOwners} carries owners in their normalized form
     * ({@link OwnerNormalizer#normalize(String)}); {@code rejectedRateKeys} carries the rate keys exactly as the
     * export's {@code currnkey} column held them, because that value is the identity the legacy join compared and
     * the one a staged row is keyed by. They are returned rather than merely recorded so that
     * {@code migration.load.LegacyLoader} can decline to apply exactly those rows.</p>
     *
     * @param legacyAccountCount rows read from the account export, rejected rows included
     * @param varianceCount      {@code VARIANCE} rows this validation wrote under the run
     */
    // WHAT WAS REFUSED, NEVER WHAT WAS ACCEPTED. An accepted-list would hold one entry per row of the export and
    // would therefore grow with a whole DB2 unload, while every consumer of this record is itself reading that
    // same export row by row and only needs to know whether the row in hand was refused. Refusals are the
    // exceptions a data owner reviews, so this state is proportional to the findings rather than to the file.
    public record SourceValidation(Set<String> rejectedOwners,
                                   Set<String> rejectedRateKeys,
                                   int legacyAccountCount,
                                   int varianceCount) {
    }

    /** Reads the account and rate exports out of {@code inputDirectory} and validates them. */
    // Transactional on every public form, and REQUIRED rather than REQUIRES_NEW: load/LegacyLoader and
    // reconcile() call in from a transaction of their own, which these join, so the findings still land with
    // the work that produced them (AAP 0.6.3); a direct caller with no transaction of its own gets one here,
    // because classifying an export bounds the persistence context as it goes and a flush has no meaning
    // outside a transaction.
    @Transactional
    public SourceValidation validateSource(MigrationRun run, Path inputDirectory) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(inputDirectory, "inputDirectory");
        return validateSource(run,
                inputDirectory.resolve(LegacyExportFormat.CASH_ACCOUNT_FILE),
                inputDirectory.resolve(LegacyExportFormat.RATE_TABLE_FILE));
    }

    /**
     * Classifies the named exports without holding either file, applying the same per-record rules the list
     * form applies.
     *
     * @param accountFile the account export, required
     * @param rateFile    the rate-table export, or {@code null} for a run that carries accounts alone
     */
    // The bounded entry point, and the one load/LegacyLoader's first pass uses: a bulk load has to classify a
    // whole DB2 unload before it writes its first account row, and holding that file as a list of records in
    // order to do so is exactly the unbounded read this form exists to avoid. No rule is restated here - all
    // three entry points drive classifyAccount/classifyRate against one accumulator, so the classification
    // keeps a single home and the list form stays behaviourally identical to it.
    @Transactional
    public SourceValidation validateSource(MigrationRun run, Path accountFile, Path rateFile) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(accountFile, "accountFile");

        SourceClassification classification = new SourceClassification();
        exportReader.streamCashAccounts(accountFile, record -> classifyAccount(run, record, classification));
        // A null rate file is the accounts-only shape: the validation then sees no rate row at all, so nothing
        // is staged and no rate finding is recorded - the run simply has nothing to say about currencies.
        if (rateFile != null) {
            exportReader.streamRates(rateFile, rateRecord -> classifyRate(run, rateRecord, classification));
        }
        return classification.toValidation();
    }

    /**
     * Classifies every exported row that the target's {@code NOT NULL} columns and accepted-currency set cannot
     * accept, writing one row per finding and naming what may be loaded.
     *
     * <p>Both {@code load} and {@code reconcile} run this first, under their own run identifier.</p>
     */
    // @Transactional with the default REQUIRED, and never REQUIRES_NEW: a load applies the whole export in ONE
    // transaction (AAP 0.6.3), so either every row lands or none does, and this joins that transaction when the
    // loader calls in. A transaction of its own here would commit these variance rows even where the load it
    // belongs to then rolled back, leaving findings attributed to a run that applied nothing. The annotation is
    // there for a caller with no transaction at all: classifying bounds the persistence context as it goes, and
    // a flush has no meaning outside one.
    //
    // Classification, never repair. Five of the eight legacy columns are nullable and the program declared no null
    // indicators (DB2DDL.jcl:L48-L49, L56-L58; DCLFRANK.cpy:L19-L23), so a null in an export is a legitimate
    // legacy state whose meaning only the data owner can settle. Substituting a zero or a default here would erase
    // the very finding an operator has to review.
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

    // One exported account row against the two conditions the legacy catalog genuinely permitted, and the only
    // place they are written down: the list form and the streaming form both call this, so neither can drift.
    private void classifyAccount(MigrationRun run, LegacyCashAccountRecord record,
                                 SourceClassification classification) {
        classification.legacyAccountCount++;
        boundFindings(classification);

        // An owner the legacy CHAR(32) column could not have held is a malformed export rather than a
        // variance: normalize(...) raises, the transaction aborts and the run is recorded FAILED, which is the
        // right outcome for a file that cannot be trusted at all. Only the conditions the legacy catalog
        // genuinely permitted are classified below.
        String owner = OwnerNormalizer.normalize(record.owner());

        // Rule 1 - a NULL balance or currency. Selecting either into a host variable with no indicator was the
        // SQLCODE -305 case, so the legacy account was unreachable through Q/U/X/C/D for as long as the null
        // stood (CASH00.cbl:L136-L150, L204-L211). The target's columns are NOT NULL, so the row is recorded
        // and not loaded.
        if (record.balance() == null || LegacyExportFormat.isNull(record.currency())) {
            record(run, owner, VarianceKind.STATE, ReconciliationStatus.VARIANCE,
                    NULL_IN_LEGACY, null, null, null);
            classification.rejectedOwners.add(owner);
            classification.variances++;
            return;
        }

        // Rule 2 - a currency outside the accepted set. The legacy column was a nullable CHAR(8) and the rate
        // join only ever compared its first five characters (CASH00.cbl:L213-L219), so values the target can
        // neither validate nor convert could be stored. Recorded rather than coerced to a default currency,
        // which would silently re-denominate real money.
        String currency = normalizeCurrency(record.currency());
        if (!isAcceptedCurrency(currency)) {
            // The token stands alone in legacy_value so a test can assert it verbatim; the offending code
            // itself stays in the export, which is the checksummed evidence artifact the row's owner joins to.
            record(run, owner, VarianceKind.CURRENCY, ReconciliationStatus.VARIANCE,
                    INVALID_IN_LEGACY, null, null, null);
            classification.rejectedOwners.add(owner);
            classification.variances++;
            return;
        }

        // Once rejected, always rejected, and file order cannot undo it: an export that names one owner twice -
        // a shape a delimited unload of a CHAR(32) primary key should not produce - must not have a later clean
        // row reinstate a row already recorded as unloadable. Recording refusals rather than acceptances is what
        // makes that true for free: a clean row adds nothing, so it cannot remove an earlier refusal.
    }

    // One exported rate row, classified by the same rules whichever entry point read it.
    private void classifyRate(MigrationRun run, LegacyRateRecord rateRecord,
                              SourceClassification classification) {
        String rateKey = LegacyExportFormat.trimPadding(rateRecord.currnkey());
        boundFindings(classification);

        // Rule 3 - a NULL rate. This is the condition the legacy program hid: the rate SELECT raised -305, the
        // COMPUTE ran on an uninitialized RATES and the following UPDATE's SQLCODE 0 overwrote the failure
        // before anyone saw it (CASH00.cbl:L215-L231, L249-L264). The row is not staged, so a later C/D replay
        // for that currency is rejected by the target instead of being computed against an invented rate.
        if (rateRecord.rates() == null) {
            record(run, rateKey, VarianceKind.RATE_SOURCE, ReconciliationStatus.VARIANCE,
                    NULL_RATE, null, null, null);
            classification.rejectedRateKeys.add(rateKey);
            classification.variances++;
            return;
        }

        // Rule 4 - a NULL currnbase or amount is NOT a finding and gets no row. Both columns are fetched by
        // the rate SELECT (CASH00.cbl:L215, L249) and then referenced by no COMPUTE and no MOVE anywhere in
        // the program, so a null in either changed no balance the reconciliation could be judging. They are
        // staged as NULL for the evidence trail (AAP 0.4.1).
        // Nothing is recorded for an acceptable rate row, for the same reason as above: the staging pass reads
        // this file itself and asks only whether the key in hand was refused.
    }

    /**
     * Compares the legacy export in {@code inputDirectory} with the migrated state, owner by owner.
     *
     * @return the number of {@code VARIANCE} rows standing under this run, which is what makes the tool exit 2
     *         rather than 0; {@code ACCEPTED_EXCEPTION} rows are deliberately not counted
     */
    // One transaction: the findings and the run summary that reports them have to land together, or an operator
    // can read a count that its rows do not support.
    @Transactional
    public int reconcile(MigrationRun run, Path inputDirectory) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(inputDirectory, "inputDirectory");

        // Materialized before the first finding because migration_reconciliation.run_id references migration_run:
        // a row written under a run whose own row does not exist yet would fail on the foreign key. For the runner,
        // which opens the run before calling in, this is a no-op merge. Flushed immediately so the run row is in
        // the database before any finding row is ordered against it, rather than relying on the insert ordering
        // hibernate.order_inserts chooses (application.yml).
        runs.save(run);
        entityManager.flush();

        // File names come from LegacyExportFormat and are written down nowhere else, so the export's shape keeps
        // one home. CASH_ACCOUNT_FILE is the legacy truth; TARGET_STATE_FILE carries a migrated state a caller
        // loads beforehand and is deliberately never read here - the target side of this comparison is the
        // database, not a file.
        Path accountFile = inputDirectory.resolve(LegacyExportFormat.CASH_ACCOUNT_FILE);
        Path rateFile = inputDirectory.resolve(LegacyExportFormat.RATE_TABLE_FILE);

        // First, always: the export's own rows that cannot be applied are findings of this run, and the owners they
        // name are the ones the comparison below must leave alone.
        SourceValidation validation = validateSource(run, accountFile, rateFile);

        // JOIN ON THE NORMALIZED OWNER KEY, never on sort order and never on ordinal position. EBCDIC and
        // ASCII/UTF-8 collate differently - digits sort after letters in EBCDIC and before them in ASCII (AAP
        // 0.12.2) - so two exports of the same data can arrive in different orders, and an index-wise or
        // sort-wise comparison would report differences that are purely an artifact of the code page.
        //
        // The export is streamed and compared batchChunkSize owners at a time, so neither the file nor the target
        // table is ever resident whole. The one structure that grows with the export is the set of owner keys it
        // named - a short String per row, never a record and never an entity - because the second direction below
        // has to distinguish an owner the export omitted from one it named, and no query over the database can
        // answer that about a file.
        // ONE SNAPSHOT FOR THE WHOLE COMPARISON, taken before the first owner is examined. The rates a
        // live-mode difference is judged against belong to the batch's completed load, so resolving that run
        // and reading its table here - rather than per differing owner - both fixes the number of statements a
        // reconcile issues and fixes WHICH rows every owner is judged on: a retry load committing under the
        // same batch while this loop runs cannot move half the comparison onto a different table.
        //
        // Read unconditionally, not behind another reading of tool.rate-source: it is two statements before a
        // loop that already reads every account row and an export file, and one place deciding the mode
        // (recordBalanceDifference) is worth more than the reads it would save in legacy-table mode.
        Map<String, BigDecimal> stagedRates = stagedRatesOfBatchLoad(run.batchId());

        TargetComparison comparison = new TargetComparison(stagedRates);
        exportReader.streamCashAccounts(accountFile, record -> {
            String owner = OwnerNormalizer.normalize(record.owner());

            // An export naming one owner twice is malformed - the legacy primary key was the owner itself and
            // storage was upper case (CASH00.cbl:L155), and load/LegacyLoader refuses such a file outright - so the
            // first occurrence is the one compared and a repeat is passed over. Comparing both would report one
            // owner's single condition as two findings.
            if (!comparison.namedByExport.add(owner)) {
                return;
            }

            // THE SKIP-REJECTED INVARIANT. An owner validateSource already recorded has been reported once, and
            // the reason it was rejected is also the reason it was never loaded - so comparing it would report its
            // absence from the target a second time, as a MISSING_IN_TARGET row that describes the same single
            // fact. One condition, one row. It stays counted as named by the export, so the second direction
            // below does not then report it as MISSING_IN_LEGACY either.
            if (validation.rejectedOwners().contains(owner)) {
                return;
            }

            comparison.chunk.put(owner, record);
            if (comparison.chunk.size() >= batchChunkSize) {
                compareChunk(run, comparison);
            }
        });
        compareChunk(run, comparison);

        // The other direction, a page at a time rather than accounts.findAll(): the target is the unbounded side
        // here, and this transaction inserts only migration_reconciliation rows, so a page window ordered by the
        // primary key is stable while it runs.
        for (int page = 0; ; page++) {
            Page<CashAccount> targetRows = accounts.findAll(PageRequest.of(page, batchChunkSize, Sort.by("owner")));

            // The owners of this page the export does not name at all, gathered before any of them is classified.
            // Their classification is a question about ledger_entry, and asking it once for the whole page is the
            // difference between one statement and one read per owner: a partial or empty export against a target
            // of N accounts makes every one of them a candidate, so a per-owner read is O(N) statements for an
            // answer the database can compute for the whole set in a single pass. The page is what bounds the
            // set, so neither this map nor the statement it feeds grows with the target.
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

                // Only an account the tooling itself produced is reported: if anything other than a load has written
                // to this owner's ledger, its absence from the export is ordinary post-load activity rather than a
                // migration variance.
                if (!producedByMigration.contains(target.owner())) {
                    continue;
                }

                comparison.consideredTargetRows++;

                // NEVER DELETED AUTOMATICALLY (AAP 0.6.3): a delta export that omits an owner may equally mean the row
                // was removed upstream or that the export was partial, and only the operator can tell which. The
                // decision is theirs, taken through the retail DELETE endpoint, which writes an ACCOUNT_DELETED ledger
                // event; a reconciler that deleted rows would destroy evidence on the strength of a missing line.
                record(run, entry.getKey(), VarianceKind.STATE, ReconciliationStatus.VARIANCE,
                        MISSING_IN_LEGACY, PRESENT, null, target.availableBalance().amount());
            }
            boundPersistenceContext();
            if (!targetRows.hasNext()) {
                break;
            }
        }

        int consideredTargetRows = comparison.consideredTargetRows;

        // NO VarianceKind.TRANSACTION_COUNT HERE. After a bulk load the target holds one MIGRATION_LOAD row per
        // account and no per-transaction history, so there is no target count to compare a legacy count against;
        // the kind belongs to a shadow window, where both sides processed the same stream (AAP 0.10.3).

        // One authority for the number, read back from the rows themselves rather than accumulated in a local:
        // only VARIANCE counts, so an ACCEPTED_EXCEPTION never inflates the count or changes the exit code.
        int varianceCount = Math.toIntExact(
                reconciliations.countByRunIdAndStatus(run.runId(), ReconciliationStatus.VARIANCE));

        run.setLegacyRecordCount(validation.legacyAccountCount());
        run.setMigratedRecordCount(consideredTargetRows);
        run.setVarianceCount(varianceCount);
        // The verdict is set here because it is this comparison's finding; finishedAt is deliberately left unset so
        // MigrationToolRunner stays the single closer of the row.
        run.setStatus(varianceCount == 0 ? MigrationRun.Status.CLEAN : MigrationRun.Status.VARIANCE);
        // Bounding the context detached this instance, so this is a merge of a detached run - which is what it
        // already was for every caller that opened the run in an earlier transaction. It is safe because
        // MigrationRun carries no @Version and nothing else writes the row inside this transaction, and it is
        // required: without it the counts and the verdict would exist only in memory.
        runs.save(run);

        // A variance is a row plus an exit code, never an exception: throwing would abort the transaction and
        // destroy the very findings the run exists to record. Exceptions are reserved for a missing or unreadable
        // export and for database failures, which must abort so the run is recorded FAILED.
        return varianceCount;
    }

    // One chunk of the export against exactly the target rows that chunk names, then the context is bounded
    // again. The per-owner comparison inside is the whole of the comparison and is unchanged by the chunking:
    // what chunking changes is only how many of the export's records and the target's entities exist at once.
    private void compareChunk(MigrationRun run, TargetComparison comparison) {
        if (comparison.chunk.isEmpty()) {
            return;
        }

        // findAllById, not findAll: the owner IS cash_account's primary key, so this reads exactly the rows this
        // chunk asks about instead of materializing the table. Re-keyed through the same normalizer the export
        // side used, so the join cannot depend on how a stored owner happens to be cased.
        Map<String, CashAccount> targetByOwner = new LinkedHashMap<>();
        for (CashAccount account : accounts.findAllById(comparison.chunk.keySet())) {
            targetByOwner.put(OwnerNormalizer.normalize(account.owner()), account);
        }

        for (Map.Entry<String, LegacyCashAccountRecord> entry : comparison.chunk.entrySet()) {
            String owner = entry.getKey();
            LegacyCashAccountRecord legacy = entry.getValue();
            CashAccount target = targetByOwner.get(owner);

            if (target == null) {
                // Present in the legacy export, absent from the target: the load either has not run for this owner
                // or declined to apply it. Recorded with the legacy balance so the operator can see what is
                // missing; no migrated balance exists to render.
                record(run, owner, VarianceKind.STATE, ReconciliationStatus.VARIANCE,
                        PRESENT, MISSING_IN_TARGET, legacy.balance(), null);
                continue;
            }

            comparison.consideredTargetRows++;

            // FUNDS ON HOLD ARE A STATE FINDING, NOT A BALANCE DIFFERENCE - and the test comes FIRST, before
            // either comparison below. A hold moves money out of available_balance into reserved_balance, so an
            // owner with a HELD reservation legitimately carries a lower available balance than the legacy
            // absolute figure, and the currency or balance rows a comparison would write here would describe the
            // hold rather than a migration difference. It is the same condition the load records when it declines
            // to overwrite such an owner (AAP 0.6.3), reported in the same shape, so the operator reads one row
            // for one fact whichever command found it. continue, therefore: one condition, one row.
            if (fundsAreOnHold(target)) {
                recordReservationsOutstanding(run, target);
                continue;
            }

            // Guaranteed non-null by the invariant above: validateSource rejects exactly the rows whose balance or
            // currency it could not accept, and those were skipped.
            String legacyCurrency = normalizeCurrency(legacy.currency());
            String targetCurrency = normalizeCurrency(target.currency());
            BigDecimal legacyBalance = legacy.balance();

            // The available balance, not the total: retail balance IS the available balance (AAP 0.6.2), and with
            // no reservation outstanding the two are equal, which is what makes legacy parity exact. Reaching
            // this line already means nothing is on hold - the gate above reported any such owner and skipped it
            // - so this figure is never compared against a legacy balance the hold has moved.
            BigDecimal targetBalance = target.availableBalance().amount();

            // The currency check and the balance check are INDEPENDENT, each writing at most one row, so an owner
            // may produce both, one or neither. Collapsing them would hide the second difference behind the first.
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

            // A MATCHING OWNER GETS NO ROW AT ALL - not even a MATCHED one. The acceptance criteria are "zero
            // VARIANCE rows" for a matched fixture and "exactly the seeded rows" for a seeded one, asserted over
            // the deterministic set findByRunIdOrderByReconciliationIdAsc returns; a row per agreeing owner would
            // bury the seeded rows in it and make both assertions depend on fixture size. MATCHED is the value an
            // operator sets when reclassifying a reviewed row, not something this comparison writes.
        }

        comparison.chunk.clear();
        boundPersistenceContext();
    }

    // The classification's own bound. A finding row is a managed entity until it is flushed, so an export whose
    // every row is a finding - an unload of a table with a nullable balance, for instance - would hold one per
    // row for the length of the validation. Counted per classified record rather than per finding so the check
    // is reached on a clean export too, where it costs one comparison and never flushes anything.
    private void boundFindings(SourceClassification classification) {
        if (++classification.classifiedSinceFlush < batchChunkSize) {
            return;
        }
        classification.classifiedSinceFlush = 0;
        boundPersistenceContext();
    }

    // FLUSH BEFORE CLEAR, ALWAYS. clear() discards everything pending, so clearing without flushing would drop
    // the very findings this run exists to record. Both stay inside the one ambient transaction (AAP 0.6.3): a
    // flush is not a commit, so a failure after one still leaves the database as it was, and no chunk of a
    // reconcile is durable until the whole reconcile is.
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
        // the same staged RATES, so a difference cannot be a rate difference and every one of them is a genuine
        // VARIANCE (AAP 0.12.5). This is why the seeded KARRI difference stays BALANCE/VARIANCE.
        if (rateSource.isLive() && rateExplains(stagedRates, currency, legacyBalance, targetBalance)) {
            // The two figures are rendered into the value columns because this row is no longer a BALANCE row and
            // so does not pass through MigrationReconciliation.balance(...). The variance column stays null on
            // purpose: it means "the signed difference this row leaves outstanding", and an accepted exception
            // leaves none - the difference is still readable from the two balance columns beside it.
            record(run, owner, VarianceKind.RATE_SOURCE, ReconciliationStatus.ACCEPTED_EXCEPTION,
                    legacyBalance.toPlainString(), targetBalance.toPlainString(),
                    legacyBalance, targetBalance);
            return;
        }
        record(run, owner, VarianceKind.BALANCE, ReconciliationStatus.VARIANCE,
                null, null, legacyBalance, targetBalance);
    }

    /**
     * Whether re-deriving the target balance with the staged legacy rate yields the legacy balance exactly.
     */
    // Deliberately narrow, and conservative by design. Reconcile mode holds two absolute balances and not the
    // transaction behind them, so "the difference is a rate difference" is only decidable for the one shape that
    // re-derives exactly: the target carries the unconverted figure while the legacy carried it scaled by the
    // staged RATES - truncate2(RATES x target) == legacy, the legacy COMPUTE of CASH00.cbl:L222 applied from zero.
    // Anything else keeps its BALANCE/VARIANCE row, because accepting a difference no arithmetic re-derives
    // would sign off a defect. The general live-versus-legacy rule needs the replayed transaction and therefore belongs
    // to shadow.ShadowComparator.
    //
    // THE RATE COMES FROM THE BATCH'S LOAD RUN, NEVER FROM THIS RUN. legacy_rate_table is staged by the load,
    // and a reconcile is a different invocation with its own run_id under the same --tool.batch-id (AAP 0.6.3);
    // a lookup keyed on the reconcile's own run_id therefore matches nothing in normal operation, which would
    // silence this path entirely and leave every live-mode rate difference recorded as a BALANCE variance. The
    // owning run is resolved from the batch instead, exactly as shadow.ShadowComparator and
    // fx.LegacyRateTableSource resolve it - once per reconcile, into the snapshot passed in here.
    //
    // An absent rate is an ordinary outcome and must stay one: a batch whose load has not run, a load that
    // failed and staged nothing, a currency the export never carried, and a currency whose exported rates
    // column was NULL - which validateSource refuses to stage and records as RATE_SOURCE/NULL_RATE - all reach
    // here as "no rate", and every one of them means the difference is not explained away.
    private boolean rateExplains(Map<String, BigDecimal> stagedRates, String currency,
                                 BigDecimal legacyBalance, BigDecimal targetBalance) {
        BigDecimal stagedRate = stagedRates.get(rateKey(currency));
        if (stagedRate == null) {
            return false;
        }
        Money rederived = LegacyBalanceCalculator.credit(BigDecimal.ZERO, stagedRate, targetBalance);
        return rederived.amount().compareTo(legacyBalance) == 0;
    }

    /**
     * The rates the batch's completed load staged, by staged key: the one snapshot a whole reconcile judges on.
     */
    // Read whole rather than key by key because the table is one row per currency - the legacy catalog keyed it
    // on CURRNKEY CHAR(5) (DB2DDL.jcl:L54-L62) against an accepted set of 31 codes (AAP 0.7.2) - so the whole
    // table costs less than the per-key statements it replaces, and an immutable map is what lets every owner
    // in the loop be judged on identical inputs (AAP 0.12.5). Rows whose rates column is NULL are left out, so
    // a staged-but-unusable rate cannot be mistaken for a usable one. An empty map is the correct answer for a
    // batch with no completed load: nothing was staged, so nothing can be explained away.
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

    /** The run whose staged rate rows a reconcile of this batch judges: the batch's latest load that did not fail. */
    // A load applies its whole export in one database transaction (AAP 0.6.3), so it either ends CLEAN or
    // VARIANCE with every row staged, or FAILED with none staged, and a retry is a new run_id under the same
    // batch_id. A FAILED run therefore names a table that was never written, and because the repository returns
    // the batch ordered by started_at ascending, the last non-FAILED load is the most recent one that did stage.
    private UUID latestLoadRunId(UUID batchId) {
        UUID resolved = null;
        for (MigrationRun candidate : runs.findByBatchIdOrderByStartedAtAsc(batchId)) {
            if (candidate.mode() == MigrationRun.Mode.LOAD && candidate.status() != MigrationRun.Status.FAILED) {
                resolved = candidate.runId();
            }
        }
        return resolved;
    }

    /**
     * Which of {@code owners} carry at least one ledger row and not one written by anything but the migration
     * tooling.
     */
    // The two conditions are the ones the reported owner has to satisfy, and the query decides both at once:
    // an owner with NO ledger row was not produced by a load - a load always writes its MIGRATION_LOAD event -
    // and an owner with a row from any other source has been written to since, so its absence from the export
    // is ordinary post-load activity rather than a migration variance. Neither owner is reported, and the two
    // are deliberately not distinguished here because the comparison does the same thing with both.
    //
    // Set-based and chunked, never per owner: the answer is a property of ledger_entry that the database can
    // evaluate for a whole set in one statement, so a target of N unexported owners costs ceil(N/CHUNK)
    // statements instead of N reads of unbounded history. The chunk is what keeps the statement itself
    // bounded, so neither the query count nor any single query grows with the target.
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
     * Persists one reconciliation row: the single place the row shape and the variance sign are decided.
     *
     * <p>{@code shadow.ShadowComparator} writes its {@code BALANCE}, {@code TRANSACTION_COUNT},
     * {@code REJECTED_BY_TARGET} and {@code RATE_SOURCE} rows through here for that reason. For
     * {@link VarianceKind#BALANCE} the value columns are the two balances rendered as plain decimal text and the
     * variance is {@code migrated - legacy}, both computed by {@link MigrationReconciliation#balance}, so the
     * {@code legacyValue} and {@code migratedValue} arguments are not used for that kind and callers pass null.
     * Every other kind carries the caller's own value text - a reason token, a rendered figure or a count - and a
     * null variance, because a row that leaves no signed balance difference outstanding must not claim one.</p>
     *
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
     * @param account the account row the caller has already established as holding funds - the very row a load
     *                declined to overwrite, or the row a reconcile declined to compare
     * @return the saved row, never {@code null}
     */
    // A PURE RECORDER: the caller establishes the condition and this method only writes what it found. Both
    // callers already hold the answer and the row it concerns - migration.load.LegacyLoader has the account
    // locked FOR UPDATE and has tested cash_reservation under that lock, and reconcile has tested it against
    // the row it is comparing - so re-asking the database here would repeat two statements to reach a state
    // neither caller can have lost: a release has to take the same cash_account row lock the loader holds, so
    // the condition cannot change underneath it.
    //
    // The retained target balance is rendered so the operator can see what was kept; the legacy figure belongs
    // to the export the caller was applying and is not this row's claim.
    public MigrationReconciliation recordReservationsOutstanding(MigrationRun run, CashAccount account) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(account, "account");
        return record(run, account.owner(), VarianceKind.STATE, ReconciliationStatus.VARIANCE,
                PRESENT, RESERVATIONS_OUTSTANDING, null, account.availableBalance().amount());
    }

    /** Whether this account holds reserved funds, so that comparing its available balance would report the hold. */
    // The account's own reserved_balance is tested FIRST because the row is already in hand and answers for
    // free: it is the aggregate of the owner's HELD reservations, so a zero there is every owner that has
    // nothing on hold - which is every owner, in every fixture and in the overwhelming majority of a real
    // estate - and none of them costs a statement. cash_reservation then decides for the few that remain,
    // because the row this gate leads to names outstanding RESERVATIONS and must not be written on the strength
    // of a balance column alone. Neither test alone is enough: without the first this would be a query per
    // compared owner, and without the second a reserved figure with no reservation behind it would be reported
    // as one.
    private boolean fundsAreOnHold(CashAccount account) {
        return !account.reservedBalance().isZero()
                && reservations.existsByOwnerAndState(account.owner(), ReservationState.HELD);
    }

    // Binder, never @Value: application.yml writes cashaccount.fx.accepted-currencies as a YAML sequence, which
    // the Environment exposes only as indexed keys, and Binder is what aggregates those back into a Set. It also
    // accepts the comma-separated scalar form, so relaxed binding through CASHACCOUNT_FX_ACCEPTED_CURRENCIES keeps
    // working. The Environment rather than config/CashAccountProperties because this package must not depend on
    // config (AAP 0.8.2) - the same route retail/RetailCashAccountService takes to the same property.
    private static Set<String> acceptedCurrenciesFrom(Environment environment) {
        Objects.requireNonNull(environment, "environment");

        // Fail closed rather than substitute a set of this class's own. An Environment that cannot expose property
        // sources, an absent property and an empty one all mean the same thing: the accepted-currency policy this
        // reconciler must judge an export against is unknown. Guessing it is how a reconciliation silently
        // classifies rows under a policy the running service does not enforce.
        if (!(environment instanceof ConfigurableEnvironment)) {
            throw new IllegalStateException(ACCEPTED_CURRENCIES_PROPERTY + " cannot be read from a"
                    + " non-configurable Environment; reconciliation has no accepted-currency policy to apply");
        }
        return normalizedCodes(Binder.get(environment)
                .bind(ACCEPTED_CURRENCIES_PROPERTY, Bindable.setOf(String.class))
                .orElse(null));
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

    /** The accepted-currency test: the three-letter shape and membership of the accepted set, both required. */
    private boolean isAcceptedCurrency(String currency) {
        return currency != null
                && CURRENCY_CODE.matcher(currency).matches()
                && acceptedCurrencies.contains(currency);
    }

    // The legacy join compared only the first RATE_KEY_LENGTH characters of the account's currency
    // (MOVE CURRENCYC TO WS-CURRENCY-KEY, CASH00.cbl:L213), and the width comes from LegacyCharacterization rather
    // than from a literal here so the characterized parameters have one declaration point.
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

    /** The accumulating state of one source validation, shared by the list, path-pair and directory entry points. */
    // Identity keys and counters only, never the classified records: that is what lets a streaming validation of a
    // bulk export stay bounded by the number of distinct owners and rate keys instead of by the file's length.
    private static final class SourceClassification {

        private final Set<String> rejectedOwners = new LinkedHashSet<>();

        private final Set<String> rejectedRateKeys = new LinkedHashSet<>();

        private int legacyAccountCount;

        private int variances;

        // Records classified since the last time the findings were flushed out of the persistence context. An
        // export in which every row is a finding would otherwise hold one managed MigrationReconciliation per
        // row until the validation ended.
        private int classifiedSinceFlush;

        private SourceValidation toValidation() {
            return new SourceValidation(Set.copyOf(rejectedOwners), Set.copyOf(rejectedRateKeys),
                    legacyAccountCount, variances);
        }
    }

    /** The state of one chunked export-versus-target comparison, carried across its chunks. */
    // chunk holds at most batchChunkSize exported records and is emptied by every compareChunk; namedByExport is
    // the only member that grows with the export, and it holds one normalized owner key per row - the compact
    // identity the second direction needs and nothing else.
    private static final class TargetComparison {

        // The batch load's staged rates, read once before the first chunk so every owner is judged on identical
        // inputs (AAP 0.12.5); immutable, and empty for a batch with no completed load.
        private final Map<String, BigDecimal> stagedRates;

        private final Set<String> namedByExport = new LinkedHashSet<>();

        private final Map<String, LegacyCashAccountRecord> chunk = new LinkedHashMap<>();

        private int consideredTargetRows;

        private TargetComparison(Map<String, BigDecimal> stagedRates) {
            this.stagedRates = Objects.requireNonNull(stagedRates, "stagedRates");
        }
    }
}
