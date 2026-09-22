package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.export;

import java.math.BigDecimal;

/** One record of the legacy write-only VSAM HISTORY KSDS, in the WS-VSAM-RECORD layout of CASH00.cbl:L38-L45. */
public record VsamHistoryRecord(
        // Keeps the caller's original casing: CASH00.cbl:L111 moves WS-NAME straight into the record with
        // no case folding, whereas the account table normalizes (UPPER on insert at L155, LOWER on match
        // at L141). "John"+stamp and "JOHN"+stamp are therefore two separate, equally valid KSDS keys
        // (WS-VSAM-KEY, CASH00.cbl:L47-L50; KEYS, DEFKSDS.jcl:L14) and both must survive an import
        // unaltered. The uppercased join key is a later, separate derivation: reconcile/LegacyHistory.ownerKey.
        String name,
        // Kept as the raw YYYYMMDD / HHMMSS text written at CASH00.cbl:L112-L113 from FORMATTIME
        // (L82-L85): resolving it to a point in time needs the CICS region's zone, which is not in this
        // repository and arrives as the tool.legacy-timezone property, so that conversion belongs to
        // load/LegacyLoader rather than to this carrier.
        String eventDate,
        String eventTime,
        String requestCode,
        BigDecimal balance,
        String currency,
        String retcode) {
}
