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
import java.util.Objects;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.Money;

/*
 * WHY THIS CLASS EXISTS, AND WHY NOTHING ON THE REQUEST PATH MAY REACH IT. It is the only place in the
 * module that reproduces the two behaviours the replacement deliberately refuses: the absolute value the
 * unsigned WS-CALC stored for a negative result (CASH00.cbl:L17, stored on through L225 credit / L259
 * debit) and the high-order digits an oversized result silently lost, neither COMPUTE carrying an
 * ON SIZE ERROR phrase (CASH00.cbl:L222, L256). The live service rejects both instead --
 * 422 INSUFFICIENT_FUNDS and 422 AMOUNT_OUT_OF_RANGE in Money.applyRateChecked (AAP 0.4.6,
 * docs/legacy-characterization.md sections 1.5 and 1.6).
 *
 * Reconciliation and the shadow comparator need the number the legacy system *would have stored*, because
 * parity is only meaningful when it is judged against the real historical value. Judged against a
 * cleaned-up one, every legacy overdraft and every wrapped balance would surface as an unexplained
 * variance, and the genuine defects the dual run exists to find would be lost among them.
 *
 * WHY THE MULTIPLICAND IS THE CALLER'S AMOUNT AND NOT FRANKFURT1.AMOUNT -- the finding the whole
 * reconciliation rests on, and the one a reader is most likely to get wrong. The account SELECT overwrites
 * host BALANCE with the *stored* balance (CASH00.cbl:L205-L209 credit, L240-L245 debit), so the caller's
 * amount is re-read from the COMMAREA field itself immediately before the COMPUTE: MOVE WS-BALANCE TO
 * BALANC-RATE (CASH00.cbl:L221 credit, L255 debit). The rate row's own AMOUNT column is fetched by the very
 * same SELECT that fetches RATES (column list at CASH00.cbl:L215 credit, L249 debit) and is then
 * referenced by no COMPUTE and no MOVE anywhere in the program, as are CURRNBASE and LOADDT
 * (DCLFRANK.cpy:L11, L10, L13). A reconciler that multiplied by the rate table's AMOUNT would reproduce a
 * number the legacy system never computed -- consistently enough to look plausible
 * (docs/legacy-characterization.md section 1.1).
 *
 * WHY NO SPRING STEREOTYPE, NO STATE AND NO INTERFACE: this is a pure function of its arguments, reached
 * only from ReconciliationService and shadow.ShadowComparator, and its unit test has to exercise it with no
 * Spring context. A bean, an implemented interface or a cache would also hand the legacy arithmetic a route
 * toward the request path, which is exactly where these two behaviours must stay unreachable.
 */
/** The legacy {@code CASH00} credit/debit arithmetic, reproduced exactly for the migration tooling. */
public final class LegacyBalanceCalculator {

    private LegacyBalanceCalculator() {
    }

    /** Legacy request code {@code C}: what {@code COMPUTE} at CASH00.cbl:L222 would have stored. */
    public static Money credit(BigDecimal storedBalance, BigDecimal rate, BigDecimal callerAmount) {
        return apply(storedBalance, Money.SIGN_CREDIT, rate, callerAmount);
    }

    /** Legacy request code {@code D}: what {@code COMPUTE} at CASH00.cbl:L256 would have stored. */
    public static Money debit(BigDecimal storedBalance, BigDecimal rate, BigDecimal callerAmount) {
        return apply(storedBalance, Money.SIGN_DEBIT, rate, callerAmount);
    }

    /*
     * The characterized formula, in the order the legacy statement imposed. Every parameter of every step
     * comes from LegacyCharacterization rather than from a literal here, because a second statement of
     * scale, rounding, sign handling or the wrap point would be a second characterization -- free to
     * diverge from docs/legacy-characterization.md section 1 without anything failing (AAP 0.10.1).
     *
     * STEP 1 -- ONE TRUNCATION, NOT TWO. Money.applyRate keeps the product RATES x caller_amount at full
     * BigDecimal precision, performs the signed addition, and scales the final result exactly once; it
     * returns that value raw and signed, with no bounds check, which is precisely why it is the right
     * primitive to layer the legacy behaviour on. The legacy COMPUTE was a single statement with one store
     * into the two-decimal WS-CALC (CASH00.cbl:L222, L256), so pre-scaling the product is not an equivalent
     * reordering but a defect worth a penny on every small debit: with stored 100.00, rate 0.03 and amount
     * 0.30, truncating the final result gives 99.99 while truncating the product first gives 100.00
     * (docs/legacy-characterization.md section 1.8). Nothing here re-truncates, re-multiplies or rounds.
     *
     * STEP 2 -- THE SIGN IS DROPPED. WS-CALC carries no S in its picture (CASH00.cbl:L17), so the COMPUTE
     * stored the magnitude and MOVE WS-CALC TO BALANCE (L225, L259) carried that magnitude into the UPDATE:
     * a debit larger than the balance committed a positive balance equal to the overdraft. Guarded by the
     * characterization's own flag, never by an unconditional abs(), so the claim stays traceable to the
     * picture clause it comes from. Taking the magnitude before the wrap is safe and is the order the
     * legacy store implies: RoundingMode.DOWN truncates toward zero, so the magnitude of the truncated
     * value equals the truncation of the magnitude, and BigDecimal.remainder on a non-negative dividend
     * yields a non-negative result.
     *
     * STEP 3 -- HIGH-ORDER DIGITS ARE LOST, NOT DIAGNOSED. WS-CALC holds only
     * LegacyCharacterization.BALANCE_INTEGER_DIGITS integer digits and neither COMPUTE carries
     * ON SIZE ERROR (CASH00.cbl:L222, L256), so a result of ten million or more was stored wrapped while
     * the return channel still reported the UPDATE's SQLCODE 0 -- arithmetically a remainder by the
     * characterized modulus.
     *
     * After step 3 the value is non-negative and strictly below the modulus, so it is always inside
     * Money's 0.00 - 9,999,999.99 range and Money.of cannot throw here; no guard of its own is needed, and
     * Money's constructor normalizes the scale, so no setScale belongs in this method either.
     *
     * A null operand or a sign that is neither direction is a programming error rather than a caller
     * condition: the only callers are the tooling services, which validate their export rows first, and
     * this class has no HTTP surface, so it raises the plain JDK exceptions instead of a CashAccountException
     * that would render a bug as a business ApiError.
     */
    public static Money apply(BigDecimal storedBalance, int sign, BigDecimal rate, BigDecimal callerAmount) {
        Objects.requireNonNull(storedBalance, "storedBalance is required");
        Objects.requireNonNull(rate, "rate is required");
        Objects.requireNonNull(callerAmount, "callerAmount is required");
        if (sign != Money.SIGN_CREDIT && sign != Money.SIGN_DEBIT) {
            throw new IllegalArgumentException("sign must be Money.SIGN_CREDIT or Money.SIGN_DEBIT, was " + sign);
        }

        BigDecimal computed = Money.applyRate(storedBalance, sign, rate, callerAmount);
        BigDecimal magnitude = LegacyCharacterization.RESULT_IS_UNSIGNED ? computed.abs() : computed;
        return Money.of(magnitude.remainder(LegacyCharacterization.BALANCE_MODULUS));
    }
}
