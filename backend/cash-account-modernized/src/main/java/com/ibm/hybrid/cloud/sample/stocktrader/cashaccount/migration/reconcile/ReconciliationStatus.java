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

// Typed rather than free text: the legacy program carried one status channel, and MOVE SQLCODE TO
// WS-RETCODE rendered it into an alphanumeric field without its sign (CASH00.cbl:L104), so a caller
// could not tell an error from a warning. Every outcome here is a row with a comparable disposition.
//
// These three identifiers are a database contract. migration_reconciliation.status is VARCHAR(20)
// (schema/cash-account-schema.sql) and MigrationReconciliation maps it @Enumerated(EnumType.STRING),
// so the name is what persists: renaming a constant orphans rows an earlier run wrote, and a name
// over 20 characters fails the insert. ACCEPTED_EXCEPTION, the longest, is 18.
/** Disposition of a single {@code migration_reconciliation} row. */
public enum ReconciliationStatus {

    /**
     * A difference that has been reviewed and is no longer outstanding.
     *
     * <p>Deliberately not written per matching owner: {@code ReconciliationService} and
     * {@code shadow.ShadowComparator} persist a row only for a condition that needs recording, which
     * is what makes the acceptance criteria decidable -- "zero {@code VARIANCE} rows" for the matched
     * fixtures and "exactly the seeded rows" for the seeded ones, asserted over the deterministic set
     * {@code MigrationReconciliationRepository.findByRunIdOrderByReconciliationIdAsc} returns. One
     * row per agreeing owner would bury the seeded rows in that set and make the assertion depend on
     * fixture size. {@code MATCHED} is therefore the value an operator sets when reclassifying a
     * reviewed row, and the value {@code countByRunIdAndStatus(runId, MATCHED)} reports.</p>
     */
    MATCHED,

    /**
     * An unexplained difference between the legacy export and the migrated state.
     *
     * <p>The only status that counts against a run: {@code migration_run.variance_count} is
     * {@code MigrationReconciliationRepository.countByRunIdAndStatus(runId, VARIANCE)}, and a
     * non-zero count is what makes {@code MigrationToolRunner} exit 2 instead of 0. It is also the
     * sign-off gate -- the operational runbook's bulk-migration step cannot be accepted while any
     * {@code VARIANCE} row stands; the data owner either resolves it or reclassifies it
     * {@code ACCEPTED_EXCEPTION} with a written reason.</p>
     */
    VARIANCE,

    /**
     * A real but pre-approved difference: recorded for the evidence trail, not counted against the run.
     *
     * <p>{@code ACCEPTED_EXCEPTION} must never inflate the variance count or the exit code. Only
     * {@code VARIANCE} feeds either, so a run carrying nothing but accepted exceptions is still
     * clean and still exits 0. Collapsing the two would make every characterized legacy quirk look
     * like a migration defect and would block a sign-off that the quirk itself explains.</p>
     *
     * <p>The two characterized cases: a balance difference fully explained by a live-versus-legacy
     * exchange-rate difference ({@code VarianceKind.RATE_SOURCE}, which arises only when the tooling
     * runs with a live rate source instead of the staged legacy rate table); and the legacy debit
     * that stored the absolute value of a negative result, because {@code WS-CALC} is unsigned
     * (CASH00.cbl:L17) and {@code COMPUTE WS-CALC = BALANCE - (RATES * BALANC-rate)} silently dropped
     * the sign (CASH00.cbl:L256), where the replacement deliberately rejects the debit as
     * insufficient funds ({@code VarianceKind.REJECTED_BY_TARGET}).</p>
     */
    ACCEPTED_EXCEPTION
}
