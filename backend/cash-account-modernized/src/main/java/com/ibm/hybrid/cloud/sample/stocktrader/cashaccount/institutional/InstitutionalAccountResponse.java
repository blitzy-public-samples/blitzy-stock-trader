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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.institutional;

import java.math.BigDecimal;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.CashAccount;

/*
 * WHY THE BALANCES ARE BigDecimal AND NOT THE MODULE'S Money TYPE. Money is a class wrapping a single
 * amount field, so Jackson would serialize each balance as a nested object - {"availableBalance":
 * {"amount": 750.00}} - and the shape this surface publishes is flat: five scalar fields, named exactly
 * owner, currency, availableBalance, reservedBalance, totalBalance (AAP 0.6.2). Those names are also an
 * operational contract, not only an API one: the cutover runbook's pre-routing validation reads this view
 * per owner, and its rollback precondition gates on reservedBalance being 0.00 before any state hand-back
 * (AAP 0.3.3), so renaming a field breaks a documented procedure as well as a caller.
 *
 * Unwrapping Money here costs nothing in precision because every value it hands out is already at scale 2
 * with RoundingMode.DOWN, and this record neither computes nor re-scales anything. IEEE 754 binary
 * arithmetic is prohibited on every money path and appears nowhere here - not as a component type, not as
 * a conversion - for the reason Money itself records: a value such as 0.01 has no exact binary
 * representation, and a fraction of a cent introduced on the way out reconciles against COBOL packed
 * decimal as data corruption rather than as arithmetic (AAP 0.7.1).
 */
/** The owner's cash position in one view: what is spendable now, what is held, and the two together. */
public record InstitutionalAccountResponse(
        String owner,

        String currency,

        // WHY A SPLIT WHERE THE LEGACY HAD ONE FIGURE. The CICS program carried a single mutable balance
        // per owner - one BALANCE DECIMAL(9, 2) column [backend/cash-account-cobol/COBOL/DCLCASH.cpy:L10]
        // that credit and debit recomputed and stored outright
        // [backend/cash-account-cobol/COBOL/CASH00.cbl:L222, L225: COMPUTE WS-CALC = BALANCE +
        // (RATES * BALANC-RATE) then MOVE WS-CALC TO BALANCE] - so held funds were indistinguishable from
        // spendable funds and reserving money meant debiting it. The pair below is therefore new
        // capability, and it is the reason this view exists: retail GET /cash-account/{owner} reports the
        // available part as its balance, which equals totalBalance only while nothing is held, and this is
        // the one place a caller can see both halves at once. Each mirrors a NUMERIC(9,2) column that the
        // schema's CHECK constraints hold at or above zero, so neither is ever negative.
        BigDecimal availableBalance,

        BigDecimal reservedBalance,

        // Derived, never stored: no column holds the sum, and the factory takes it from the entity's own
        // totalBalance() rather than adding the two components above, so the wire value cannot drift from
        // the arithmetic the domain performs. The entity returns it as a BigDecimal on purpose - each
        // column is independently bounded, so a legitimate pair can sum past Money's ceiling, and a read
        // must not fail on state the writes were entitled to create.
        BigDecimal totalBalance) {

    /**
     * Renders {@code account} as the institutional account view.
     *
     * @param account the account to project; its balances are read, never modified
     * @return the flat five-field view of that account's position
     * @throws IllegalArgumentException if {@code account} is null, which is a caller defect rather than a
     *         request condition and so carries no {@code CashAccountErrorCode}
     */
    public static InstitutionalAccountResponse from(CashAccount account) {
        if (account == null) {
            throw new IllegalArgumentException("account is required");
        }
        return new InstitutionalAccountResponse(
                account.owner(),
                account.currency(),
                account.availableBalance().amount(),
                account.reservedBalance().amount(),
                account.totalBalance());
    }
}
