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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.retail;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.CashAccount;

/*
 * WHY balance IS A BigDecimal ON THIS SIDE OF A SEAM WHOSE CALLER DECLARES A double. The caller's DTO carries
 * `private double balance` [backend/broker/src/main/java/com/ibm/hybrid/cloud/sample/stocktrader/broker/json/
 * CashAccount.java:L23], but floating point is prohibited on every balance, amount, rate and variance path in
 * this module (AAP 0.7.1): a binary double cannot reproduce the legacy fixed-point WS-CALC PIC 9(7)V99
 * [backend/cash-account-cobol/COBOL/CASH00.cbl:L17] digit for digit, so a migrated balance that passed through
 * one would reconcile against COBOL packed decimal with penny-level variances that read as data corruption.
 * The caller's type is not ours to inherit and not ours to change - the contract between us is the response
 * text, and a double parses a bare JSON number regardless of how the producer stored it.
 *
 * Which is why nothing here formats, scales or rounds: domain/Money holds the module's single truncation point,
 * and plain rendering is settled once by WRITE_BIGDECIMAL_AS_PLAIN in application.yml and config/JacksonConfig.
 * A @JsonFormat or a custom serializer here would be a second authority over the one thing the contract test
 * asserts as literal text ("balance":1234.56), and stripTrailingZeros would render 1000.00 as 1E+3.
 */
/** The retail seam's wire shape: the {@code {owner, balance, currency}} JSON object broker already exchanges. */
// Tolerant deserialization is structural, not defensive: the retail package is exactly three files (AAP 0.6.1),
// so this same record is also the @RequestBody of POST and PUT /cash-account/{owner} (AAP 0.6.2). Every
// component is consequently nullable, and judging an absent or malformed one belongs to RetailCashAccountService
// rather than to Bean Validation here, because migration/shadow/ShadowComparator reaches that service with no
// HTTP layer in front of it and has to be judged identically. An unknown property is ignored rather than made a
// 400 the legacy program never had.
@JsonIgnoreProperties(ignoreUnknown = true)
public record CashAccountResponse(String owner, BigDecimal balance, String currency) {

    /**
     * Renders {@code account} as the retail wire shape, reporting its available balance as {@code balance}.
     *
     * <p>Available and not total, deliberately: {@code balance} is the spendable amount, which is what a retail
     * debit must be checked against, and it equals the total whenever no reservation is held - the only state
     * the legacy single-balance program could ever be in, so parity with it is exact (AAP 0.6.2). A retail
     * caller observes the difference only after an institutional caller has held funds on the same owner.</p>
     */
    public static CashAccountResponse from(CashAccount account) {
        return new CashAccountResponse(account.owner(), account.availableBalance().amount(), account.currency());
    }
}
