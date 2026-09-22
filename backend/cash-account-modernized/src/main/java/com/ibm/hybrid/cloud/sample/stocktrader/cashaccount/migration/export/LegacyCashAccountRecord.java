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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.export;

import java.math.BigDecimal;

// balance and currency are nullable on purpose: DB2DDL.jcl:L48-L49 declare neither column NOT NULL and
// DCLCASH.cpy:L17-L19 declares no null indicators, so an export may legitimately carry an empty field for
// either one. This carrier therefore keeps whatever the export held and decides nothing about it - a null
// balance or currency, an out-of-set currency and an oversized owner are classified (NULL_IN_LEGACY,
// INVALID_IN_LEGACY) by ReconciliationService.validateSource(), which records a variance and declines to
// load the account rather than inventing a value here.
/** One exported row of STOCKTRD.CASHACCOUNTY, as a delimited DB2 UNLOAD/DSNTIAUL export carries it. */
public record LegacyCashAccountRecord(String owner, BigDecimal balance, String currency) {
}
