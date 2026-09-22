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

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.LegacyHistory;

import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Test-scope reads over the {@code legacy_history} staging rows, for asserting what a load run staged. */
// WHY THIS TYPE IS IN THE TEST TREE. Nothing the service ships reads legacy_history: migration/load's
// LegacyLoader writes the staging rows and the loader's own MigrationRun summary carries the counts the
// tooling acts on, so a read path on persistence/LegacyHistoryRepository would be shipped data-access
// surface with no shipped caller - reachable from the request path's classpath, maintained as production
// code, and exercised only by LoaderIT. The queries are staging assertions, so they live with the test that
// makes them and cannot outlive it.
//
// The bare Repository marker, as persistence/LedgerEntryRepository uses it, keeps that boundary structural:
// it publishes no save, no delete and no findAll, so this type cannot become a second write path into
// staging, and - being unassignable to LegacyHistoryRepository - it cannot make LegacyLoader's injection
// point ambiguous when Spring Data finds both interfaces under the ...cashaccount scan root. Both are
// derived-query interfaces over the same @IdClass entity, which Spring Data resolves independently.
public interface LegacyHistoryTestQueries extends Repository<LegacyHistory, LegacyHistory.Key> {

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
