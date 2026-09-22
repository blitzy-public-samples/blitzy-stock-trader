package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;

/** Unit tests for the module's only monetary type: scale-2 DOWN arithmetic with one final truncation. */
class MoneyTest {

    // Scale-sensitive isEqualTo throughout, because scale is part of the contract: it proves both the digits
    // and the two decimals of WS-CALC pic 9(7)V99 (backend/cash-account-cobol/COBOL/CASH00.cbl:L17). Every
    // literal reaches BigDecimal as text, so these cases turn on the order of truncation rather than on binary
    // representation error, and rates stay inside the legacy DECIMAL(3, 2) (DCLFRANK.cpy:L12, L22).

    @Test
    void debitTruncatesOnceAfterTheSignedSubtraction() {
        // The discriminating case for the whole design: the legacy COMPUTE evaluated the entire expression and
        // truncated once, on storing into the two-decimal WS-CALC (CASH00.cbl:L255-L256, field at L17, with
        // neither ROUNDED nor ON SIZE ERROR). 0.03 x 0.30 = 0.0090 stays at full precision, so 100.00 - 0.0090
        // truncates once to 99.99; truncating the product first would compute 100.00 - 0.00 and answer 100.00.
        assertThat(Money.applyRate(new BigDecimal("100.00"), Money.SIGN_DEBIT, new BigDecimal("0.03"),
                new BigDecimal("0.30")))
                .isEqualTo(new BigDecimal("99.99"));
        assertThat(Money.applyRateChecked(Money.of("100.00"), Money.SIGN_DEBIT, new BigDecimal("0.03"),
                Money.of("0.30")).amount())
                .isEqualTo(new BigDecimal("99.99"));

        // A second, independent triple from the shadow fixtures, so the property does not rest on one
        // hand-picked case: truncating the product first would subtract 0.27 and answer 1234567.62.
        assertThat(Money.applyRate(new BigDecimal("1234567.89"), Money.SIGN_DEBIT, new BigDecimal("0.92"),
                new BigDecimal("0.30")))
                .isEqualTo(new BigDecimal("1234567.61"));
        assertThat(Money.applyRateChecked(Money.of("1234567.89"), Money.SIGN_DEBIT, new BigDecimal("0.92"),
                Money.of("0.30")).amount())
                .isEqualTo(new BigDecimal("1234567.61"));
    }

    @Test
    void creditTruncatesOnceAfterTheSignedAddition() {
        // The credit mirror of the same single-truncation property: CASH00.cbl:L221-L222 differs from the debit
        // COMPUTE at L256 only in the operator, so one implementation serves both directions and both have to
        // be evidenced. This first case is exact at two decimals; the second carries a sub-cent fraction.
        assertThat(Money.applyRate(new BigDecimal("123456.78"), Money.SIGN_CREDIT, new BigDecimal("0.79"),
                new BigDecimal("100.00")))
                .isEqualTo(new BigDecimal("123535.78"));
        assertThat(Money.applyRateChecked(Money.of("123456.78"), Money.SIGN_CREDIT, new BigDecimal("0.79"),
                Money.of("100.00")).amount())
                .isEqualTo(new BigDecimal("123535.78"));

        // The added fraction of a cent is discarded rather than rounded up, matching the absent ROUNDED phrase.
        assertThat(Money.applyRate(new BigDecimal("1000.00"), Money.SIGN_CREDIT, new BigDecimal("0.92"),
                new BigDecimal("0.30")))
                .isEqualTo(new BigDecimal("1000.27"));
        assertThat(Money.applyRateChecked(Money.of("1000.00"), Money.SIGN_CREDIT, new BigDecimal("0.92"),
                Money.of("0.30")).amount())
                .isEqualTo(new BigDecimal("1000.27"));
    }

    @Test
    void factoriesNormalizeToScaleTwoTruncatingTowardZero() {
        assertThat(Money.SCALE).isEqualTo(2);
        assertThat(Money.ROUNDING).isEqualTo(RoundingMode.DOWN);

        // Every arity of the incoming text is normalized because a hold's idempotency hash is computed over the
        // scaled amount (AAP 0.7.3): were "10" and "10.00" to carry different scales, a legitimate replay would
        // hash differently and be refused as key reuse.
        assertThat(Money.of("1").amount()).isEqualTo(new BigDecimal("1.00"));
        assertThat(Money.of("1").amount().scale()).isEqualTo(2);
        assertThat(Money.of("1.0").amount().scale()).isEqualTo(2);
        assertThat(Money.of("1.00").amount().scale()).isEqualTo(2);
        assertThat(Money.of("1").compareTo(Money.of("1.0"))).isZero();
        assertThat(Money.of("1.0").compareTo(Money.of("1.00"))).isZero();

        // The rounding-mode discriminators: a third decimal is discarded, never rounded, so a HALF_UP
        // implementation would answer 1.01, 12.35 and 1.00 and fail here rather than silently replace the
        // truncation of WS-CALC (CASH00.cbl:L17) with commercial rounding.
        assertThat(Money.of("1.005").amount()).isEqualTo(new BigDecimal("1.00"));
        assertThat(Money.of(new BigDecimal("12.349")).amount()).isEqualTo(new BigDecimal("12.34"));
        assertThat(Money.of("0.999").amount()).isEqualTo(new BigDecimal("0.99"));
    }

    @Test
    void ceilingIsRejectedOnTheCheckedPathAndDeliberatelyNotOnTheRawPath() {
        assertThat(Money.MAX_VALUE).isEqualTo(new BigDecimal("9999999.99"));
        assertThat(Money.of("9999999.99").amount()).isEqualTo(Money.MAX_VALUE);

        // Deliberate deviation, not a parity gap (AAP 0.4.6): WS-CALC is pic 9(7)V99 (CASH00.cbl:L17) while
        // BALANC-RATE was the wider PIC 9(8)V99 (CASH00.cbl:L26) and neither COMPUTE carries ON SIZE ERROR, so
        // a result of ten million or more silently lost its high-order digits and committed under a success
        // code. The checked path refuses the write instead.
        assertThatThrownBy(() -> Money.of("10000000.00"))
                .isInstanceOf(CashAccountException.class)
                .extracting(rejection -> ((CashAccountException) rejection).errorCode())
                .isEqualTo(CashAccountErrorCode.AMOUNT_OUT_OF_RANGE);
        assertThatThrownBy(() -> Money.applyRateChecked(Money.of("9999999.99"), Money.SIGN_CREDIT,
                new BigDecimal("1.00"), Money.of("1.00")))
                .isInstanceOf(CashAccountException.class)
                .extracting(rejection -> ((CashAccountException) rejection).errorCode())
                .isEqualTo(CashAccountErrorCode.AMOUNT_OUT_OF_RANGE);

        // Why two entry points exist: the raw primitive returns the oversized value unbounded, because the
        // reconciliation tooling has to see it in order to layer the legacy loss of high-order digits on top
        // and so recognize a historical balance. Clamping here would erase what reconciliation explains.
        assertThat(Money.applyRate(new BigDecimal("9999999.99"), Money.SIGN_CREDIT, new BigDecimal("1.00"),
                new BigDecimal("1.00")))
                .isEqualTo(new BigDecimal("10000000.99"));
    }

    @Test
    void negativeIsRejectedOnTheCheckedPathAndDeliberatelyNotOnTheRawPath() {
        assertThat(Money.MIN_VALUE).isEqualTo(new BigDecimal("0.00"));

        // A negative literal handed straight to the factory is a caller error, so it is INVALID_AMOUNT (400)
        // rather than the 422 of a computed shortfall - two conditions the legacy return field could not tell
        // apart, dropping the sign of every SQLCODE (CASH00.cbl:L104). AAP 0.6.2 groups "missing, negative or
        // non-numeric" under the one code, so unparsable text answers the same.
        assertThatThrownBy(() -> Money.of("-0.01"))
                .isInstanceOf(CashAccountException.class)
                .extracting(rejection -> ((CashAccountException) rejection).errorCode())
                .isEqualTo(CashAccountErrorCode.INVALID_AMOUNT);
        assertThatThrownBy(() -> Money.of("not-a-number"))
                .isInstanceOf(CashAccountException.class)
                .extracting(rejection -> ((CashAccountException) rejection).errorCode())
                .isEqualTo(CashAccountErrorCode.INVALID_AMOUNT);

        // The sub-cent case is what distinguishes judging the sign before scaling from scaling first, which
        // -0.01 alone cannot: -0.001 truncates DOWN to 0.00, so an implementation that normalized first would
        // accept it as a legal zero - a retail debit answering 200 with a zero-amount ledger row, and a settle
        // read as the zero settlement that consumes nothing and releases the whole hold. The last assertion is
        // the other half of the ordering: the sign judged, scaling still happens and still truncates DOWN.
        assertThatThrownBy(() -> Money.of(new BigDecimal("-0.001")))
                .isInstanceOf(CashAccountException.class)
                .extracting(rejection -> ((CashAccountException) rejection).errorCode())
                .isEqualTo(CashAccountErrorCode.INVALID_AMOUNT);
        assertThatThrownBy(() -> Money.of("-0.001"))
                .isInstanceOf(CashAccountException.class)
                .extracting(rejection -> ((CashAccountException) rejection).errorCode())
                .isEqualTo(CashAccountErrorCode.INVALID_AMOUNT);
        assertThat(Money.of("0.001").amount()).isEqualTo(new BigDecimal("0.00"));

        // Deliberate deviation, not a parity gap (AAP 0.4.6, 0.6.2): WS-CALC is unsigned (CASH00.cbl:L17), so a
        // debit larger than the balance did not fail at L255-L256 - it committed a positive balance equal to the
        // magnitude of the overdraft. This rejects it with INSUFFICIENT_FUNDS, thrown before any write.
        assertThatThrownBy(() -> Money.applyRateChecked(Money.of("100.00"), Money.SIGN_DEBIT,
                new BigDecimal("1.00"), Money.of("150.00")))
                .isInstanceOf(CashAccountException.class)
                .extracting(rejection -> ((CashAccountException) rejection).errorCode())
                .isEqualTo(CashAccountErrorCode.INSUFFICIENT_FUNDS);

        // The same contrast as the ceiling: the raw primitive keeps the sign so the tooling can reproduce
        // what the unsigned legacy field stored for this very input.
        assertThat(Money.applyRate(new BigDecimal("100.00"), Money.SIGN_DEBIT, new BigDecimal("1.00"),
                new BigDecimal("150.00")))
                .isEqualTo(new BigDecimal("-50.00"));
    }

    @Test
    void extremeExponentInputIsRefusedBeforeAnythingScalesIt() {
        // The two limits are asserted because they are the contract every boundary binds against - the retail and
        // institutional controllers and fx/FrankfurterExchangeRateClient hold no numbers of their own - and because
        // lowering either towards a value a real amount or rate could reach would silently reject live traffic: a
        // balance occupies nine digits at scale 2 and a published rate four to six significant digits.
        assertThat(Money.MAX_INPUT_SCALE).isEqualTo(1000);
        assertThat(Money.MAX_INPUT_PRECISION).isEqualTo(1000);

        // Both edges of each limit, so the bound is pinned rather than merely present.
        assertThat(Money.isWithinInputBounds(new BigDecimal("1e-1000"))).isTrue();
        assertThat(Money.isWithinInputBounds(new BigDecimal("1e-1001"))).isFalse();
        assertThat(Money.isWithinInputBounds(new BigDecimal("1e1000"))).isTrue();
        assertThat(Money.isWithinInputBounds(new BigDecimal("1e1001"))).isFalse();
        assertThat(Money.isWithinInputBounds(new BigDecimal("1".repeat(1000)))).isTrue();
        assertThat(Money.isWithinInputBounds(new BigDecimal("1".repeat(1001)))).isFalse();
        assertThat(Money.isWithinInputBounds(null)).isFalse();

        // The case an abs-based bound would wave through, which is why the implementation compares both signs:
        // this literal parses to a scale of exactly Integer.MIN_VALUE, and Math.abs(Integer.MIN_VALUE) is itself
        // Integer.MIN_VALUE, so it would read as comfortably inside a limit of 1000.
        BigDecimal minimumScale = new BigDecimal("1E+2147483648");
        assertThat(minimumScale.scale()).isEqualTo(Integer.MIN_VALUE);
        assertThat(Money.isWithinInputBounds(minimumScale)).isFalse();

        /*
         * The adversarial calls are timed, with assertTimeout and not assertTimeoutPreemptively. Every
         * literal below is a dozen characters that parse for nothing - BigDecimal keeps the exponent as an int
         * scale field - and each one used to reach setScale, which has to materialize the digits: measured on this
         * JDK, 1e10000000 costs 3.6 s and ten million digits, and a larger exponent raises an ArithmeticException
         * from BigInteger's size ceiling, so a caller bought either seconds of CPU and hundreds of megabytes or a
         * 500 where the contract names a 400. A regression therefore shows up in elapsed time as much as in a wrong
         * error code, and the non-preemptive form measures the time of a call it lets finish - the preemptive form
         * abandons the thread, leaving a multi-gigabyte allocation running behind a passing suite.
         */
        assertTimeout(Duration.ofSeconds(2), () -> {
            assertThatThrownBy(() -> Money.of("1e1000000000"))
                    .isInstanceOf(CashAccountException.class)
                    .extracting(rejection -> ((CashAccountException) rejection).errorCode())
                    .isEqualTo(CashAccountErrorCode.INVALID_AMOUNT);
            assertThatThrownBy(() -> Money.of("1e-1000000000"))
                    .isInstanceOf(CashAccountException.class)
                    .extracting(rejection -> ((CashAccountException) rejection).errorCode())
                    .isEqualTo(CashAccountErrorCode.INVALID_AMOUNT);
            assertThatThrownBy(() -> Money.of(new BigDecimal("1E+1000000000")))
                    .isInstanceOf(CashAccountException.class)
                    .extracting(rejection -> ((CashAccountException) rejection).errorCode())
                    .isEqualTo(CashAccountErrorCode.INVALID_AMOUNT);

            // The measured multi-second case, and the reason the timeout is not decoration: this exponent is small
            // enough that the unguarded expansion completes instead of failing fast, so only the clock catches it.
            assertThatThrownBy(() -> Money.of("1e10000000"))
                    .isInstanceOf(CashAccountException.class)
                    .extracting(rejection -> ((CashAccountException) rejection).errorCode())
                    .isEqualTo(CashAccountErrorCode.INVALID_AMOUNT);

            // The rate is third-party data and reaches this primitive directly from the migration tooling, so the
            // operands are bounded here too. IllegalArgumentException and not CashAccountException, consistently
            // with the method's existing null and sign contract: every production path bounds its input at the
            // boundary that received it, so an operand of this size arriving here is a bug and belongs in a 500.
            assertThatThrownBy(() -> Money.applyRate(new BigDecimal("100.00"), Money.SIGN_CREDIT,
                    new BigDecimal("1e1000000000"), new BigDecimal("1.00")))
                    .isInstanceOf(IllegalArgumentException.class);
        });

        /*
         * The adjacent legitimate outcomes, asserted here so the size bound cannot be widened into a value bound
         * without this failing. 1.0E7 is what Double.toString produces on the caller's side for ten million
         * [.../broker/client/CashAccountClient.java:L83] - scale -6 and precision 2, comfortably inside the bounds -
         * so it must still be read as 10000000.00 and refused by the storage ceiling as 422 AMOUNT_OUT_OF_RANGE,
         * never as a malformed request. The 25-decimal rate is the live-precision case the legacy two-decimal RATES
         * column could not hold (AAP 0.12.5): it stays accepted at its natural scale and still truncates exactly
         * once, 100.00 + 0.9200000000000000123456789 = 100.9200000000000000123456789 -> 100.92.
         */
        assertThatThrownBy(() -> Money.of("1.0E7"))
                .isInstanceOf(CashAccountException.class)
                .extracting(rejection -> ((CashAccountException) rejection).errorCode())
                .isEqualTo(CashAccountErrorCode.AMOUNT_OUT_OF_RANGE);
        assertThatThrownBy(() -> Money.of(new BigDecimal("1.0E7")))
                .isInstanceOf(CashAccountException.class)
                .extracting(rejection -> ((CashAccountException) rejection).errorCode())
                .isEqualTo(CashAccountErrorCode.AMOUNT_OUT_OF_RANGE);
        assertThat(Money.of("1.005").amount()).isEqualTo(new BigDecimal("1.00"));
        assertThat(Money.applyRate(new BigDecimal("100.00"), Money.SIGN_CREDIT,
                new BigDecimal("0.9200000000000000123456789"), new BigDecimal("1.00")))
                .isEqualTo(new BigDecimal("100.92"));
    }

    @Test
    void zeroIsExactAndReportedByIsZero() {
        assertThat(Money.ZERO.isZero()).isTrue();
        assertThat(Money.of("0.00").isZero()).isTrue();
        assertThat(Money.of("0.01").isZero()).isFalse();

        Money balance = Money.of("1000.00");
        assertThat(balance.plus(Money.ZERO).compareTo(balance)).isZero();
        assertThat(balance.minus(Money.ZERO).compareTo(balance)).isZero();
        assertThat(Money.of("1000.01").compareTo(balance)).isPositive();
        assertThat(balance.compareTo(Money.of("1000.01"))).isNegative();

        // The zero-amount baseline of AAP 0.4.5, preserved rather than improved: legacy C and D with amount 0
        // computed stored +/- 0 at L222 and L256, updated the row and returned success, and the broker still
        // issues such a call, so both directions leave the balance exactly unchanged, scale included.
        assertThat(Money.applyRateChecked(balance, Money.SIGN_CREDIT, new BigDecimal("0.92"), Money.ZERO)
                .amount())
                .isEqualTo(new BigDecimal("1000.00"));
        assertThat(Money.applyRateChecked(balance, Money.SIGN_DEBIT, new BigDecimal("0.92"), Money.ZERO)
                .amount())
                .isEqualTo(new BigDecimal("1000.00"));
    }
}
