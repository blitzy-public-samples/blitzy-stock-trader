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

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
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

    // The one value of tool.rate-source that opens the reclassification path guarded by rateExplains(...).
    private static final String LIVE_RATE_SOURCE = "live";

    // Declared as a constant so the annotation below stays inside a readable line; a static final String with a
    // literal initializer is a compile-time constant and is therefore legal as an annotation value. The DEFAULT
    // carries the whole 31-code set on purpose: application.yml declares cashaccount.fx.accepted-currencies as a
    // YAML sequence, which reaches the Environment only as indexed keys, so a ${...} placeholder cannot aggregate
    // it and this default is what takes effect in the default profile. The two must therefore be kept in step -
    // the set is AAP 0.7.2's, adopted verbatim from the allowed_currencies CHECK the estate already enforces
    // (infra/stocktrader-setup/azure/modules/postgres_init/init_schema.sql.tmpl:L7), which is also the set the
    // exchange-rate provider serves. A comma-separated override (a property, an environment variable or a test's
    // @DynamicPropertySource) still resolves normally.
    private static final String ACCEPTED_CURRENCIES_PROPERTY = "${cashaccount.fx.accepted-currencies:"
            + "AUD,BGN,BRL,CAD,CHF,CNY,CZK,DKK,EUR,GBP,HKD,HUF,IDR,ILS,INR,ISK,"
            + "JPY,KRW,MXN,MYR,NOK,NZD,PHP,PLN,RON,SEK,SGD,THB,TRY,USD,ZAR}";

    // The three-letter shape the API accepts; membership of the accepted set is the second, independent test.
    private static final Pattern CURRENCY_CODE = Pattern.compile("^[A-Z]{3}$");

    // The ledger is read a page at a time rather than whole: an owner's history is unbounded, and the descending
    // order means the first page already answers "was anything here written by something other than a load?".
    private static final int LEDGER_PAGE_SIZE = 200;

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

    private final String rateSource;

    public ReconciliationService(CashAccountRepository accounts,
                                 CashReservationRepository reservations,
                                 LedgerEntryRepository ledgerEntries,
                                 LegacyRateTableRepository legacyRates,
                                 MigrationReconciliationRepository reconciliations,
                                 MigrationRunRepository runs,
                                 @Value(ACCEPTED_CURRENCIES_PROPERTY) Set<String> acceptedCurrencies,
                                 @Value("${tool.rate-source:legacy-table}") String rateSource) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.ledgerEntries = Objects.requireNonNull(ledgerEntries, "ledgerEntries");
        this.legacyRates = Objects.requireNonNull(legacyRates, "legacyRates");
        this.reconciliations = Objects.requireNonNull(reconciliations, "reconciliations");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.exportReader = new DelimitedExportReader();
        this.rateSource = Objects.requireNonNull(rateSource, "rateSource");

        Set<String> normalized = new LinkedHashSet<>();
        for (String code : Objects.requireNonNull(acceptedCurrencies, "acceptedCurrencies")) {
            String candidate = normalizeCurrency(code);
            if (candidate != null && !candidate.isEmpty()) {
                normalized.add(candidate);
            }
        }
        this.acceptedCurrencies = Set.copyOf(normalized);
    }

    /**
     * What the source validation established about one legacy export, for the loader that has to act on it.
     *
     * <p>{@code loadableOwners} and {@code rejectedOwners} carry owners in their normalized form
     * ({@link OwnerNormalizer#normalize(String)}); {@code loadableRateKeys} and {@code rejectedRateKeys} carry the
     * rate keys exactly as the export's {@code currnkey} column held them, because that value is the identity the
     * legacy join compared and the one a staged row is keyed by. The rejected sets are returned rather than merely
     * recorded so that {@code migration.load.LegacyLoader} can decline to apply exactly those rows.</p>
     *
     * @param legacyAccountCount rows read from the account export, rejected rows included
     * @param varianceCount      {@code VARIANCE} rows this validation wrote under the run
     */
    public record SourceValidation(Set<String> loadableOwners,
                                   Set<String> rejectedOwners,
                                   Set<String> loadableRateKeys,
                                   Set<String> rejectedRateKeys,
                                   int legacyAccountCount,
                                   int varianceCount) {
    }

    /** Reads the account and rate exports out of {@code inputDirectory} and validates them. */
    public SourceValidation validateSource(MigrationRun run, Path inputDirectory) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(inputDirectory, "inputDirectory");
        return validateSource(run,
                exportReader.readCashAccounts(inputDirectory.resolve(LegacyExportFormat.CASH_ACCOUNT_FILE)),
                exportReader.readRates(inputDirectory.resolve(LegacyExportFormat.RATE_TABLE_FILE)));
    }

    /**
     * Classifies every exported row that the target's {@code NOT NULL} columns and accepted-currency set cannot
     * accept, writing one row per finding and naming what may be loaded.
     *
     * <p>Both {@code load} and {@code reconcile} run this first, under their own run identifier.</p>
     */
    // NO @Transactional, so this joins the caller's transaction (Propagation.REQUIRED by default) and must never
    // be REQUIRES_NEW: a load applies the whole export in ONE transaction (AAP 0.6.3), so either every row lands
    // or none does. A transaction of its own here would commit these variance rows even where the load it belongs
    // to then rolled back, leaving findings attributed to a run that applied nothing.
    //
    // Classification, never repair. Five of the eight legacy columns are nullable and the program declared no null
    // indicators (DB2DDL.jcl:L48-L49, L56-L58; DCLFRANK.cpy:L19-L23), so a null in an export is a legitimate
    // legacy state whose meaning only the data owner can settle. Substituting a zero or a default here would erase
    // the very finding an operator has to review.
    public SourceValidation validateSource(MigrationRun run,
                                           List<LegacyCashAccountRecord> accountRecords,
                                           List<LegacyRateRecord> rateRecords) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(accountRecords, "accountRecords");
        Objects.requireNonNull(rateRecords, "rateRecords");

        Set<String> loadableOwners = new LinkedHashSet<>();
        Set<String> rejectedOwners = new LinkedHashSet<>();
        int variances = 0;

        for (LegacyCashAccountRecord record : accountRecords) {
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
                rejectedOwners.add(owner);
                loadableOwners.remove(owner);
                variances++;
                continue;
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
                rejectedOwners.add(owner);
                loadableOwners.remove(owner);
                variances++;
                continue;
            }

            // Once rejected, always rejected: an export that names one owner twice - a shape a delimited unload of
            // a CHAR(32) primary key should not produce - must not have a later clean row reinstate a row that was
            // already recorded as unloadable. Fail closed rather than let file order decide.
            if (!rejectedOwners.contains(owner)) {
                loadableOwners.add(owner);
            }
        }

        Set<String> loadableRateKeys = new LinkedHashSet<>();
        Set<String> rejectedRateKeys = new LinkedHashSet<>();

        for (LegacyRateRecord rateRecord : rateRecords) {
            String rateKey = LegacyExportFormat.trimPadding(rateRecord.currnkey());

            // Rule 3 - a NULL rate. This is the condition the legacy program hid: the rate SELECT raised -305, the
            // COMPUTE ran on an uninitialized RATES and the following UPDATE's SQLCODE 0 overwrote the failure
            // before anyone saw it (CASH00.cbl:L215-L231, L249-L264). The row is not staged, so a later C/D replay
            // for that currency is rejected by the target instead of being computed against an invented rate.
            if (rateRecord.rates() == null) {
                record(run, rateKey, VarianceKind.RATE_SOURCE, ReconciliationStatus.VARIANCE,
                        NULL_RATE, null, null, null);
                rejectedRateKeys.add(rateKey);
                loadableRateKeys.remove(rateKey);
                variances++;
                continue;
            }

            // Rule 4 - a NULL currnbase or amount is NOT a finding and gets no row. Both columns are fetched by
            // the rate SELECT (CASH00.cbl:L215, L249) and then referenced by no COMPUTE and no MOVE anywhere in
            // the program, so a null in either changed no balance the reconciliation could be judging. They are
            // staged as NULL for the evidence trail (AAP 0.4.1).
            if (!rejectedRateKeys.contains(rateKey)) {
                loadableRateKeys.add(rateKey);
            }
        }

        return new SourceValidation(Set.copyOf(loadableOwners), Set.copyOf(rejectedOwners),
                Set.copyOf(loadableRateKeys), Set.copyOf(rejectedRateKeys),
                accountRecords.size(), variances);
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
        // which opens the run before calling in, this is a no-op merge.
        runs.save(run);

        // File names come from LegacyExportFormat and are written down nowhere else, so the export's shape keeps
        // one home. CASH_ACCOUNT_FILE is the legacy truth; TARGET_STATE_FILE carries a migrated state a caller
        // loads beforehand and is deliberately never read here - the target side of this comparison is the
        // database, not a file.
        List<LegacyCashAccountRecord> exportedAccounts =
                exportReader.readCashAccounts(inputDirectory.resolve(LegacyExportFormat.CASH_ACCOUNT_FILE));
        List<LegacyRateRecord> exportedRates =
                exportReader.readRates(inputDirectory.resolve(LegacyExportFormat.RATE_TABLE_FILE));

        // First, always: the export's own rows that cannot be applied are findings of this run, and the owners they
        // name are the ones the comparison below must leave alone.
        SourceValidation validation = validateSource(run, exportedAccounts, exportedRates);

        // JOIN ON THE NORMALIZED OWNER KEY, never on sort order and never on ordinal position. EBCDIC and
        // ASCII/UTF-8 collate differently - digits sort after letters in EBCDIC and before them in ASCII (AAP
        // 0.12.2) - so two exports of the same data can arrive in different orders, and an index-wise or
        // sort-wise comparison would report differences that are purely an artifact of the code page.
        Map<String, LegacyCashAccountRecord> legacyByOwner = new LinkedHashMap<>();
        for (LegacyCashAccountRecord record : exportedAccounts) {
            legacyByOwner.put(OwnerNormalizer.normalize(record.owner()), record);
        }
        Map<String, CashAccount> targetByOwner = new LinkedHashMap<>();
        for (CashAccount account : accounts.findAll()) {
            targetByOwner.put(OwnerNormalizer.normalize(account.owner()), account);
        }

        int consideredTargetRows = 0;

        for (Map.Entry<String, LegacyCashAccountRecord> entry : legacyByOwner.entrySet()) {
            String owner = entry.getKey();

            // THE SKIP-REJECTED INVARIANT. An owner validateSource already recorded has been reported once, and
            // the reason it was rejected is also the reason it was never loaded - so comparing it would report its
            // absence from the target a second time, as a MISSING_IN_TARGET row that describes the same single
            // fact. One condition, one row.
            if (validation.rejectedOwners().contains(owner)) {
                continue;
            }

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

            consideredTargetRows++;

            // Guaranteed non-null by the invariant above: validateSource rejects exactly the rows whose balance or
            // currency it could not accept, and those were skipped.
            String legacyCurrency = normalizeCurrency(legacy.currency());
            String targetCurrency = normalizeCurrency(target.currency());
            BigDecimal legacyBalance = legacy.balance();

            // The available balance, not the total: retail balance IS the available balance (AAP 0.6.2), and with
            // no reservation outstanding the two are equal, which is what makes legacy parity exact. An owner with
            // funds on hold is reported by the loader's RESERVATIONS_OUTSTANDING row instead of being compared
            // against a figure the legacy never had a concept of.
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
                recordBalanceDifference(run, owner, legacyCurrency, legacyBalance, targetBalance);
            }

            // A MATCHING OWNER GETS NO ROW AT ALL - not even a MATCHED one. The acceptance criteria are "zero
            // VARIANCE rows" for a matched fixture and "exactly the seeded rows" for a seeded one, asserted over
            // the deterministic set findByRunIdOrderByReconciliationIdAsc returns; a row per agreeing owner would
            // bury the seeded rows in it and make both assertions depend on fixture size. MATCHED is the value an
            // operator sets when reclassifying a reviewed row, not something this comparison writes.
        }

        for (Map.Entry<String, CashAccount> entry : targetByOwner.entrySet()) {
            if (legacyByOwner.containsKey(entry.getKey())) {
                continue;
            }
            CashAccount target = entry.getValue();

            // Only an account the tooling itself produced is reported: if anything other than a load has written
            // to this owner's ledger, its absence from the export is ordinary post-load activity rather than a
            // migration variance.
            if (!producedOnlyByMigration(target.owner())) {
                continue;
            }

            consideredTargetRows++;

            // NEVER DELETED AUTOMATICALLY (AAP 0.6.3): a delta export that omits an owner may equally mean the row
            // was removed upstream or that the export was partial, and only the operator can tell which. The
            // decision is theirs, taken through the retail DELETE endpoint, which writes an ACCOUNT_DELETED ledger
            // event; a reconciler that deleted rows would destroy evidence on the strength of a missing line.
            record(run, entry.getKey(), VarianceKind.STATE, ReconciliationStatus.VARIANCE,
                    MISSING_IN_LEGACY, PRESENT, null, target.availableBalance().amount());
        }

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
        runs.save(run);

        // A variance is a row plus an exit code, never an exception: throwing would abort the transaction and
        // destroy the very findings the run exists to record. Exceptions are reserved for a missing or unreadable
        // export and for database failures, which must abort so the run is recorded FAILED.
        return varianceCount;
    }

    /**
     * Records a balance difference, reclassifying it only where the staged legacy rate proves the exchange rate
     * fully explains it.
     */
    private void recordBalanceDifference(MigrationRun run, String owner, String currency,
                                         BigDecimal legacyBalance, BigDecimal targetBalance) {
        // With the default tool.rate-source=legacy-table nothing is reclassified: both sides were computed from
        // the same staged RATES, so a difference cannot be a rate difference and every one of them is a genuine
        // VARIANCE (AAP 0.12.5). This is why the seeded KARRI difference stays BALANCE/VARIANCE.
        if (LIVE_RATE_SOURCE.equals(rateSource) && rateExplains(run, currency, legacyBalance, targetBalance)) {
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
    // to shadow.ShadowComparator. No fixture expectation depends on this path: the fixtures run with the default
    // legacy-table source, and the staged row is looked up under THIS run's identifier, so the path is reachable
    // only where the rate table was staged by a load sharing the run.
    private boolean rateExplains(MigrationRun run, String currency,
                                 BigDecimal legacyBalance, BigDecimal targetBalance) {
        Optional<LegacyRateTable> staged = legacyRates.findByRunIdAndCurrnkey(run.runId(), rateKey(currency));
        if (staged.isEmpty() || staged.get().rates() == null) {
            return false;
        }
        Money rederived = LegacyBalanceCalculator.credit(BigDecimal.ZERO, staged.get().rates(), targetBalance);
        return rederived.amount().compareTo(legacyBalance) == 0;
    }

    /** Whether every ledger row this owner has was written by the migration tooling, and there is at least one. */
    private boolean producedOnlyByMigration(String owner) {
        boolean sawAnyRow = false;
        for (int page = 0; ; page++) {
            List<LedgerEntry> rows = ledgerEntries.findByOwnerOrderByRecordedAtDescEntryIdDesc(
                    owner, PageRequest.of(page, LEDGER_PAGE_SIZE));
            if (rows.isEmpty()) {
                // An owner with no ledger row at all was not produced by a load - a load always writes its
                // MIGRATION_LOAD event - so it is not reported as a migration variance.
                return sawAnyRow;
            }
            sawAnyRow = true;
            for (LedgerEntry row : rows) {
                if (row.source() != LedgerEntry.Source.MIGRATION) {
                    return false;
                }
            }
            if (rows.size() < LEDGER_PAGE_SIZE) {
                return true;
            }
        }
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
     * Records the row that says a load left an existing owner untouched because funds are on hold.
     *
     * @return the saved row, or {@code null} when no {@code HELD} reservation stands for the owner and there is
     *         therefore nothing to record - which is also the answer a loader needs in order to overwrite
     */
    // The HELD condition is established here, through the repository, rather than trusted from the caller: the row
    // asserts a fact about the database, so the database is what decides it. Returning null instead of raising
    // keeps a hold that was released between the loader's check and this call from aborting a whole load.
    public MigrationReconciliation recordReservationsOutstanding(MigrationRun run, String owner) {
        Objects.requireNonNull(run, "run");
        String normalized = OwnerNormalizer.normalize(owner);
        if (!reservations.existsByOwnerAndState(normalized, ReservationState.HELD)) {
            return null;
        }
        // The retained target balance is rendered so the operator can see what was kept; the legacy figure belongs
        // to the export the loader was applying and is not this row's claim.
        BigDecimal retained = accounts.findByOwner(normalized)
                .map(account -> account.availableBalance().amount())
                .orElse(null);
        return record(run, normalized, VarianceKind.STATE, ReconciliationStatus.VARIANCE,
                PRESENT, RESERVATIONS_OUTSTANDING, null, retained);
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
}
