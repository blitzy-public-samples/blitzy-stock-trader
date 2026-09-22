package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.shadow;

import java.math.BigDecimal;

/**
 * One captured legacy reply from a shadow-mode window.
 *
 * @param seq     the sequence number of the request this answers
 * @param owner   the owner as the legacy COMMAREA echoed it
 * @param retcode the legacy SQLCODE as raw unsigned text
 * @param balance the balance the legacy reply carried
 */
public record ShadowLegacyResponse(

        long seq,

        String owner,

        /* The legacy SQLCODE as raw text: an alphanumeric MOVE (CASH00.cbl:L104, L117) renders the
           absolute digits and drops the sign, so -803 and +803 both arrive as "000000803" and an error
           class is indistinguishable from a warning class. Only a zero-parse is defensible. */
        String retcode,

        BigDecimal balance) {
}
