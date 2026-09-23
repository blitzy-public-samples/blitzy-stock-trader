package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.export;

import java.math.BigDecimal;

/**
 * One exported row of STOCKTRD.CASHACCOUNTY, as a delimited DB2 UNLOAD/DSNTIAUL export carries it.
 *
 * @param owner    the exported owner key, NOT NULL in the legacy catalog (DB2DDL.jcl:L47)
 * @param balance  the exported balance, or {@code null} because the legacy column is nullable
 *                 (DB2DDL.jcl:L48); a null, an out-of-set currency and an oversized owner are classified
 *                 by ReconciliationService.validateSource() as a variance that declines the account,
 *                 never given an invented value here
 * @param currency the exported currency, or {@code null} for the same reason (DB2DDL.jcl:L49)
 */
public record LegacyCashAccountRecord(String owner, BigDecimal balance, String currency) {
}
