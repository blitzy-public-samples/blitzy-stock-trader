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

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.LegacyHistory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Data access to the run-scoped {@code legacy_history} staging rows of an exported legacy audit file. */
// Migration-source staging only: the loader writes these rows and the reconciler and the shadow comparator
// read them back, never the request path, which is why the balance the legacy system held is reconstructed
// here and not served from here. Nothing reads the KSDS itself — the single application-level access in the
// legacy estate is the EXEC CICS WRITE at backend/cash-account-cobol/COBOL/CASH00.cbl:L126-L131 — so this
// read path exists only because the export makes one possible.
public interface LegacyHistoryRepository extends JpaRepository<LegacyHistory, LegacyHistory.Key> {

    List<LegacyHistory> findByRunId(UUID runId);

    // Joins run through the uppercased owner_key and never through the raw name: CASH00.cbl:L111 and L119
    // move the caller's name into the record and the key with no case folding, so "John" and "JOHN" under
    // one stamp were two legitimate KSDS keys that both belong to the primary key, while the account table
    // stores owners uppercase (L155). EBCDIC collation also differs from UTF-8, so legacy and migrated rows
    // are matched on this normalized key rather than on ordinal position or sort order.
    List<LegacyHistory> findByRunIdAndOwnerKey(UUID runId, String ownerKey);

    long countByRunId(UUID runId);

    // A count of staged rows is a lower bound on what the legacy system actually processed, never a total:
    // EXEC CICS IGNORE CONDITION DUPREC (CASH00.cbl:L124) silently discarded a second record for the same
    // owner within one second, so a target count above this one is an accepted exception and only a target
    // count below it is a real variance. The request code reaches the record at L114 ahead of a write that
    // follows the EVALUATE unconditionally, so reads and unrecognized codes are staged too; the code set
    // that excludes them is the caller's to pass, keeping this interface free of those literals.
    long countByRunIdAndRequestCodeIn(UUID runId, Collection<String> requestCodes);
}
