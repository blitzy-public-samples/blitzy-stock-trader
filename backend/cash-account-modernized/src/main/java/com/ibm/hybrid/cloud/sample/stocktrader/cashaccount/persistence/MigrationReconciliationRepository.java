package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.MigrationReconciliation;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.ReconciliationStatus;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for {@code migration_reconciliation}: the append path for one run's findings and their count. */
public interface MigrationReconciliationRepository extends JpaRepository<MigrationReconciliation, Long> {

    // With VARIANCE this count is what ReconciliationService writes into migration_run.variance_count and
    // what makes MigrationToolRunner exit 2 instead of 0, so a run carrying only ACCEPTED_EXCEPTION rows
    // stays clean and exits 0. Counted in the database rather than by sizing a list, so a run with many
    // findings costs one number. No row-returning finder is declared: the findings are evidence an operator
    // reads through SQL or a report, and a finder here would be shipped surface with no shipped caller.
    long countByRunIdAndStatus(UUID runId, ReconciliationStatus status);
}
