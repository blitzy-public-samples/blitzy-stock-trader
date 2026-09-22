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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain;

import java.time.OffsetDateTime;
import java.util.List;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;

/*
 * WHY THIS CLASS APPLIES THE TRANSITION AS WELL AS DECIDING IT. Deciding without applying would leave the
 * balance arithmetic to be re-done by whichever service acted on the decision, which is exactly the second
 * implementation the module is built to prevent. It mutates through CashReservation.applyTransition and
 * CashAccount.moveBalances, both package-private: sitting in this package is what makes them reachable, and
 * retail, institutional, migration and config code physically cannot reach them. "Entities never mutate
 * state outside the state machine" is therefore a compile-time fact rather than a convention.
 *
 * WHY IT CANNOT LOCK OR OPEN A TRANSACTION. institutional/ReservationService owns both: every mutation runs
 * in one @Transactional unit whose first lock is always the cash_account row (PESSIMISTIC_WRITE) before the
 * reservation row is read FOR UPDATE, and that fixed order is what makes the scheduled expiry sweep
 * cycle-free. A lock taken here, below that ordering and outside the transaction that owns it, could only
 * take it in a different order. The service also maps ObjectOptimisticLockingFailureException and lock
 * timeouts to 409 CONCURRENT_MODIFICATION, which needs the framework this class stays free of.
 *
 * WHY IT NAMES LEDGER ROWS INSTEAD OF WRITING THEM. audit/LedgerService appends them inside the caller's
 * transaction, so they are queryable the instant it commits. Every row of one transition carries the same
 * final available_after / reserved_after - the post-transition balances this class returns in Effect - which
 * is why a partial settlement's SETTLEMENT and RELEASE rows share them and read back as one transition.
 *
 * WHY now IS A PARAMETER AND NEVER READ FROM A CLOCK HERE. One reservation must be judged against one instant
 * by both the scheduled sweep and the lazy check a settle or release performs first; reading a clock here
 * would let those two disagree inside one transaction, and would make expiry untestable without waiting.
 *
 * The reservation lifecycle has no legacy counterpart to cite: CASH00 held one mutable balance per owner and
 * dispatched six request codes over it (backend/cash-account-cobol/COBOL/CASH00.cbl:L89-L102), so nothing
 * here is a parity requirement.
 */
/** The single authority on which reservation transitions are legal, idempotent no-ops or rejected. */
public final class ReservationStateMachine {

    private ReservationStateMachine() {
    }

    /**
     * One ledger row a transition requires, as an event type and the non-negative magnitude it carries.
     *
     * <p>Direction is implied by the event type, never by a sign: {@code ledger_entry.amount} is
     * {@code NUMERIC(9,2)} under {@code CHECK (amount >= 0)}, and any signed delta a consumer needs is
     * derived from consecutive {@code available_after} / {@code reserved_after} values.</p>
     */
    public record LedgerEffect(LedgerEventType eventType, Money amount) {

        public LedgerEffect {
            // A null component is a defect in this module rather than a caller condition, so it is reported
            // the way Money and the entities report one - the exception handler's catch-all renders it as
            // 500 INTERNAL instead of dressing a bug up as a plausible 4xx a caller could act on.
            if (eventType == null || amount == null) {
                throw new IllegalArgumentException("eventType and amount are required");
            }
        }
    }

    /**
     * The outcome of one transition: the resulting state, the resulting balances and the rows to append.
     *
     * @param resultingState the reservation's state after the transition, which the response reports
     * @param availableBalance the account's available balance after the transition, absolute, not a delta
     * @param reservedBalance the account's reserved balance after the transition, absolute, not a delta
     * @param ledgerEffects the rows to append, in the order they must be written; empty for a no-op
     * @param idempotentNoOp {@code true} exactly when nothing changed, so the caller appends nothing
     */
    public record Effect(ReservationState resultingState, Money availableBalance, Money reservedBalance,
            List<LedgerEffect> ledgerEffects, boolean idempotentNoOp) {

        public Effect {
            if (resultingState == null || availableBalance == null || reservedBalance == null) {
                throw new IllegalArgumentException("resultingState and both balances are required");
            }
            // Copied rather than stored as handed in: the ledger effects are the instruction audit/LedgerService
            // acts on, and a caller that could still mutate the list after the decision was taken could write
            // rows this class never sanctioned. List.copyOf also rejects a null list or element.
            ledgerEffects = List.copyOf(ledgerEffects);
        }
    }

    /**
     * Moves the reservation's amount out of available funds and into reserved funds.
     *
     * <p>Balance effect: {@code available -= amount}, {@code reserved += amount}. The total is conserved, so
     * the institutional account view reports an unchanged {@code totalBalance} across a hold while the retail
     * {@code balance}, which is the available balance, falls. Ledger: one {@code HOLD} row of that amount.</p>
     *
     * @param account the account whose funds are held, already locked by the caller
     * @param reservation a hold in {@code HELD}, as {@link CashReservation#newHold} built it
     * @return the post-transition balances and the single {@code HOLD} ledger effect
     * @throws CashAccountException {@link CashAccountErrorCode#CURRENCY_MISMATCH} when the hold currency is
     *         not the account currency, {@link CashAccountErrorCode#INSUFFICIENT_FUNDS} when the available
     *         balance will not cover the hold, {@link CashAccountErrorCode#INVALID_TRANSITION} when the
     *         reservation is already terminal
     */
    public static Effect hold(CashAccount account, CashReservation reservation) {
        requirePair(account, reservation);

        // Conversion never happens on the institutional path (AAP 0.7.2), so a hold in another currency is a
        // caller error and not something to translate: reserving 100 EUR against a USD account would make the
        // reserved balance and the account currency describe different money.
        if (!account.currency().equals(reservation.currency())) {
            throw CashAccountException.forReservation(CashAccountErrorCode.CURRENCY_MISMATCH,
                    reservation.reservationId());
        }
        // A hold whose reservation is already terminal can only be a mis-paired call, but it is answered as a
        // rejected transition rather than as a defect because that is what the state diagram says about
        // hold-modifying calls on a terminal state, and because the outcome - no money moves - is the same.
        if (isTerminal(reservation.state())) {
            throw CashAccountException.forReservation(CashAccountErrorCode.INVALID_TRANSITION,
                    reservation.reservationId());
        }

        Money amount = reservation.amount();
        Money newAvailable;
        try {
            // The sufficiency check is Money.minus's, deliberately: a retail debit and an institutional hold
            // must agree on what "enough" means, and a second check here would be free to disagree. Money
            // raises INSUFFICIENT_FUNDS (422) exactly when the available balance would go negative, which
            // replaces the legacy unsigned WS-CALC storing the magnitude of an overdraft (CASH00.cbl:L17).
            newAvailable = account.availableBalance().minus(amount);
        } catch (CashAccountException insufficient) {
            // Only the context is added, never the decision: the owner is known here and not inside Money, and
            // ApiError.owner is what tells an operator which account refused the hold.
            throw CashAccountException.forOwner(insufficient.errorCode(), account.owner(),
                    insufficient.getMessage(), insufficient);
        }
        Money newReserved = account.reservedBalance().plus(amount);

        account.moveBalances(newAvailable, newReserved);
        return new Effect(ReservationState.HELD, newAvailable, newReserved,
                List.of(new LedgerEffect(LedgerEventType.HOLD, amount)), false);
    }

    /**
     * Settles up to the held amount, returning any unsettled remainder to available funds.
     *
     * <p>Balance effect from {@code HELD}: {@code reserved -= heldAmount},
     * {@code available += (heldAmount - settleAmount)} - the settled portion leaves the account for good and
     * only the remainder comes back. Ledger: a {@code SETTLEMENT} row of {@code settleAmount}, followed by a
     * {@code RELEASE} row of the remainder when there is one, so a full settlement writes one row and a
     * partial or zero settlement writes two. A settle on an already {@code SETTLED} reservation is an
     * idempotent no-op that mutates nothing and appends nothing.</p>
     *
     * @param account the account holding the funds, already locked by the caller
     * @param reservation the reservation to settle
     * @param settleAmount the amount to settle, or {@code null} for the full held amount; zero is legal and
     *        settles nothing while releasing the whole hold
     * @param now the instant the transition is judged against, for the overdue check
     * @return the post-transition balances and the rows to append
     * @throws CashAccountException {@link CashAccountErrorCode#INVALID_AMOUNT} when {@code settleAmount}
     *         exceeds the held amount, {@link CashAccountErrorCode#INVALID_TRANSITION} from
     *         {@code RELEASED} or {@code EXPIRED}, including a reservation that was overdue on arrival
     */
    public static Effect settle(CashAccount account, CashReservation reservation, Money settleAmount,
            OffsetDateTime now) {
        requirePair(account, reservation);

        // Lazy expiry first, so an overdue hold is resolved by the request that touched it rather than waiting
        // for the sweep. Evaluation then continues from EXPIRED, which rejects a settle.
        //
        // The expiry write is NOT lost to that rejection, and the arrangement that saves it lives in
        // institutional/ReservationService: it applies the expiry under the account lock, commits it together
        // with its EXPIRY ledger row, and raises the 409 only after that transaction has committed - so the
        // row every transition is required to leave behind survives the refusal. Because the service expires
        // a lapsed hold before it ever calls this method, the branch below is unreachable from the request
        // path; it stays because this class must answer correctly for any caller, including a unit test, and
        // because a caller that has not committed the expiry itself must still be refused rather than allowed
        // to commit funds a lapsed hold has already handed back.
        if (!expire(account, reservation, now).idempotentNoOp()) {
            throw CashAccountException.forReservation(CashAccountErrorCode.INVALID_TRANSITION,
                    reservation.reservationId());
        }

        // An arrow switch over the enum, with no default: adding a state to ReservationState breaks this file
        // at compile time, which is how "the single authority on transitions" stays true of a later change.
        return switch (reservation.state()) {
            case SETTLED -> unchanged(account, reservation);
            case RELEASED, EXPIRED -> throw CashAccountException.forReservation(
                    CashAccountErrorCode.INVALID_TRANSITION, reservation.reservationId());
            case HELD -> settleHeld(account, reservation, settleAmount);
        };
    }

    /**
     * Returns the whole held amount to available funds without settling any of it.
     *
     * <p>Balance effect from {@code HELD}: {@code reserved -= amount}, {@code available += amount}, so the
     * total is conserved exactly as the hold conserved it. Ledger: one {@code RELEASE} row. A release on a
     * {@code RELEASED} or {@code EXPIRED} reservation is an idempotent no-op reporting the current state; a
     * release on an overdue hold expires it and reports {@code EXPIRED}.</p>
     *
     * @param account the account holding the funds, already locked by the caller
     * @param reservation the reservation to release
     * @param now the instant the transition is judged against, for the overdue check
     * @return the post-transition balances and the rows to append
     * @throws CashAccountException {@link CashAccountErrorCode#INVALID_TRANSITION} from {@code SETTLED},
     *         because settled funds have already left the account and cannot be given back by a release
     */
    public static Effect release(CashAccount account, CashReservation reservation, OffsetDateTime now) {
        requirePair(account, reservation);

        // Lazy expiry first, as for a settle - but here the expiry is the answer rather than a rejection, so
        // its Effect is returned intact and its EXPIRY row is appended and committed. The asymmetry is the
        // whole point: a release and an expiry move the money the same way, so the caller's intent is already
        // satisfied and there is nothing to refuse, whereas a settle cannot commit funds a lapsed hold has
        // already handed back. Both outcomes commit either way - the service holds them in the one
        // transaction it locked the account in, and only the settle's refusal is raised after it.
        Effect expiry = expire(account, reservation, now);
        if (!expiry.idempotentNoOp()) {
            return expiry;
        }

        return switch (reservation.state()) {
            // Both terminal states already have the funds where a release would put them, so reporting the
            // current state is the idempotent answer and no second ledger row is written for one hold.
            case RELEASED, EXPIRED -> unchanged(account, reservation);
            case SETTLED -> throw CashAccountException.forReservation(CashAccountErrorCode.INVALID_TRANSITION,
                    reservation.reservationId());
            case HELD -> releaseHeld(account, reservation);
        };
    }

    /**
     * Expires an overdue hold, returning its amount to available funds; every other case changes nothing.
     *
     * <p>Balance effect when it expires: {@code reserved -= amount}, {@code available += amount}. Ledger: one
     * {@code EXPIRY} row. A reservation that is already terminal, or held but not yet due, is an idempotent
     * no-op.</p>
     *
     * @param account the account holding the funds, already locked by the caller
     * @param reservation the reservation to consider for expiry
     * @param now the instant the reservation's {@code expiresAt} is judged against, strictly
     * @return the post-transition balances and the {@code EXPIRY} effect, or an unchanged no-op
     */
    public static Effect expire(CashAccount account, CashReservation reservation, OffsetDateTime now) {
        requirePair(account, reservation);

        /*
         * WHY THIS IS A NO-OP AND NOT A THROW - the property the scheduled sweep depends on. The sweep reads
         * candidates without a lock, then for each one opens its own transaction, locks the account row, reads
         * the reservation FOR UPDATE and re-checks that it is still held and still overdue. A second sweeper,
         * or a settle or release that won the race, will already have made the row terminal by then. That is
         * a benign outcome, not a conflict: exactly one terminal transition and exactly one ledger row were
         * written for the hold, which is the guarantee. A throwing expire would turn it into a 409 on a
         * request that did nothing wrong, and would make the sweep log failures for work another thread had
         * correctly completed.
         *
         * isExpiredAt is also the only overdue judgement in the module: it is true only for a held
         * reservation whose expiresAt is strictly before now, so a reservation due exactly at now survives
         * this pass and expires on the next one.
         */
        if (!reservation.isExpiredAt(now)) {
            return unchanged(account, reservation);
        }

        Money amount = reservation.amount();
        Money newReserved = reservedAfterReleasing(account, amount);
        Money newAvailable = availableAfterCrediting(account, amount);

        account.moveBalances(newAvailable, newReserved);
        reservation.applyTransition(ReservationState.EXPIRED, null);
        return new Effect(ReservationState.EXPIRED, newAvailable, newReserved,
                List.of(new LedgerEffect(LedgerEventType.EXPIRY, amount)), false);
    }

    /*
     * WHY THE TWO METHODS BELOW TAKE NO ACCOUNT. cash_reservation carries no foreign key and its rows are
     * retained after a retail DELETE (AAP 0.11.1), so a settled, released or expired reservation legitimately
     * outlives the account row it names - and its outcome moves no money, so no account and no lock are
     * needed to decide it. They exist so that institutional/ReservationService can answer such a call without
     * demanding a row that may be gone, while the (state, command) table stays in this one class: the
     * decisions they take are exactly the terminal arms of settle and release above, with the mutating HELD
     * arm excluded rather than reimplemented.
     */
    /**
     * Decides a settle on an already-terminal reservation, without the account row.
     *
     * <p>Returns for {@code SETTLED}, the idempotent no-op the contract answers {@code 200} with; refuses
     * {@code RELEASED} and {@code EXPIRED} exactly as {@link #settle} refuses them. Nothing is mutated and no
     * ledger row is named, because a terminal settle moves no money.</p>
     *
     * @param reservation a reservation in one of the three terminal states
     * @throws CashAccountException {@link CashAccountErrorCode#INVALID_TRANSITION} from {@code RELEASED} or
     *         {@code EXPIRED}
     * @throws IllegalArgumentException when the reservation is still {@code HELD}, which moves money and so
     *         must go through {@link #settle} with the account locked
     */
    public static void settleFromTerminal(CashReservation reservation) {
        requireReservation(reservation);

        boolean refused = switch (reservation.state()) {
            case SETTLED -> false;
            // Both have already returned the held funds to available, so a settle has nothing left to commit.
            case RELEASED, EXPIRED -> true;
            case HELD -> throw heldNeedsTheAccount(reservation, "settled");
        };
        if (refused) {
            throw CashAccountException.forReservation(CashAccountErrorCode.INVALID_TRANSITION,
                    reservation.reservationId());
        }
    }

    /**
     * Decides a release on an already-terminal reservation, without the account row.
     *
     * <p>Returns for {@code RELEASED} and {@code EXPIRED}, both of which already have the funds where a
     * release would put them; refuses {@code SETTLED} exactly as {@link #release} refuses it.</p>
     *
     * @param reservation a reservation in one of the three terminal states
     * @throws CashAccountException {@link CashAccountErrorCode#INVALID_TRANSITION} from {@code SETTLED}
     * @throws IllegalArgumentException when the reservation is still {@code HELD}, which moves money and so
     *         must go through {@link #release} with the account locked
     */
    public static void releaseFromTerminal(CashReservation reservation) {
        requireReservation(reservation);

        boolean refused = switch (reservation.state()) {
            case RELEASED, EXPIRED -> false;
            // Settled funds have left the account for good, so a release has nothing to give back.
            case SETTLED -> true;
            case HELD -> throw heldNeedsTheAccount(reservation, "released");
        };
        if (refused) {
            throw CashAccountException.forReservation(CashAccountErrorCode.INVALID_TRANSITION,
                    reservation.reservationId());
        }
    }

    /*
     * A defect in the caller's routing rather than a caller condition: a HELD reservation is the one state
     * from which money moves, so it must be reached through settle or release with the account locked. It is
     * reported the way every other pairing defect in this class is, and the handler's catch-all renders it as
     * 500 INTERNAL instead of dressing a bug up as a 4xx a caller could act on.
     */
    private static IllegalArgumentException heldNeedsTheAccount(CashReservation reservation, String verb) {
        return new IllegalArgumentException("reservation " + reservation.reservationId()
                + " is HELD and can only be " + verb + " with its account locked");
    }

    private static Effect settleHeld(CashAccount account, CashReservation reservation, Money settleAmount) {
        Money held = reservation.amount();
        // Resolved here rather than in the service so that an absent request body, a body with a null amount
        // and a body naming the full amount cannot be answered differently (AAP 0.6.2).
        Money settled = settleAmount == null ? held : settleAmount;

        // Rejected rather than clamped: settling more than was held would commit money the hold never
        // reserved, and the DDL's CHECK (settled_amount IS NULL OR settled_amount <= amount) would refuse the
        // row at flush and surface as a 500 instead of the 400 the caller can act on. A settle of zero is
        // legal and deliberately falls through: it records that the order committed nothing and releases the
        // whole hold.
        if (settled.compareTo(held) > 0) {
            throw CashAccountException.forReservation(CashAccountErrorCode.INVALID_AMOUNT,
                    reservation.reservationId());
        }

        Money remainder = held.minus(settled);
        Money newReserved = reservedAfterReleasing(account, held);
        // The remainder always has room to return: CashAccount bounds available + reserved on every write, so
        // available + remainder <= available + reserved <= Money.MAX_VALUE however large a credit was taken
        // while the funds were held. A credit that would have filled that room was refused at the time with
        // 422 AMOUNT_OUT_OF_RANGE instead of being allowed to strand this hold.
        Money newAvailable = availableAfterCrediting(account, remainder);

        account.moveBalances(newAvailable, newReserved);
        reservation.applyTransition(ReservationState.SETTLED, settled);

        // The SETTLEMENT row is written even when it is zero, so the ledger carries one row per settlement
        // decision and a reader can tell "settled nothing" from "never settled". The RELEASE row appears only
        // when there is a remainder, because a row of zero would assert a movement that did not happen.
        List<LedgerEffect> effects = remainder.isZero()
                ? List.of(new LedgerEffect(LedgerEventType.SETTLEMENT, settled))
                : List.of(new LedgerEffect(LedgerEventType.SETTLEMENT, settled),
                        new LedgerEffect(LedgerEventType.RELEASE, remainder));
        return new Effect(ReservationState.SETTLED, newAvailable, newReserved, effects, false);
    }

    private static Effect releaseHeld(CashAccount account, CashReservation reservation) {
        Money amount = reservation.amount();
        Money newReserved = reservedAfterReleasing(account, amount);
        Money newAvailable = availableAfterCrediting(account, amount);

        account.moveBalances(newAvailable, newReserved);
        reservation.applyTransition(ReservationState.RELEASED, null);
        return new Effect(ReservationState.RELEASED, newAvailable, newReserved,
                List.of(new LedgerEffect(LedgerEventType.RELEASE, amount)), false);
    }

    /*
     * Not a caller-facing sufficiency check, despite calling the same Money.minus the available path calls.
     * Reserved funds only ever move by an amount this class placed there at hold time, so the reserved
     * balance cannot be short of a held reservation's amount while the invariant holds. If Money raises
     * INSUFFICIENT_FUNDS here it means a hold moved money that a settle, release or expiry then failed to
     * move back - a defect in this class or a row edited outside it - so it is re-reported as one instead of
     * being rendered as a 422 that would tell the caller its perfectly valid request lacked funds.
     */
    private static Money reservedAfterReleasing(CashAccount account, Money amount) {
        try {
            return account.reservedBalance().minus(amount);
        } catch (CashAccountException underflow) {
            throw new IllegalStateException("reserved balance " + account.reservedBalance() + " of account "
                    + account.owner() + " is short of the held amount " + amount, underflow);
        }
    }

    /*
     * The available-side counterpart of reservedAfterReleasing, shared by the settle, release and expiry
     * credit-backs, and there for the same reason: this addition cannot legitimately be refused, so a refusal
     * is a defect report rather than a caller-facing 422.
     *
     * CashAccount bounds available + reserved on every write, so for any row this application created
     * available + amount <= available + reserved <= Money.MAX_VALUE whenever amount is money this class
     * reserved - which is the only amount any of the three callers passes. An AMOUNT_OUT_OF_RANGE here
     * therefore means the pair was written past the ceiling by something outside the application: a psql
     * UPDATE, or a row stored before that guard existed. Reporting it as a 422 would tell an institutional
     * caller its perfectly valid release lacked range over an amount it never chose, and would leave the hold
     * with no reachable terminal state at all (AAP 0.6.3) - money stuck in reserved_balance and retail
     * PUT/DELETE refused for as long as the row exists. It is re-reported as the defect it is instead, naming
     * the owner, both balances and the amount so the offending row can be found and corrected. Widening the
     * ceiling is a recorded open item (AAP 0.11.2), not a decision this class makes.
     */
    private static Money availableAfterCrediting(CashAccount account, Money amount) {
        try {
            return account.availableBalance().plus(amount);
        } catch (CashAccountException overflow) {
            throw new IllegalStateException("available balance " + account.availableBalance() + " of account "
                    + account.owner() + " cannot take back " + amount + " with " + account.reservedBalance()
                    + " still reserved", overflow);
        }
    }

    /*
     * The no-op answer, and the reason every no-op reports balances read back from the account rather than
     * remembered ones: nothing was mutated, so the current values are the post-transition values by
     * definition, and a test can assert that the account is untouched by comparing it before and after.
     */
    private static Effect unchanged(CashAccount account, CashReservation reservation) {
        return new Effect(reservation.state(), account.availableBalance(), account.reservedBalance(),
                List.of(), true);
    }

    /*
     * Terminality is encoded here and nowhere else: ReservationState is deliberately behaviour-free, so a
     * helper there would split the knowledge this class exists to hold. HELD is the only state from which
     * anything can move, which is exactly what the three rejected transitions and the four idempotent no-ops
     * above are consequences of.
     */
    private static boolean isTerminal(ReservationState state) {
        return state != ReservationState.HELD;
    }

    /*
     * The service pairs an account with a reservation; a null of either is a defect in that pairing. The pair
     * is deliberately not checked for identity: cash_reservation carries no foreign key to cash_account and
     * its rows are retained after a retail DELETE (AAP 0.11.1), so a terminal reservation may legitimately
     * outlive the account row it names, and demanding a matching owner here would refuse the very no-ops that
     * make settle and release idempotent.
     */
    private static void requirePair(CashAccount account, CashReservation reservation) {
        if (account == null || reservation == null) {
            throw new IllegalArgumentException("account and reservation are required");
        }
    }

    /* The account-free decisions have only the reservation to check, and a null of it is the same defect. */
    private static void requireReservation(CashReservation reservation) {
        if (reservation == null) {
            throw new IllegalArgumentException("reservation is required");
        }
    }
}

