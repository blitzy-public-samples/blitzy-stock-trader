package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.export;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One row of a DB2 UNLOAD/DSNTIAUL delimited export of the legacy {@code STOCKTRD.FRANKFURT1} rate table. */
// FRANKFURT1.AMOUNT, CURRNBASE and LOADDT are read by the rate SELECT at CASH00.cbl:L215 (credit) and
// CASH00.cbl:L249 (debit) and are then referenced by no COMPUTE and no MOVE anywhere else in the program;
// only RATES enters the arithmetic, and it multiplies the caller-supplied COMMAREA amount. These three
// are carried solely to be staged for reconciliation, and AMOUNT is never the multiplicand: reading it as
// one invalidates every reconciliation expectation in this module.

// currnbase, amount and rates are nullable because the legacy DDL declares no NOT NULL for them
// (DB2DDL.jcl:L56-L58), and rates arrives at scale 2 with a ceiling of 9.99 because its legacy type is
// DECIMAL(3,2) / PIC S9(1)V9(2) COMP-3 (DCLFRANK.cpy:L12, L22). Deciding what a null means belongs to
// ReconciliationService.validateSource(): a RATE_SOURCE NULL_RATE variance for a null rates, and no
// variance at all for a null currnbase or amount, which the program never reads. So this carrier rejects,
// rescales and defaults nothing, and an exported null-rate row stays constructible for that validator.
public record LegacyRateRecord(String currnkey, String currnbase, BigDecimal amount, BigDecimal rates, LocalDate loaddt) { }
