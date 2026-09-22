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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.LegacyRateTable;

/** Data access to the run-scoped staging of exported {@code STOCKTRD.FRANKFURT1} rate rows. */
// Staging for the migration tooling only, never a target-state dependency. The live service resolves
// rates over HTTP through the exchange-rate client at cashaccount.fx.url; these rows exist so the
// reconciler and the shadow comparator can judge parity on the very rates the legacy arithmetic used,
// which is why they are read by migration/** and by the @Profile("tool") fx/LegacyRateTableSource and
// never on the request path.
public interface LegacyRateTableRepository extends JpaRepository<LegacyRateTable, LegacyRateTable.Key> {

    // The key arrives already truncated to five characters by the caller, reproducing the legacy lookup
    // that moved the account's CURRENCYC X(8) into WS-CURRENCY-KEY X(5) before selecting the rate row
    // (backend/cash-account-cobol/COBOL/CASH00.cbl:L213-L218 for credit, L247-L252 for debit); trimming,
    // padding or case folding here would resolve a key the legacy program itself would have missed, so
    // this repository deliberately normalizes nothing.
    //
    // An empty result must stay empty. A currency with no staged row - including one whose export
    // carried a null rates value, which ReconciliationService.validateSource() refuses to stage and
    // reports as a RATE_SOURCE variance of kind NULL_RATE - makes any C/D replay for that currency
    // REJECTED_BY_TARGET, whereas the legacy program went on to read its uninitialized RATES host
    // variable (backend/cash-account-cobol/COBOL/DCLFRANK.cpy:L18-L23) and committed a wrong balance
    // under a success code (CASH00.cbl:L214-L231). The live service answers 503
    // EXCHANGE_RATE_UNAVAILABLE instead, so substituting any rate for the absent row here would
    // reintroduce exactly the defect both paths were built to eliminate.
    Optional<LegacyRateTable> findByRunIdAndCurrnkey(UUID runId, String currnkey);

    List<LegacyRateTable> findByRunId(UUID runId);

    boolean existsByRunIdAndCurrnkey(UUID runId, String currnkey);
}
