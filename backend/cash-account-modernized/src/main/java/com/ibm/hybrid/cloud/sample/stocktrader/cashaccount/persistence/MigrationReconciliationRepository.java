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

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.MigrationReconciliation;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.ReconciliationStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for {@code migration_reconciliation}: the recorded differences of one tooling run. */
public interface MigrationReconciliationRepository extends JpaRepository<MigrationReconciliation, Long> {

    // Ordered by the generated identity, which is insertion order, because row order here is evidence
    // order: the acceptance criteria are "zero rows" for a matched fixture and "exactly the seeded rows"
    // for a seeded one, and an assertion can only read a set as exact if the query fixes both its scope
    // and its sequence. Scoping by run alone would leave ordering to the database, where PostgreSQL is
    // free to return rows in any sequence without an ORDER BY. The scope is the run rather than the batch
    // because a reconcile writes its findings under its own run_id, source-validation rows included.
    List<MigrationReconciliation> findByRunIdOrderByReconciliationIdAsc(UUID runId);

    List<MigrationReconciliation> findByRunIdAndStatus(UUID runId, ReconciliationStatus status);

    // Counted in the database rather than by sizing the list above, since this number is consumed as a
    // number: with VARIANCE it is what ReconciliationService writes into migration_run.variance_count and
    // what makes MigrationToolRunner exit 2 instead of 0, so a run carrying only ACCEPTED_EXCEPTION rows
    // stays clean and exits 0.
    long countByRunIdAndStatus(UUID runId, ReconciliationStatus status);
}
