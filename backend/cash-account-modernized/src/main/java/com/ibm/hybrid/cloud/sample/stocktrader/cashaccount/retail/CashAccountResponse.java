package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.retail;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.CashAccount;

/**
 * The retail seam's wire shape: the {@code {owner, balance, currency}} JSON object broker already exchanges.
 *
 * @param owner    the account owner; nullable, because this record is also the {@code @RequestBody} of POST and
 *                 PUT (AAP 0.6.2) and judging an absent component belongs to the service the shadow comparator
 *                 also drives with no HTTP layer in front of it
 * @param balance  a {@code BigDecimal} although the caller's DTO declares a {@code double}
 *                 [backend/broker/src/main/java/com/ibm/hybrid/cloud/sample/stocktrader/broker/json/CashAccount.java:L23]:
 *                 floating point is prohibited on every money path (AAP 0.7.1), since a binary double cannot
 *                 reproduce the legacy WS-CALC PIC 9(7)V99 [backend/cash-account-cobol/COBOL/CASH00.cbl:L17]
 *                 digit for digit. Nothing here formats or scales it: WRITE_BIGDECIMAL_AS_PLAIN in
 *                 application.yml settles the plain rendering the contract test asserts as text
 * @param currency the ISO 4217 account currency; nullable as above
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CashAccountResponse(String owner, BigDecimal balance, String currency) {

    /**
     * Renders an account as the retail wire shape, reporting the AVAILABLE and not the total balance: it is what
     * a retail debit is checked against and equals the total while nothing is held, the only state the legacy
     * single-balance program could be in, so parity is exact (AAP 0.6.2).
     *
     * @param account the account to render
     * @return its {@code {owner, balance, currency}} wire shape
     */
    public static CashAccountResponse from(CashAccount account) {
        return new CashAccountResponse(account.owner(), account.availableBalance().amount(), account.currency());
    }
}
