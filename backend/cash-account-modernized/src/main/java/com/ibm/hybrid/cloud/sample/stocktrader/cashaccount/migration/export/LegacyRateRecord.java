package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.export;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of a DB2 UNLOAD/DSNTIAUL delimited export of the legacy {@code STOCKTRD.FRANKFURT1} rate table.
 *
 * @param currnkey  the exported currency key, NOT NULL in the legacy catalog (DB2DDL.jcl:L55)
 * @param currnbase the exported base currency, or {@code null} (DB2DDL.jcl:L56); staged only, because the
 *                  rate SELECT fetches it (CASH00.cbl:L215, L249) and no COMPUTE or MOVE reads it
 * @param amount    the exported amount, or {@code null} (DB2DDL.jcl:L57); staged only for the same reason,
 *                  and never the multiplicand - the legacy arithmetic multiplies RATES by the caller's
 *                  COMMAREA amount (CASH00.cbl:L222, L256)
 * @param rates     the exported rate, or {@code null} (DB2DDL.jcl:L58), at scale 2 with a ceiling of 9.99
 *                  from its legacy DECIMAL(3,2) / PIC S9(1)V9(2) COMP-3 (DCLFRANK.cpy:L12, L22); a null is
 *                  recorded as a NULL_RATE variance rather than rescaled or defaulted here
 * @param loaddt    the exported load date, NOT NULL (DB2DDL.jcl:L59); staged only, like the two above
 */
public record LegacyRateRecord(String currnkey, String currnbase, BigDecimal amount, BigDecimal rates, LocalDate loaddt) { }
