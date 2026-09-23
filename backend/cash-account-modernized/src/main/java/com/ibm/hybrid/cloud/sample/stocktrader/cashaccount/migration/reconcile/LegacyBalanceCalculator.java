package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile;

import java.math.BigDecimal;
import java.util.Objects;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.Money;

/** The legacy {@code CASH00} credit/debit arithmetic, reproduced exactly for the migration tooling. */
public final class LegacyBalanceCalculator {

    private LegacyBalanceCalculator() {
    }

    /**
     * Legacy request code {@code C}: what the {@code COMPUTE} at CASH00.cbl:L222 would have stored.
     *
     * @param storedBalance the balance the account {@code SELECT} re-read from the row (CASH00.cbl:L205-L209)
     * @param rate          {@code FRANKFURT1.RATES} for the account's rate key
     * @param callerAmount  the COMMAREA amount, which is the multiplicand and not the rate row's own
     *                      {@code AMOUNT} column
     * @return the value the legacy program would have committed, unsigned and wrapped
     */
    public static Money credit(BigDecimal storedBalance, BigDecimal rate, BigDecimal callerAmount) {
        return apply(storedBalance, Money.SIGN_CREDIT, rate, callerAmount);
    }

    /**
     * Legacy request code {@code D}: what the {@code COMPUTE} at CASH00.cbl:L256 would have stored.
     *
     * @param storedBalance the balance the account {@code SELECT} re-read from the row (CASH00.cbl:L240-L245)
     * @param rate          {@code FRANKFURT1.RATES} for the account's rate key
     * @param callerAmount  the COMMAREA amount, which is the multiplicand and not the rate row's own
     *                      {@code AMOUNT} column
     * @return the value the legacy program would have committed, unsigned and wrapped -- a debit past
     *         zero therefore yields the magnitude of the overdraft, not a negative balance
     */
    public static Money debit(BigDecimal storedBalance, BigDecimal rate, BigDecimal callerAmount) {
        return apply(storedBalance, Money.SIGN_DEBIT, rate, callerAmount);
    }

    /**
     * The characterized formula {@code truncate2(stored +/- RATES x caller_amount)}, in the order the
     * legacy statement imposed. Every parameter comes from {@link LegacyCharacterization} rather than a
     * literal here, so scale, rounding, sign handling and the wrap point cannot be characterized twice
     * and drift (AAP 0.10.1).
     *
     * @param storedBalance the balance the account {@code SELECT} re-read from the row
     * @param sign          {@link Money#SIGN_CREDIT} or {@link Money#SIGN_DEBIT}
     * @param rate          {@code FRANKFURT1.RATES} for the account's rate key
     * @param callerAmount  the COMMAREA amount the {@code COMPUTE} multiplied by the rate
     * @return the value the legacy program would have committed: unsigned, wrapped, and always inside
     *         {@code Money}'s range, so no bounds check of its own is needed here
     * @throws NullPointerException     if any {@code BigDecimal} operand is {@code null}
     * @throws IllegalArgumentException if {@code sign} is neither direction
     */
    public static Money apply(BigDecimal storedBalance, int sign, BigDecimal rate, BigDecimal callerAmount) {
        // A null operand or an unknown sign is a programming error and not a caller condition -- only the
        // tooling services reach this class, and they validate their export rows first -- so the plain JDK
        // exceptions are raised rather than a CashAccountException that would render a bug as an ApiError.
        Objects.requireNonNull(storedBalance, "storedBalance is required");
        Objects.requireNonNull(rate, "rate is required");
        Objects.requireNonNull(callerAmount, "callerAmount is required");
        if (sign != Money.SIGN_CREDIT && sign != Money.SIGN_DEBIT) {
            throw new IllegalArgumentException("sign must be Money.SIGN_CREDIT or Money.SIGN_DEBIT, was " + sign);
        }

        // One truncation, not two: Money.applyRate holds the product RATES x caller_amount at full
        // precision and scales the signed result exactly once, matching the single store into the
        // two-decimal WS-CALC (CASH00.cbl:L222 credit, L256 debit). Pre-scaling the product is not an
        // equivalent reordering but a penny on every small debit -- stored 100.00, rate 0.03, amount 0.30
        // truncates to 99.99, while truncating the product first gives 100.00.
        // The multiplicand is the caller's COMMAREA amount, re-read by MOVE WS-BALANCE TO BALANC-RATE
        // immediately before the COMPUTE (CASH00.cbl:L221, L255), never FRANKFURT1.AMOUNT, which the rate
        // SELECT fetches (L215, L249) and no COMPUTE or MOVE in the program then references, as with
        // CURRNBASE and LOADDT (DCLFRANK.cpy:L11, L10, L13).
        BigDecimal computed = Money.applyRate(storedBalance, sign, rate, callerAmount);
        // The sign is dropped because WS-CALC carries no S (CASH00.cbl:L17) and MOVE WS-CALC TO BALANCE
        // (L225, L259) carried the magnitude into the UPDATE, so a debit past zero committed the overdraft
        // as a positive balance; the live service refuses that with 422 INSUFFICIENT_FUNDS instead
        // (AAP 0.4.6). Flagged by the characterization rather than an unconditional abs() so the claim
        // stays traceable to the picture clause. Magnitude before wrap is the order the legacy store
        // implies: truncation toward zero commutes with abs(), and remainder on a non-negative dividend
        // is non-negative.
        BigDecimal magnitude = LegacyCharacterization.RESULT_IS_UNSIGNED ? computed.abs() : computed;
        // High-order digits were lost, not diagnosed: neither COMPUTE carries ON SIZE ERROR
        // (CASH00.cbl:L222, L256), so a result of ten million or more was stored wrapped while the return
        // channel still reported the UPDATE's SQLCODE 0 -- arithmetically this remainder. The live service
        // answers 422 AMOUNT_OUT_OF_RANGE for the same condition, in Money.applyRateChecked (AAP 0.4.6).
        return Money.of(magnitude.remainder(LegacyCharacterization.BALANCE_MODULUS));
    }
}
