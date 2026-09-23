package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;

/** The module's only money type: a non-negative fixed-point amount at scale 2, truncated toward zero. */
public final class Money implements Comparable<Money> {

    /** Two decimals, the {@code V99} of {@code WS-CALC pic 9(7)V99} (CASH00.cbl:L17) and of NUMERIC(9,2). */
    public static final int SCALE = 2;

    /*
     * DOWN rather than HALF_UP because neither legacy COMPUTE carries a ROUNDED phrase (CASH00.cbl:L222
     * credit, L256 debit). DOWN rather than FLOOR is the trap: DOWN truncates the magnitude, as COBOL did
     * before dropping the sign, so a raw -0.276 gives -0.27 and not FLOOR's -0.28, which would diverge by a
     * cent on every overdraft the migration tooling reproduces.
     */
    public static final RoundingMode ROUNDING = RoundingMode.DOWN;

    /*
     * The representable range of the legacy result field: WS-CALC is pic 9(7)V99 (CASH00.cbl:L17) with no S
     * in its picture, so seven integer digits and two decimals, unsigned.
     *
     * Both bounds are declared once and compared against only through these two constants. The ceiling is a
     * recorded open item - institutional volumes may need widening, which is one DDL change plus these
     * constants (docs/legacy-characterization.md section 9.8) - and that change stays surgical only while
     * the literal exists in no other place.
     */
    public static final BigDecimal MIN_VALUE = new BigDecimal("0.00");

    public static final BigDecimal MAX_VALUE = new BigDecimal("9999999.99");

    // A size bound exists although MIN_VALUE and MAX_VALUE already bound the value because neither comparison is
    // the expensive step: compareTo settles on the adjusted exponents alone, so new BigDecimal("1e1000000000") -
    // twelve characters of a query parameter, a JSON body or a third-party rate - costs nothing until setScale
    // materializes its digits (1e100000 costs 29 ms; 1e10000000 costs 3.6 s and 10,000,003 digits; past a few
    // hundred million digits BigInteger raises ArithmeticException, answering a malformed amount with 500
    // INTERNAL where AAP 0.6.2 names 400 INVALID_AMOUNT). Twelve bytes buying seconds of CPU and hundreds of
    // megabytes inside a 2 GiB pod is a denial of service, so the size is judged before the value is normalized.
    // 1000 and 1000 because 10^1000 is a sub-millisecond expansion while every legitimate value sits orders of
    // magnitude inside them (a balance is nine digits at scale 2; a live rate carries four to six significant
    // digits). A resource bound, not a precision policy: the legacy RATES DECIMAL(3, 2) column
    // (backend/cash-account-cobol/COBOL/DCLFRANK.cpy:L12, L22) could not represent JPY or INR, so a bound on a
    // rate's scale or magnitude would re-introduce the defect this replacement removes (AAP 0.12.5).
    public static final int MAX_INPUT_SCALE = 1000;

    public static final int MAX_INPUT_PRECISION = 1000;

    public static final Money ZERO = new Money(MIN_VALUE);

    /*
     * Named constants rather than a boolean because applyRate reproduces one COBOL statement whose credit
     * (CASH00.cbl:L222) and debit (L256) differ only in the operator, and the reconciliation tooling carries
     * the legacy one-character C/D request code that has to map onto it unambiguously.
     */
    public static final int SIGN_CREDIT = 1;

    public static final int SIGN_DEBIT = -1;

    /*
     * BigDecimal and never double or float: 0.01 has no exact binary representation, so a floating-point
     * money path accumulates a fraction of a cent per operation and reconciles against COBOL packed decimal
     * with penny variances that read as data corruption. The legacy fields were fixed-point end to end -
     * BALANCE is DECIMAL(9, 2) / PIC S9(7)V9(2) COMP-3 (backend/cash-account-cobol/COBOL/DCLCASH.cpy:L10,
     * L18) - so this is the only representation that reproduces them digit for digit;
     * backend/portfolio/createTables.ddl stores money as DOUBLE PRECISION and is deliberately not followed.
     */
    private final BigDecimal amount;

    /*
     * The one normalization point, and what makes equals and hashCode consistent: BigDecimal's own are
     * scale-sensitive, so without it of("10") and of("10.00") would be unequal amounts and a legitimate
     * replay would fail a hold's idempotency hash (AAP 0.7.3). Private, so the factories below are the only
     * way to obtain a validated amount. Being the one normalization point also makes it the one place the
     * size guard has to stand: the setScale below is the expansion MAX_INPUT_SCALE and MAX_INPUT_PRECISION
     * defend against, and every factory, plus, minus and applyRateChecked path reaches it.
     */
    private Money(BigDecimal value) {
        requireWithinInputBounds(value);
        this.amount = value.setScale(SCALE, ROUNDING);
    }

    /**
     * Whether a raw decimal is small enough that normalizing or multiplying it is bounded work.
     *
     * @param value the unnormalized value as the caller or the rate provider supplied it; {@code null} is not
     *     within bounds, so a caller that has no separate null branch still refuses it
     * @return {@code true} when the value's scale and digit count are both inside {@link #MAX_INPUT_SCALE} and
     *     {@link #MAX_INPUT_PRECISION}
     */
    public static boolean isWithinInputBounds(BigDecimal value) {
        if (value == null) {
            return false;
        }
        /*
         * The scale is judged first because it is a field read while precision() walks the unscaled value, and the
         * scale is what carries the attack: an extreme exponent is a cheap parse and an expensive normalization.
         *
         * Compared against both signs rather than through Math.abs, which is a trap here and not a style
         * preference: Math.abs(Integer.MIN_VALUE) is itself Integer.MIN_VALUE, and new BigDecimal("1E+2147483648")
         * parses to exactly that scale, so an abs-based bound would wave the single worst input straight through.
         */
        int scale = value.scale();
        if (scale > MAX_INPUT_SCALE || scale < -MAX_INPUT_SCALE) {
            return false;
        }
        return value.precision() <= MAX_INPUT_PRECISION;
    }

    /**
     * Refuses an out-of-size raw decimal at the boundary that received it, before any arithmetic touches it.
     *
     * <p>400 INVALID_AMOUNT and not 422 AMOUNT_OUT_OF_RANGE, on every path: a value of this size is a malformed
     * request rather than a representable amount the storage cannot hold, and the closed hold and settle error sets
     * of AAP 0.6.2 do not contain the range code at all.
     *
     * @param value the raw value to admit
     * @return {@code value} itself, so the guard composes into an expression
     * @throws CashAccountException 400 INVALID_AMOUNT when the value is null or outside the input bounds
     */
    public static BigDecimal requireWithinInputBounds(BigDecimal value) {
        if (!isWithinInputBounds(value)) {
            throw CashAccountException.of(CashAccountErrorCode.INVALID_AMOUNT);
        }
        return value;
    }

    /*
     * Scaling a caller amount DOWN to two decimals is a recorded target decision, not a legacy fact - the
     * z/OS Connect mapping that filled the two-decimal COMMAREA field (CASH00.cbl:L56) is absent from the
     * repository (AAP 0.11.2). The sign is judged before that scaling, which would otherwise turn a mistyped
     * value in (-0.01, 0) into a legal 0.00 and lose the 400 INVALID_AMOUNT of AAP 0.6.2; the ceiling is
     * judged after it, since what NUMERIC(9,2) can hold is decided at two decimals.
     */
    public static Money of(BigDecimal raw) {
        if (raw == null) {
            throw CashAccountException.of(CashAccountErrorCode.INVALID_AMOUNT);
        }
        if (raw.signum() < 0) {
            throw CashAccountException.of(CashAccountErrorCode.INVALID_AMOUNT);
        }
        Money candidate = new Money(raw);
        if (candidate.amount.compareTo(MAX_VALUE) > 0) {
            throw CashAccountException.of(CashAccountErrorCode.AMOUNT_OUT_OF_RANGE);
        }
        return candidate;
    }

    /*
     * Text is a first-class input, not a convenience: the export readers build every value from digit strings
     * decoded out of a DB2 UNLOAD or an EBCDIC VSAM record. new BigDecimal(String) also accepts the 1.0E7
     * exponent form Double.toString emits on the caller's side (AAP 0.6.2), so an oversized value trips the
     * ceiling instead of being misread; an unparsable one is a 400 rather than an unhandled 500, with the
     * cause retained for the log and never echoed into the response.
     */
    public static Money of(String raw) {
        if (raw == null) {
            throw CashAccountException.of(CashAccountErrorCode.INVALID_AMOUNT);
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(raw);
        } catch (NumberFormatException cause) {
            throw CashAccountException.of(CashAccountErrorCode.INVALID_AMOUNT, null, cause);
        }
        return of(parsed);
    }

    public BigDecimal amount() {
        return amount;
    }

    /*
     * Both operands are already at scale 2, so the sum is exact and no rounding decision arises here - the
     * only reachable failure is the ceiling, which replaces the legacy silent loss of high-order digits
     * (CASH00.cbl:L17, L26). A sum of two non-negative values cannot underflow, so there is no floor check.
     */
    public Money plus(Money other) {
        requireOperand(other);
        BigDecimal sum = amount.add(other.amount);
        if (sum.compareTo(MAX_VALUE) > 0) {
            throw CashAccountException.of(CashAccountErrorCode.AMOUNT_OUT_OF_RANGE);
        }
        return new Money(sum);
    }

    /*
     * Deliberate deviation from the legacy: WS-CALC is unsigned (CASH00.cbl:L17), so a debit larger than the
     * balance committed a positive balance equal to the magnitude of the overdraft; the replacement refuses
     * the write instead (AAP 0.4.6, 0.14.2). INSUFFICIENT_FUNDS rather than a generic range error because
     * this is the available-balance path, and centralizing the check here is what stops the retail debit and
     * institutional hold paths from disagreeing about what "enough" means.
     */
    public Money minus(Money other) {
        requireOperand(other);
        BigDecimal difference = amount.subtract(other.amount);
        if (difference.compareTo(MIN_VALUE) < 0) {
            throw CashAccountException.of(CashAccountErrorCode.INSUFFICIENT_FUNDS);
        }
        return new Money(difference);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    /*
     * One final truncation, never two. The legacy COMPUTE evaluated the whole expression and truncated once,
     * on storing into the two-decimal WS-CALC (CASH00.cbl:L222 credit, L256 debit, field at L17), and
     * truncating the product first is not equivalent for a debit: stored 100.00, rate 0.03 and amount 0.30
     * give truncate2(100.00 - 0.009) = 99.99 that way and 100.00 - 0.00 = 100.00 the other. The product
     * therefore keeps full precision and setScale is applied exactly once, to the final result.
     *
     * The result is returned raw and signed - no bounds, no abs(), no modulus - so that one primitive serves
     * both callers: LegacyBalanceCalculator needs the negative or oversized value in order to layer the
     * legacy behaviour on top, the absolute value the unsigned WS-CALC stored and the modulus 10^7 by which
     * an oversized product lost high-order digits to the wider BALANC-RATE PIC 9(8)V99 (CASH00.cbl:L26).
     * Clamping here would erase the historical values the reconciliation exists to recognize; the live
     * service's bounds live in applyRateChecked instead.
     *
     * The rate is unconstrained in scale because the legacy column was DECIMAL(3, 2) / PIC S9(1)V9(2) COMP-3
     * (backend/cash-account-cobol/COBOL/DCLFRANK.cpy:L12, L22) with a ceiling of 9.99, which left JPY and
     * INR unrepresentable; bounding the rate here would re-introduce that defect on the live path.
     * MAX_INPUT_SCALE and MAX_INPUT_PRECISION are not that bound: they cap how many digits an operand may
     * occupy, three orders of magnitude above any published rate, and neither pre-scale nor limit its worth. A
     * null operand or an unrecognized sign is a programming error, so it raises IllegalArgumentException, which
     * the handler's catch-all renders as 500 INTERNAL instead of dressing a bug up as a plausible 4xx.
     */
    public static BigDecimal applyRate(BigDecimal stored, int sign, BigDecimal rate, BigDecimal amount) {
        if (stored == null || rate == null || amount == null) {
            throw new IllegalArgumentException("stored, rate and amount are required");
        }
        if (sign != SIGN_CREDIT && sign != SIGN_DEBIT) {
            throw new IllegalArgumentException("sign must be Money.SIGN_CREDIT or Money.SIGN_DEBIT, was " + sign);
        }
        /*
         * The same size guard as the constructor's, and for the same reason: the multiply below and the final
         * setScale are the expansions, so an extreme-exponent operand - a rate is third-party data - has to be
         * refused before either runs. It is checked here as well as in the constructor because this primitive is
         * entered directly, by migration/reconcile/LegacyBalanceCalculator, without constructing a Money first.
         *
         * IllegalArgumentException and not CashAccountException, consistently with the null and sign checks above:
         * every production path validates its operands at the boundary that received them (retail and institutional
         * controllers through requireWithinInputBounds, fx/FrankfurterExchangeRateClient through
         * isWithinInputBounds), so an operand arriving here out of size is a programming error, and the exception
         * handler's catch-all rendering it as 500 INTERNAL is the correct signal rather than a plausible 4xx that
         * would hide the bug.
         */
        if (!isWithinInputBounds(stored) || !isWithinInputBounds(rate) || !isWithinInputBounds(amount)) {
            throw new IllegalArgumentException("stored, rate and amount must each be within Money's input bounds");
        }
        BigDecimal product = rate.multiply(amount);
        BigDecimal result = sign == SIGN_CREDIT ? stored.add(product) : stored.subtract(product);
        return result.setScale(SCALE, ROUNDING);
    }

    /*
     * The live service's entry point to the same arithmetic, delegating so no second implementation can
     * drift, then applying the two bounds the legacy program did not enforce. Both are authorized deviations
     * (AAP 0.4.6, 0.14.2) and both throw before any write, so the balance is left unchanged: a negative
     * result is INSUFFICIENT_FUNDS (422) where the unsigned WS-CALC stored its absolute value
     * (CASH00.cbl:L17), and a result past the ceiling is AMOUNT_OUT_OF_RANGE (422) where the wider
     * BALANC-RATE let high-order digits drop silently (CASH00.cbl:L17, L26).
     */
    public static Money applyRateChecked(Money stored, int sign, BigDecimal rate, Money amount) {
        if (stored == null || amount == null) {
            throw new IllegalArgumentException("stored and amount are required");
        }
        BigDecimal result = applyRate(stored.amount, sign, rate, amount.amount);
        if (result.compareTo(MIN_VALUE) < 0) {
            throw CashAccountException.of(CashAccountErrorCode.INSUFFICIENT_FUNDS);
        }
        if (result.compareTo(MAX_VALUE) > 0) {
            throw CashAccountException.of(CashAccountErrorCode.AMOUNT_OUT_OF_RANGE);
        }
        return new Money(result);
    }

    @Override
    public int compareTo(Money other) {
        return amount.compareTo(other.amount);
    }

    /*
     * Compared by value rather than by BigDecimal.equals, which is scale-sensitive. The normalization
     * invariant makes the two coincide today; comparing numerically states the intent that survives it.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Money that)) {
            return false;
        }
        return amount.compareTo(that.amount) == 0;
    }

    /*
     * Consistent with equals only because every instance is normalized to scale 2 by the constructor:
     * numerically equal amounts therefore have an identical scale and so an identical BigDecimal hash.
     */
    @Override
    public int hashCode() {
        return amount.hashCode();
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }

    /*
     * A null Money operand is a programming error for the same reason a bad sign is, and is reported the same
     * way rather than as a business condition. compareTo is excluded deliberately: Comparable specifies a
     * NullPointerException for a null argument, which dereferencing the operand already produces.
     */
    private static void requireOperand(Money other) {
        if (other == null) {
            throw new IllegalArgumentException("operand is required");
        }
    }
}
