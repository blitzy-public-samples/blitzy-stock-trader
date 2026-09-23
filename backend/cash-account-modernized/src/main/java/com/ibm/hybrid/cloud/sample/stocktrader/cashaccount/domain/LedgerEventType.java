package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain;

/** Closed vocabulary of the state changes recorded in the append-only ledger. */
public enum LedgerEventType {

    /*
     * Direction is carried by the constant and is not evident from the names: ledger_entry.amount is a
     * non-negative magnitude under ck_ledger_entry_amount_nonneg, so CREDIT, HOLD, RELEASE and EXPIRY
     * are inflows to the balance they name, DEBIT and SETTLEMENT outflows, and the ACCOUNT_* and
     * MIGRATION_LOAD events carry a resulting balance rather than a delta. A signed delta is derived
     * from consecutive available_after / reserved_after values, never stored, so the magnitude and the
     * balances cannot drift apart.
     *
     * No READ constant: the legacy write followed the dispatch unconditionally, so it also logged reads
     * ('Q') and unrecognized codes (CASH00.cbl:L111-L131). This ledger records state changes only, and
     * legacy 'Q' records are staged in legacy_history by the migration tooling instead.
     */
    ACCOUNT_CREATED,
    ACCOUNT_UPDATED,
    ACCOUNT_DELETED,
    CREDIT,
    DEBIT,
    HOLD,
    SETTLEMENT,
    RELEASE,
    EXPIRY,
    // Never renamed: the partial index uq_ledger_entry_migration_load in cash-account-schema.sql matches
    // this name as a SQL string literal, and that index is what stops a retried load writing a second
    // load row for one owner.
    MIGRATION_LOAD
}
