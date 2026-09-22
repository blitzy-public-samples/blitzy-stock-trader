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

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.LegacyHistory;

import org.springframework.data.jpa.repository.JpaRepository;

/** Write surface for the run-scoped {@code legacy_history} staging rows of an exported legacy audit file. */
// Migration-source staging only, and staging here is written and never read back: migration/load's
// LegacyLoader batches the decoded export through the inherited saveAll and records what it staged on the
// run's MigrationRun summary, while reconcile/ReconciliationService and migration/shadow/ShadowComparator
// re-read the export files from the run's input directory and compare those against the live tables. No
// shipped caller reads these rows, so no read is declared here - the staging assertions that do read them
// are test-scope, on support/LegacyHistoryTestQueries in the test tree. Nothing reads the KSDS either:
// the single application-level access in the legacy estate is the EXEC CICS WRITE at
// backend/cash-account-cobol/COBOL/CASH00.cbl:L126-L131, and the export is only what made the rows
// readable at all.
//
// JpaRepository rather than the bare Repository marker that persistence/LedgerEntryRepository uses, because
// a bulk load needs saveAll's batching and staging carries no append-only invariant to protect: these rows
// are derived from a file that can be re-staged under a fresh run_id, unlike ledger_entry, whose audit
// trail an inherited delete would let a caller destroy.
public interface LegacyHistoryRepository extends JpaRepository<LegacyHistory, LegacyHistory.Key> {
}
