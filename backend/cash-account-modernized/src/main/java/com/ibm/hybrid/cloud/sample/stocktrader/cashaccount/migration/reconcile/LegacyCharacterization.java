package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** The characterized fixed-point arithmetic of the legacy {@code CASH00} program, declared once. */
public final class LegacyCharacterization {

    /**
     * Two decimal places, the {@code V99} of {@code WS-CALC pic 9(7)V99} (CASH00.cbl:L17) -- a width
     * matched from the source, not a convention chosen by the replacement.
     *
     * <p>See docs/legacy-characterization.md, section "1.3 Constant: decimal scale 2". Each constant
     * here quotes its subsection heading verbatim and {@code CharacterizationDocPresentTest} asserts
     * those lines, so renaming one fails the build rather than orphaning the reference.</p>
     *
     * <p>Deliberately independent of {@code domain.Money}, whose coinciding scale describes the
     * target's money type and whose {@code NUMERIC(9,2)} ceiling may yet be widened (section 9.8);
     * what {@code CASH00} did is a frozen historical fact, so nothing outside {@code java.math} is
     * imported here.</p>
     */
    public static final int BALANCE_SCALE = 2;

    /**
     * Truncation toward zero, because neither {@code COMPUTE} carries a {@code ROUNDED} phrase
     * (CASH00.cbl:L222, L256) and COBOL discards the excess fractional digits on the store into the
     * two-decimal {@code WS-CALC}; with a two-decimal {@code RATES} and amount the product carries four
     * decimals, so this is reached on ordinary input rather than in an edge case.
     *
     * <p>See docs/legacy-characterization.md, section "1.4 Constant: rounding is `RoundingMode.DOWN`".</p>
     */
    public static final RoundingMode BALANCE_ROUNDING = RoundingMode.DOWN;

    /**
     * The result field carries no {@code S} ({@code WS-CALC pic 9(7)V99}, CASH00.cbl:L17), so the
     * {@code COMPUTE} stored the magnitude and {@code MOVE WS-CALC TO BALANCE} (L225 credit, L259
     * debit) carried it into the {@code UPDATE}: a debit larger than the balance committed a positive
     * balance equal to the overdraft. The live service refuses that with {@code 422
     * INSUFFICIENT_FUNDS} instead -- an intentional deviation from CASH00.cbl:L222/L256 -- so the old
     * result is reproduced only by the tooling, where a historical absolute-value balance must be
     * recognizable rather than reported as an unexplained variance.
     *
     * <p>See docs/legacy-characterization.md, section "1.5 Constant: unsigned result — where the sign
     * is dropped".</p>
     */
    public static final boolean RESULT_IS_UNSIGNED = true;

    /**
     * Seven integer digits, the {@code 9(7)} of {@code WS-CALC pic 9(7)V99} (CASH00.cbl:L17). Declared
     * beside {@link #BALANCE_MODULUS} so the modulus is derived from the digit count and checkable
     * against the picture clause, rather than restated as an unexplained 10^7.
     *
     * <p>See docs/legacy-characterization.md, section "1.6 Constant: modulus 10^7 — high-order digit
     * loss on overflow".</p>
     */
    public static final int BALANCE_INTEGER_DIGITS = 7;

    /**
     * 10^7, the wrap point of the legacy result field: neither {@code COMPUTE} carries an
     * {@code ON SIZE ERROR} phrase (CASH00.cbl:L222, L256), so a result of 10,000,000.00 or more was
     * stored with its high-order digits lost while the {@code UPDATE} still reported {@code SQLCODE 0}.
     * The live service returns {@code 422 AMOUNT_OUT_OF_RANGE} instead -- an intentional deviation from
     * the same two lines -- and the wrap is reproduced only by the tooling, because a wrapped legacy
     * balance is a legitimate historical value reconciliation has to explain.
     *
     * <p>See docs/legacy-characterization.md, section "1.6 Constant: modulus 10^7 — high-order digit
     * loss on overflow".</p>
     */
    public static final BigDecimal BALANCE_MODULUS = BigDecimal.TEN.pow(BALANCE_INTEGER_DIGITS);

    /**
     * Five characters, from {@code WS-CURRENCY-KEY PIC X(5)} (CASH00.cbl:L19), which the account's
     * {@code CURRENCYC CHAR(8)} is truncated into (CASH00.cbl:L213 credit, L247 debit) before the
     * {@code FRANKFURT1} row is selected on it (L215-L219, L249-L252) -- so a staged rate table keyed
     * on any other width would not reproduce the legacy lookup for longer currency values. Only the
     * length is declared here; the truncation itself belongs to {@code fx.LegacyRateTableSource}.
     *
     * <p>See docs/legacy-characterization.md, section "1.7 Constant: rate key length 5".</p>
     */
    public static final int RATE_KEY_LENGTH = 5;

    private LegacyCharacterization() {
    }
}
