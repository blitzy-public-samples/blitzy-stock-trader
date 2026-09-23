package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile;

/**
 * The kind of difference a reconciliation or shadow-comparison row records, persisted by name into
 * {@code migration_reconciliation.variance_kind VARCHAR(24)}, so a rename orphans rows already written.
 */
public enum VarianceKind {

    BALANCE,

    /**
     * Shadow-window only: after a bulk load the target holds one {@code MIGRATION_LOAD} row per account
     * and no per-transaction history to count against (AAP 0.10.3), and a legacy count is a lower bound
     * in any case because {@code IGNORE CONDITION DUPREC} dropped same-second history writes
     * (CASH00.cbl:L124).
     */
    TRANSACTION_COUNT,

    /**
     * Existence or loadability; the reason token sits in the row's value columns
     * ({@code MISSING_IN_TARGET}, {@code MISSING_IN_LEGACY}, {@code NULL_IN_LEGACY},
     * {@code RESERVATIONS_OUTSTANDING}).
     */
    STATE,

    /** A currency mismatch, or an exported currency outside the accepted set ({@code INVALID_IN_LEGACY}). */
    CURRENCY,

    /**
     * Both an unusable exported rate ({@code NULL_RATE}) and a live-mode balance difference the staged
     * legacy rate fully explains, which is why this one kind carries either status.
     */
    RATE_SOURCE,

    /**
     * What the legacy accepted and the target refuses, such as the absolute-value debit answered with
     * 422 {@code INSUFFICIENT_FUNDS}.
     */
    REJECTED_BY_TARGET
}
