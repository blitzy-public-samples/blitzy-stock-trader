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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile;

import java.math.BigDecimal;
import java.math.RoundingMode;

/*
 * Why one declaration point: every value below is a fixed-point parameter of a single COBOL statement
 * -- COMPUTE WS-CALC = BALANCE +/- (RATES * BALANC-RATE) (CASH00.cbl:L222 credit, L256 debit) -- and
 * reconciliation only means anything if LegacyBalanceCalculator, ReconciliationService and
 * shadow.ShadowComparator judge parity against identical parameters. An inline literal in any of those
 * would be a second characterization, free to diverge from this one without anything failing.
 * docs/legacy-characterization.md section 1 is the declared source, and each constant names the
 * subsection it comes from so a reviewer can check the claim against the legacy source instead of
 * trusting it. Those subsection headings are load-bearing: each reference below quotes one verbatim,
 * and CharacterizationDocPresentTest asserts those heading lines, so renaming or merging one fails the
 * build instead of leaving the reference here pointing at a section that no longer exists.
 *
 * Why this is deliberately independent of domain/Money, which declares a coinciding scale of 2 and
 * rounding of DOWN: Money describes the *target's* money type, whose NUMERIC(9,2) ceiling is a
 * recorded open item the requesting organization may authorize widening -- "one DDL change plus the
 * bounds in Money" (docs/legacy-characterization.md section 9.8). Widening the target must never
 * silently re-describe what CASH00 did, because that is a frozen historical fact rather than a
 * setting. The two agreeing today is by construction, the target having been built to preserve legacy
 * precision, and not by dependency -- so nothing outside java.math is imported here.
 */

/** The characterized fixed-point arithmetic of the legacy {@code CASH00} program, declared once. */
public final class LegacyCharacterization {

    /**
     * Two decimal places, from the {@code V99} of {@code WS-CALC pic 9(7)V99} (CASH00.cbl:L17).
     *
     * <p>See docs/legacy-characterization.md, section "1.3 Constant: decimal scale 2", which
     * establishes that every money field on the legacy path carried exactly this width -- so scale 2
     * is a width matched from the source, not a convention chosen by the replacement.</p>
     */
    public static final int BALANCE_SCALE = 2;

    /**
     * Truncation toward zero, because neither {@code COMPUTE} carries a {@code ROUNDED} phrase
     * (CASH00.cbl:L222, L256) and COBOL discards the excess fractional digits when storing a
     * higher-precision intermediate result into the two-decimal {@code WS-CALC}.
     *
     * <p>See docs/legacy-characterization.md, section "1.4 Constant: rounding is `RoundingMode.DOWN`".
     * With a two-decimal {@code RATES} and a two-decimal amount the product carries up to four
     * decimals, so this truncation is reached on ordinary input rather than in an edge case.</p>
     */
    public static final RoundingMode BALANCE_ROUNDING = RoundingMode.DOWN;

    /**
     * The legacy result field carries no {@code S} in its picture ({@code WS-CALC pic 9(7)V99},
     * CASH00.cbl:L17), so the sign is dropped as the {@code COMPUTE} stores into it and the absolute
     * value is what {@code MOVE WS-CALC TO BALANCE} (L225 credit, L259 debit) carries into the
     * {@code UPDATE}: a debit larger than the balance did not fail, it committed a positive balance
     * equal to the magnitude of the overdraft.
     *
     * <p>See docs/legacy-characterization.md, section "1.5 Constant: unsigned result — where the sign
     * is dropped".</p>
     *
     * <p>This constant characterizes the legacy program and never describes target behaviour. The
     * live service instead rejects the condition with {@code 422 INSUFFICIENT_FUNDS} and writes
     * nothing -- an intentional deviation from CASH00.cbl:L222/L256. The old result is reproduced
     * only by the migration tooling, so that a historical absolute-value balance can be recognized
     * for what it is rather than reported as an unexplained variance.</p>
     */
    public static final boolean RESULT_IS_UNSIGNED = true;

    /**
     * Seven integer digits, the {@code 9(7)} of {@code WS-CALC pic 9(7)V99} (CASH00.cbl:L17).
     *
     * <p>See docs/legacy-characterization.md, section "1.6 Constant: modulus 10^7 — high-order digit
     * loss on overflow". Declared beside {@link #BALANCE_MODULUS} rather than folded into it because
     * both express the same characterized fact; deriving the modulus from the digit count keeps that
     * relationship checkable instead of restating 10^7 as an unexplained number.</p>
     */
    public static final int BALANCE_INTEGER_DIGITS = 7;

    /**
     * 10^7, the wrap point of the legacy result field. Neither {@code COMPUTE} carries an
     * {@code ON SIZE ERROR} phrase (CASH00.cbl:L222, L256), so a result of 10,000,000.00 or more was
     * stored having silently lost its high-order digits while the return channel still reported the
     * {@code UPDATE}'s {@code SQLCODE 0} -- arithmetically a modulus by 10^7.
     *
     * <p>See docs/legacy-characterization.md, section "1.6 Constant: modulus 10^7 — high-order digit
     * loss on overflow".</p>
     *
     * <p>The live service instead returns {@code 422 AMOUNT_OUT_OF_RANGE} for this condition -- an
     * intentional deviation from CASH00.cbl:L222/L256 -- and the wrap is reproduced only by the
     * migration tooling, because a legacy balance that wrapped is a legitimate historical value that
     * reconciliation has to be able to explain. Held as a {@code BigDecimal} because it is an operand
     * of balance arithmetic, where {@code float} and {@code double} are prohibited outright.</p>
     */
    public static final BigDecimal BALANCE_MODULUS = BigDecimal.TEN.pow(BALANCE_INTEGER_DIGITS);

    /**
     * Five characters, from {@code WS-CURRENCY-KEY PIC X(5)} (CASH00.cbl:L19). The account's
     * {@code CURRENCYC CHAR(8)} is moved into that field by {@code MOVE CURRENCYC TO WS-CURRENCY-KEY}
     * (CASH00.cbl:L213 credit, L247 debit), truncating to five, and the {@code FRANKFURT1} row is then
     * selected on it (L215-L219, L249-L252) -- five is therefore the width the legacy join actually
     * compared, and a staged rate table keyed on any other width would not reproduce the legacy lookup
     * for currency values longer than five characters.
     *
     * <p>See docs/legacy-characterization.md, section "1.7 Constant: rate key length 5". Only the
     * length is declared here; performing the truncation belongs to {@code fx.LegacyRateTableSource},
     * which owns the staged-rate lookup.</p>
     */
    public static final int RATE_KEY_LENGTH = 5;

    private LegacyCharacterization() {
    }
}
