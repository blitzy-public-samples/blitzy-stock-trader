package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain;

import java.time.OffsetDateTime;
import java.util.List;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;

/** The single authority on which reservation transitions are legal, idempotent no-ops or rejected. */
public final class ReservationStateMachine {

    private ReservationStateMachine() {
    }

    /**
     * One ledger row a transition requires: its event type, its magnitude and the balances as of that row.
     *
     * <p>The after-state belongs to the leg rather than to the transition because a transition may write more
     * than one row - a partial settlement writes {@code SETTLEMENT} then {@code RELEASE} - and consumers
     * derive every signed delta from consecutive {@code available_after}/{@code reserved_after} values
     * (AAP 0.6.3). A second row repeating the transition's final pair would therefore state an amount against
     * a derived delta of zero.</p>
     *
     * @param eventType      the row's event type, which alone carries the direction
     * @param amount         the row's non-negative magnitude
     * @param availableAfter the available balance once this leg, and only this leg, has been applied
     * @param reservedAfter  the reserved balance once this leg, and only this leg, has been applied
     */
    public record LedgerEffect(LedgerEventType eventType, Money amount, Money availableAfter,
            Money reservedAfter) {

        public LedgerEffect {
            // A null component is a defect in this module rather than a caller condition, so the handler's
            // catch-all renders it as 500 INTERNAL instead of a plausible 4xx a caller could act on.
            if (eventType == null || amount == null || availableAfter == null || reservedAfter == null) {
                throw new IllegalArgumentException("eventType, amount and both after-balances are required");
            }
        }
    }

    /**
     * The outcome of one transition: the resulting state, the resulting balances and the rows to append.
     *
     * @param resultingState the reservation's state after the transition, which the response reports
     * @param availableBalance the account's available balance after the transition, absolute, not a delta
     * @param reservedBalance the account's reserved balance after the transition, absolute, not a delta
     * @param ledgerEffects the rows to append, in the order they must be written, each carrying the balances
     *        as of its own leg; empty for a no-op
     * @param idempotentNoOp {@code true} exactly when nothing changed, so the caller appends nothing
     */
    public record Effect(ReservationState resultingState, Money availableBalance, Money reservedBalance,
            List<LedgerEffect> ledgerEffects, boolean idempotentNoOp) {

        public Effect {
            if (resultingState == null || availableBalance == null || reservedBalance == null) {
                throw new IllegalArgumentException("resultingState and both balances are required");
            }
            // Copied because the effects are the instruction audit/LedgerService acts on: a caller still
            // holding the list could otherwise write rows this class never sanctioned.
            ledgerEffects = List.copyOf(ledgerEffects);

            // The chain of rows has to end where the aggregate ended, which is what a future multi-leg
            // transition would break silently: the rollback-replay derivation reads an owner's LAST row as its
            // absolute end state (AAP 0.12.1), so a final leg carrying an intermediate snapshot would report a
            // balance the account never held. Value comparison, not identity - Money instances are rebuilt by
            // every arithmetic step.
            if (!ledgerEffects.isEmpty()) {
                LedgerEffect last = ledgerEffects.get(ledgerEffects.size() - 1);
                if (last.availableAfter().compareTo(availableBalance) != 0
                        || last.reservedAfter().compareTo(reservedBalance) != 0) {
                    throw new IllegalArgumentException("the last ledger effect must carry the transition's own"
                            + " balances, but " + last.eventType() + " carries available "
                            + last.availableAfter() + " / reserved " + last.reservedAfter() + " against "
                            + availableBalance + " / " + reservedBalance);
                }
            }
        }
    }

    /**
     * Moves the reservation's amount out of available funds and into reserved funds.
     *
     * <p>Balance effect: {@code available -= amount}, {@code reserved += amount}. The total is conserved, so
     * the institutional account view reports an unchanged {@code totalBalance} across a hold while the retail
     * {@code balance}, which is the available balance, falls. Ledger: one {@code HOLD} row of that amount.</p>
     *
     * @param account the account whose funds are held, already locked by the caller: this class takes no
     *        lock and opens no transaction, so the module's one fixed order - the {@code cash_account} row
     *        first, the reservation row second - stays with the service that keeps the expiry sweep
     *        cycle-free
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
        // caller error rather than something to translate: it would make the reserved balance and the account
        // currency describe different money.
        if (!account.currency().equals(reservation.currency())) {
            throw CashAccountException.forReservation(CashAccountErrorCode.CURRENCY_MISMATCH,
                    reservation.reservationId());
        }
        // Answered as a rejected transition rather than as a defect, even though only a mis-paired call can
        // reach it: that is what the state diagram says about a hold-modifying call on a terminal state.
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
            // Adds the context Money cannot know - ApiError.owner is what tells an operator which account
            // refused the hold - and never the decision.
            throw CashAccountException.forOwner(insufficient.errorCode(), account.owner(),
                    insufficient.getMessage(), insufficient);
        }
        Money newReserved = account.reservedBalance().plus(amount);

        account.moveBalances(newAvailable, newReserved);
        return new Effect(ReservationState.HELD, newAvailable, newReserved,
                List.of(new LedgerEffect(LedgerEventType.HOLD, amount, newAvailable, newReserved)), false);
    }

    /**
     * Settles up to the held amount, returning any unsettled remainder to available funds.
     *
     * <p>Balance effect from {@code HELD}: {@code reserved -= heldAmount},
     * {@code available += (heldAmount - settleAmount)} - the settled portion leaves the account for good and
     * only the remainder comes back. Ledger: a {@code SETTLEMENT} row of {@code settleAmount}, followed by a
     * {@code RELEASE} row of the remainder when there is one, so a full settlement writes one row and a
     * partial or zero settlement writes two. The two rows carry different after-states - the settled portion
     * leaving reserved, then the remainder arriving in available - so each row's amount is visible as the
     * delta between consecutive rows the way AAP 0.6.3 has consumers read it. A settle on an already
     * {@code SETTLED} reservation is an idempotent no-op that mutates nothing and appends nothing.</p>
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

        // Lazy expiry first, so an overdue hold is resolved by the request that touched it rather than
        // waiting for the sweep, and a settle is then refused from EXPIRED. The expiry write is not lost to
        // that refusal: institutional/ReservationService commits the expiry and its EXPIRY ledger row, then
        // raises the 409. Because the service expires a lapsed hold before calling here, this branch is
        // unreachable from the request path and stays for any other caller, which must still be refused
        // rather than allowed to commit funds a lapsed hold has already handed back.
        if (!expire(account, reservation, now).idempotentNoOp()) {
            throw CashAccountException.forReservation(CashAccountErrorCode.INVALID_TRANSITION,
                    reservation.reservationId());
        }

        // No default arm, deliberately: a state added to ReservationState must break this file at compile
        // time rather than fall through to one arbitrary outcome.
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

        // Lazy expiry first, as for a settle, but here the expiry IS the answer and its Effect is returned
        // intact: a release and an expiry move the money the same way, so the caller's intent is already
        // satisfied and there is nothing to refuse.
        Effect expiry = expire(account, reservation, now);
        if (!expiry.idempotentNoOp()) {
            return expiry;
        }

        return switch (reservation.state()) {
            // Both already have the funds where a release would put them, so reporting the current state is
            // the idempotent answer and no second ledger row is written for one hold.
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
         * A no-op and not a throw, which is the property the scheduled sweep depends on: the sweep re-checks
         * each candidate under the account lock, and by then a second sweeper or a settle that won the race
         * may already have made the row terminal. Exactly one terminal transition and one ledger row were
         * still written for the hold, so a throw would be a 409 on a request that did nothing wrong.
         *
         * isExpiredAt is the only overdue judgement in the module, and it is judged against the caller's
         * instant so the sweep and a lazy check cannot disagree inside one transaction. It is true only for
         * a held reservation whose expiresAt is strictly before now, so one due exactly at now survives this
         * pass and expires on the next.
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
                List.of(new LedgerEffect(LedgerEventType.EXPIRY, amount, newAvailable, newReserved)), false);
    }

    /**
     * Decides a settle on an already-terminal reservation, without the account row.
     *
     * <p>Returns for {@code SETTLED}, the idempotent no-op the contract answers {@code 200} with; refuses
     * {@code RELEASED} and {@code EXPIRED} exactly as {@link #settle} refuses them. Nothing is mutated and no
     * ledger row is named, because a terminal settle moves no money. No account is required because
     * {@code cash_reservation} carries no foreign key and its rows are retained after a retail
     * {@code DELETE} (AAP 0.11.1), so a terminal reservation may outlive the account row it names.</p>
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
     * A defect in the caller's routing rather than a caller condition: HELD is the one state from which money
     * moves, so it must be reached through settle or release with the account locked.
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
        // reserved, and ck_cash_reservation_settled_le_amount would refuse the row at flush and surface as a
        // 500 instead of the 400 the caller can act on. A settle of zero is legal and falls through, which
        // records that the order committed nothing and releases the whole hold.
        if (settled.compareTo(held) > 0) {
            throw CashAccountException.forReservation(CashAccountErrorCode.INVALID_AMOUNT,
                    reservation.reservationId());
        }

        Money remainder = held.minus(settled);
        // The two legs' snapshots are taken before moveBalances, in this order, because both helpers read the
        // aggregate: after the write there is only the final pair, which is what made a partial settlement's
        // RELEASE row state a remainder against a derived delta of zero. The settled portion leaves reserved
        // while available does not move, so the SETTLEMENT leg is (available unchanged, reserved - settled).
        Money availableWhileHeld = account.availableBalance();
        Money reservedAfterSettlement = reservedAfterReleasing(account, settled);
        Money newReserved = reservedAfterReleasing(account, held);
        // The remainder always has room to return: CashAccount bounds available + reserved on every write, so
        // available + remainder never exceeds Money.MAX_VALUE however large a credit was taken while the
        // funds were held - such a credit was refused at the time rather than allowed to strand this hold.
        Money newAvailable = availableAfterCrediting(account, remainder);

        account.moveBalances(newAvailable, newReserved);
        reservation.applyTransition(ReservationState.SETTLED, settled);

        // The SETTLEMENT row is written even when it is zero, so the ledger carries one row per settlement
        // decision and a reader can tell "settled nothing" from "never settled". The RELEASE row appears only
        // when there is a remainder, because a row of zero would assert a movement that did not happen - and
        // where it is absent the SETTLEMENT leg's snapshot is already the transition's final pair.
        List<LedgerEffect> effects = remainder.isZero()
                ? List.of(new LedgerEffect(LedgerEventType.SETTLEMENT, settled, availableWhileHeld,
                        reservedAfterSettlement))
                : List.of(new LedgerEffect(LedgerEventType.SETTLEMENT, settled, availableWhileHeld,
                                reservedAfterSettlement),
                        new LedgerEffect(LedgerEventType.RELEASE, remainder, newAvailable, newReserved));
        return new Effect(ReservationState.SETTLED, newAvailable, newReserved, effects, false);
    }

    private static Effect releaseHeld(CashAccount account, CashReservation reservation) {
        Money amount = reservation.amount();
        Money newReserved = reservedAfterReleasing(account, amount);
        Money newAvailable = availableAfterCrediting(account, amount);

        account.moveBalances(newAvailable, newReserved);
        reservation.applyTransition(ReservationState.RELEASED, null);
        return new Effect(ReservationState.RELEASED, newAvailable, newReserved,
                List.of(new LedgerEffect(LedgerEventType.RELEASE, amount, newAvailable, newReserved)), false);
    }

    /*
     * Not a caller-facing sufficiency check, despite calling the same Money.minus: reserved funds only ever
     * move by an amount this class placed there at hold time, or by a settled portion of one, so an
     * INSUFFICIENT_FUNDS here means a hold was never moved back - a defect, not a 422 telling the caller its
     * valid request lacked funds.
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
     * The available-side counterpart, and a refusal here is a defect report for the same reason: CashAccount
     * bounds available + reserved on every write, so crediting back money this class reserved cannot exceed
     * the ceiling. An AMOUNT_OUT_OF_RANGE therefore means the pair was written past it from outside the
     * application, and a 422 would leave the hold with no reachable terminal state - money stuck in
     * reserved_balance with retail PUT/DELETE refused - so the exception names the owner, both balances and
     * the amount instead. Widening the ceiling is a recorded open item (AAP 0.11.2).
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
     * A no-op reports the account's current balances rather than remembered ones: nothing was mutated, so
     * those are the post-transition values by definition.
     */
    private static Effect unchanged(CashAccount account, CashReservation reservation) {
        return new Effect(reservation.state(), account.availableBalance(), account.reservedBalance(),
                List.of(), true);
    }

    /*
     * Terminality is encoded here and nowhere else - ReservationState is deliberately behaviour-free - and
     * HELD being the only state anything can move from is what the rejected transitions and the idempotent
     * no-ops above are consequences of.
     */
    private static boolean isTerminal(ReservationState state) {
        return state != ReservationState.HELD;
    }

    /*
     * The pair is deliberately not checked for identity: a terminal reservation may outlive the account row
     * it names (AAP 0.11.1), so demanding a matching owner here would refuse the very no-ops that make
     * settle and release idempotent.
     */
    private static void requirePair(CashAccount account, CashReservation reservation) {
        if (account == null || reservation == null) {
            throw new IllegalArgumentException("account and reservation are required");
        }
    }

    private static void requireReservation(CashReservation reservation) {
        if (reservation == null) {
            throw new IllegalArgumentException("reservation is required");
        }
    }
}

