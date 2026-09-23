package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.MigrationRun;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for {@code migration_run}: one row per migration-tooling invocation. */
public interface MigrationRunRepository extends JpaRepository<MigrationRun, UUID> {

    // The only statuses in which a load has staged anything: a load applies its whole export in one transaction
    // (AAP 0.6.3), ending CLEAN or VARIANCE with every row applied or FAILED with none. RUNNING is excluded on
    // purpose - it names an attempt still in flight or one whose JVM died before it could be closed, whose staging
    // is empty, partial or about to be rolled back - so a newer interrupted attempt cannot mask the last load that
    // really staged.
    List<MigrationRun.Status> COMPLETED_LOAD_STATUSES =
            List.of(MigrationRun.Status.CLEAN, MigrationRun.Status.VARIANCE);

    /**
     * The load whose staged rows a later command of the same batch must read, or empty when the batch has none.
     *
     * @param batchId the runbook step's {@code --tool.batch-id}, shared by its load and the command judging it
     * @return the batch's most recent completed load, or empty when no load of the batch has staged
     */
    // One selector for three callers: reconcile/ReconciliationService, shadow/ShadowComparator and
    // fx/LegacyRateTableSource all ask which run staged the rate rows this batch is judged on, and answering it
    // per caller left three copies of the rule to drift apart. Keyed on batch_id rather than run_id because a load
    // and the reconcile judging it are separate invocations sharing --tool.batch-id, and a retry after a failure
    // is a new run_id under that same batch (AAP 0.6.3).
    default Optional<MigrationRun> findLatestCompletedLoad(UUID batchId) {
        return findFirstByBatchIdAndModeAndStatusInOrderByStartedAtDescRunIdDesc(
                batchId, MigrationRun.Mode.LOAD, COMPLETED_LOAD_STATUSES);
    }

    // Intended to be called only through the selector above; a caller-supplied mode or status set would reopen
    // the per-caller drift it closes. run_id DESC is a deterministic tie-break: started_at is not unique, so
    // ordering on it alone would let two commands of one batch resolve different loads.
    Optional<MigrationRun> findFirstByBatchIdAndModeAndStatusInOrderByStartedAtDescRunIdDesc(
            UUID batchId, MigrationRun.Mode mode, Collection<MigrationRun.Status> statuses);

    /**
     * The most recent completed load anywhere in the current schema, or empty when the schema holds none.
     *
     * @return the schema's most recent completed load, whatever batch it belongs to
     */
    // For the one reader whose own batch cannot hold a load: a shadow window is a distinct invocation with its
    // own --tool.batch-id (AAP 0.3.3 Step 2), so the rates its cross-currency replays are priced from were staged
    // by the migration step's load under a different batch. Resolving that load batch-first and only then
    // schema-wide keeps a batch that does hold one authoritative - a reconcile still judges the load it names -
    // while making the documented per-window invocation price from the load the rehearsal schema was built by,
    // instead of finding no rate at all. Deliberately NOT named findLatestCompletedLoad: the batch-scoped
    // selector's name is what ReconciliationIT's counting proxy matches, and an overload would make its count
    // ambiguous. Same status set and the same started_at DESC, run_id DESC ordering, so the two selectors cannot
    // disagree about which run "completed" and "most recent" mean.
    default Optional<MigrationRun> findLatestCompletedLoadInSchema() {
        return findFirstByModeAndStatusInOrderByStartedAtDescRunIdDesc(
                MigrationRun.Mode.LOAD, COMPLETED_LOAD_STATUSES);
    }

    // Intended to be called only through the schema-wide selector above, for the same reason as its sibling.
    Optional<MigrationRun> findFirstByModeAndStatusInOrderByStartedAtDescRunIdDesc(
            MigrationRun.Mode mode, Collection<MigrationRun.Status> statuses);
}
