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
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for {@code migration_reconciliation}: the append path for one run's findings and their count. */
public interface MigrationReconciliationRepository extends JpaRepository<MigrationReconciliation, Long> {

    // The only read the service ships, and it is consumed as a NUMBER: with VARIANCE it is what
    // ReconciliationService writes into migration_run.variance_count and what makes MigrationToolRunner exit 2
    // instead of 0, so a run carrying only ACCEPTED_EXCEPTION rows stays clean and exits 0. Counted in the
    // database rather than by sizing a list, so a run with many findings costs one number.
    //
    // The row-returning finders that read a run's findings back live in the test tree, on
    // support/MigrationReconciliationTestQueries: the rows are evidence an operator reads through SQL or a
    // report, and the only code that reads them is the integration tests asserting the exact row sets of AAP
    // 0.10.3 - so a list-returning finder here would be shipped data-access surface with no shipped caller.
    long countByRunIdAndStatus(UUID runId, ReconciliationStatus status);
}
