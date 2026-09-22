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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.shadow;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

/*
 * NEVER POINTED AT THE LIVE LEGACY SYSTEM (AAP 0.3.2). This class reads two ordinary delimited files and
 * replays them into whatever database the operator configured; it opens no socket, speaks to no CICS
 * region, names no DB2 table and reads no VSAM data set. Its proof is src/test/resources/fixtures/shadow
 * only. A live dual-run needs capture infrastructure, sign-off and a rollback plan that belongs to
 * docs/operational-runbook.md step 2, which this deliverable documents and does not execute.
 *
 * THE REPLAY GOES THROUGH THE SERVICE LAYER, NEVER OVER HTTP. What a shadow window compares is business
 * outcomes - the balance an operation leaves behind and the condition it raises - not transport. Going in
 * through the retail web endpoint, a servlet test harness or any HTTP client would put serialization and
 * status mapping inside the thing under comparison, so a divergence in either of them would read as a
 * ledger divergence. Nothing in this package may depend on a controller in any case (AAP 0.8.2).
 *
 * NO @Profile, mirroring reconcile/ReconciliationService's recorded decision, so that
 * migration.MigrationToolRunner (which carries @Profile("tool")) and ShadowComparatorIT can both inject it
 * whichever profile they run under. Leaving it un-profiled is inert in the deployed web context: no request
 * mapping, no @Scheduled work, no constructor side effect, and nothing on the request path injects it.
 */
/** Replays a captured legacy transaction stream through the service layer and records every difference as a row. */
@Service
public class ShadowComparator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShadowComparator.class);

    // The one value of tool.rate-source that opens the RATE_SOURCE reclassification path; the default,
    // legacy-table, is the parity gate, where both sides were priced from the same staged RATES and a
    // difference therefore cannot be a rate difference (AAP 0.12.5).
    private static final String LIVE_RATE_SOURCE = "live";

    // The legacy-side reason token for a success reply that carried no balance to compare against.
    private static final String ABSENT_IN_CAPTURE = "ABSENT_IN_CAPTURE";

    // The owner column of migration_reconciliation is NOT NULL, so a capture whose owner the target refuses
    // still needs a grouping key; this is the one used when nothing usable survives stripping.
    private static final String UNUSABLE_OWNER_KEY = "UNUSABLE_OWNER";

    /*
     * The dispatch table of EVALUATE WS-REQ (CASH00.cbl:L89-L102), which is the one thing a set-membership
     * test cannot supply: classifying a code needs LegacyExportFormat.REQUEST_CODES, but ROUTING one needs
     * code identity, and LegacyExportFormat declares the accepted set without declaring its members
     * individually. Rather than restate the six codes as a second, free-to-diverge authority, the enum is
     * cross-checked against that set at class initialization (see operationsByCode), so a change there
     * fails this class loudly instead of silently leaving a code unroutable.
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

    /*
     * The closed table of AAP 0.4.6 / 0.14.2: the deliberate behavioural improvements the user authorized,
     * and nothing else. A target rejection listed here is the authorized replacement for a legacy behaviour
     * and is recorded as an ACCEPTED_EXCEPTION; any other code has no authorization behind it and is a
     * VARIANCE for review, so a real target defect can never be signed off as an accepted difference.
     *
     *   INSUFFICIENT_FUNDS         unsigned WS-CALC stored the absolute value (CASH00.cbl:L17, L256)
     *   AMOUNT_OUT_OF_RANGE        high-order digits dropped, no ON SIZE ERROR (CASH00.cbl:L222, L256)
     *   EXCHANGE_RATE_UNAVAILABLE  a missing rate row committed undefined arithmetic under SQLCODE 0
     *                              (CASH00.cbl:L214-L231)
     *   INVALID_CURRENCY           nullable, blank-padded CHAR(8) currency accepted (CASH00.cbl:L57)
     *   INVALID_OWNER              owner silently truncated to 15 characters (CASH00.cbl:L55)
     *   UNSUPPORTED_PATH/METHOD    the catch-all-free EVALUATE fell through as success (CASH00.cbl:L89-L102)
     */
    private static final Set<CashAccountErrorCode> AUTHORIZED_DEVIATIONS = Set.of(
            CashAccountErrorCode.INSUFFICIENT_FUNDS,
            CashAccountErrorCode.AMOUNT_OUT_OF_RANGE,
            CashAccountErrorCode.EXCHANGE_RATE_UNAVAILABLE,
            CashAccountErrorCode.INVALID_CURRENCY,
            CashAccountErrorCode.INVALID_OWNER,
            CashAccountErrorCode.UNSUPPORTED_PATH,
            CashAccountErrorCode.UNSUPPORTED_METHOD);

    private final RetailCashAccountService retailService;

    private final ReconciliationService reconciliationService;

    private final MigrationReconciliationRepository reconciliations;

    private final MigrationRunRepository runs;

    private final LegacyRateTableRepository legacyRates;

    private final CashAccountRepository accounts;

    private final String rateSource;

    // Constructed, not injected: DelimitedExportReader carries no Spring stereotype, holds no state and is
    // thread-safe, so a bean definition would add a wiring dependency that buys nothing - and declaring one
    // would mean reaching into config, which this package may not depend on (AAP 0.8.2).
    private final DelimitedExportReader exportReader;

    public ShadowComparator(RetailCashAccountService retailService,
                            ReconciliationService reconciliationService,
                            MigrationReconciliationRepository reconciliations,
                            MigrationRunRepository runs,
                            LegacyRateTableRepository legacyRates,
                            CashAccountRepository accounts,
                            @Value("${tool.rate-source:legacy-table}") String rateSource) {
        this.retailService = Objects.requireNonNull(retailService, "retailService");
        this.reconciliationService = Objects.requireNonNull(reconciliationService, "reconciliationService");
        this.reconciliations = Objects.requireNonNull(reconciliations, "reconciliations");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.legacyRates = Objects.requireNonNull(legacyRates, "legacyRates");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.rateSource = Objects.requireNonNull(rateSource, "rateSource");
        this.exportReader = new DelimitedExportReader();
    }

    /**
     * Compares one shadow window read from a directory of captured files.
     *
     * <p>The directory must hold the two delimited captures named by
     * {@link LegacyExportFormat#SHADOW_TRANSACTIONS_FILE} and
     * {@link LegacyExportFormat#SHADOW_LEGACY_RESPONSES_FILE}, in the column shapes
     * {@link LegacyExportFormat#SHADOW_TRANSACTION_COLUMNS} and
     * {@link LegacyExportFormat#SHADOW_LEGACY_RESPONSE_COLUMNS} declare.</p>
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

        Path transactionsFile = inputDirectory.resolve(LegacyExportFormat.SHADOW_TRANSACTIONS_FILE);
        Path responsesFile = inputDirectory.resolve(LegacyExportFormat.SHADOW_LEGACY_RESPONSES_FILE);

        return compare(run, readTransactions(transactionsFile), readLegacyResponses(responsesFile));
    }

    /**
     * Compares one shadow window already in memory: the core of the dual-run mechanism.
     *
     * <p>Every difference becomes a {@code migration_reconciliation} row and the three count fields of
     * {@code run} are set from what was actually read, replayed and persisted. The run's own verdict,
     * {@code finishedAt} and the process exit code stay with {@code migration.MigrationToolRunner}, which
     * opened the row and closes it.</p>
     *
     * @param run             the {@code SHADOW} run this window's findings belong to, already persisted
     * @param transactions    the captured requests, in any order; replayed in ascending sequence number
     * @param legacyResponses the captured replies, in any order; joined by sequence number and owner
     * @return the number of {@code VARIANCE} rows this window persisted under {@code run}
     * @throws IllegalArgumentException when a sequence number repeats within a capture, or when the two
     *                                  captures disagree about the owner at one sequence number
     */
    // NOT ONE TRANSACTION FOR THE WINDOW, and deliberately not plain @Transactional. RetailCashAccountService's
    // methods are @Transactional and CashAccountException is unchecked, so a replay joined to one outer
    // transaction would be marked rollback-only by the first deliberate rejection - the seeded over-debit that
    // must come back 422 INSUFFICIENT_FUNDS - and the whole window would then die at commit with
    // UnexpectedRollbackException, destroying every row of evidence it had gathered. NOT_SUPPORTED suspends any
    // inherited transaction so each replay step gets its own (the service's REQUIRED starts it) and each
    // evidence row is written independently of the replay outcome beside it: ReconciliationService.record is
    // itself unannotated, so its save runs in the repository's own transaction and commits on its own.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int compare(MigrationRun run,
                       List<ShadowTransaction> transactions,
                       List<ShadowLegacyResponse> legacyResponses) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(transactions, "transactions");
        Objects.requireNonNull(legacyResponses, "legacyResponses");

        // JOIN ON THE SEQUENCE NUMBER PLUS THE NORMALIZED OWNER, never on file order or ordinal position:
        // EBCDIC and UTF-8 collate differently (AAP 0.12.2), so two exports of one window can arrive in
        // different orders and a position-wise pairing would compare unrelated lines. A TreeMap because the
        // capture is a time series - replaying a stream out of order produces different balances, so ascending
        // sequence order is a correctness requirement and not a presentation choice.
        Map<Long, ShadowTransaction> transactionsBySeq = indexTransactions(transactions);
        Map<Long, ShadowLegacyResponse> responsesBySeq = indexLegacyResponses(legacyResponses);
        requireOwnersAgree(transactionsBySeq, responsesBySeq);

        LOGGER.info("Shadow window {}: replaying {} captured transactions against {} captured replies",
                run.runId(), transactionsBySeq.size(), responsesBySeq.size());

        // Resolved once, and only where it can be used: the staged rate table is read exclusively by the
        // live-mode explanation below, so the default parity gate spends no query on it at all. One lookup up
        // front is also what keeps this class free of a mutable per-instance cache, which a @Service shared
        // across tool invocations must not carry.
        UUID stagingLoadRunId = LIVE_RATE_SOURCE.equals(rateSource) ? latestLoadRunId(run.batchId()) : null;

        for (Map.Entry<Long, ShadowTransaction> entry : transactionsBySeq.entrySet()) {
            replayAndClassify(run, entry.getKey(), entry.getValue(),
                    responsesBySeq.get(entry.getKey()), stagingLoadRunId);
        }

        recordTransactionCounts(run, transactionsBySeq, responsesBySeq);

        // One authority for the number, read back from the rows themselves rather than accumulated in a local,
        // so the count MigrationToolRunner turns into exit code 2 is the count that actually reached the
        // database. Only VARIANCE is counted: an ACCEPTED_EXCEPTION records an authorized difference and must
        // never inflate the run's variance count or change its exit code.
        int varianceCount = Math.toIntExact(
                reconciliations.countByRunIdAndStatus(run.runId(), ReconciliationStatus.VARIANCE));

        // Exactly three fields of the run, and nothing else. The verdict, finishedAt, sourcePath, batchId and
        // characterizationStatus belong to MigrationToolRunner, which opened this row as SHADOW/RUNNING and is
        // the single closer of it; a second writer would let the row's status and its rows disagree.
        run.setLegacyRecordCount(responsesBySeq.size());
        // Attempted, not accepted: a capture line the target refused was still replayed, and its rejection is
        // already recorded as its own row.
        run.setMigratedRecordCount(transactionsBySeq.size());
        run.setVarianceCount(varianceCount);

        LOGGER.info("Shadow window {}: {} variance rows persisted", run.runId(), varianceCount);
        return varianceCount;
    }

    /**
     * Replays one captured transaction and records at most one row for it.
     */
    // A MATCHING SEQUENCE NUMBER GETS NO ROW AT ALL - not even a MATCHED one. The acceptance criteria are
    // "zero VARIANCE rows" for the matched stream and "exactly the seeded rows" for the seeded one, asserted
    // over the set findByRunIdOrderByReconciliationIdAsc returns; a row per agreeing line would bury the
    // seeded rows in it and tie both assertions to the window's size.
    private void replayAndClassify(MigrationRun run,
                                   long seq,
                                   ShadowTransaction transaction,
                                   ShadowLegacyResponse legacyResponse,
                                   UUID stagingLoadRunId) {
        String owner = joinKey(transaction.owner());
        // The null is tested here rather than handed to the map: an immutable Map rejects a null key with a
        // NullPointerException, and a capture line that carries no request code at all is an unrecognized
        // code to be classified, never a failure that ends the window.
        String requestCode = LegacyExportFormat.trimPadding(transaction.req());
        ReplayOperation operation = requestCode == null ? null : OPERATIONS_BY_CODE.get(requestCode);

        if (operation == null) {
            // FAIL CLOSED ON AN UNRECOGNIZED CODE, and without calling the service at all. EVALUATE WS-REQ has
            // no WHEN OTHER (CASH00.cbl:L89-L102), so an unknown code - a lowercase 'a' included, the EVALUATE
            // being case-sensitive - executed no SQL, left the SQLCA untouched, echoed the caller's own COMMAREA
            // back as the reply and still wrote a history record. The target has no such path (AAP 0.4.3,
            // 0.12.4), which is an authorized deviation rather than a defect, so the row is an accepted
            // exception naming the status the target would answer with.
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
            // OwnerNormalizer.normalize sits inside the try for two reasons: it produces the owner the
            // service is called with, and where the capture's owner is one the target refuses it raises
            // exactly the INVALID_OWNER the service would raise, so that rejection is classified, not lost.
            response = dispatch(operation, OwnerNormalizer.normalize(transaction.owner()), transaction);
        } catch (CashAccountException rejected) {
            classifyTargetRejection(run, owner, legacyResponse, rejected);
            return;
        } catch (RuntimeException unexpected) {
            // A ROW, NOT AN ABORTED WINDOW. One bad step must not cost a window its evidence, and an
            // unexplained failure is exactly the kind of divergence a dual-run exists to surface - so it is
            // recorded as an outstanding variance and the replay continues. Error is deliberately not caught:
            // a JVM-level failure is not a comparison outcome.
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

    /*
     * THE AMOUNT MEANS TWO DIFFERENT THINGS AND THE DISTINCTION IS LOAD-BEARING. On A and U the captured
     * amount is the ABSOLUTE balance the caller supplied: the legacy INSERT and UPDATE bound the COMMAREA
     * field straight into the row (CASH00.cbl:L155 insert, L176-L177 update). On C and D it is the DELTA that
     * the stored rate multiplies - MOVE WS-BALANCE TO BALANC-RATE then
     * COMPUTE WS-CALC = BALANCE +/- (RATES * BALANC-RATE) (CASH00.cbl:L221-L222 credit, L255-L256 debit).
     * Reading one as the other reproduces a number the legacy never computed.
     *
     * The captured currency is passed through exactly as captured, null included, so the service applies its
     * own base-currency default; defaulting it here would hide a capture that carried no currency.
     */
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

    /**
     * Classifies a capture line the target processed successfully.
     */
    private void classifyTargetSuccess(MigrationRun run,
                                       String owner,
                                       ReplayOperation operation,
                                       ShadowTransaction transaction,
                                       ShadowLegacyResponse legacyResponse,
                                       CashAccountResponse response,
                                       CashAccount before,
                                       UUID stagingLoadRunId) {
        // NO PER-LINE ROW WHERE THE LEGACY REPLY WAS A FAILURE AND THE TARGET SUCCEEDED. MOVE SQLCODE TO
        // WS-RETCODE renders the absolute digits and drops the sign (CASH00.cbl:L104), always carrying the
        // LAST statement's code, so the exact legacy condition is unknowable and a per-line verdict would be
        // invented; the asymmetry surfaces in the per-owner count instead, where a target excess is expected.
        if (!isLegacySuccess(legacyResponse)) {
            return;
        }

        BigDecimal capturedBalance = capturedBalance(legacyResponse);
        if (capturedBalance == null) {
            // A success reply that carried no balance leaves nothing to compare, so the line fails closed
            // rather than being skipped silently. ReconciliationService.record renders the value columns of a
            // BALANCE row from the two balances themselves (MigrationReconciliation.balance), so the absent
            // legacy side reaches the row as a null balance, a null rendering and a null variance; the token
            // names the condition for a reader of this classification and adding a second persist path to
            // carry it would be worse than leaving it implicit.
            reconciliationService.record(run, owner, VarianceKind.BALANCE, ReconciliationStatus.VARIANCE,
                    ABSENT_IN_CAPTURE, response.balance().toPlainString(), null, response.balance());
            return;
        }

        BigDecimal targetBalance = response.balance();
        // compareTo, never equals: 1250.5 and 1250.50 are the same amount of money and differ only in scale,
        // which BigDecimal.equals reports as a difference an operator would have to triage as one.
        if (capturedBalance.compareTo(targetBalance) == 0) {
            return;
        }

        if (stagingLoadRunId != null
                && isRateScaled(operation)
                && rateDifferenceExplains(stagingLoadRunId, operation, transaction, before, capturedBalance)) {
            // LIVE MODE ONLY (AAP 0.12.5). Re-deriving the step with the STAGED legacy rate reproduces the
            // captured balance exactly, so the whole difference is attributable to the rate the target priced
            // with and none of it to the ledger: an accepted exception the runbook's live shadow step reads for
            // information, never a parity failure. The variance column stays null because an accepted
            // exception leaves no signed difference outstanding - both balances are on the row beside it.
            reconciliationService.record(run, owner, VarianceKind.RATE_SOURCE,
                    ReconciliationStatus.ACCEPTED_EXCEPTION,
                    capturedBalance.toPlainString(), targetBalance.toPlainString(),
                    capturedBalance, targetBalance);
            return;
        }

        // The parity gate's verdict, and the fallback for every live-mode difference the rate cannot explain:
        // a genuine balance divergence. record derives the two renderings and the signed variance
        // (migrated - legacy) from the balances themselves, so the sign convention has one home.
        reconciliationService.record(run, owner, VarianceKind.BALANCE, ReconciliationStatus.VARIANCE,
                null, null, capturedBalance, targetBalance);
    }

    /**
     * Classifies a capture line the target refused with an explicit business condition.
     */
    private void classifyTargetRejection(MigrationRun run,
                                         String owner,
                                         ShadowLegacyResponse legacyResponse,
                                         CashAccountException rejected) {
        // BOTH SIDES REFUSED IS AGREEMENT, AND GETS NO ROW. The retcode is sign-dropped and carries only the
        // last statement's code (CASH00.cbl:L104), so the legacy condition cannot be compared with the
        // target's; "both refused" is the strongest statement the evidence supports (AAP 0.12.3).
        if (!isLegacySuccess(legacyResponse)) {
            return;
        }

        CashAccountErrorCode code = rejected.errorCode();
        ReconciliationStatus status = AUTHORIZED_DEVIATIONS.contains(code)
                ? ReconciliationStatus.ACCEPTED_EXCEPTION
                : ReconciliationStatus.VARIANCE;

        // The error code's constant NAME is the wire code the target answers with, so the row carries the
        // same token an operator sees in an ApiError payload.
        reconciliationService.record(run, owner, VarianceKind.REJECTED_BY_TARGET, status,
                legacySideText(legacyResponse), code.name(), capturedBalance(legacyResponse), null);
    }

    /**
     * Records the per-owner transaction-count comparison for the window.
     */
    // THE ONLY MODE THAT EMITS TRANSACTION_COUNT. After a bulk load the target holds one MIGRATION_LOAD ledger
    // row per account and no per-transaction history, so reconcile mode has no target count to compare against
    // and ReconciliationService never writes this kind; a shadow window is the one place both sides processed
    // the same stream (AAP 0.10.3).
    private void recordTransactionCounts(MigrationRun run,
                                         Map<Long, ShadowTransaction> transactionsBySeq,
                                         Map<Long, ShadowLegacyResponse> responsesBySeq) {
        Map<String, Integer> legacyCounts = new TreeMap<>();
        for (ShadowLegacyResponse legacyResponse : responsesBySeq.values()) {
            if (!isLegacySuccess(legacyResponse)) {
                continue;
            }
            ShadowTransaction paired = transactionsBySeq.get(legacyResponse.seq());
            // Q is excluded because a read changed no state, so there is nothing on the target side to count it
            // against (AAP 0.4.6, characterization 4.2). An UNPAIRED success is counted: it is evidence of a
            // legacy state change the target never saw, which is the whole point of the count.
            if (paired != null && !isCounted(paired.req())) {
                continue;
            }
            increment(legacyCounts, joinKey(legacyResponse.owner()));
        }

        Map<String, Integer> targetCounts = new TreeMap<>();
        for (ShadowTransaction transaction : transactionsBySeq.values()) {
            // ATTEMPTED, NOT ACCEPTED, and this is load-bearing: a line the target refused already carries its
            // own REJECTED_BY_TARGET row, so counting it as a shortfall here as well would report one
            // divergence twice and would turn the seeded over-debit into two rows instead of the one row the
            // acceptance criteria fix (AAP 0.10.3).
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

            // THE ASYMMETRY IS THE LEGACY'S, NOT A PREFERENCE (AAP 0.11.1). EXEC CICS IGNORE CONDITION DUPREC
            // (CASH00.cbl:L124) discarded a second history record whose 29-byte key already existed, and the
            // key's only time component is a whole second - so two requests for one owner inside one second
            // left a single record and legacy counts are a LOWER BOUND. A target excess is therefore expected
            // and accepted; a target shortfall cannot be explained that way and is a real loss.
            ReconciliationStatus status = targetCount > legacyCount
                    ? ReconciliationStatus.ACCEPTED_EXCEPTION
                    : ReconciliationStatus.VARIANCE;

            // The balance columns and the variance stay null: a count is not money, and a 0.00 variance on a
            // count row would read as monetary agreement.
            reconciliationService.record(run, owner, VarianceKind.TRANSACTION_COUNT, status,
                    Integer.toString(legacyCount), Integer.toString(targetCount), null, null);
        }
    }

    /**
     * Whether re-deriving the replayed step with the staged legacy rate reproduces the captured balance exactly.
     */
    // Deliberately narrow and conservative: only a difference the staged RATES re-derives to the cent is
    // attributed to the exchange rate, because accepting one that no arithmetic reproduces would sign off a
    // defect. A missing or NULL staged rate explains nothing and therefore explains nothing away.
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

    /**
     * The staged rate table's owning run: the latest load of this batch that did not fail.
     */
    // Resolved from the batch rather than from this run because the rate table was staged by the batch's LOAD
    // invocation and a shadow window is a different run under the same batch id (AAP 0.6.3). The repository
    // returns the batch in start order and the enum filtering happens here, exactly as
    // MigrationRunRepository's contract prescribes.
    private UUID latestLoadRunId(UUID batchId) {
        if (batchId == null) {
            return null;
        }
        UUID resolved = null;
        for (MigrationRun candidate : runs.findByBatchIdOrderByStartedAtAsc(batchId)) {
            if (candidate.mode() == MigrationRun.Mode.LOAD && candidate.status() != MigrationRun.Status.FAILED) {
                resolved = candidate.runId();
            }
        }
        return resolved;
    }

    private List<ShadowTransaction> readTransactions(Path file) {
        List<DelimitedExportReader.DelimitedRow> rows =
                exportReader.readRows(file, LegacyExportFormat.SHADOW_TRANSACTION_COLUMNS);
        List<ShadowTransaction> transactions = new ArrayList<>(rows.size());
        for (DelimitedExportReader.DelimitedRow row : rows) {
            // Values reach the carrier exactly as captured - unnormalized owner, un-uppercased request code,
            // unrescaled amount - because both records are deliberately raw and every judgement about them
            // belongs to the classification below or to the service layer.
            transactions.add(new ShadowTransaction(
                    row.requireLong(LegacyExportFormat.SHADOW_TRANSACTION_SEQ_COLUMN),
                    row.requireText(LegacyExportFormat.SHADOW_TRANSACTION_OWNER_COLUMN),
                    row.requireText(LegacyExportFormat.SHADOW_TRANSACTION_REQUEST_CODE_COLUMN),
                    row.decimal(LegacyExportFormat.SHADOW_TRANSACTION_AMOUNT_COLUMN),
                    row.text(LegacyExportFormat.SHADOW_TRANSACTION_CURRENCY_COLUMN)));
        }
        return List.copyOf(transactions);
    }

    private List<ShadowLegacyResponse> readLegacyResponses(Path file) {
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

    // A malformed capture is an INPUT ERROR, not a variance: a repeated sequence number makes the join
    // ambiguous and a variance row written from an ambiguous pairing would be evidence of nothing.
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

    // What the legacy side of a non-balance row carries: the reply's balance where it had one, and otherwise
    // the raw return code, which is the only other thing the reply stated.
    private static String legacySideText(ShadowLegacyResponse legacyResponse) {
        if (legacyResponse == null) {
            return null;
        }
        return legacyResponse.balance() != null
                ? legacyResponse.balance().toPlainString()
                : LegacyExportFormat.trimPadding(legacyResponse.retcode());
    }

    // Null-guarded for the same reason the dispatch lookup is: an immutable Set rejects a null probe, and a
    // capture line without a request code counts as no state change on either side.
    private static boolean isCounted(String requestCode) {
        String trimmed = LegacyExportFormat.trimPadding(requestCode);
        return trimmed != null && LegacyExportFormat.COUNTED_REQUEST_CODES.contains(trimmed);
    }

    private static boolean isRateScaled(ReplayOperation operation) {
        return operation == ReplayOperation.CREDIT || operation == ReplayOperation.DEBIT;
    }

    // The legacy join compared only the first RATE_KEY_LENGTH characters of the account's currency
    // (MOVE CURRENCYC TO WS-CURRENCY-KEY, CASH00.cbl:L213 credit, L247 debit), and the width comes from
    // LegacyCharacterization so the characterized parameters keep one declaration point.
    private static String rateKey(String currency) {
        if (currency == null) {
            return "";
        }
        return currency.length() <= LegacyCharacterization.RATE_KEY_LENGTH
                ? currency
                : currency.substring(0, LegacyCharacterization.RATE_KEY_LENGTH);
    }

    /**
     * The grouping key for one captured owner, which never throws.
     */
    // OwnerNormalizer.normalize is the authority and is used wherever it succeeds. It raises INVALID_OWNER for
    // a blank owner or one longer than 32 characters, and the owner column of migration_reconciliation is NOT
    // NULL - so a capture the target refuses still needs a deterministic key to be recorded and counted
    // under. The fallback applies the same folding the normalizer would (strip, upper case with Locale.ROOT
    // so a Turkish default locale cannot map "i" to a dotted capital) and then cuts to the stored column
    // width, which is how the legacy interface itself lost long owners (CASH00.cbl:L55).
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
            return folded.length() <= OwnerNormalizer.MAX_LENGTH
                    ? folded
                    : folded.substring(0, OwnerNormalizer.MAX_LENGTH);
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
        // The cross-check that keeps LegacyExportFormat the single authority on the accepted codes: if the set
        // there ever changes, this class fails to initialize rather than quietly leaving a code unroutable or
        // routing one the legacy never recognized.
        if (!byCode.keySet().equals(LegacyExportFormat.REQUEST_CODES)) {
            throw new IllegalStateException("The replay dispatch table covers " + byCode.keySet()
                    + " but LegacyExportFormat.REQUEST_CODES declares " + LegacyExportFormat.REQUEST_CODES);
        }
        return Map.copyOf(byCode);
    }
}
