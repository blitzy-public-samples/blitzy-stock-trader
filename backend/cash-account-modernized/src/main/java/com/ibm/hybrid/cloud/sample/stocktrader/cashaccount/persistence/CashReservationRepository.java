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

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.CashReservation;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.ReservationState;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/** Data access for {@code cash_reservation}: replay lookups, the outstanding-hold test and the expiry sweep. */
public interface CashReservationRepository extends JpaRepository<CashReservation, UUID> {

    Optional<CashReservation> findByOwnerAndIdempotencyKey(String owner, String idempotencyKey);

    // Scoped by the account's incarnation rather than by its owner, which is the whole point of the method:
    // incarnation_id is renewed on every create, so an Idempotency-Key presented against an owner that was deleted
    // and recreated matches nothing here and is answered as a fresh hold or as 422 IDEMPOTENCY_KEY_REUSED - never
    // as a replay of a reservation that reserved funds in an account life that no longer exists. The same pair is
    // UNIQUE (schema/cash-account-schema.sql:L69), so at most one row can ever match and the Optional is safe by
    // construction rather than by convention.
    //
    // Called from a FRESH transaction, never from the one that lost the race. The losing INSERT's unique violation
    // reaches the service as DataIntegrityViolationException, and PostgreSQL has already aborted that transaction,
    // so a re-read inside it could only fail again; the winner's row is visible only to a new one. The service then
    // compares request_hash to tell a replay (200 plus Idempotent-Replayed: true) from key reuse (422).
    Optional<CashReservation> findByIncarnationIdAndIdempotencyKey(UUID incarnationId, String idempotencyKey);

    boolean existsByOwnerAndState(String owner, ReservationState state);

    List<CashReservation> findByStateAndExpiresAtBefore(ReservationState state, OffsetDateTime cutoff);

    // Deliberately UNLOCKED, and no @Lock may be added here. Every balance mutation in this module locks the
    // cash_account row first and the reservation row second; a lock taken on reservation rows by this read would
    // invert that order against a concurrent settle or release and deadlock the two. Collecting identifiers under
    // no lock lets each candidate's own transaction still take the account row first, which is what makes the sweep
    // cycle-free rather than merely lucky.
    //
    // A projection instead of entities for the same reason: entities returned here would join the sweeper's
    // persistence context, where a copy read before the per-candidate transaction decided anything could later be
    // flushed over that decision. (state, expires_at) serves the predicate and the ordering alike
    // (schema/cash-account-schema.sql:L156-L157), and the Pageable bounds one pass so a backlog is drained over
    // several short transactions instead of one long one.
    @Query("select r.reservationId as reservationId, r.owner as owner from CashReservation r "
            + "where r.state = :state and r.expiresAt < :cutoff order by r.expiresAt asc")
    List<ExpiryCandidate> findExpiryCandidates(@Param("state") ReservationState state,
            @Param("cutoff") OffsetDateTime cutoff, Pageable pageable);

    // Nested rather than an eighth file in this package: the type exists only as the shape of the query above and
    // has no meaning apart from it, so declaring it here keeps the two impossible to change independently.
    interface ExpiryCandidate {

        UUID getReservationId();

        String getOwner();
    }

    // A precondition the compiler cannot state: the caller must ALREADY hold this owner's cash_account row lock
    // from CashAccountRepository.findByOwnerForUpdate. Reaching the reservation row first would invert the module's
    // single fixed lock order and deadlock against any concurrent settle, release or expiry sweep on the same owner.
    //
    // Pessimistic row locking is the estate's sanctioned strategy
    // (backend/portfolio/src/main/resources/META-INF/persistence.xml:L13-L14), narrowed here from that unit-wide
    // setting to the two queries that need it so plain reads stay lock-free. The same file's
    // cache.shared.default=false - "need this to scale beyond one pod" - is equally why no second-level or query
    // cache is enabled on any method here: a settle decided against a cached state is the exact loss the lock is
    // taken to prevent.
    //
    // The JPQL is declared rather than derived because "ForUpdate" is not a property of CashReservation: derivation
    // would read the method name as the property path reservationIdForUpdate and fail the repository factory at
    // context start-up, whereas a declared query pre-empts derivation under the default CREATE_IF_NOT_FOUND lookup
    // strategy.
    //
    // PESSIMISTIC_WRITE reaches PostgreSQL as FOR NO KEY UPDATE, the rendering Hibernate's dialect gives every
    // write lock, so the logged SQL reads weaker than it is: that mode conflicts with itself, with FOR SHARE and
    // FOR UPDATE, and with any UPDATE or DELETE of the row, leaving only the FOR KEY SHARE a foreign-key check
    // takes - and no table references cash_reservation.
    //
    // No lock-timeout hint accompanies it: PostgreSQL's row locks express only NOWAIT and SKIP LOCKED, so a
    // positive wait would be silently ignored. Blocking is therefore the server's to arbitrate, and its deadlock
    // detection surfaces as CannotAcquireLockException, which the error package already renders as
    // 409 CONCURRENT_MODIFICATION - so nothing is caught or translated here.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CashReservation r where r.reservationId = :reservationId")
    Optional<CashReservation> findByReservationIdForUpdate(@Param("reservationId") UUID reservationId);

    // -2 is Hibernate's LockOptions.SKIP_LOCKED sentinel (0 is NO_WAIT, -1 WAIT_FOREVER), which the PostgreSQL
    // dialect appends to the write lock above - the emitted clause is "for no key update skip locked" - so a
    // contended row is passed over rather than waited on.
    //
    // An EMPTY Optional is therefore an ordinary outcome, not a failure: another sweeper holds the row, or it is
    // already gone. The sweep must skip such a candidate silently - reporting it would turn routine contention into
    // noise, and blocking on it would serialize the sweep behind an in-flight settle that is about to make the row
    // terminal anyway, after which the re-check would decline to expire it regardless.
    //
    // Same account-lock-first precondition, and same reason for declaring the JPQL, as the method above.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select r from CashReservation r where r.reservationId = :reservationId")
    Optional<CashReservation> claimForUpdateSkipLocked(@Param("reservationId") UUID reservationId);
}
