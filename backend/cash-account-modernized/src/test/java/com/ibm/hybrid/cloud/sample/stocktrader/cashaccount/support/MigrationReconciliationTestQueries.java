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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.MigrationReconciliation;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.ReconciliationStatus;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/** Test-scope reads over {@code migration_reconciliation}, for asserting the exact row set one run recorded. */
// WHY THIS TYPE IS IN THE TEST TREE. Nothing the service ships reads these rows back: the tooling writes a
// finding and consumes only its count - ReconciliationService and ShadowComparator write
// migration_run.variance_count from countByRunIdAndStatus, and MigrationToolRunner derives its exit code from
// the same number - while the rows themselves are evidence an operator reads through SQL or a report at the
// runbook's sign-off. A row-returning finder on persistence/MigrationReconciliationRepository would therefore
// be shipped data-access surface whose only caller is an integration test, so the reads live with the tests
// that make them.
//
// The bare Repository marker, as persistence/LedgerEntryRepository uses it, and NOT an extension of
// MigrationReconciliationRepository: a type assignable to the production interface would give every production
// injection point two candidate beans once Spring Data finds both under the ...cashaccount scan root. It
// publishes no save and no delete either, so a test cannot reach through it to forge a finding the code under
// test did not record.
//
// countByRunIdAndStatus is declared here as well as on the production interface: the assertions read a run's
// rows and its count together, and duplicating one derived count keeps that pair on one injected type rather
// than splitting the test's view of a run across two.
public interface MigrationReconciliationTestQueries extends Repository<MigrationReconciliation, Long> {

    // Ordered by the generated identity, which is insertion order, because row order here is evidence
    // order: the acceptance criteria are "zero rows" for a matched fixture and "exactly the seeded rows"
    // for a seeded one, and an assertion can only read a set as exact if the query fixes both its scope
    // and its sequence. Scoping by run alone would leave ordering to the database, where PostgreSQL is
    // free to return rows in any sequence without an ORDER BY. The scope is the run rather than the batch
    // because a reconcile writes its findings under its own run_id, source-validation rows included.
    List<MigrationReconciliation> findByRunIdOrderByReconciliationIdAsc(UUID runId);

    List<MigrationReconciliation> findByRunIdAndStatus(UUID runId, ReconciliationStatus status);

    long countByRunIdAndStatus(UUID runId, ReconciliationStatus status);
}
