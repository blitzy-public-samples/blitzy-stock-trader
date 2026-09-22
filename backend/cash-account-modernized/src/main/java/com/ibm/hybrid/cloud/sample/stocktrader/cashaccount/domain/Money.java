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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;

/*
 * Why binary floating point appears nowhere in this file - no double, no float, no
 * BigDecimal.valueOf(double), no new BigDecimal(double), no doubleValue(), no Math call: a value such as
 * 0.01 has no exact binary representation, so a floating-point money path accumulates a fraction of a cent
 * per operation and then reconciles against COBOL packed and zoned decimal with penny-level variances that
 * read as data corruption rather than as arithmetic. The legacy fields were fixed-point end to end -
 * BALANCE is DECIMAL(9, 2) / PIC S9(7)V9(2) COMP-3 (backend/cash-account-cobol/COBOL/DCLCASH.cpy:L10, L18)
 * and the result field is PIC 9(7)V99 (backend/cash-account-cobol/COBOL/CASH00.cbl:L17) - so BigDecimal is
 * the only representation that can reproduce them digit for digit. backend/portfolio/createTables.ddl
 * stores money as DOUBLE PRECISION; that is the in-estate example this module deliberately does not follow.
 *
 * Why every balance, amount and rate application in the module funnels through this one type: scale,
 * rounding and bounds are parity-critical, and a second place that scaled or bounded a balance would be
 * free to disagree with this one without anything failing.
 */
/** The module's only money type: a non-negative fixed-point amount at scale 2, truncated toward zero. */
public final class Money implements Comparable<Money> {

    /** Two decimals, the {@code V99} of {@code WS-CALC pic 9(7)V99} (CASH00.cbl:L17) and of NUMERIC(9,2). */
    public static final int SCALE = 2;

    /*
     * DOWN rather than HALF_UP because neither legacy COMPUTE carries a ROUNDED phrase (CASH00.cbl:L222
     * credit, L256 debit), so COBOL discarded the excess fractional digits when storing into WS-CALC.
     *
     * DOWN rather than FLOOR, which is the trap here: DOWN truncates toward zero and therefore truncates
     * the magnitude, which is what COBOL did before dropping the sign. A raw result of -0.276 truncates to
     * -0.27 under DOWN (magnitude 0.27, matching the legacy truncate-then-drop-sign outcome) but to -0.28
     * under FLOOR, which would diverge from the legacy value by a cent on every overdraft the migration
     * tooling reproduces. BigDecimal has no negative zero, so a raw -0.009 truncates to 0.00 with
     * signum() == 0, which coincides exactly with the legacy unsigned 0.00.
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

    /** Declared after the bounds it is built from, since static initializers run in textual order. */
    public static final Money ZERO = new Money(MIN_VALUE);

    /*
     * The two directions are named constants rather than a boolean or an enum because applyRate reproduces a
     * single COBOL statement in which the only difference between credit (CASH00.cbl:L222) and debit (L256)
     * is the operator, and because the reconciliation tooling carries the legacy one-character request code
     * C or D and needs an unambiguous mapping onto that operator.
     */
    public static final int SIGN_CREDIT = 1;

    public static final int SIGN_DEBIT = -1;

    private final BigDecimal amount;

    /*
     * The one and only normalization point. Every instance is at scale 2 before it is stored, which is what
     * makes equals and hashCode consistent: BigDecimal's own equals and hashCode are scale-sensitive, so
     * without a single choke point of("10") and of("10.00") would be unequal values of the same amount. The
     * idempotency hash of a hold depends on numerically equal decimals being indistinguishable (AAP 0.7.3),
     * so that inconsistency would surface as a replayed reservation being rejected as key reuse.
     *
     * Private, so no caller can construct an unnormalized or unvalidated amount; the factories below are the
     * only entry points, and the callers inside this class pass values that are already at scale 2, for which
     * this setScale is an identity and discards nothing.
     */
    private Money(BigDecimal value) {
        this.amount = value.setScale(SCALE, ROUNDING);
    }

    /*
     * Scaling the incoming amount DOWN is a recorded decision, not a legacy fact. The COMMAREA field held
     * exactly two decimals (WS-BALANCE PIC 9(7)V99, CASH00.cbl:L56), but whether the z/OS Connect mapping
     * that filled it truncated or rounded a caller value with more decimals cannot be read from this
     * repository - the API/SAR artifact is absent (AAP 0.11.2). DOWN is chosen for consistency with COBOL's
     * default truncation, and every parity fixture uses two-decimal amounts so no expected value depends on
     * the choice.
     *
     * The validation order is deliberate: the value is scaled first and the bounds are then judged on the
     * stored form, because the stored form is what a balance column would have to hold. A negative amount is
     * a caller error (400 INVALID_AMOUNT) while an amount past the ceiling is a rejected outcome
     * (422 AMOUNT_OUT_OF_RANGE), and only the scaled value can distinguish -0.001, which normalizes to 0.00
     * and is accepted, from -0.01, which does not.
     */
    public static Money of(BigDecimal raw) {
        if (raw == null) {
            throw CashAccountException.of(CashAccountErrorCode.INVALID_AMOUNT);
        }
        Money candidate = new Money(raw);
        if (candidate.amount.compareTo(MIN_VALUE) < 0) {
            throw CashAccountException.of(CashAccountErrorCode.INVALID_AMOUNT);
        }
        if (candidate.amount.compareTo(MAX_VALUE) > 0) {
            throw CashAccountException.of(CashAccountErrorCode.AMOUNT_OUT_OF_RANGE);
        }
        return candidate;
    }

    /*
     * Text is a first-class input, not a convenience: the export readers build every value from digit strings
     * decoded out of a DB2 UNLOAD or an EBCDIC VSAM record, and the retail amount arrives as a query
     * parameter. Parsing from text is also the only construction that cannot lose precision on the way in.
     *
     * new BigDecimal(String) accepts the 1.0E7 exponent form that Double.toString emits on the caller's side
     * (AAP 0.6.2), which then normalizes to 10000000.00 and correctly trips the ceiling rather than being
     * silently misread. A NumberFormatException is translated instead of being allowed to escape, because an
     * unparsable amount is a 400 with an explicit code and not an unhandled 500; the cause is retained so the
     * offending text stays in the log without being echoed into the response payload.
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

    /** Safe to hand out directly: BigDecimal is immutable, so no defensive copy can be defeated. */
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
     * INSUFFICIENT_FUNDS rather than a generic range error because this is the available-balance path and the
     * only path a caller can drive negative: a retail debit or an institutional hold asks for more than is
     * spendable. Centralizing the sufficiency check here means the debit and hold paths cannot disagree about
     * what "enough" means. The reserved-balance path never reaches this branch - ReservationStateMachine
     * protects that invariant, since reserved funds only move by amounts it already placed there.
     *
     * Deliberate deviation from the legacy: WS-CALC is unsigned (CASH00.cbl:L17), so a debit larger than the
     * balance did not fail - it committed a positive balance equal to the magnitude of the overdraft. The
     * replacement refuses the write instead (AAP 0.4.6, 0.14.2).
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
     * The legacy-parity primitive, shared by the live service (through applyRateChecked) and by the
     * migration tooling's LegacyBalanceCalculator, so that one implementation of the arithmetic exists.
     *
     * WHY ONE FINAL TRUNCATION AND NOT TWO - the single most important property of this file. The legacy
     * COMPUTE evaluated the whole expression and truncated once, on storing the result into the two-decimal
     * WS-CALC (CASH00.cbl:L222 credit, L256 debit, field at L17). Truncating the product first is NOT
     * equivalent for a debit: with stored 100.00, rate 0.03 and amount 0.30, the single final truncation
     * gives truncate2(100.00 - 0.009) = 99.99, whereas a truncated product gives 100.00 - 0.00 = 100.00.
     * The product therefore keeps full BigDecimal precision - no setScale, no round, no MathContext between
     * the multiply and the signed addition - and setScale is applied exactly once, to the final result.
     *
     * WHY THE RESULT IS RETURNED RAW AND SIGNED, with no bounds check, no abs() and no modulus: this is the
     * split that makes one primitive serve both callers. LegacyBalanceCalculator must still see the negative
     * or oversized value in order to layer the legacy behaviour on top - the absolute value the unsigned
     * WS-CALC stored, and the modulus 10^7 by which an oversized product lost its high-order digits because
     * BALANC-RATE was the wider PIC 9(8)V99 (CASH00.cbl:L26). Clamping here would erase exactly the
     * historical values the reconciliation exists to recognize. The live service's bounds live in
     * applyRateChecked instead.
     *
     * WHY THE RATE IS UNCONSTRAINED IN SCALE: the legacy column was DECIMAL(3, 2) / PIC S9(1)V9(2) COMP-3
     * (backend/cash-account-cobol/COBOL/DCLFRANK.cpy:L12, L22), two decimals with a ceiling of 9.99, which
     * could not represent a currency worth less than a tenth of the base unit - JPY and INR were simply
     * unrepresentable. Live rates carry four to six significant digits. Bounding or pre-scaling the rate here
     * would re-introduce that defect on the live path.
     *
     * WHY A BAD ARGUMENT IS AN IllegalArgumentException AND NOT A CashAccountException: a null operand or a
     * sign that is neither SIGN_CREDIT nor SIGN_DEBIT is a programming error, not a condition a caller can
     * provoke. Rendering it as a business ApiError would give a bug a plausible-looking 4xx and hide it; the
     * exception handler's catch-all maps it to 500 INTERNAL, which is the correct signal.
     */
    public static BigDecimal applyRate(BigDecimal stored, int sign, BigDecimal rate, BigDecimal amount) {
        if (stored == null || rate == null || amount == null) {
            throw new IllegalArgumentException("stored, rate and amount are required");
        }
        if (sign != SIGN_CREDIT && sign != SIGN_DEBIT) {
            throw new IllegalArgumentException("sign must be Money.SIGN_CREDIT or Money.SIGN_DEBIT, was " + sign);
        }
        BigDecimal product = rate.multiply(amount);
        BigDecimal result = sign == SIGN_CREDIT ? stored.add(product) : stored.subtract(product);
        return result.setScale(SCALE, ROUNDING);
    }

    /*
     * The live service's entry point to the same arithmetic: it delegates so that no second implementation
     * can drift, then applies the two bounds the legacy program did not enforce.
     *
     * A negative result is INSUFFICIENT_FUNDS (422): the legacy stored its absolute value because WS-CALC is
     * unsigned (CASH00.cbl:L17). A result past the ceiling is AMOUNT_OUT_OF_RANGE (422): the legacy silently
     * dropped high-order digits because BALANC-RATE was wider than WS-CALC (CASH00.cbl:L17, L26). Both are
     * authorized deviations, and both leave the balance unchanged because the exception is thrown before any
     * write. The accepted result is wrapped without re-truncating - applyRate already scaled it, so the
     * constructor's setScale is an identity here and discards nothing.
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

    /** Ordered by value, consistently with equals, so sorted collections and comparisons agree. */
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

    /** Plain notation, never exponent: the wire and the logs carry money as 1234.56, not as 1.23456E+3. */
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
