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

    /*
     * WHY THIS TEST LIVES IN THE PRODUCTION PACKAGE RATHER THAN A .test ONE. CashAccount.moveBalances and
     * CashReservation.applyTransition are package-private, which is how "entities never mutate reservation
     * state outside the state machine" is a compile-time fact. A test outside the package could not compile
     * against this corner of the model at all. The visibility is respected rather than used: every fixture is
     * built through the public factories and every transition is driven through the machine's own statics, so
     * what is asserted is the machine's behaviour and not the test's own bookkeeping.
     *
     * WHY ASSERTIONS READ THE RETURNED Effect AND NOT THE ENTITIES. institutional/ReservationService turns the
     * Effect into the response it answers with and into the rows audit/LedgerService appends, so the Effect is
     * the contract the rest of the module consumes; how the machine applied it to the entities is a mechanism.
     * The one deliberate exception is the rejection method, where the entities are read precisely to show that
     * nothing moved.
     *
     * The lifecycle has no legacy counterpart to compare against: CASH00 kept one mutable BALANCE per OWNER
     * (backend/cash-account-cobol/COBOL/DCLCASH.cpy:L8-L11) and dispatched six request codes over it
     * (backend/cash-account-cobol/COBOL/CASH00.cbl:L89-L102), so available/reserved balances and the
     * reservation state machine are new capability and every expected value below comes from the target
     * design, never from the COBOL.
     *
     * Balances are asserted as BigDecimal at scale 2, as in MoneyTest: NUMERIC(9,2) precision is part of the
     * contract, and every literal reaches BigDecimal as text so no expected value can depend on a binary
     * fractional representation.
     */

    /*
     * A fixed instant, never a clock. Lazy expiry is judged with a strict "expiresAt before now", so a test
     * that read the wall clock would decide by accident whether a settle resolved as a settle or as an expiry.
     * PAST_EXPIRY is the only instant outside the hold's lifetime and appears only where an expiry is wanted.
     */
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

    /*
     * A fresh pair per scenario: the statics mutate what they are given, so a shared fixture would make one
     * method's outcome depend on the order the others ran in.
     *
     * newHold returns a reservation that is already HELD but has moved no money - ReservationStateMachine.hold
     * is what moves it and what enforces the available-funds check - so this helper stops at "held at rest"
     * and every scenario that needs funds actually reserved calls hold first.
     */
    private static Fixture heldAtRest(String holdAmount) {
        return heldAtRest(holdAmount, ACCOUNT_CURRENCY);
    }

    /*
     * The reservation currency is a parameter only so the CURRENCY_MISMATCH rejection can build a hold in a
     * currency the account is not held in. CashReservation.newHold validates the code's shape and column
     * width but never its equality with the account - that pairing rule is the state machine's, which is
     * exactly what the rejection asserts.
     */
    private static Fixture heldAtRest(String holdAmount, String reservationCurrency) {
        CashAccount account = CashAccount.open("JOHN", ACCOUNT_CURRENCY, Money.of(OPENING_BALANCE));
        CashReservation reservation = CashReservation.newHold(account, "ORDER-1", Money.of(holdAmount),
                reservationCurrency, EXPIRES_AT, "idem-key-1", REQUEST_HASH);
        return new Fixture(account, reservation);
    }

    /*
     * The error code is the assertion and the message never is: CashAccountErrorCode maps each code to exactly
     * one HTTP status, so the code is what the caller observes, while the message is prose that may be
     * reworded without any contract changing.
     */
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

        // available -= amount, reserved += amount, so the total is conserved: the retail balance, which is the
        // available one, falls to 750.00 while the institutional totalBalance still reports 1000.00.
        assertThat(effect.resultingState()).isEqualTo(ReservationState.HELD);
        assertThat(effect.availableBalance().amount()).isEqualTo(new BigDecimal("750.00"));
        assertThat(effect.reservedBalance().amount()).isEqualTo(new BigDecimal("250.00"));
        assertThat(effect.ledgerEffects())
                .extracting(LedgerEffect::eventType, ledger -> ledger.amount().amount())
                .containsExactly(tuple(LedgerEventType.HOLD, new BigDecimal("250.00")));
        assertThat(effect.idempotentNoOp()).isFalse();
    }

    @Test
    void fullSettlementConsumesTheWholeHoldAndReleasesNothing() {
        Fixture fixture = heldAtRest(HOLD_AMOUNT);
        ReservationStateMachine.hold(fixture.account(), fixture.reservation());

        Effect effect = ReservationStateMachine.settle(fixture.account(), fixture.reservation(),
                Money.of(HOLD_AMOUNT), NOW);

        // The settled funds leave the account for good, so available stays at the post-hold 750.00 and only
        // the reserved balance falls. No RELEASE row accompanies it: the remainder is zero, and a row of zero
        // would assert a movement that did not happen.
        assertThat(effect.resultingState()).isEqualTo(ReservationState.SETTLED);
        assertThat(effect.availableBalance().amount()).isEqualTo(new BigDecimal("750.00"));
        assertThat(effect.reservedBalance().amount()).isEqualTo(new BigDecimal("0.00"));
        assertThat(effect.ledgerEffects())
                .extracting(LedgerEffect::eventType, ledger -> ledger.amount().amount())
                .containsExactly(tuple(LedgerEventType.SETTLEMENT, new BigDecimal("250.00")));
        assertThat(effect.idempotentNoOp()).isFalse();
    }

    @Test
    void partialSettlementSettlesItsAmountAndReleasesTheRemainder() {
        Fixture fixture = heldAtRest(HOLD_AMOUNT);
        ReservationStateMachine.hold(fixture.account(), fixture.reservation());

        Effect effect = ReservationStateMachine.settle(fixture.account(), fixture.reservation(),
                Money.of("100.00"), NOW);

        // 100.00 of the 250.00 hold is committed and the 150.00 remainder returns to available funds:
        // 750.00 + 150.00 = 900.00, with the whole hold leaving the reserved balance either way.
        assertThat(effect.resultingState()).isEqualTo(ReservationState.SETTLED);
        assertThat(effect.availableBalance().amount()).isEqualTo(new BigDecimal("900.00"));
        assertThat(effect.reservedBalance().amount()).isEqualTo(new BigDecimal("0.00"));
        // Order is part of the contract, not incidental: the ledger is append-only, so the pair of rows one
        // partial settlement writes is read back in the order named here and containsExactly pins both.
        assertThat(effect.ledgerEffects())
                .extracting(LedgerEffect::eventType, ledger -> ledger.amount().amount())
                .containsExactly(tuple(LedgerEventType.SETTLEMENT, new BigDecimal("100.00")),
                        tuple(LedgerEventType.RELEASE, new BigDecimal("150.00")));
        assertThat(effect.idempotentNoOp()).isFalse();
    }

    @Test
    void zeroSettlementIsLegalAndReleasesTheWholeHold() {
        Fixture fixture = heldAtRest(HOLD_AMOUNT);
        ReservationStateMachine.hold(fixture.account(), fixture.reservation());

        Effect effect = ReservationStateMachine.settle(fixture.account(), fixture.reservation(), Money.ZERO,
                NOW);

        /*
         * A settlement of zero is a legal transition with no legacy analogue (AAP 0.4.5): the order committed
         * nothing, so the whole 250.00 returns and the account is back to its opening 1000.00. The zero-amount
         * SETTLEMENT row is kept deliberately rather than omitted, so the ledger carries one event per
         * transition and a reader can tell "settled nothing" from "never settled" - the same reason
         * CashReservation.settledAmount() returns null, not zero, while a hold has never been settled.
         */
        assertThat(effect.resultingState()).isEqualTo(ReservationState.SETTLED);
        assertThat(effect.availableBalance().amount()).isEqualTo(new BigDecimal("1000.00"));
        assertThat(effect.reservedBalance().amount()).isEqualTo(new BigDecimal("0.00"));
        assertThat(effect.ledgerEffects())
                .extracting(LedgerEffect::eventType, ledger -> ledger.amount().amount())
                .containsExactly(tuple(LedgerEventType.SETTLEMENT, new BigDecimal("0.00")),
                        tuple(LedgerEventType.RELEASE, new BigDecimal("250.00")));
        assertThat(effect.idempotentNoOp()).isFalse();
    }

    @Test
    void releaseReturnsTheWholeHoldToAvailableFunds() {
        Fixture fixture = heldAtRest(HOLD_AMOUNT);
        ReservationStateMachine.hold(fixture.account(), fixture.reservation());

        Effect effect = ReservationStateMachine.release(fixture.account(), fixture.reservation(), NOW);

        // A release conserves the total exactly as the hold did, so the account returns to its pre-hold
        // balances: nothing was settled, so nothing left the account.
        assertThat(effect.resultingState()).isEqualTo(ReservationState.RELEASED);
        assertThat(effect.availableBalance().amount()).isEqualTo(new BigDecimal("1000.00"));
        assertThat(effect.reservedBalance().amount()).isEqualTo(new BigDecimal("0.00"));
        assertThat(effect.ledgerEffects())
                .extracting(LedgerEffect::eventType, ledger -> ledger.amount().amount())
                .containsExactly(tuple(LedgerEventType.RELEASE, new BigDecimal("250.00")));
        assertThat(effect.idempotentNoOp()).isFalse();
    }

    @Test
    void expiryReturnsTheWholeHoldToAvailableFunds() {
        Fixture fixture = heldAtRest(HOLD_AMOUNT);
        ReservationStateMachine.hold(fixture.account(), fixture.reservation());

        // The only scenario judged past EXPIRES_AT. An expiry moves the money exactly as a release does, and
        // differs only in the event type it records, which is what lets the lazy check inside release answer a
        // caller's release with an EXPIRED outcome instead of refusing it.
        Effect effect = ReservationStateMachine.expire(fixture.account(), fixture.reservation(), PAST_EXPIRY);

        assertThat(effect.resultingState()).isEqualTo(ReservationState.EXPIRED);
        assertThat(effect.availableBalance().amount()).isEqualTo(new BigDecimal("1000.00"));
        assertThat(effect.reservedBalance().amount()).isEqualTo(new BigDecimal("0.00"));
        assertThat(effect.ledgerEffects())
                .extracting(LedgerEffect::eventType, ledger -> ledger.amount().amount())
                .containsExactly(tuple(LedgerEventType.EXPIRY, new BigDecimal("250.00")));
        assertThat(effect.idempotentNoOp()).isFalse();
    }

    @Test
    void everyRejectedTransitionCarriesItsOwnCodeAndMovesNothing() {
        // Settled funds have already left the account, so a release has nothing to give back and a settle on
        // a hold whose funds were handed back has nothing left to commit: all three are 409s about the state
        // the reservation is in, and each is driven to that state through the machine's own statics.
        Fixture fromReleased = heldAtRest(HOLD_AMOUNT);
        ReservationStateMachine.hold(fromReleased.account(), fromReleased.reservation());
        ReservationStateMachine.release(fromReleased.account(), fromReleased.reservation(), NOW);
        assertRejectedWith(CashAccountErrorCode.INVALID_TRANSITION,
                () -> ReservationStateMachine.settle(fromReleased.account(), fromReleased.reservation(),
                        Money.of(HOLD_AMOUNT), NOW));
        /*
         * The account-free decision answers the same rejection. It exists because a terminal reservation
         * outlives the account it names - cash_reservation has no foreign key and its rows are retained after
         * a retail DELETE (AAP 0.11.1) - so institutional/ReservationService must be able to refuse this
         * without a CashAccount to pass in. Asserting it beside its account-taking twin is what keeps the two
         * answers from drifting apart.
         */
        assertRejectedWith(CashAccountErrorCode.INVALID_TRANSITION,
                () -> ReservationStateMachine.settleFromTerminal(fromReleased.reservation()));

        // PAST_EXPIRY appears only to drive the expiry; the settle under test is judged at NOW, and the
        // outcome is the same at either instant because isExpiredAt reports only a HELD reservation as
        // overdue, so an already terminal one is never expired a second time.
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

        /*
         * The two amount rejections also assert the balances afterwards, and this is the only place the test
         * reads them off the entities instead of off an Effect - deliberately, because the point is that the
         * machine threw before it mutated anything. That is what makes "every failure path throws before the
         * transaction commits" true at the domain layer and not merely at the service one, and it is the
         * behaviour that replaces the legacy program committing the magnitude of an overdraft into its
         * unsigned WS-CALC (backend/cash-account-cobol/COBOL/CASH00.cbl:L17).
         *
         * Settling more than was held is a 400 rather than a clamp: it would commit money the hold never
         * reserved, and the DDL's CHECK (settled_amount <= amount) would refuse the row at flush as an opaque
         * 500. Holding more than is available is the 422 the retail debit path also answers with, because
         * both ask Money.minus the same question about the same balance.
         */
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

        /*
         * The fifth rejection, and the only one that is about the pairing rather than the state: a hold in a
         * currency the account is not held in is a 400 and not something to translate, because conversion
         * never happens on the institutional path (AAP 0.7.2) - reserving 250.00 EUR against a USD account
         * would leave the reserved balance and the account currency describing different money. It is refused
         * before the available-funds check, so the balances and the reservation are read afterwards to show
         * that nothing moved even though the account could easily have covered the amount.
         */
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
        /*
         * WHY THIS CASE EXISTS. A credit that bounded only the available balance could raise it to the ceiling
         * while a hold still held funds in reserved_balance, and the release, settle or expiry that had to hand
         * those funds back then had nowhere to put them: the reservation could reach no terminal state, the
         * money stayed reserved for good, and retail PUT/DELETE answered RESERVATIONS_OUTSTANDING for as long
         * as the row existed. The ceiling therefore bounds available + reserved, and the entry point exercised
         * here is the one the retail credit, update and the migration loader all write through.
         */
        Fixture fixture = heldAtRest(HOLD_AMOUNT);
        ReservationStateMachine.hold(fixture.account(), fixture.reservation());

        // 9999999.99 is a legal Money and a legal available balance on its own; with 250.00 reserved the pair
        // is not, so the write is refused and neither balance moves.
        assertRejectedWith(CashAccountErrorCode.AMOUNT_OUT_OF_RANGE,
                () -> fixture.account().overwriteAvailableBalance(Money.of("9999999.99")));
        assertThat(fixture.account().availableBalance().amount()).isEqualTo(new BigDecimal("750.00"));
        assertThat(fixture.account().reservedBalance().amount()).isEqualTo(new BigDecimal("250.00"));
        assertThat(fixture.reservation().state()).isEqualTo(ReservationState.HELD);

        // The credit that exactly fills the room the hold does not need is still accepted, so the guard bounds
        // the pair rather than reserving headroom the account never uses: 9999749.99 + 250.00 is the ceiling.
        fixture.account().overwriteAvailableBalance(Money.of("9999749.99"));
        assertThat(fixture.account().totalBalance()).isEqualTo(new BigDecimal("9999999.99"));

        // The point of the whole case: from that maximal state the hold is still terminable, which is what the
        // lifecycle promises for every HELD reservation.
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
        /*
         * The SETTLED, RELEASED and EXPIRED self-loops of the lifecycle. They exist so a retrying
         * institutional caller - which cannot know whether its first call reached the service - is answered
         * 200 with the reservation's current state instead of a spurious 409, without a second ledger row
         * being written for one hold. An empty ledgerEffects list is therefore the assertion that matters
         * most: it is what audit/LedgerService acts on, so an empty list is what keeps the retry lossless.
         */
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
        // has since been deleted: it accepts rather than throwing, and the caller reports the current state.
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
