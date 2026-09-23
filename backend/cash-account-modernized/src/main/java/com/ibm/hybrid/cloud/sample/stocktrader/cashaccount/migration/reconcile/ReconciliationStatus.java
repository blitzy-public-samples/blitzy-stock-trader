package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile;

/**
 * Disposition of a single {@code migration_reconciliation} row, persisted by name into its
 * {@code status VARCHAR(20)} column, so a rename orphans rows an earlier run wrote.
 */
public enum ReconciliationStatus {

    /**
     * A reviewed difference that is no longer outstanding: the value an operator sets when closing a
     * row, never one a comparison writes.
     */
    MATCHED,

    /**
     * An unexplained difference, and the only status counted into {@code migration_run.variance_count}
     * and therefore the only one that makes {@code MigrationToolRunner} exit 2 rather than 0.
     */
    VARIANCE,

    /**
     * A real but pre-approved difference, recorded for the evidence trail and deliberately not counted,
     * so a run carrying nothing else is still clean: collapsing it into {@code VARIANCE} would make
     * every characterized legacy quirk look like a migration defect.
     *
     * <p>The two characterized cases are a live-mode balance difference the staged legacy rate fully
     * explains ({@code VarianceKind.RATE_SOURCE}), and the legacy debit that stored the absolute value
     * of a negative result because {@code WS-CALC} is unsigned (CASH00.cbl:L17, L256) where the
     * replacement answers 422 {@code INSUFFICIENT_FUNDS} ({@code VarianceKind.REJECTED_BY_TARGET}).</p>
     */
    ACCEPTED_EXCEPTION
}
