package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.InstanceOfAssertFactories.throwable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.ReservationStateMachine.Effect;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.ReservationStateMachine.LedgerEffect;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;

/** Unit tests for the reservation lifecycle: its balance effects, ledger rows, rejections and no-ops. */
class ReservationStateMachineTest {

    // This test sits in the production package because CashAccount.moveBalances and
    // CashReservation.applyTransition are package-private, which is how "entities never mutate reservation state
    // outside the state machine" is a compile-time fact. Assertions read the returned Effect, the contract
    // institutional/ReservationService and audit/LedgerService consume, except in the rejection method, where the
    // entities are read to show nothing moved. The lifecycle has no legacy counterpart - CASH00 kept one mutable
    // BALANCE per OWNER (backend/cash-account-cobol/COBOL/DCLCASH.cpy:L8-L11) dispatched over six request codes
    // (CASH00.cbl:L89-L102) - so every expected value comes from the target design, never from the COBOL.

    // A fixed instant, never a clock: lazy expiry is judged with a strict "expiresAt before now", so reading the
    // wall clock would decide by accident whether a settle resolves as a settle or as an expiry. PAST_EXPIRY is
    // the only instant outside the hold's lifetime and appears only where an expiry is wanted.
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-01-15T10:00:00Z");

    private static final OffsetDateTime EXPIRES_AT = NOW.plusHours(24);

    private static final OffsetDateTime PAST_EXPIRY = NOW.plusHours(25);

    private static final String OPENING_BALANCE = "1000.00";

    private static final String HOLD_AMOUNT = "250.00";

    /** The account's currency; a hold in anything else is the CURRENCY_MISMATCH the institutional path refuses. */
    private static final String ACCOUNT_CURRENCY = "USD";

    private static final String OTHER_CURRENCY = "EUR";

    /** cash_reservation.request_hash is CHAR(64), so the factory demands exactly 64 characters. */
    private static final String REQUEST_HASH = "0123456789abcdef".repeat(4);

    /** One scenario's collaborators, handed out together because the statics act on the pair. */
    private record Fixture(CashAccount account, CashReservation reservation) {
    }

    // A fresh pair per scenario, because the statics mutate what they are given. newHold yields a reservation
    // already HELD that has moved no money - ReservationStateMachine.hold is what moves it and what enforces
    // the available-funds check - so a scenario needing funds actually reserved calls hold first.
    private static Fixture heldAtRest(String holdAmount) {
        return heldAtRest(holdAmount, ACCOUNT_CURRENCY);
    }

    // The reservation currency is a parameter only so the CURRENCY_MISMATCH rejection can build a hold in a
    // currency the account is not held in: newHold validates the code's shape and column width, never its
    // equality with the account, which is the state machine's pairing rule.
    private static Fixture heldAtRest(String holdAmount, String reservationCurrency) {
        CashAccount account = CashAccount.open("JOHN", ACCOUNT_CURRENCY, Money.of(OPENING_BALANCE));
        CashReservation reservation = CashReservation.newHold(account, "ORDER-1", Money.of(holdAmount),
                reservationCurrency, EXPIRES_AT, "idem-key-1", REQUEST_HASH);
        return new Fixture(account, reservation);
    }

    // The code, never the message: CashAccountErrorCode maps each code to exactly one HTTP status, so the code
    // is what the caller observes while the message is prose that may be reworded freely.
    private static void assertRejectedWith(CashAccountErrorCode expected, ThrowingCallable transition) {
        assertThatThrownBy(transition)
                .isInstanceOf(CashAccountException.class)
                .asInstanceOf(throwable(CashAccountException.class))
                .extracting(CashAccountException::errorCode)
                .isEqualTo(expected);
    }

    @Test
    void holdMovesTheAmountFromAvailableIntoReserved() {
        Fixture fixture = heldAtRest(HOLD_AMOUNT);

        Effect effect = ReservationStateMachine.hold(fixture.account(), fixture.reservation());

        // A hold conserves the total: the amount leaves available and enters reserved, so the retail balance
        // falls while the institutional totalBalance does not move.
        assertThat(effect.resultingState()).isEqualTo(ReservationState.HELD);
        assertThat(effect.availableBalance().amount()).isEqualTo(new BigDecimal("750.00"));
        assertThat(effect.reservedBalance().amount()).isEqualTo(new BigDecimal("250.00"));
        // The row's own after-state is asserted, not just its amount: a consumer derives the signed delta from
        // consecutive available_after/reserved_after values (AAP 0.6.3), so a single-leg transition's row has to
        // carry the pair the transition ended on.
        assertThat(effect.ledgerEffects())
                .extracting(LedgerEffect::eventType, ledger -> ledger.amount().amount(),
                        ledger -> ledger.availableAfter().amount(),
                        ledger -> ledger.reservedAfter().amount())
                .containsExactly(tuple(LedgerEventType.HOLD, new BigDecimal("250.00"),
                        new BigDecimal("750.00"), new BigDecimal("250.00")));
        assertThat(effect.idempotentNoOp()).isFalse();
    }

    @Test
    void fullSettlementConsumesTheWholeHoldAndReleasesNothing() {
        Fixture fixture = heldAtRest(HOLD_AMOUNT);
        ReservationStateMachine.hold(fixture.account(), fixture.reservation());

        Effect effect = ReservationStateMachine.settle(fixture.account(), fixture.reservation(),
                Money.of(HOLD_AMOUNT), NOW);

        // Settled funds leave the account for good, so only the reserved balance falls. No RELEASE row
        // accompanies a zero remainder: such a row would record a movement that did not happen.
        assertThat(effect.resultingState()).isEqualTo(ReservationState.SETTLED);
        assertThat(effect.availableBalance().amount()).isEqualTo(new BigDecimal("750.00"));
        assertThat(effect.reservedBalance().amount()).isEqualTo(new BigDecimal("0.00"));
        // One leg, so its snapshot is the transition's own pair: the settled amount is the fall in reserved and
        // available does not move.
        assertThat(effect.ledgerEffects())
                .extracting(LedgerEffect::eventType, ledger -> ledger.amount().amount(),
                        ledger -> ledger.availableAfter().amount(),
                        ledger -> ledger.reservedAfter().amount())
                .containsExactly(tuple(LedgerEventType.SETTLEMENT, new BigDecimal("250.00"),
                        new BigDecimal("750.00"), new BigDecimal("0.00")));
        assertThat(effect.idempotentNoOp()).isFalse();
    }

    @Test
    void partialSettlementSettlesItsAmountAndReleasesTheRemainder() {
        Fixture fixture = heldAtRest(HOLD_AMOUNT);
        ReservationStateMachine.hold(fixture.account(), fixture.reservation());

        Effect effect = ReservationStateMachine.settle(fixture.account(), fixture.reservation(),
                Money.of("100.00"), NOW);

        // The settled part is committed and the remainder returns to available funds, while the whole hold
        // leaves the reserved balance either way.
        assertThat(effect.resultingState()).isEqualTo(ReservationState.SETTLED);
        assertThat(effect.availableBalance().amount()).isEqualTo(new BigDecimal("900.00"));
        assertThat(effect.reservedBalance().amount()).isEqualTo(new BigDecimal("0.00"));
        // Order is part of the contract, not incidental: the ledger is append-only, so the pair of rows one
        // partial settlement writes is read back in the order named here. Each row carries the state as of its
        // own leg, which is what makes both stated amounts visible as deltas (AAP 0.6.3): the settled 100.00 is
        // reserved falling 250.00 -> 150.00 with available untouched, then the 150.00 remainder is available
        // rising 750.00 -> 900.00 as the rest of the hold leaves reserved.
        assertThat(effect.ledgerEffects())
                .extracting(LedgerEffect::eventType, ledger -> ledger.amount().amount(),
                        ledger -> ledger.availableAfter().amount(),
                        ledger -> ledger.reservedAfter().amount())
                .containsExactly(tuple(LedgerEventType.SETTLEMENT, new BigDecimal("100.00"),
                                new BigDecimal("750.00"), new BigDecimal("150.00")),
                        tuple(LedgerEventType.RELEASE, new BigDecimal("150.00"),
                                new BigDecimal("900.00"), new BigDecimal("0.00")));
        assertThat(effect.idempotentNoOp()).isFalse();
    }

    @Test
    void zeroSettlementIsLegalAndReleasesTheWholeHold() {
        Fixture fixture = heldAtRest(HOLD_AMOUNT);
        ReservationStateMachine.hold(fixture.account(), fixture.reservation());

        Effect effect = ReservationStateMachine.settle(fixture.account(), fixture.reservation(), Money.ZERO,
                NOW);

        // A settlement of zero is a legal transition with no legacy analogue (AAP 0.4.5): the order committed
        // nothing, so the whole hold returns. The zero-amount SETTLEMENT row is kept deliberately, so the ledger
        // carries one event per transition and a reader can tell "settled nothing" from "never settled" - the
        // same reason settledAmount() returns null rather than zero until a hold is settled.
        assertThat(effect.resultingState()).isEqualTo(ReservationState.SETTLED);
        assertThat(effect.availableBalance().amount()).isEqualTo(new BigDecimal("1000.00"));
        assertThat(effect.reservedBalance().amount()).isEqualTo(new BigDecimal("0.00"));
        // The zero SETTLEMENT leg moves nothing, so it repeats the balances the hold left, and the RELEASE leg
        // alone shows the whole hold returning - the one case where two consecutive rows differ in exactly one
        // of the two columns.
        assertThat(effect.ledgerEffects())
                .extracting(LedgerEffect::eventType, ledger -> ledger.amount().amount(),
                        ledger -> ledger.availableAfter().amount(),
                        ledger -> ledger.reservedAfter().amount())
                .containsExactly(tuple(LedgerEventType.SETTLEMENT, new BigDecimal("0.00"),
                                new BigDecimal("750.00"), new BigDecimal("250.00")),
                        tuple(LedgerEventType.RELEASE, new BigDecimal("250.00"),
                                new BigDecimal("1000.00"), new BigDecimal("0.00")));
        assertThat(effect.idempotentNoOp()).isFalse();
    }

    @Test
    void releaseReturnsTheWholeHoldToAvailableFunds() {
        Fixture fixture = heldAtRest(HOLD_AMOUNT);
        ReservationStateMachine.hold(fixture.account(), fixture.reservation());

        Effect effect = ReservationStateMachine.release(fixture.account(), fixture.reservation(), NOW);

        // Nothing was settled, so nothing left the account and the balances return to their pre-hold values.
        assertThat(effect.resultingState()).isEqualTo(ReservationState.RELEASED);
        assertThat(effect.availableBalance().amount()).isEqualTo(new BigDecimal("1000.00"));
        assertThat(effect.reservedBalance().amount()).isEqualTo(new BigDecimal("0.00"));
        assertThat(effect.ledgerEffects())
                .extracting(LedgerEffect::eventType, ledger -> ledger.amount().amount(),
                        ledger -> ledger.availableAfter().amount(),
                        ledger -> ledger.reservedAfter().amount())
                .containsExactly(tuple(LedgerEventType.RELEASE, new BigDecimal("250.00"),
                        new BigDecimal("1000.00"), new BigDecimal("0.00")));
        assertThat(effect.idempotentNoOp()).isFalse();
    }

    @Test
    void expiryReturnsTheWholeHoldToAvailableFunds() {
        Fixture fixture = heldAtRest(HOLD_AMOUNT);
        ReservationStateMachine.hold(fixture.account(), fixture.reservation());

        // The only scenario judged past EXPIRES_AT. An expiry moves the money exactly as a release does and
        // differs only in the event type, which is what lets the lazy check inside release answer a caller's
        // release with an EXPIRED outcome instead of refusing it.
        Effect effect = ReservationStateMachine.expire(fixture.account(), fixture.reservation(), PAST_EXPIRY);

        assertThat(effect.resultingState()).isEqualTo(ReservationState.EXPIRED);
        assertThat(effect.availableBalance().amount()).isEqualTo(new BigDecimal("1000.00"));
        assertThat(effect.reservedBalance().amount()).isEqualTo(new BigDecimal("0.00"));
        assertThat(effect.ledgerEffects())
                .extracting(LedgerEffect::eventType, ledger -> ledger.amount().amount(),
                        ledger -> ledger.availableAfter().amount(),
                        ledger -> ledger.reservedAfter().amount())
                .containsExactly(tuple(LedgerEventType.EXPIRY, new BigDecimal("250.00"),
                        new BigDecimal("1000.00"), new BigDecimal("0.00")));
        assertThat(effect.idempotentNoOp()).isFalse();
    }

    @Test
    void everyRejectedTransitionCarriesItsOwnCodeAndMovesNothing() {
        // Settled funds have already left the account, so a release has nothing to give back, and a settle on a
        // hold whose funds were handed back has nothing left to commit: all three are 409s about the state the
        // reservation is in.
        Fixture fromReleased = heldAtRest(HOLD_AMOUNT);
        ReservationStateMachine.hold(fromReleased.account(), fromReleased.reservation());
        ReservationStateMachine.release(fromReleased.account(), fromReleased.reservation(), NOW);
        assertRejectedWith(CashAccountErrorCode.INVALID_TRANSITION,
                () -> ReservationStateMachine.settle(fromReleased.account(), fromReleased.reservation(),
                        Money.of(HOLD_AMOUNT), NOW));
        // The account-free decision answers the same rejection, because a terminal reservation outlives the
        // account it names - cash_reservation has no foreign key and its rows are retained after a retail DELETE
        // (AAP 0.11.1) - so the service must be able to refuse this with no CashAccount to pass in.
        assertRejectedWith(CashAccountErrorCode.INVALID_TRANSITION,
                () -> ReservationStateMachine.settleFromTerminal(fromReleased.reservation()));

        // PAST_EXPIRY appears only to drive the expiry; the settle under test is judged at NOW, and the outcome
        // is the same at either instant because isExpiredAt reports only a HELD reservation as overdue, so an
        // already terminal one is never expired a second time.
        Fixture fromExpired = heldAtRest(HOLD_AMOUNT);
        ReservationStateMachine.hold(fromExpired.account(), fromExpired.reservation());
        ReservationStateMachine.expire(fromExpired.account(), fromExpired.reservation(), PAST_EXPIRY);
        assertRejectedWith(CashAccountErrorCode.INVALID_TRANSITION,
                () -> ReservationStateMachine.settle(fromExpired.account(), fromExpired.reservation(),
                        Money.of(HOLD_AMOUNT), NOW));
        assertRejectedWith(CashAccountErrorCode.INVALID_TRANSITION,
                () -> ReservationStateMachine.settleFromTerminal(fromExpired.reservation()));

        Fixture fromSettled = heldAtRest(HOLD_AMOUNT);
        ReservationStateMachine.hold(fromSettled.account(), fromSettled.reservation());
        ReservationStateMachine.settle(fromSettled.account(), fromSettled.reservation(),
                Money.of(HOLD_AMOUNT), NOW);
        assertRejectedWith(CashAccountErrorCode.INVALID_TRANSITION,
                () -> ReservationStateMachine.release(fromSettled.account(), fromSettled.reservation(), NOW));
        assertRejectedWith(CashAccountErrorCode.INVALID_TRANSITION,
                () -> ReservationStateMachine.releaseFromTerminal(fromSettled.reservation()));

        // The amount rejections read the balances off the entities, the only place this test does, because the
        // point is that the machine threw before it mutated anything - the behaviour that replaces the legacy
        // program committing the magnitude of an overdraft into its unsigned WS-CALC
        // (backend/cash-account-cobol/COBOL/CASH00.cbl:L17). Settling more than was held is a 400 rather than a
        // clamp, since it would commit money the hold never reserved and the DDL's CHECK (settled_amount <=
        // amount) would refuse the row at flush as an opaque 500; holding more than is available is the 422 the
        // retail debit path answers with, both asking Money.minus the same question.
        Fixture overSettle = heldAtRest(HOLD_AMOUNT);
        ReservationStateMachine.hold(overSettle.account(), overSettle.reservation());
        assertRejectedWith(CashAccountErrorCode.INVALID_AMOUNT,
                () -> ReservationStateMachine.settle(overSettle.account(), overSettle.reservation(),
                        Money.of("300.00"), NOW));
        assertThat(overSettle.account().availableBalance().amount()).isEqualTo(new BigDecimal("750.00"));
        assertThat(overSettle.account().reservedBalance().amount()).isEqualTo(new BigDecimal("250.00"));
        assertThat(overSettle.reservation().state()).isEqualTo(ReservationState.HELD);

        Fixture overHold = heldAtRest("2000.00");
        assertRejectedWith(CashAccountErrorCode.INSUFFICIENT_FUNDS,
                () -> ReservationStateMachine.hold(overHold.account(), overHold.reservation()));
        assertThat(overHold.account().availableBalance().amount()).isEqualTo(new BigDecimal("1000.00"));
        assertThat(overHold.account().reservedBalance().amount()).isEqualTo(new BigDecimal("0.00"));

        // The one rejection about the pairing rather than the state: a hold in a currency the account is not
        // held in is a 400 and never something to translate, because conversion never happens on the
        // institutional path (AAP 0.7.2) and a reserved balance in another currency would describe different
        // money. It is refused before the available-funds check, which the covered amount here shows.
        Fixture currencyMismatch = heldAtRest(HOLD_AMOUNT, OTHER_CURRENCY);
        assertRejectedWith(CashAccountErrorCode.CURRENCY_MISMATCH,
                () -> ReservationStateMachine.hold(currencyMismatch.account(),
                        currencyMismatch.reservation()));
        assertThat(currencyMismatch.account().availableBalance().amount())
                .isEqualTo(new BigDecimal("1000.00"));
        assertThat(currencyMismatch.account().reservedBalance().amount()).isEqualTo(new BigDecimal("0.00"));
        assertThat(currencyMismatch.reservation().state()).isEqualTo(ReservationState.HELD);
        assertThat(currencyMismatch.reservation().settledAmount()).isNull();
    }

    @Test
    void aBalanceIncreaseCannotFillTheRoomHeldFundsMustReturnTo() {
        // A credit bounding only the available balance could raise it to the ceiling while a hold still held
        // funds in reserved_balance, leaving the release, settle or expiry that must hand those funds back with
        // nowhere to put them: no terminal state, money reserved for good, and retail PUT/DELETE answering
        // RESERVATIONS_OUTSTANDING for as long as the row existed. The ceiling therefore bounds available +
        // reserved, at the entry point the retail credit, update and the migration loader all write through.
        Fixture fixture = heldAtRest(HOLD_AMOUNT);
        ReservationStateMachine.hold(fixture.account(), fixture.reservation());

        // A legal Money and a legal available balance on its own; with the hold reserved the pair is not.
        assertRejectedWith(CashAccountErrorCode.AMOUNT_OUT_OF_RANGE,
                () -> fixture.account().overwriteAvailableBalance(Money.of("9999999.99")));
        assertThat(fixture.account().availableBalance().amount()).isEqualTo(new BigDecimal("750.00"));
        assertThat(fixture.account().reservedBalance().amount()).isEqualTo(new BigDecimal("250.00"));
        assertThat(fixture.reservation().state()).isEqualTo(ReservationState.HELD);

        // The credit that exactly fills the room the hold does not need is still accepted, so the guard bounds
        // the pair rather than reserving headroom the account never uses.
        fixture.account().overwriteAvailableBalance(Money.of("9999749.99"));
        assertThat(fixture.account().totalBalance()).isEqualTo(new BigDecimal("9999999.99"));

        // From that maximal state the hold is still terminable, which the lifecycle promises for every hold.
        Effect effect = ReservationStateMachine.release(fixture.account(), fixture.reservation(), NOW);

        assertThat(effect.resultingState()).isEqualTo(ReservationState.RELEASED);
        assertThat(effect.availableBalance().amount()).isEqualTo(new BigDecimal("9999999.99"));
        assertThat(effect.reservedBalance().amount()).isEqualTo(new BigDecimal("0.00"));
        assertThat(effect.ledgerEffects())
                .extracting(LedgerEffect::eventType, ledger -> ledger.amount().amount())
                .containsExactly(tuple(LedgerEventType.RELEASE, new BigDecimal("250.00")));
        assertThat(effect.idempotentNoOp()).isFalse();
    }

    @Test
    void idempotentNoOpsReportTheCurrentStateAndAppendNothing() {
        // The SETTLED, RELEASED and EXPIRED self-loops exist so a retrying institutional caller, which cannot
        // know whether its first call reached the service, is answered 200 with the reservation's current state
        // instead of a spurious 409. The empty ledgerEffects list is what matters most: audit/LedgerService acts
        // on that list, so an empty one is what keeps one hold from collecting a second ledger row.
        Fixture settled = heldAtRest(HOLD_AMOUNT);
        ReservationStateMachine.hold(settled.account(), settled.reservation());
        ReservationStateMachine.settle(settled.account(), settled.reservation(), Money.of(HOLD_AMOUNT), NOW);

        Effect settleAgain = ReservationStateMachine.settle(settled.account(), settled.reservation(),
                Money.of(HOLD_AMOUNT), NOW);

        assertThat(settleAgain.idempotentNoOp()).isTrue();
        assertThat(settleAgain.resultingState()).isEqualTo(ReservationState.SETTLED);
        assertThat(settleAgain.ledgerEffects()).isEmpty();
        assertThat(settleAgain.availableBalance().amount()).isEqualTo(new BigDecimal("750.00"));
        assertThat(settleAgain.reservedBalance().amount()).isEqualTo(new BigDecimal("0.00"));
        // The same no-op decided without the account, which is how the service answers a retry whose account
        // has since been deleted.
        assertThatNoException()
                .isThrownBy(() -> ReservationStateMachine.settleFromTerminal(settled.reservation()));

        Fixture released = heldAtRest(HOLD_AMOUNT);
        ReservationStateMachine.hold(released.account(), released.reservation());
        ReservationStateMachine.release(released.account(), released.reservation(), NOW);

        Effect releaseAgain = ReservationStateMachine.release(released.account(), released.reservation(), NOW);

        assertThat(releaseAgain.idempotentNoOp()).isTrue();
        assertThat(releaseAgain.resultingState()).isEqualTo(ReservationState.RELEASED);
        assertThat(releaseAgain.ledgerEffects()).isEmpty();
        assertThat(releaseAgain.availableBalance().amount()).isEqualTo(new BigDecimal("1000.00"));
        assertThat(releaseAgain.reservedBalance().amount()).isEqualTo(new BigDecimal("0.00"));
        assertThatNoException()
                .isThrownBy(() -> ReservationStateMachine.releaseFromTerminal(released.reservation()));

        // An expiry already put the funds where a release would: the caller's intent is satisfied, so the
        // release reports EXPIRED rather than refusing what the sweep happened to do first.
        Fixture expired = heldAtRest(HOLD_AMOUNT);
        ReservationStateMachine.hold(expired.account(), expired.reservation());
        ReservationStateMachine.expire(expired.account(), expired.reservation(), PAST_EXPIRY);

        Effect releaseExpired = ReservationStateMachine.release(expired.account(), expired.reservation(), NOW);

        assertThat(releaseExpired.idempotentNoOp()).isTrue();
        assertThat(releaseExpired.resultingState()).isEqualTo(ReservationState.EXPIRED);
        assertThat(releaseExpired.ledgerEffects()).isEmpty();
        assertThat(releaseExpired.availableBalance().amount()).isEqualTo(new BigDecimal("1000.00"));
        assertThat(releaseExpired.reservedBalance().amount()).isEqualTo(new BigDecimal("0.00"));
        assertThatNoException()
                .isThrownBy(() -> ReservationStateMachine.releaseFromTerminal(expired.reservation()));
    }
}
