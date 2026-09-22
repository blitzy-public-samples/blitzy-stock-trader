package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.shadow;

import java.math.BigDecimal;

/**
 * One captured legacy request replayed in a shadow-mode window.
 *
 * @param seq      the window-ordered sequence number the comparator joins on
 * @param owner    the owner as captured
 * @param req      the legacy one-character request code as captured
 * @param amount   the caller-supplied amount, the multiplicand of the legacy rate computation
 * @param currency the currency as captured
 */
public record ShadowTransaction(
        long seq,

        String owner,

        // The legacy one-character request code as captured: EVALUATE WS-REQ is case-sensitive and has no
        // WHEN OTHER (CASH00.cbl:L89-L102), so a code outside A/Q/U/X/C/D is a value the comparator must
        // see and classify, never one a typed enum may refuse.
        String req,

        BigDecimal amount,

        String currency) {
}
