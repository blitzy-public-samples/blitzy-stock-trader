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

    // Owner-scoped ON PURPOSE, to see the rows the incarnation-scoped lookup below cannot: consulted after
    // that lookup misses, a match here can only be a retained row of an earlier incarnation - the
    // 422 IDEMPOTENCY_KEY_REUSED case uq_cash_reservation_incarnation_key cannot catch, because a new
    // incarnation makes the pair unique again (AAP 0.11.1).
    //
    // A List, not an Optional, because the rows are legitimately plural: cash_reservation carries no foreign
    // key and survives a retail DELETE, so every incarnation an owner has had may have left one row under the
    // same key, and an Optional-returning query would answer that with a 500.
    List<CashReservation> findByOwnerAndIdempotencyKey(String owner, String idempotencyKey);

    // Scoped by the account's incarnation rather than by its owner: incarnation_id is renewed on every create,
    // so a key presented against an owner that was deleted and recreated matches nothing here - never a replay
    // of a reservation that reserved funds in an account life that no longer exists. The pair read here is
    // uq_cash_reservation_incarnation_key, so the Optional is safe by construction.
    //
    // Called from a FRESH transaction, never the one that lost the race: PostgreSQL has already aborted that
    // one, so a re-read inside it could only fail again, and the winner's row is visible only to a new
    // transaction. The service then compares request_hash to tell a replay from key reuse.
    Optional<CashReservation> findByIncarnationIdAndIdempotencyKey(UUID incarnationId, String idempotencyKey);

    boolean existsByOwnerAndState(String owner, ReservationState state);

    // Deliberately UNLOCKED, and no @Lock may be added here: every balance mutation locks the cash_account row
    // first and the reservation row second, so a lock taken on reservation rows by this read would invert that
    // order against a concurrent settle and deadlock the two. Collecting identifiers under no lock lets each
    // candidate's own transaction still take the account row first, which is what makes the sweep cycle-free.
    //
    // A projection instead of entities for the same reason: entities returned here would join the sweeper's
    // persistence context, where a copy read before the per-candidate transaction decided anything could later
    // be flushed over that decision. idx_cash_reservation_state_expires_at serves the predicate and the
    // ordering alike, and the Pageable bounds one pass so a backlog is drained over several short transactions.
    @Query("select r.reservationId as reservationId, r.owner as owner from CashReservation r "
            + "where r.state = :state and r.expiresAt < :cutoff order by r.expiresAt asc")
    List<ExpiryCandidate> findExpiryCandidates(@Param("state") ReservationState state,
            @Param("cutoff") OffsetDateTime cutoff, Pageable pageable);

    /** The shape of the sweep query above, nested so the two cannot be changed independently. */
    interface ExpiryCandidate {

        UUID getReservationId();

        String getOwner();
    }

    // A precondition the compiler cannot state: the caller must ALREADY hold this owner's cash_account row lock
    // from CashAccountRepository.findByOwnerForUpdate. Reaching the reservation row first would invert the
    // module's single fixed lock order and deadlock against a concurrent settle, release or expiry sweep on the
    // same owner. No second-level or query cache is enabled on any method here for the same reason a lock is
    // taken at all: a settle decided against a cached state is the exact loss it prevents.
    //
    // The JPQL is declared because "ForUpdate" is not a property of CashReservation: derivation would read the
    // method name as the property path reservationIdForUpdate and fail the repository factory at start-up.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CashReservation r where r.reservationId = :reservationId")
    Optional<CashReservation> findByReservationIdForUpdate(@Param("reservationId") UUID reservationId);

    // -2 is Hibernate's LockOptions.SKIP_LOCKED sentinel (0 is NO_WAIT, -1 WAIT_FOREVER), which the translator
    // appends to the write lock as "for no key update skip locked", so a contended row is passed over rather
    // than waited on.
    //
    // An EMPTY Optional is therefore an ordinary outcome: another sweeper holds the row, or it is already gone.
    // Blocking instead would serialize the sweep behind an in-flight settle that is about to make the row
    // terminal anyway, after which the re-check would decline to expire it regardless.
    //
    // Same account-lock-first precondition, and same reason for declaring the JPQL, as the method above.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select r from CashReservation r where r.reservationId = :reservationId")
    Optional<CashReservation> claimForUpdateSkipLocked(@Param("reservationId") UUID reservationId);
}
