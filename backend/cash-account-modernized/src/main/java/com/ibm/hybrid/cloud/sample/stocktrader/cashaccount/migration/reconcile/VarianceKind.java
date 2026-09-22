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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile;

/*
 * Why a closed, typed vocabulary rather than a free-text label: a reconciliation or dual-run
 * difference is only ever recorded as a `migration_reconciliation` row carrying its kind, never as a
 * log line, so the set of kinds an operator has to triage must be enumerable. The constant names are
 * a database contract: `MigrationReconciliation` maps this enum with @Enumerated(EnumType.STRING)
 * onto `migration_reconciliation.variance_kind VARCHAR(24)`, so a rename invalidates rows already
 * written and a name longer than 24 characters fails the insert. The longest names here are
 * TRANSACTION_COUNT (17) and REJECTED_BY_TARGET (18).
 *
 * Deliberately behaviourless: which kind applies to which condition — and which reason token the
 * row's value columns carry — is ReconciliationService's and ShadowComparator's knowledge, so no
 * classification logic, display name or predicate belongs on the vocabulary itself.
 */

/** The kind of difference a reconciliation or shadow-comparison row records. */
public enum VarianceKind {

    /** The legacy and migrated/target balances differ for the same owner. */
    BALANCE,

    /**
     * The count of transactions the legacy processed for an owner in a shadow window differs from the
     * count the target processed; shadow-window only, because after a bulk load the target holds one
     * MIGRATION_LOAD row per account and no per-transaction history to count against, so reconcile
     * mode never emits it.
     */
    TRANSACTION_COUNT,

    /**
     * The owner's existence or loadability differs; the reason token is carried in the row's value
     * columns (MISSING_IN_TARGET, MISSING_IN_LEGACY, NULL_IN_LEGACY, RESERVATIONS_OUTSTANDING).
     */
    STATE,

    /**
     * The legacy and migrated currencies differ, or the exported currency is outside the accepted set
     * (INVALID_IN_LEGACY).
     */
    CURRENCY,

    /**
     * The exported rate row is NULL or absent (NULL_RATE), or a balance difference is fully explained
     * by the live-versus-legacy rate difference.
     */
    RATE_SOURCE,

    /**
     * The legacy accepted a transaction the target deliberately rejects, such as the absolute-value
     * debit the target answers with 422 INSUFFICIENT_FUNDS.
     */
    REJECTED_BY_TARGET
}
