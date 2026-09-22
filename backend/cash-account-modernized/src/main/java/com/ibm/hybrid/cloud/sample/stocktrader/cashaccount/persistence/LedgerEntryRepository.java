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

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.LedgerEntry;

import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.time.OffsetDateTime;
import java.util.List;

/** Data access to the append-only {@code ledger_entry} audit rows: one append path and two ordered reads. */
// WHY THE BARE Repository MARKER AND NOT JpaRepository. Spring Data's CRUD-derived base interfaces -
// JpaRepository, CrudRepository, ListCrudRepository, PagingAndSortingRepository - each publish a mutating
// API on every interface that extends them, and a Java interface cannot withdraw an inherited method: once
// any of them is the base type, an UPDATE or a DELETE against ledger_entry is a compiling call away and the
// only thing standing between a caller and a lost audit row is the database trigger. Repository is the empty
// marker, whose documented purpose is exactly this - declare the operations the domain permits and no others
// - so this type's whole surface is the three methods below, and the append-only shape is structural rather
// than conventional. Declared methods are still served by SimpleJpaRepository, so save() behaves precisely
// as it would on JpaRepository. The database-level half of the same invariant is the trigger
// ledger_entry_immutable (BEFORE UPDATE OR DELETE, RAISE EXCEPTION) in
// schema/cash-account-schema.sql:L194-L196; two guards of deliberately different kind, because one is
// reviewable at compile time in this module and the other holds for every path that is not this code.
//
// WHY THE INVARIANT EARNS THAT REDUNDANCY. The trail this replaces was the write-only HISTORY KSDS, whose
// EXEC CICS IGNORE CONDITION DUPREC ahead of its only WRITE
// [backend/cash-account-cobol/COBOL/CASH00.cbl:L123-L131] discarded a second event for the same owner within
// one second, silently and with no error to the caller - and nothing ever read the file back to notice. The
// replacement is lossless and queryable, so a row that could be altered or discarded would not be a lesser
// audit trail than the legacy one; it would be a worse one, because consumers now trust it.
public interface LedgerEntryRepository extends Repository<LedgerEntry, Long> {

    // INSERT-only by construction rather than by discipline, on three independent counts: entry_id is
    // BIGINT GENERATED ALWAYS AS IDENTITY (schema/cash-account-schema.sql:L76) mapped
    // GenerationType.IDENTITY, so an instance from LedgerEntry.of(...) carries a null identifier and is
    // always transient - SimpleJpaRepository.save() therefore takes its persist() branch and never merge();
    // every mapped column is updatable = false; and the entity publishes no mutator, leaving Hibernate's
    // dirty check nothing it could ever flush as an UPDATE. The generic signature mirrors CrudRepository's
    // own so the declaration binds to the implementation's method instead of being parsed as a derived
    // query. The call joins the caller's transaction - audit/LedgerService appends inside it - which is what
    // makes the row visible to the ledger query the instant that transaction commits; no flush, no separate
    // transaction and no asynchronous hand-off belongs here or the guarantee becomes eventual.
    <S extends LedgerEntry> S save(S entry);

    // Callers pass an UNSORTED Pageable - PageRequest.of(0, limit), the limit the controller has already
    // bounded. A Sort carried on the Pageable is appended after this name-derived ordering rather than
    // replacing it, so it cannot reorder the result, entry_id having already made the ordering total; what
    // it does is add ORDER BY terms that idx_ledger_entry_owner_recorded_at
    // (schema/cash-account-schema.sql:L159-L160) cannot serve, turning an index-ordered read into a sort of
    // every matched row.
    //
    // GreaterThanEqual, not GreaterThan: "since" is an inclusive lower bound, so a timestamp copied from a
    // row an earlier page returned yields that row again instead of silently skipping the event it was read
    // from. The entry_id tie-break is what a partial settlement needs - its SETTLEMENT and RELEASE rows are
    // written in one transaction and can share recorded_at to the stored microsecond, and only the
    // monotonic surrogate then gives them a stable newest-first order across repeated reads.
    List<LedgerEntry> findByOwnerAndRecordedAtGreaterThanEqualOrderByRecordedAtDescEntryIdDesc(
            String owner, OffsetDateTime since, Pageable pageable);

    List<LedgerEntry> findByOwnerOrderByRecordedAtDescEntryIdDesc(String owner, Pageable pageable);
}
