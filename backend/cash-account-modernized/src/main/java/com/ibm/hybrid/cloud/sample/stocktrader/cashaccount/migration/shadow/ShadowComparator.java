package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.shadow;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.CashAccount;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.Money;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.OwnerNormalizer;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.LegacyExportFormat;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.export.DelimitedExportReader;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.LegacyBalanceCalculator;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.LegacyCharacterization;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.LegacyRateTable;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.MigrationRun;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.ReconciliationService;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.ReconciliationStatus;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.VarianceKind;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.CashAccountRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.LegacyRateTableRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.MigrationReconciliationRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.MigrationRunRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.retail.CashAccountResponse;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.retail.RetailCashAccountService;

/** Replays a captured legacy transaction stream through the service layer and records every difference as a row. */
@Service
public class ShadowComparator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShadowComparator.class);

    // The owner column of migration_reconciliation is NOT NULL, so a capture whose owner the target refuses
    // still needs a grouping key; this is the one used when nothing usable survives stripping.
    private static final String UNUSABLE_OWNER_KEY = "UNUSABLE_OWNER";

    /**
     * The dispatch table of {@code EVALUATE WS-REQ} (CASH00.cbl:L89-L102), which routing needs and a
     * set-membership test cannot supply, cross-checked against {@code LegacyExportFormat.REQUEST_CODES} at
     * class initialization so that a change there cannot silently leave a code unroutable.
     */
    private enum ReplayOperation {
        ADD("A"),
        READ("Q"),
        UPDATE("U"),
        DELETE("X"),
        CREDIT("C"),
        DEBIT("D");

        private final String code;

        ReplayOperation(String code) {
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    private static final Map<String, ReplayOperation> OPERATIONS_BY_CODE = operationsByCode();

    // The closed set of authorized deviations from the legacy behaviour (AAP 0.14.2): a rejection carrying
    // one of these codes is the sanctioned replacement for something the legacy program did silently and
    // records an ACCEPTED_EXCEPTION, while any other code is a VARIANCE for review - so a real target
    // defect can never be signed off as an accepted difference.
    //
    // EXCHANGE_RATE_UNAVAILABLE is deliberately NOT a member, though answering 503 where the legacy committed
    // undefined arithmetic from an uninitialized RATES host variable (CASH00.cbl:L214-L231) is itself an
    // authorized deviation. The distinction is what each side of the comparison says: a captured legacy reply
    // that succeeded with a computed balance means the legacy HAD its rate, so a target that cannot price the
    // same line is reporting its own environment - a missing staging load, an unreachable provider, a currency
    // no staged row carries - and not a characterized difference in behaviour. Accepting it meant the dual-run
    // gate of AAP 0.3.3 Step 2 could close CLEAN on a window in which every cross-currency transaction went
    // unpriced: variance_count counts only VARIANCE rows, so the evidence of the omission raised neither the
    // count nor the exit code. As a VARIANCE the window fails until the rate question is answered, which is the
    // only reading under which "zero VARIANCE rows for N consecutive windows" measures parity. AAP 0.10.3's
    // seeded expectation is unaffected: RAUNAK's over-debit is INSUFFICIENT_FUNDS, which remains a member.
    private static final Set<CashAccountErrorCode> AUTHORIZED_DEVIATIONS = Set.of(
            CashAccountErrorCode.INSUFFICIENT_FUNDS,
            CashAccountErrorCode.AMOUNT_OUT_OF_RANGE,
            CashAccountErrorCode.INVALID_CURRENCY,
            CashAccountErrorCode.INVALID_OWNER,
            CashAccountErrorCode.UNSUPPORTED_PATH,
            CashAccountErrorCode.UNSUPPORTED_METHOD);

    // The rate source in force for a window, and the default application-tool.yml ships: parity can only be judged
    // on identical inputs, so an operator who passes nothing gets the legacy-table gate.
    private static final String RATE_SOURCE_PROPERTY = "tool.rate-source";

    private static final String DEFAULT_RATE_SOURCE = "legacy-table";

    // The replay goes through the service layer and never over HTTP: a window compares business outcomes -
    // the balance an operation leaves and the condition it raises - so driving it through a controller would
    // put serialization and status mapping inside the thing under comparison.
    private final RetailCashAccountService retailService;

    private final ReconciliationService reconciliationService;

    private final MigrationReconciliationRepository reconciliations;

    private final MigrationRunRepository runs;

    private final LegacyRateTableRepository legacyRates;

    private final CashAccountRepository accounts;

    // Held as inert text and canonicalized on use, never in the constructor: an unusable tool.rate-source must
    // reach an operator as MigrationToolRunner's one-line argument error, and resolving it during bean creation
    // made that same message the innermost Caused-by of a 90-line refresh failure instead. Canonicalization
    // still happens exactly once per window, through the one policy the delegate pricing the replay also reads,
    // so a live-priced window can never be classified as the legacy-table parity gate.
    private final String configuredRateSource;

    // Constructed, not injected: DelimitedExportReader carries no Spring stereotype, holds no state and is
    // thread-safe.
    private final DelimitedExportReader exportReader;

    /**
     * Container constructor.
     *
     * @param retailService         the service layer a window's transactions are replayed through
     * @param reconciliationService the shared classification the findings are written with
     * @param reconciliations       {@code migration_reconciliation} rows
     * @param runs                  {@code migration_run} rows
     * @param legacyRates           the staged rate rows a legacy expected balance is priced from
     * @param accounts              the target account rows a replayed balance is read back from
     * @param environment           source of {@code tool.rate-source}, read through {@link Binder}
     */
    // Binder, never a @Value placeholder: a placeholder's RESOLVED TEXT is then handed to Spring's expression
    // resolver, so a rate source written as #{...} would execute while this comparator was being created. Binder
    // resolves ${...} and converts, evaluating nothing, so an unusable value is refused by RateSource.of below.
    public ShadowComparator(RetailCashAccountService retailService,
                            ReconciliationService reconciliationService,
                            MigrationReconciliationRepository reconciliations,
                            MigrationRunRepository runs,
                            LegacyRateTableRepository legacyRates,
                            CashAccountRepository accounts,
                            Environment environment) {
        this.retailService = Objects.requireNonNull(retailService, "retailService");
        this.reconciliationService = Objects.requireNonNull(reconciliationService, "reconciliationService");
        this.reconciliations = Objects.requireNonNull(reconciliations, "reconciliations");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.legacyRates = Objects.requireNonNull(legacyRates, "legacyRates");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.configuredRateSource = rateSourceFrom(environment);
        this.exportReader = new DelimitedExportReader();
    }

    // A non-configurable Environment exposes no property sources, so it takes the default an unset key would give -
    // legacy-table, the parity gate application-tool.yml documents.
    private static String rateSourceFrom(Environment environment) {
        if (!(environment instanceof ConfigurableEnvironment)) {
            return DEFAULT_RATE_SOURCE;
        }
        return Binder.get(environment)
                .bind(RATE_SOURCE_PROPERTY, Bindable.of(String.class))
                .orElse(DEFAULT_RATE_SOURCE);
    }

    /**
     * Compares one shadow window read from a directory holding the two delimited captures named by
     * {@link LegacyExportFormat#SHADOW_TRANSACTIONS_FILE} and
     * {@link LegacyExportFormat#SHADOW_LEGACY_RESPONSES_FILE}.
     *
     * @param run            the {@code SHADOW} run this window's findings belong to, already persisted by its
     *                       caller - {@code migration_reconciliation.run_id} references {@code migration_run}
     * @param inputDirectory the directory holding the captured window
     * @return the number of {@code VARIANCE} rows this window persisted under {@code run}
     * @throws RuntimeException when a capture is missing, unreadable or breaks the input contract; a malformed
     *                          capture is an input error, never a variance
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int compare(MigrationRun run, Path inputDirectory) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(inputDirectory, "inputDirectory");

        // Resolved through LegacyExportFormat rather than by Path.resolve, and opened relative to one
        // approval taken here, so a captured window is two ordinary files inside the directory that was
        // validated and can never be a symbolic link - nor a file in some other directory the name leads to
        // by the time it is read. A link here would be replayed against live target state and its content
        // echoed into the window's findings (AAP 0.3.2).
        LegacyExportFormat.ApprovedDirectory approved =
                LegacyExportFormat.approveInputDirectory(inputDirectory);
        LegacyExportFormat.resolveInputFile(inputDirectory, LegacyExportFormat.SHADOW_TRANSACTIONS_FILE);
        LegacyExportFormat.resolveInputFile(inputDirectory, LegacyExportFormat.SHADOW_LEGACY_RESPONSES_FILE);
        LegacyExportFormat.ExportFile transactionsFile = LegacyExportFormat.ExportFile
                .inApprovedDirectory(approved, LegacyExportFormat.SHADOW_TRANSACTIONS_FILE);
        LegacyExportFormat.ExportFile responsesFile = LegacyExportFormat.ExportFile
                .inApprovedDirectory(approved, LegacyExportFormat.SHADOW_LEGACY_RESPONSES_FILE);

        return compare(run, readTransactions(transactionsFile), readLegacyResponses(responsesFile));
    }

    /**
     * Compares one shadow window already in memory: the core of the dual-run mechanism, which sets the three
     * count fields of {@code run} while leaving its verdict and {@code finishedAt} to the runner that opened
     * the row.
     *
     * @param run             the {@code SHADOW} run this window's findings belong to, already persisted
     * @param transactions    the captured requests, in any order; replayed in ascending sequence number
     * @param legacyResponses the captured replies, in any order; joined by sequence number and owner
     * @return the number of {@code VARIANCE} rows this window persisted under {@code run}
     * @throws IllegalArgumentException when a sequence number repeats within a capture, or when the two
     *                                  captures disagree about the owner at one sequence number
     */
    // Not one transaction for the window: the replayed service methods are transactional and their
    // rejections are unchecked, so a single outer transaction would be marked rollback-only by the first
    // deliberate rejection and die at commit, destroying the evidence gathered. NOT_SUPPORTED suspends any
    // inherited transaction, so each replay step and each evidence row commits on its own.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int compare(MigrationRun run,
                       List<ShadowTransaction> transactions,
                       List<ShadowLegacyResponse> legacyResponses) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(transactions, "transactions");
        Objects.requireNonNull(legacyResponses, "legacyResponses");

        // Canonicalized before a single line is replayed, so an unusable tool.rate-source still stops the window
        // with the accepted tokens named and nothing applied - the fail-closed backstop for a caller that did
        // not pass MigrationToolRunner's validation, which rejects the value before any command runs.
        MigrationRun.RateSource rateSource = MigrationRun.RateSource.of(configuredRateSource);

        // Joined on the sequence number plus the normalized owner, never on file order or ordinal position:
        // EBCDIC and UTF-8 collate differently, so two exports of one window can arrive in different orders
        // and a position-wise pairing would compare unrelated lines. A TreeMap because the capture is a time
        // series: replaying it out of order produces different balances, so ascending order is correctness.
        Map<Long, ShadowTransaction> transactionsBySeq = indexTransactions(transactions);
        Map<Long, ShadowLegacyResponse> responsesBySeq = indexLegacyResponses(legacyResponses);
        requireOwnersAgree(transactionsBySeq, responsesBySeq);

        LOGGER.info("Shadow window {}: replaying {} captured transactions against {} captured replies",
                run.runId(), transactionsBySeq.size(), responsesBySeq.size());

        // The counts are recorded as they become true, not at the end: every evidence row below commits on
        // its own, so a window that dies part-way leaves its findings in the table while this instance is
        // the only statement of how far the replay got. Set before the lookup below, which queries the
        // database and can fail: a window that has joined its whole capture must not read as one that
        // read nothing.
        run.setLegacyRecordCount(responsesBySeq.size());

        // Resolved once, and only where it can be used: the staged rate table is read exclusively by the
        // live-mode explanation below, so the default parity gate spends no query on it, and a shared
        // @Service carries no mutable per-invocation cache.
        UUID stagingLoadRunId = rateSource.isLive() ? latestLoadRunId(run.batchId()) : null;

        int replayed = 0;
        for (Map.Entry<Long, ShadowTransaction> entry : transactionsBySeq.entrySet()) {
            // Raised before the line is taken up, because the replay and the row recording its outcome
            // commit separately: a line whose operation committed and whose evidence insert then failed has
            // really happened. Attempted, not accepted - a refusal is its own row, not a shortfall here.
            run.setMigratedRecordCount(++replayed);
            replayAndClassify(run, entry.getKey(), entry.getValue(),
                    responsesBySeq.get(entry.getKey()), stagingLoadRunId);
        }

        recordTransactionCounts(run, transactionsBySeq, responsesBySeq);

        // Read back from the rows rather than accumulated locally, so the count the runner turns into an
        // exit code is the count that actually reached the database. Only VARIANCE is counted: an
        // ACCEPTED_EXCEPTION records an authorized difference and must not change the exit code.
        int varianceCount = Math.toIntExact(
                reconciliations.countByRunIdAndStatus(run.runId(), ReconciliationStatus.VARIANCE));

        // Three fields of the run and nothing else, and the row is deliberately not saved here: the runner
        // that opened it is its single closer, and a second writer would let the row's status and its
        // findings disagree.
        run.setVarianceCount(varianceCount);

        LOGGER.info("Shadow window {}: {} variance rows persisted", run.runId(), varianceCount);
        return varianceCount;
    }

    // Replays one captured line and records at most one row for it: an agreeing line gets no row at all,
    // not even a MATCHED one, because the acceptance criteria are "zero variance rows" and "exactly the
    // seeded rows", which a row per agreeing line would tie to the window's size.
    private void replayAndClassify(MigrationRun run,
                                   long seq,
                                   ShadowTransaction transaction,
                                   ShadowLegacyResponse legacyResponse,
                                   UUID stagingLoadRunId) {
        String owner = joinKey(transaction.owner());
        // Null is tested rather than handed to the map, which rejects a null key: a capture line carrying no
        // request code is an unrecognized code to classify, never a failure that ends the window.
        String requestCode = LegacyExportFormat.trimPadding(transaction.req());
        ReplayOperation operation = requestCode == null ? null : OPERATIONS_BY_CODE.get(requestCode);

        if (operation == null) {
            // Fail closed on an unrecognized code, without calling the service at all: the legacy EVALUATE
            // had no WHEN OTHER (CASH00.cbl:L89-L102), so such a code executed no SQL and still echoed a
            // success-looking reply. The target has no such path, which is an authorized deviation, so the
            // row is an accepted exception naming the status the target would answer with.
            reconciliationService.record(run, owner, VarianceKind.REJECTED_BY_TARGET,
                    ReconciliationStatus.ACCEPTED_EXCEPTION,
                    legacySideText(legacyResponse), CashAccountErrorCode.UNSUPPORTED_PATH.name(),
                    capturedBalance(legacyResponse), null);
            return;
        }

        // Read before the replay because it is the pre-operation state the legacy arithmetic was applied to,
        // and only where the live-mode explanation can use it: in the default parity gate no query is spent.
        CashAccount before = stagingLoadRunId != null && isRateScaled(operation)
                ? accounts.findByOwner(owner).orElse(null)
                : null;

        CashAccountResponse response;
        // The try brackets the replay step alone, never the classification of its outcome: a defect in the
        // rules below is a programming error that must surface, not a comparison result to be recorded.
        try {
            // The normalization sits inside the try because an owner the target refuses raises exactly the
            // INVALID_OWNER the service would raise, so that rejection is classified rather than lost.
            response = dispatch(operation, OwnerNormalizer.normalize(transaction.owner()), transaction);
        } catch (CashAccountException rejected) {
            classifyTargetRejection(run, owner, legacyResponse, rejected);
            return;
        } catch (RuntimeException unexpected) {
            // A row, not an aborted window: one bad step must not cost a window its evidence, and an
            // unexplained failure is the kind of divergence a dual-run exists to surface. Error is
            // deliberately not caught - a JVM-level failure is not a comparison outcome.
            LOGGER.warn("Shadow window {}: capture line {} failed unexpectedly", run.runId(), seq, unexpected);
            reconciliationService.record(run, owner, VarianceKind.REJECTED_BY_TARGET,
                    ReconciliationStatus.VARIANCE,
                    legacySideText(legacyResponse), CashAccountErrorCode.INTERNAL.name(),
                    capturedBalance(legacyResponse), null);
            return;
        }

        classifyTargetSuccess(run, owner, operation, transaction, legacyResponse, response,
                before, stagingLoadRunId);
    }

    // The captured amount means two different things: on A and U it is the absolute balance the caller
    // supplied, which the legacy INSERT and UPDATE bound straight into the row (CASH00.cbl:L155, L176-L177),
    // while on C and D it is the delta the stored rate multiplies (CASH00.cbl:L221-L222, L255-L256). Reading
    // one as the other reproduces a number the legacy never computed. The currency passes through as
    // captured, null included, so the service applies its own default and a currency-less capture stays visible.
    private CashAccountResponse dispatch(ReplayOperation operation, String owner, ShadowTransaction transaction) {
        return switch (operation) {
            case READ -> retailService.read(owner);
            case ADD -> retailService.create(owner, transaction.amount(), transaction.currency());
            case UPDATE -> retailService.update(owner, transaction.amount(), transaction.currency());
            case DELETE -> retailService.delete(owner);
            case CREDIT -> retailService.credit(owner, transaction.amount());
            case DEBIT -> retailService.debit(owner, transaction.amount());
        };
    }

    /** Classifies a capture line the target processed successfully. */
    private void classifyTargetSuccess(MigrationRun run,
                                       String owner,
                                       ReplayOperation operation,
                                       ShadowTransaction transaction,
                                       ShadowLegacyResponse legacyResponse,
                                       CashAccountResponse response,
                                       CashAccount before,
                                       UUID stagingLoadRunId) {
        // No per-line row where the legacy reply failed and the target succeeded: the sign-dropped retcode
        // carries only the last statement's code (CASH00.cbl:L104), so the exact legacy condition is
        // unknowable and a per-line verdict would be invented. The asymmetry surfaces in the per-owner count.
        if (!isLegacySuccess(legacyResponse)) {
            return;
        }

        BigDecimal capturedBalance = capturedBalance(legacyResponse);
        if (capturedBalance == null) {
            // A success reply carrying no balance leaves nothing to compare, so the line fails closed rather
            // than being skipped silently. The value arguments are null as on every BALANCE row, because
            // MigrationReconciliation.balance renders them and names the absent side ABSENT_IN_CAPTURE.
            reconciliationService.record(run, owner, VarianceKind.BALANCE, ReconciliationStatus.VARIANCE,
                    null, null, null, response.balance());
            return;
        }

        BigDecimal targetBalance = response.balance();
        // compareTo, never equals: 1250.5 and 1250.50 are the same money and differ only in scale, which
        // BigDecimal.equals would report as a difference an operator then has to triage.
        if (capturedBalance.compareTo(targetBalance) == 0) {
            return;
        }

        if (stagingLoadRunId != null
                && isRateScaled(operation)
                && rateDifferenceExplains(stagingLoadRunId, operation, transaction, before, capturedBalance)) {
            // Live mode only: re-deriving the step with the staged legacy rate reproduces the captured
            // balance exactly, so the whole difference is attributable to the rate the target priced with
            // and none of it to the ledger - an accepted exception rather than a parity failure.
            reconciliationService.record(run, owner, VarianceKind.RATE_SOURCE,
                    ReconciliationStatus.ACCEPTED_EXCEPTION,
                    capturedBalance.toPlainString(), targetBalance.toPlainString(),
                    capturedBalance, targetBalance);
            return;
        }

        // The parity gate's verdict, and the fallback for a live-mode difference the rate cannot explain.
        reconciliationService.record(run, owner, VarianceKind.BALANCE, ReconciliationStatus.VARIANCE,
                null, null, capturedBalance, targetBalance);
    }

    /** Classifies a capture line the target refused with an explicit business condition. */
    private void classifyTargetRejection(MigrationRun run,
                                         String owner,
                                         ShadowLegacyResponse legacyResponse,
                                         CashAccountException rejected) {
        // Both sides refused is agreement and gets no row: the sign-dropped retcode cannot be compared with
        // the target's condition, so "both refused" is the strongest statement the evidence supports.
        if (!isLegacySuccess(legacyResponse)) {
            return;
        }

        CashAccountErrorCode code = rejected.errorCode();
        ReconciliationStatus status = AUTHORIZED_DEVIATIONS.contains(code)
                ? ReconciliationStatus.ACCEPTED_EXCEPTION
                : ReconciliationStatus.VARIANCE;

        // The constant's name is the wire code the target answers with, so the row carries the same token
        // an operator sees in an ApiError payload.
        reconciliationService.record(run, owner, VarianceKind.REJECTED_BY_TARGET, status,
                legacySideText(legacyResponse), code.name(), capturedBalance(legacyResponse), null);
    }

    // The per-owner count comparison, which only a shadow window can make: after a bulk load the target
    // holds one MIGRATION_LOAD row per account and no per-transaction history, so reconcile mode has no
    // target count to compare against and never writes this kind.
    private void recordTransactionCounts(MigrationRun run,
                                         Map<Long, ShadowTransaction> transactionsBySeq,
                                         Map<Long, ShadowLegacyResponse> responsesBySeq) {
        Map<String, Integer> legacyCounts = new TreeMap<>();
        for (ShadowLegacyResponse legacyResponse : responsesBySeq.values()) {
            if (!isLegacySuccess(legacyResponse)) {
                continue;
            }
            ShadowTransaction paired = transactionsBySeq.get(legacyResponse.seq());
            // Q is excluded because a read changed no state, so there is nothing on the target side to count
            // it against. An unpaired success is counted: it is evidence of a legacy state change the target
            // never saw, which is the whole point of the count.
            if (paired != null && !isCounted(paired.req())) {
                continue;
            }
            increment(legacyCounts, joinKey(legacyResponse.owner()));
        }

        Map<String, Integer> targetCounts = new TreeMap<>();
        for (ShadowTransaction transaction : transactionsBySeq.values()) {
            // Attempted, not accepted: a line the target refused already carries its own REJECTED_BY_TARGET
            // row, so counting it as a shortfall here would report one divergence twice.
            if (isCounted(transaction.req())) {
                increment(targetCounts, joinKey(transaction.owner()));
            }
        }

        // Sorted owners, so the row set a test asserts over is reproducible rather than dependent on hashing.
        Set<String> owners = new TreeSet<>(legacyCounts.keySet());
        owners.addAll(targetCounts.keySet());

        for (String owner : owners) {
            int legacyCount = legacyCounts.getOrDefault(owner, 0);
            int targetCount = targetCounts.getOrDefault(owner, 0);
            if (legacyCount == targetCount) {
                continue;
            }

            // The asymmetry is the legacy's, not a preference: IGNORE CONDITION DUPREC (CASH00.cbl:L124)
            // discarded a second history record whose 29-byte key already existed, and that key's only time
            // component is a whole second, so legacy counts are a lower bound. A target excess is therefore
            // expected and accepted; a target shortfall cannot be explained that way and is a real loss.
            ReconciliationStatus status = targetCount > legacyCount
                    ? ReconciliationStatus.ACCEPTED_EXCEPTION
                    : ReconciliationStatus.VARIANCE;

            // The balance columns and the variance stay null: a count is not money, and a 0.00 variance on a
            // count row would read as monetary agreement.
            reconciliationService.record(run, owner, VarianceKind.TRANSACTION_COUNT, status,
                    Integer.toString(legacyCount), Integer.toString(targetCount), null, null);
        }
    }

    // Deliberately narrow: only a difference the staged RATES re-derives to the cent is attributed to the
    // exchange rate, because accepting one that no arithmetic reproduces would sign off a defect. A missing
    // or null staged rate therefore explains nothing away.
    private boolean rateDifferenceExplains(UUID stagingLoadRunId,
                                           ReplayOperation operation,
                                           ShadowTransaction transaction,
                                           CashAccount before,
                                           BigDecimal capturedBalance) {
        if (before == null || transaction.amount() == null) {
            return false;
        }
        BigDecimal stagedRate = legacyRates
                .findByRunIdAndCurrnkey(stagingLoadRunId, rateKey(before.currency()))
                .map(LegacyRateTable::rates)
                .orElse(null);
        if (stagedRate == null) {
            return false;
        }

        BigDecimal storedBefore = before.availableBalance().amount();
        Money expectedLegacy = operation == ReplayOperation.CREDIT
                ? LegacyBalanceCalculator.credit(storedBefore, stagedRate, transaction.amount())
                : LegacyBalanceCalculator.debit(storedBefore, stagedRate, transaction.amount());
        return capturedBalance.compareTo(expectedLegacy.amount()) == 0;
    }

    // The staged rate table's owning run, resolved from the batch rather than from this run: the table was
    // staged by a LOAD invocation, and a shadow window is a different run_id. MigrationRunRepository's shared
    // selectors admit only CLEAN and VARIANCE, so a window, a reconcile of the same batch and
    // fx/LegacyRateTableSource price against one run and a newer RUNNING attempt that staged nothing cannot
    // stand in for it. Batch first, then the schema's most recent completed load - the same two-step rule
    // fx/LegacyRateTableSource applies to the replay itself, and for the same reason: a window carries its own
    // --tool.batch-id (AAP 0.3.3 Step 2), so its batch holds no load and the rows it must explain against were
    // staged under another one. A reconcile deliberately does NOT take that second step: it judges the load it
    // names through the shared batch id (AAP 0.6.3), where a window names none. null rather than a throw when
    // the schema holds no completed load at all, so the window reports the difference as an outstanding
    // VARIANCE instead of absorbing it.
    private UUID latestLoadRunId(UUID batchId) {
        Optional<MigrationRun> inBatch = batchId == null
                ? Optional.empty()
                : runs.findLatestCompletedLoad(batchId);
        return inBatch.or(runs::findLatestCompletedLoadInSchema)
                .map(MigrationRun::runId)
                .orElse(null);
    }

    private List<ShadowTransaction> readTransactions(LegacyExportFormat.ExportFile file) {
        List<DelimitedExportReader.DelimitedRow> rows =
                exportReader.readRows(file, LegacyExportFormat.SHADOW_TRANSACTION_COLUMNS);
        List<ShadowTransaction> transactions = new ArrayList<>(rows.size());
        for (DelimitedExportReader.DelimitedRow row : rows) {
            // Values reach the carrier exactly as captured - unnormalized owner, un-uppercased request code,
            // unrescaled amount - because every judgement about them belongs to the classification below or
            // to the service layer.
            transactions.add(new ShadowTransaction(
                    row.requireLong(LegacyExportFormat.SHADOW_TRANSACTION_SEQ_COLUMN),
                    row.requireText(LegacyExportFormat.SHADOW_TRANSACTION_OWNER_COLUMN),
                    row.requireText(LegacyExportFormat.SHADOW_TRANSACTION_REQUEST_CODE_COLUMN),
                    row.decimal(LegacyExportFormat.SHADOW_TRANSACTION_AMOUNT_COLUMN),
                    row.text(LegacyExportFormat.SHADOW_TRANSACTION_CURRENCY_COLUMN)));
        }
        return List.copyOf(transactions);
    }

    private List<ShadowLegacyResponse> readLegacyResponses(LegacyExportFormat.ExportFile file) {
        List<DelimitedExportReader.DelimitedRow> rows =
                exportReader.readRows(file, LegacyExportFormat.SHADOW_LEGACY_RESPONSE_COLUMNS);
        List<ShadowLegacyResponse> responses = new ArrayList<>(rows.size());
        for (DelimitedExportReader.DelimitedRow row : rows) {
            responses.add(new ShadowLegacyResponse(
                    row.requireLong(LegacyExportFormat.SHADOW_RESPONSE_SEQ_COLUMN),
                    row.requireText(LegacyExportFormat.SHADOW_RESPONSE_OWNER_COLUMN),
                    row.text(LegacyExportFormat.SHADOW_RESPONSE_RETCODE_COLUMN),
                    row.decimal(LegacyExportFormat.SHADOW_RESPONSE_BALANCE_COLUMN)));
        }
        return List.copyOf(responses);
    }

    private static Map<Long, ShadowTransaction> indexTransactions(List<ShadowTransaction> transactions) {
        Map<Long, ShadowTransaction> bySeq = new TreeMap<>();
        for (ShadowTransaction transaction : transactions) {
            Objects.requireNonNull(transaction, "a captured transaction must not be null");
            ShadowTransaction previous = bySeq.put(transaction.seq(), transaction);
            if (previous != null) {
                throw duplicateSequence(LegacyExportFormat.SHADOW_TRANSACTIONS_FILE, transaction.seq());
            }
        }
        return bySeq;
    }

    private static Map<Long, ShadowLegacyResponse> indexLegacyResponses(List<ShadowLegacyResponse> responses) {
        Map<Long, ShadowLegacyResponse> bySeq = new LinkedHashMap<>();
        for (ShadowLegacyResponse response : responses) {
            Objects.requireNonNull(response, "a captured legacy response must not be null");
            ShadowLegacyResponse previous = bySeq.put(response.seq(), response);
            if (previous != null) {
                throw duplicateSequence(LegacyExportFormat.SHADOW_LEGACY_RESPONSES_FILE, response.seq());
            }
        }
        return bySeq;
    }

    // A malformed capture is an input error, not a variance: a repeated sequence number makes the join
    // ambiguous, and a row written from an ambiguous pairing would be evidence of nothing.
    private static IllegalArgumentException duplicateSequence(String capture, long seq) {
        return new IllegalArgumentException("The capture " + capture + " repeats "
                + LegacyExportFormat.SHADOW_TRANSACTION_SEQ_COLUMN + " " + seq
                + ", so the two captures cannot be joined on it");
    }

    private static void requireOwnersAgree(Map<Long, ShadowTransaction> transactionsBySeq,
                                           Map<Long, ShadowLegacyResponse> responsesBySeq) {
        for (Map.Entry<Long, ShadowLegacyResponse> entry : responsesBySeq.entrySet()) {
            ShadowTransaction paired = transactionsBySeq.get(entry.getKey());
            if (paired == null) {
                continue;
            }
            String transactionOwner = joinKey(paired.owner());
            String responseOwner = joinKey(entry.getValue().owner());
            if (!transactionOwner.equals(responseOwner)) {
                // The two captures describe the same window, so a disagreement about whose account a line
                // touched means one of them is misaligned; comparing them anyway would attribute a balance to
                // the wrong owner.
                throw new IllegalArgumentException("At "
                        + LegacyExportFormat.SHADOW_TRANSACTION_SEQ_COLUMN + " " + entry.getKey() + ", "
                        + LegacyExportFormat.SHADOW_TRANSACTIONS_FILE + " names owner " + transactionOwner
                        + " while " + LegacyExportFormat.SHADOW_LEGACY_RESPONSES_FILE + " names owner "
                        + responseOwner);
            }
        }
    }

    private static boolean isLegacySuccess(ShadowLegacyResponse legacyResponse) {
        return legacyResponse != null && LegacyExportFormat.isSuccessRetcode(legacyResponse.retcode());
    }

    private static BigDecimal capturedBalance(ShadowLegacyResponse legacyResponse) {
        return legacyResponse == null ? null : legacyResponse.balance();
    }

    // The reply's balance where it had one, and otherwise the raw return code, which is the only other
    // thing the reply stated.
    private static String legacySideText(ShadowLegacyResponse legacyResponse) {
        if (legacyResponse == null) {
            return null;
        }
        return legacyResponse.balance() != null
                ? legacyResponse.balance().toPlainString()
                : LegacyExportFormat.trimPadding(legacyResponse.retcode());
    }

    // Null-guarded like the dispatch lookup: a capture line without a request code counts as no state
    // change on either side.
    private static boolean isCounted(String requestCode) {
        String trimmed = LegacyExportFormat.trimPadding(requestCode);
        return trimmed != null && LegacyExportFormat.COUNTED_REQUEST_CODES.contains(trimmed);
    }

    private static boolean isRateScaled(ReplayOperation operation) {
        return operation == ReplayOperation.CREDIT || operation == ReplayOperation.DEBIT;
    }

    // The legacy join compared only the first five characters of the account's currency, the width of
    // WS-CURRENCY-KEY that CURRENCYC was moved into (CASH00.cbl:L19, L213 credit, L247 debit); the constant
    // comes from LegacyCharacterization so the characterized parameters keep one declaration point.
    private static String rateKey(String currency) {
        if (currency == null) {
            return "";
        }
        return currency.length() <= LegacyCharacterization.RATE_KEY_LENGTH
                ? currency
                : currency.substring(0, LegacyCharacterization.RATE_KEY_LENGTH);
    }

    // The grouping key for one captured owner, which never throws: OwnerNormalizer is the authority wherever
    // it succeeds, but it rejects a blank or over-long owner, and the row's owner column is NOT NULL - so a
    // capture the target refuses still needs a deterministic key. The fallback folds as the normalizer would
    // and then cuts to the stored width, which is how the legacy interface itself lost long owners
    // (CASH00.cbl:L55).
    private static String joinKey(String rawOwner) {
        try {
            return OwnerNormalizer.normalize(rawOwner);
        } catch (CashAccountException unusable) {
            if (rawOwner == null) {
                return UNUSABLE_OWNER_KEY;
            }
            String folded = rawOwner.strip().toUpperCase(Locale.ROOT);
            if (folded.isEmpty()) {
                return UNUSABLE_OWNER_KEY;
            }
            // Counted and cut in code points, the unit OwnerNormalizer.MAX_LENGTH is stated in: a UTF-16 cut
            // could split a supplementary character and leave an unpaired surrogate as the key's last unit.
            return folded.codePointCount(0, folded.length()) <= OwnerNormalizer.MAX_LENGTH
                    ? folded
                    : folded.substring(0, folded.offsetByCodePoints(0, OwnerNormalizer.MAX_LENGTH));
        }
    }

    private static void increment(Map<String, Integer> counts, String owner) {
        counts.merge(owner, 1, Integer::sum);
    }

    private static Map<String, ReplayOperation> operationsByCode() {
        Map<String, ReplayOperation> byCode = new LinkedHashMap<>();
        for (ReplayOperation operation : ReplayOperation.values()) {
            byCode.put(operation.code(), operation);
        }
        // Keeps LegacyExportFormat the single authority on the accepted codes: if the set there changes,
        // this class fails to initialize rather than leaving a code unroutable or routing one the legacy
        // never recognized.
        if (!byCode.keySet().equals(LegacyExportFormat.REQUEST_CODES)) {
            throw new IllegalStateException("The replay dispatch table covers " + byCode.keySet()
                    + " but LegacyExportFormat.REQUEST_CODES declares " + LegacyExportFormat.REQUEST_CODES);
        }
        return Map.copyOf(byCode);
    }
}
