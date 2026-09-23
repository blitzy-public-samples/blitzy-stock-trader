package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.export;

import java.math.BigDecimal;

/**
 * One record of the legacy write-only VSAM HISTORY KSDS, in the WS-VSAM-RECORD layout of CASH00.cbl:L38-L45.
 *
 * @param name        the owner as the caller supplied it, part of the 29-byte KSDS key
 * @param eventDate   the raw YYYYMMDD stamp, part of that key
 * @param eventTime   the raw HHMMSS stamp, part of that key
 * @param requestCode the legacy one-character request code, including codes the dispatch did not recognize
 * @param balance     the balance the record carried, decoded from unsigned zoned decimal
 * @param currency    the currency the record carried
 * @param retcode     the SQLCODE as unsigned digits, sign already dropped by the legacy MOVE
 */
public record VsamHistoryRecord(
        // Unfolded caller casing: CASH00.cbl:L111 moves WS-NAME in without the account table's UPPER/LOWER
        // normalization (L155, L141), so "John"+stamp and "JOHN"+stamp are two equally valid 29-byte KSDS
        // keys (CASH00.cbl:L47-L50; DEFKSDS.jcl:L14) and both must survive an import unaltered.
        String name,
        // Raw YYYYMMDD / HHMMSS text (CASH00.cbl:L112-L113, from FORMATTIME at L82-L85): resolving it to an
        // instant needs the CICS region's zone, which arrives as tool.legacy-timezone, so that conversion
        // belongs to load/LegacyLoader rather than to this carrier.
        String eventDate,
        String eventTime,
        String requestCode,
        BigDecimal balance,
        String currency,
        String retcode) {
}
