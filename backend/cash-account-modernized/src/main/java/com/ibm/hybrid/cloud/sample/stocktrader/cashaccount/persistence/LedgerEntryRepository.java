package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.LedgerEntry;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/** Data access to the append-only {@code ledger_entry} audit rows: one append path and three reads. */
public interface LedgerEntryRepository extends Repository<LedgerEntry, Long> {

    // No update or delete method is exposed, and none may be added. The bare Repository marker is what makes
    // that structural: the CRUD bases publish a mutating API on every interface that extends them, and a Java
    // interface cannot withdraw an inherited method, so under any of them a DELETE against ledger_entry would
    // be one compiling call away. The database half of the guard is the ledger_entry_immutable trigger
    // (BEFORE UPDATE OR DELETE) in cash-account-schema.sql, which holds for every path that is not this code.
    //
    // The generic signature mirrors CrudRepository's own so the declaration binds to SimpleJpaRepository's
    // method rather than being parsed as a derived query, and the append joins the caller's transaction,
    // which is what makes the row visible the instant that transaction commits - a separate transaction or an
    // asynchronous hand-off here would make the guarantee eventual.
    <S extends LedgerEntry> S save(S entry);

    // Callers pass an UNSORTED Pageable: a Sort carried on it is appended after this name-derived ordering
    // rather than replacing it, so it cannot reorder the result but does add terms
    // idx_ledger_entry_owner_recorded_at cannot serve, turning an index-ordered read into a full sort.
    //
    // GreaterThanEqual, not GreaterThan: "since" is an inclusive lower bound, so a timestamp copied from a
    // row an earlier page returned yields that row again instead of silently skipping the event it was read
    // from. The entry_id tie-break is what a partial settlement needs - its SETTLEMENT and RELEASE rows are
    // written in one transaction and can share recorded_at to the stored microsecond, and only the monotonic
    // surrogate then gives them a stable newest-first order across repeated reads.
    List<LedgerEntry> findByOwnerAndRecordedAtGreaterThanEqualOrderByRecordedAtDescEntryIdDesc(
            String owner, OffsetDateTime since, Pageable pageable);

    List<LedgerEntry> findByOwnerOrderByRecordedAtDescEntryIdDesc(String owner, Pageable pageable);

    // Answers "produced by a load and untouched since" for a whole set of owners in one statement. Reading
    // each owner's history instead is a query per owner, and every account the export does not name is a
    // candidate, so a partial export against a large target would turn the classification into O(N) reads of
    // unbounded rows; the anti-join returns owner names rather than entities, so nothing proportional to an
    // owner's history is materialized. The caller passes the owners in bounded chunks, which keeps the IN
    // list's bind-parameter count independent of how many accounts the target holds.
    //
    // NOT EXISTS rather than an aggregate over sources, because it stops at an owner's first disqualifying
    // row and idx_ledger_entry_owner_recorded_at serves both the outer scan and the correlated probe. The
    // query is declared because no property path expresses "and no row of another source"; it projects one
    // column read-only, so it adds no mutating surface to this deliberately append-only interface.
    @Query("select distinct entry.owner from LedgerEntry entry where entry.owner in :owners "
            + "and not exists (select other.entryId from LedgerEntry other "
            + "where other.owner = entry.owner and other.source <> :source)")
    List<String> findOwnersWithEveryEntryFrom(@Param("owners") Collection<String> owners,
            @Param("source") LedgerEntry.Source source);
}
