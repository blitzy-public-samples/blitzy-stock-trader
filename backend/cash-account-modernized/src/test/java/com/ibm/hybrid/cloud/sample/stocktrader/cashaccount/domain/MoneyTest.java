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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;

/** Unit tests for the module's only monetary type: scale-2 DOWN arithmetic with one final truncation. */
class MoneyTest {

    /*
     * Every expected value below is asserted with a scale-sensitive isEqualTo(new BigDecimal("...")) rather
     * than isEqualByComparingTo, because scale is part of the contract: BigDecimal.equals requires an
     * identical scale, so one assertion proves both the digits and the two decimals of
     * "WS-CALC pic 9(7)V99" (backend/cash-account-cobol/COBOL/CASH00.cbl:L17). Every literal reaches
     * BigDecimal as text, never through a binary fractional value, because 0.03 has no exact binary
     * representation and would make the discriminating cases below pass or fail on representation error
     * instead of on the order of truncation they exist to pin down.
     *
     * Rate literals stay within two decimals and 9.99: the legacy column was DECIMAL(3, 2) /
     * PIC S9(1)V9(2) COMP-3 (backend/cash-account-cobol/COBOL/DCLFRANK.cpy:L12, L22), so a wider rate is a
     * shape the legacy table could never have held and would not evidence parity.
     */

    @Test
    void debitTruncatesOnceAfterTheSignedSubtraction() {
        /*
         * The discriminating case for the whole design. The legacy COMPUTE evaluated the entire expression
         * and truncated once, on storing into the two-decimal WS-CALC (CASH00.cbl:L255-L256, field at L17,
         * with neither ROUNDED nor ON SIZE ERROR). Here 0.03 x 0.30 = 0.0090 is kept at full precision,
         * 100.00 - 0.0090 = 99.9910, and one final setScale(2, DOWN) yields 99.99. An implementation that
         * truncated the product first would compute 100.00 - 0.00 and answer 100.00, so this single
         * assertion separates the correct order of operations from the plausible wrong one.
         */
        assertThat(Money.applyRate(new BigDecimal("100.00"), Money.SIGN_DEBIT, new BigDecimal("0.03"),
                new BigDecimal("0.30")))
                .isEqualTo(new BigDecimal("99.99"));
        assertThat(Money.applyRateChecked(Money.of("100.00"), Money.SIGN_DEBIT, new BigDecimal("0.03"),
                Money.of("0.30")).amount())
                .isEqualTo(new BigDecimal("99.99"));

        /*
         * A second, independent discriminator taken from the shadow fixtures, so the property is not
         * evidenced by one hand-picked triple: 0.92 x 0.30 = 0.2760, 1234567.89 - 0.2760 = 1234567.6140,
         * truncated once to 1234567.61. Truncating the product first would round the 0.2760 down to 0.27
         * and answer 1234567.62.
         */
        assertThat(Money.applyRate(new BigDecimal("1234567.89"), Money.SIGN_DEBIT, new BigDecimal("0.92"),
                new BigDecimal("0.30")))
                .isEqualTo(new BigDecimal("1234567.61"));
        assertThat(Money.applyRateChecked(Money.of("1234567.89"), Money.SIGN_DEBIT, new BigDecimal("0.92"),
                Money.of("0.30")).amount())
                .isEqualTo(new BigDecimal("1234567.61"));
    }

    @Test
    void creditTruncatesOnceAfterTheSignedAddition() {
        /*
         * The credit mirror of the same single-truncation property: CASH00.cbl:L221-L222 differs from the
         * debit COMPUTE at L256 only in the operator, so one implementation serves both directions and both
         * directions have to be evidenced. 0.79 x 100.00 = 79.0000 is exact at two decimals, which is why
         * the second case below carries a fraction of a cent for the final truncation to discard.
         */
        assertThat(Money.applyRate(new BigDecimal("123456.78"), Money.SIGN_CREDIT, new BigDecimal("0.79"),
                new BigDecimal("100.00")))
                .isEqualTo(new BigDecimal("123535.78"));
        assertThat(Money.applyRateChecked(Money.of("123456.78"), Money.SIGN_CREDIT, new BigDecimal("0.79"),
                Money.of("100.00")).amount())
                .isEqualTo(new BigDecimal("123535.78"));

        // 0.92 x 0.30 = 0.2760; 1000.00 + 0.2760 = 1000.2760, truncated once to 1000.27 - the added
        // fraction of a cent is discarded rather than rounded up, matching the absent ROUNDED phrase.
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

        /*
         * Normalization is asserted on every arity of the incoming text because the idempotency hash of a
         * hold is computed over the scaled amount: if "10" and "10.00" produced instances of different
         * scale, a legitimate replay would hash differently and be rejected as key reuse (AAP 0.7.3).
         * compareTo is used for the cross-checks so this test does not depend on equals semantics.
         */
        assertThat(Money.of("1").amount()).isEqualTo(new BigDecimal("1.00"));
        assertThat(Money.of("1").amount().scale()).isEqualTo(2);
        assertThat(Money.of("1.0").amount().scale()).isEqualTo(2);
        assertThat(Money.of("1.00").amount().scale()).isEqualTo(2);
        assertThat(Money.of("1").compareTo(Money.of("1.0"))).isZero();
        assertThat(Money.of("1.0").compareTo(Money.of("1.00"))).isZero();

        /*
         * The rounding-mode discriminators: a third decimal is discarded, never rounded. A HALF_UP
         * implementation would answer 1.01, 12.35 and 1.00 respectively, so these three cases fail loudly
         * if the truncation of CASH00.cbl:L17 is ever replaced by commercial rounding. The middle case goes
         * through the of(BigDecimal) factory so both entry points are covered.
         */
        assertThat(Money.of("1.005").amount()).isEqualTo(new BigDecimal("1.00"));
        assertThat(Money.of(new BigDecimal("12.349")).amount()).isEqualTo(new BigDecimal("12.34"));
        assertThat(Money.of("0.999").amount()).isEqualTo(new BigDecimal("0.99"));
    }

    @Test
    void ceilingIsRejectedOnTheCheckedPathAndDeliberatelyNotOnTheRawPath() {
        assertThat(Money.MAX_VALUE).isEqualTo(new BigDecimal("9999999.99"));
        assertThat(Money.of("9999999.99").amount()).isEqualTo(Money.MAX_VALUE);

        /*
         * Deliberate deviation, not a parity gap (AAP 0.4.6): WS-CALC is pic 9(7)V99 (CASH00.cbl:L17) while
         * BALANC-RATE was the wider PIC 9(8)V99 (CASH00.cbl:L26) and neither COMPUTE carries ON SIZE ERROR,
         * so a result of 10,000,000.00 or more silently lost its high-order digits and committed. The
         * replacement refuses the write instead. Only the error code is asserted: the message is a default
         * drawn from CashAccountErrorCode and is not part of the contract.
         */
        assertThatThrownBy(() -> Money.of("10000000.00"))
                .isInstanceOf(CashAccountException.class)
                .extracting(rejection -> ((CashAccountException) rejection).errorCode())
                .isEqualTo(CashAccountErrorCode.AMOUNT_OUT_OF_RANGE);
        assertThatThrownBy(() -> Money.applyRateChecked(Money.of("9999999.99"), Money.SIGN_CREDIT,
                new BigDecimal("1.00"), Money.of("1.00")))
                .isInstanceOf(CashAccountException.class)
                .extracting(rejection -> ((CashAccountException) rejection).errorCode())
                .isEqualTo(CashAccountErrorCode.AMOUNT_OUT_OF_RANGE);

        /*
         * The contrast that documents why two entry points exist: the raw primitive returns the oversized
         * value unbounded, because the reconciliation tooling has to see it in order to layer the legacy
         * loss of high-order digits on top and so recognize a historical balance. Clamping here would erase
         * exactly the values that reconciliation exists to explain. The legacy wrap itself is asserted by
         * the sibling migration test, not here.
         */
        assertThat(Money.applyRate(new BigDecimal("9999999.99"), Money.SIGN_CREDIT, new BigDecimal("1.00"),
                new BigDecimal("1.00")))
                .isEqualTo(new BigDecimal("10000000.99"));
    }

    @Test
    void negativeIsRejectedOnTheCheckedPathAndDeliberatelyNotOnTheRawPath() {
        assertThat(Money.MIN_VALUE).isEqualTo(new BigDecimal("0.00"));

        /*
         * A negative literal handed straight to the factory is a caller error, so it is INVALID_AMOUNT (400)
         * rather than the 422 of a computed shortfall - the two conditions are distinguishable here and were
         * indistinguishable in the legacy return field, which dropped the sign of every SQLCODE
         * (CASH00.cbl:L104). Unparsable text reaches the same code: AAP 0.6.2 groups "missing, negative or
         * non-numeric" under INVALID_AMOUNT.
         */
        assertThatThrownBy(() -> Money.of("-0.01"))
                .isInstanceOf(CashAccountException.class)
                .extracting(rejection -> ((CashAccountException) rejection).errorCode())
                .isEqualTo(CashAccountErrorCode.INVALID_AMOUNT);
        assertThatThrownBy(() -> Money.of("not-a-number"))
                .isInstanceOf(CashAccountException.class)
                .extracting(rejection -> ((CashAccountException) rejection).errorCode())
                .isEqualTo(CashAccountErrorCode.INVALID_AMOUNT);

        /*
         * The sub-cent case is what distinguishes judging the sign before scaling from scaling first, which
         * -0.01 alone cannot: -0.001 truncates DOWN to 0.00, so an implementation that normalized before
         * judging the sign would accept it as a legal zero - a retail debit answering 200 with a zero-amount
         * ledger row, and a settle read as the zero settlement that consumes nothing and releases the whole
         * hold. Both factories are asserted because of(String) has to inherit the decision through its
         * delegation rather than repeat it. The last assertion is the other half of the ordering: once the
         * sign has been judged on the value as written, scaling still happens and still truncates DOWN.
         */
        assertThatThrownBy(() -> Money.of(new BigDecimal("-0.001")))
                .isInstanceOf(CashAccountException.class)
                .extracting(rejection -> ((CashAccountException) rejection).errorCode())
                .isEqualTo(CashAccountErrorCode.INVALID_AMOUNT);
        assertThatThrownBy(() -> Money.of("-0.001"))
                .isInstanceOf(CashAccountException.class)
                .extracting(rejection -> ((CashAccountException) rejection).errorCode())
                .isEqualTo(CashAccountErrorCode.INVALID_AMOUNT);
        assertThat(Money.of("0.001").amount()).isEqualTo(new BigDecimal("0.00"));

        /*
         * Deliberate deviation, not a parity gap (AAP 0.4.6, 0.6.2): WS-CALC is unsigned (CASH00.cbl:L17),
         * so a debit larger than the balance did not fail at L255-L256 - it committed a positive balance
         * equal to the magnitude of the overdraft. The replacement rejects it with INSUFFICIENT_FUNDS and
         * leaves the balance untouched, because the exception is thrown before any write.
         */
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
    void zeroIsExactAndReportedByIsZero() {
        assertThat(Money.ZERO.isZero()).isTrue();
        assertThat(Money.of("0.00").isZero()).isTrue();
        assertThat(Money.of("0.01").isZero()).isFalse();

        Money balance = Money.of("1000.00");
        assertThat(balance.plus(Money.ZERO).compareTo(balance)).isZero();
        assertThat(balance.minus(Money.ZERO).compareTo(balance)).isZero();
        assertThat(Money.of("1000.01").compareTo(balance)).isPositive();
        assertThat(balance.compareTo(Money.of("1000.01"))).isNegative();

        /*
         * The zero-amount baseline of AAP 0.4.5, preserved rather than improved: legacy C and D with amount
         * 0 computed stored +/- 0 at L222 and L256, updated the row and returned success, and the broker
         * still issues such a call. Both directions must therefore leave the balance exactly unchanged -
         * scale included - so that the transaction the caller made stays countable without the balance
         * moving.
         */
        assertThat(Money.applyRateChecked(balance, Money.SIGN_CREDIT, new BigDecimal("0.92"), Money.ZERO)
                .amount())
                .isEqualTo(new BigDecimal("1000.00"));
        assertThat(Money.applyRateChecked(balance, Money.SIGN_DEBIT, new BigDecimal("0.92"), Money.ZERO)
                .amount())
                .isEqualTo(new BigDecimal("1000.00"));
    }
}
