package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.LegacyHistory;

import org.springframework.data.jpa.repository.JpaRepository;

/** Write surface for the run-scoped {@code legacy_history} staging rows of an exported legacy audit file. */
public interface LegacyHistoryRepository extends JpaRepository<LegacyHistory, LegacyHistory.Key> {
    // No read is declared because no shipped caller has one: the loader stages the decoded export through the
    // inherited saveAll, while the reconciler and the shadow comparator re-read the export files themselves
    // and compare those against the live tables.
    //
    // JpaRepository rather than the bare Repository marker LedgerEntryRepository uses, because a bulk load
    // needs saveAll's batching and staging carries no append-only invariant to protect - these rows are
    // derived from a file that can be re-staged under a fresh run_id, unlike ledger_entry.
}
