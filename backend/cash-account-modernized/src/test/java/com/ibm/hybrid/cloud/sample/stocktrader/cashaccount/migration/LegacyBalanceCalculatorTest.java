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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.Money;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.LegacyBalanceCalculator;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.LegacyCharacterization;

/** Unit tests pinning {@code LegacyBalanceCalculator} to the characterized {@code CASH00} arithmetic. */
class LegacyBalanceCalculatorTest {

    /*
     * WHY EVERY ASSERTION IS MADE TWICE, AGAINST A LITERAL AND AGAINST A DERIVED VALUE. A literal alone
     * records the answer but not the reason, so a change of scale, rounding, sign handling or wrap point
     * could be "fixed" by retyping the literal. A derived value alone is a mirror of the implementation:
     * it would follow the calculator wherever it went, including away from the legacy behaviour. Paired,
     * the literal is the fixed anchor a reviewer can check against
     * backend/cash-account-cobol/COBOL/CASH00.cbl, and the derivation -- built only from the constants of
     * LegacyCharacterization, never from a number typed here -- is what fails if the characterized
     * parameters and the calculator ever stop agreeing (AAP 0.10.1). That pairing is deliberate and is not
     * a duplicated test.
     *
     * WHY SCALE-SENSITIVE EQUALITY THROUGHOUT: isEqualTo on BigDecimal compares scale as well as value, so
     * one assertion proves both the digits and the two decimals of WS-CALC pic 9(7)V99 (CASH00.cbl:L17).
     * isEqualByComparingTo appears nowhere below, because it would accept 1250.5 for 1250.50 and silently
     * stop evidencing the fixed-point width this whole file exists to pin down.
     *
     * WHY EVERY BigDecimal COMES FROM TEXT: no double literal, no BigDecimal.valueOf(double), no parse of a
     * floating value (AAP 0.7.1). A rate such as 0.92 has no exact binary representation, and the
     * discriminating case below turns on a fourth decimal place, so a binary detour would decide these
     * assertions on representation error rather than on the order of truncation.
     *
     * Rate operands stay within two decimals and 9.99 -- the legacy column was DECIMAL(3, 2) /
     * PIC S9(1)V9(2) COMP-3 (backend/cash-account-cobol/COBOL/DCLFRANK.cpy:L12, L22) -- so no case asserts
     * parity on a rate shape the legacy table could never have held.
     */

    /*
     * The characterized formula, re-derived here from the legacy statement rather than by calling the class
     * under test: full-precision product, signed addition, then ONE truncation of the final result using
     * the characterization's own scale and rounding. This is the COMPUTE at CASH00.cbl:L222 (credit) and
     * L256 (debit), whose multiplicand is the caller's COMMAREA amount moved in at L221/L255 -- not the
     * rate row's own AMOUNT column, which the program fetches and never reads.
     *
     * It stops at the truncation on purpose. The absolute value and the modulus are separate characterized
     * facts of the unsigned, seven-integer-digit result field, so each scenario below applies the one it
     * exists to pin down; folding them in here as unconditional no-ops would hide which scenario proves
     * what.
     */
    private static BigDecimal characterizedRawResult(String storedBalance, int sign, String rate,
            String callerAmount) {
        BigDecimal product = new BigDecimal(rate).multiply(new BigDecimal(callerAmount));
        BigDecimal stored = new BigDecimal(storedBalance);
        BigDecimal signed = sign == Money.SIGN_CREDIT ? stored.add(product) : stored.subtract(product);
        return signed.setScale(LegacyCharacterization.BALANCE_SCALE, LegacyCharacterization.BALANCE_ROUNDING);
    }

    @Test
    void appliesTheLegacyFormulaWithOneFinalTruncation() {
        // JOHN, shadow seq 2: a same-currency credit, where the rate is exactly 1.00 and parity is trivial.
        Money johnCredit = LegacyBalanceCalculator.credit(new BigDecimal("1000.00"), new BigDecimal("1.00"),
                new BigDecimal("250.50"));
        assertThat(johnCredit.amount()).isEqualTo(new BigDecimal("1250.50"));
        assertThat(johnCredit.amount())
                .isEqualTo(characterizedRawResult("1000.00", Money.SIGN_CREDIT, "1.00", "250.50"));
        assertThat(johnCredit.amount().scale()).isEqualTo(LegacyCharacterization.BALANCE_SCALE);

        // GREG, shadow seq 4: the credit case where a rate other than 1.00 actually scales the amount.
        Money gregCredit = LegacyBalanceCalculator.credit(new BigDecimal("123456.78"), new BigDecimal("0.79"),
                new BigDecimal("100.00"));
        assertThat(gregCredit.amount()).isEqualTo(new BigDecimal("123535.78"));
        assertThat(gregCredit.amount())
                .isEqualTo(characterizedRawResult("123456.78", Money.SIGN_CREDIT, "0.79", "100.00"));

        // KARRI, shadow seq 3: the debit mirror of the same statement, differing only in the operator.
        Money karriDebit = LegacyBalanceCalculator.debit(new BigDecimal("12345.67"), new BigDecimal("1.00"),
                new BigDecimal("345.67"));
        assertThat(karriDebit.amount()).isEqualTo(new BigDecimal("12000.00"));
        assertThat(karriDebit.amount())
                .isEqualTo(characterizedRawResult("12345.67", Money.SIGN_DEBIT, "1.00", "345.67"));

        /*
         * ERIC, shadow seq 5, and the one case here that can fail for a reason other than a typo. The
         * product 0.92 x 0.30 = 0.2760 is carried at full precision, 1234567.89 - 0.2760 = 1234567.6140,
         * and the single final truncation of CASH00.cbl:L256 yields 1234567.61. An implementation that
         * truncated the product first would subtract 0.27 and answer 1234567.62; one that truncated it
         * toward a whole unit would subtract nothing and answer 1234567.89. This case is therefore the
         * witness that the product is not scaled before the subtraction.
         *
         * Routed through apply with an explicit sign rather than through debit, because the reconciliation
         * tooling carries the legacy one-character request code C or D and reaches the arithmetic by that
         * entry point; the two convenience methods delegate to it, so covering it covers the real path.
         */
        Money ericDebit = LegacyBalanceCalculator.apply(new BigDecimal("1234567.89"), Money.SIGN_DEBIT,
                new BigDecimal("0.92"), new BigDecimal("0.30"));
        assertThat(ericDebit.amount()).isEqualTo(new BigDecimal("1234567.61"));
        assertThat(ericDebit.amount())
                .isEqualTo(characterizedRawResult("1234567.89", Money.SIGN_DEBIT, "0.92", "0.30"));

        /*
         * RAUNAK, shadow seq 7: a zero amount is a legal, ordinary transaction, not a no-op to be skipped.
         * The legacy program computed stored +/- 0, ran the UPDATE, returned SQLCODE 0 and wrote a history
         * record, so the migrated ledger keeps a zero-amount row for transaction-count parity (AAP 0.4.5).
         * The balance must come back unchanged and still at scale 2.
         */
        Money raunakZeroCredit = LegacyBalanceCalculator.credit(new BigDecimal("100.00"), new BigDecimal("1.00"),
                new BigDecimal("0.00"));
        assertThat(raunakZeroCredit.amount()).isEqualTo(new BigDecimal("100.00"));
        assertThat(raunakZeroCredit.amount())
                .isEqualTo(characterizedRawResult("100.00", Money.SIGN_CREDIT, "1.00", "0.00"));
    }

    @Test
    void takesTheAbsoluteValueOfANegativeResult() {
        /*
         * The sign-drop seed of the shadow seeded-mismatch stream: RAUNAK holds 100.00 and is debited
         * 150.00 at rate 1.00. WS-CALC carries no S in its picture (CASH00.cbl:L17), so the COMPUTE at
         * L256 stored the magnitude and MOVE WS-CALC TO BALANCE (L259) carried that magnitude into the
         * UPDATE: the account ended at a positive 50.00 equal to the overdraft, reported with SQLCODE 0.
         *
         * WHY THIS IS REPRODUCED AT ALL, when the live service refuses the same input with
         * 422 INSUFFICIENT_FUNDS and writes nothing (AAP 0.4.6, an authorized deviation): the calculator
         * exists solely so reconciliation and the dual run can compute the number the legacy system
         * actually stored. Judged against a corrected value instead, every historical overdraft would
         * surface as an unexplained variance and bury the genuine defects the comparison exists to find --
         * which is why the comparator classifies this case as REJECTED_BY_TARGET / ACCEPTED_EXCEPTION
         * rather than as a balance break.
         */
        assertThat(LegacyCharacterization.RESULT_IS_UNSIGNED)
                .as("WS-CALC pic 9(7)V99 (CASH00.cbl:L17) has no S, so the legacy result carried no sign")
                .isTrue();

        BigDecimal raw = characterizedRawResult("100.00", Money.SIGN_DEBIT, "1.00", "150.00");
        assertThat(raw)
                .as("the signed computation really does go negative before the sign is dropped")
                .isEqualTo(new BigDecimal("-50.00"));

        // Gated on the characterized flag, so flipping it in one place makes the literal below fail loudly.
        BigDecimal expected = LegacyCharacterization.RESULT_IS_UNSIGNED ? raw.abs() : raw;

        Money result = LegacyBalanceCalculator.debit(new BigDecimal("100.00"), new BigDecimal("1.00"),
                new BigDecimal("150.00"));
        assertThat(result.amount()).isEqualTo(new BigDecimal("50.00"));
        assertThat(result.amount()).isEqualTo(expected);
        assertThat(result.amount().scale()).isEqualTo(LegacyCharacterization.BALANCE_SCALE);
    }

    @Test
    void dropsHighOrderDigitsOnOverflow() {
        /*
         * WS-CALC holds only LegacyCharacterization.BALANCE_INTEGER_DIGITS integer digits
         * (pic 9(7)V99, CASH00.cbl:L17) and neither COMPUTE carries an ON SIZE ERROR phrase
         * (CASH00.cbl:L222, L256), so a result of ten million or more was stored having silently lost its
         * high-order digits while the return channel still reported the UPDATE's SQLCODE 0. The result is
         * reachable because BALANC-RATE is the wider PIC 9(8)V99 (CASH00.cbl:L26), so the intermediate
         * value could exceed what the destination field could hold.
         *
         * The live service returns 422 AMOUNT_OUT_OF_RANGE for this condition instead and leaves the
         * balance untouched (AAP 0.4.6); the wrap is reproduced here only so that a migrated balance which
         * wrapped can be recognized as the legitimate historical value it is.
         *
         * Both expectations are derived with LegacyCharacterization.BALANCE_MODULUS and never with a
         * literal ten million: the wrap point is the characterized 10^BALANCE_INTEGER_DIGITS, and restating
         * it here would be a second characterization free to drift from the document.
         */
        BigDecimal justOverRaw = characterizedRawResult("9999999.99", Money.SIGN_CREDIT, "1.00", "1.00");
        assertThat(justOverRaw).isEqualTo(new BigDecimal("10000000.99"));
        assertThat(justOverRaw.precision() - justOverRaw.scale())
                .as("the raw result needs more integer digits than the legacy field could hold")
                .isGreaterThan(LegacyCharacterization.BALANCE_INTEGER_DIGITS);

        Money justOver = LegacyBalanceCalculator.credit(new BigDecimal("9999999.99"), new BigDecimal("1.00"),
                new BigDecimal("1.00"));
        assertThat(justOver.amount()).isEqualTo(new BigDecimal("0.99"));
        assertThat(justOver.amount()).isEqualTo(justOverRaw.remainder(LegacyCharacterization.BALANCE_MODULUS));
        assertThat(justOver.amount().scale()).isEqualTo(LegacyCharacterization.BALANCE_SCALE);

        /*
         * A second case that loses more than one high-order digit, so the behaviour is evidenced as a
         * modulus rather than as a single-digit truncation that a subtraction of ten million would also
         * satisfy: 0.92 x 1000000.00 = 920000.0000 gives a raw 10919999.99, of which the legacy field kept
         * 919999.99.
         */
        BigDecimal wellOverRaw = characterizedRawResult("9999999.99", Money.SIGN_CREDIT, "0.92", "1000000.00");
        assertThat(wellOverRaw).isEqualTo(new BigDecimal("10919999.99"));

        Money wellOver = LegacyBalanceCalculator.credit(new BigDecimal("9999999.99"), new BigDecimal("0.92"),
                new BigDecimal("1000000.00"));
        assertThat(wellOver.amount()).isEqualTo(new BigDecimal("919999.99"));
        assertThat(wellOver.amount()).isEqualTo(wellOverRaw.remainder(LegacyCharacterization.BALANCE_MODULUS));
    }
}
