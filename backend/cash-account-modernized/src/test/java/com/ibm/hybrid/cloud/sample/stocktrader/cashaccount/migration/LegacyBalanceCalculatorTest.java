package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.Money;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.LegacyBalanceCalculator;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.LegacyCharacterization;

/** Unit tests pinning {@code LegacyBalanceCalculator} to the characterized {@code CASH00} arithmetic. */
class LegacyBalanceCalculatorTest {

    // Every assertion is made twice, against a literal and against a derived value, because a literal alone
    // could be "fixed" by retyping it while a derivation alone would follow the calculator away from the legacy
    // behaviour: the literal is the anchor a reviewer checks against backend/cash-account-cobol/COBOL/CASH00.cbl
    // and the derivation, built only from LegacyCharacterization's constants, fails if the characterized
    // parameters and the calculator stop agreeing (AAP 0.10.1). Scale-sensitive isEqualTo throughout proves both
    // the digits and the two decimals of WS-CALC pic 9(7)V99 (CASH00.cbl:L17); every BigDecimal comes from text
    // (AAP 0.7.1), since the discriminating case turns on a fourth decimal place; and rate operands stay inside
    // the legacy DECIMAL(3, 2) / PIC S9(1)V9(2) COMP-3 column (DCLFRANK.cpy:L12, L22).

    // The characterized formula, re-derived from the legacy statement rather than by calling the class under
    // test: full-precision product, signed addition, then one truncation of the final result. This is the COMPUTE
    // at CASH00.cbl:L222 (credit) and L256 (debit), whose multiplicand is the caller's COMMAREA amount moved in
    // at L221/L255 - not the rate row's own AMOUNT column, which the program fetches and never reads. It stops
    // at the truncation because the absolute value and the modulus are separate characterized facts, applied by
    // the scenario that exists to pin each down.
    private static BigDecimal characterizedRawResult(String storedBalance, int sign, String rate,
            String callerAmount) {
        BigDecimal product = new BigDecimal(rate).multiply(new BigDecimal(callerAmount));
        BigDecimal stored = new BigDecimal(storedBalance);
        BigDecimal signed = sign == Money.SIGN_CREDIT ? stored.add(product) : stored.subtract(product);
        return signed.setScale(LegacyCharacterization.BALANCE_SCALE, LegacyCharacterization.BALANCE_ROUNDING);
    }

    @Test
    void appliesTheLegacyFormulaWithOneFinalTruncation() {
        // JOHN, shadow seq 2: a same-currency credit, where the rate is 1.00 and parity is trivial.
        Money johnCredit = LegacyBalanceCalculator.credit(new BigDecimal("1000.00"), new BigDecimal("1.00"),
                new BigDecimal("250.50"));
        assertThat(johnCredit.amount()).isEqualTo(new BigDecimal("1250.50"));
        assertThat(johnCredit.amount())
                .isEqualTo(characterizedRawResult("1000.00", Money.SIGN_CREDIT, "1.00", "250.50"));
        assertThat(johnCredit.amount().scale()).isEqualTo(LegacyCharacterization.BALANCE_SCALE);

        // GREG, shadow seq 4: the credit case where the rate actually scales the amount.
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

        // ERIC, shadow seq 5: the one case that can fail for a reason other than a typo. The product
        // 0.92 x 0.30 = 0.2760 is carried at full precision and the single final truncation of CASH00.cbl:L256
        // yields 1234567.61, where truncating the product first would subtract 0.27 and answer 1234567.62 and
        // truncating toward a whole unit would subtract nothing. Routed through apply with an explicit sign
        // because the tooling carries the legacy one-character request code and reaches the arithmetic that way.
        Money ericDebit = LegacyBalanceCalculator.apply(new BigDecimal("1234567.89"), Money.SIGN_DEBIT,
                new BigDecimal("0.92"), new BigDecimal("0.30"));
        assertThat(ericDebit.amount()).isEqualTo(new BigDecimal("1234567.61"));
        assertThat(ericDebit.amount())
                .isEqualTo(characterizedRawResult("1234567.89", Money.SIGN_DEBIT, "0.92", "0.30"));

        // RAUNAK, shadow seq 7: a zero amount is a legal, ordinary transaction, not a no-op to be skipped. The
        // legacy program computed stored +/- 0, ran the UPDATE, returned SQLCODE 0 and wrote a history record, so
        // the migrated ledger keeps a zero-amount row for transaction-count parity (AAP 0.4.5).
        Money raunakZeroCredit = LegacyBalanceCalculator.credit(new BigDecimal("100.00"), new BigDecimal("1.00"),
                new BigDecimal("0.00"));
        assertThat(raunakZeroCredit.amount()).isEqualTo(new BigDecimal("100.00"));
        assertThat(raunakZeroCredit.amount())
                .isEqualTo(characterizedRawResult("100.00", Money.SIGN_CREDIT, "1.00", "0.00"));
    }

    @Test
    void takesTheAbsoluteValueOfANegativeResult() {
        // The sign-drop seed of the shadow seeded-mismatch stream. WS-CALC carries no S in its picture
        // (CASH00.cbl:L17), so the COMPUTE at L256 stored the magnitude and MOVE WS-CALC TO BALANCE (L259)
        // carried it into the UPDATE: the account ended at a positive balance equal to the overdraft under
        // SQLCODE 0. The live service refuses that input with 422 INSUFFICIENT_FUNDS and writes nothing
        // (AAP 0.4.6, an authorized deviation), so the magnitude is reproduced here only because reconciliation
        // has to compute the number the legacy system actually stored - judged against a corrected value, every
        // historical overdraft would surface as an unexplained variance instead of the comparator's
        // REJECTED_BY_TARGET / ACCEPTED_EXCEPTION classification.
        assertThat(LegacyCharacterization.RESULT_IS_UNSIGNED)
                .as("WS-CALC pic 9(7)V99 (CASH00.cbl:L17) has no S, so the legacy result carried no sign")
                .isTrue();

        BigDecimal raw = characterizedRawResult("100.00", Money.SIGN_DEBIT, "1.00", "150.00");
        assertThat(raw)
                .as("the signed computation really does go negative before the sign is dropped")
                .isEqualTo(new BigDecimal("-50.00"));

        // Gated on the characterized flag, so flipping it in one place makes the literal below fail.
        BigDecimal expected = LegacyCharacterization.RESULT_IS_UNSIGNED ? raw.abs() : raw;

        Money result = LegacyBalanceCalculator.debit(new BigDecimal("100.00"), new BigDecimal("1.00"),
                new BigDecimal("150.00"));
        assertThat(result.amount()).isEqualTo(new BigDecimal("50.00"));
        assertThat(result.amount()).isEqualTo(expected);
        assertThat(result.amount().scale()).isEqualTo(LegacyCharacterization.BALANCE_SCALE);
    }

    @Test
    void dropsHighOrderDigitsOnOverflow() {
        // WS-CALC holds only BALANCE_INTEGER_DIGITS integer digits (pic 9(7)V99, CASH00.cbl:L17) and neither
        // COMPUTE carries an ON SIZE ERROR phrase (CASH00.cbl:L222, L256), so a result of ten million or more was
        // stored having silently lost its high-order digits while the return channel still reported the UPDATE's
        // SQLCODE 0 - reachable because BALANC-RATE is the wider PIC 9(8)V99 (CASH00.cbl:L26). The live service
        // answers 422 AMOUNT_OUT_OF_RANGE and leaves the balance untouched (AAP 0.4.6); the wrap is reproduced
        // only so a migrated balance that wrapped is recognizable as the historical value it is. Both
        // expectations derive from BALANCE_MODULUS rather than a literal ten million, which would be a second
        // characterization free to drift from the document.
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

        // A second case losing more than one high-order digit, so the behaviour is evidenced as a modulus and
        // not as a single-digit truncation that a subtraction of ten million would also satisfy.
        BigDecimal wellOverRaw = characterizedRawResult("9999999.99", Money.SIGN_CREDIT, "0.92", "1000000.00");
        assertThat(wellOverRaw).isEqualTo(new BigDecimal("10919999.99"));

        Money wellOver = LegacyBalanceCalculator.credit(new BigDecimal("9999999.99"), new BigDecimal("0.92"),
                new BigDecimal("1000000.00"));
        assertThat(wellOver.amount()).isEqualTo(new BigDecimal("919999.99"));
        assertThat(wellOver.amount()).isEqualTo(wellOverRaw.remainder(LegacyCharacterization.BALANCE_MODULUS));
    }
}
