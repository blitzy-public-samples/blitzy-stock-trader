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

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.MigrationRun;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for {@code migration_run}: one row per migration-tooling invocation. */
public interface MigrationRunRepository extends JpaRepository<MigrationRun, UUID> {

    // Keyed on batch_id rather than run_id because a load applies its whole export in one transaction: an
    // attempt that fails applies nothing and its retry is a NEW run_id under the SAME --tool.batch-id, so
    // the reconcile that judges that load can only reach it through the batch. Ascending started_at reads
    // the step in the order it happened, FAILED attempts included, since they are part of its evidence.
    //
    // Mode stays out of the signature by choice: a batch holds a handful of rows, so the caller filters
    // them in memory and this interface never depends on how MigrationRun models that enum.
    List<MigrationRun> findByBatchIdOrderByStartedAtAsc(UUID batchId);
}
