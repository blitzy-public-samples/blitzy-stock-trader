/*
       Copyright 2019-2021 IBM Corp, All Rights Reserved
       Copyright 2023-2024 Kyndryl, All Rights Reserved

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

package com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.lifecycle;

import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.audit.AuditTimeline;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.control.ControlLimits;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.control.PreTradeControlService;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.dao.OrderStore;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.dao.ReferenceDataStore;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.dao.SeedDataLoader;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.dao.SettlementExceptionStore;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.AuditEvent;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.ClientAccount;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.ControlResult;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.ExceptionStatus;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.Execution;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.Order;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.OrderRequest;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.OrderStatus;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.Position;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.PostTradeStatus;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.RecordSource;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.SettlementException;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.SettlementInstruction;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.StateMachine;

import java.math.BigDecimal;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import java.util.Arrays;
import java.util.List;
import java.util.Queue;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;


/** Unit tests for the order state machine OrderLifecycleService runs from submit to simulated execution */
public class OrderLifecycleServiceTest {

    private static final String ACTOR = "institutional-trader";
    private static final String ENTITY_ORDER = "ORDER";

    /* One fixed UTC instant for the whole graph, which is what lets submittedAt, executedAt and
       every audit timestamp be compared for equality instead of against a tolerance window. No
       mocking library is needed for it: ClockProducer is the module's only source of time and no
       service reads Instant.now() inline. */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-15T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    /* The configured defaults of META-INF/microprofile-config.properties, restated here rather
       than read from it: a test that loaded the same file it checks would still pass if an
       operator lowered a default, and the arithmetic below is only meaningful against a known
       ceiling. */
    private static final BigDecimal MAX_ORDER_NOTIONAL = new BigDecimal("1000000.00");
    private static final BigDecimal MAX_POSITION_NOTIONAL = new BigDecimal("5000000.00");
    private static final BigDecimal FAT_FINGER_THRESHOLD = new BigDecimal("2500000.00");
    private static final List<String> RESTRICTED_SYMBOLS = Arrays.asList("RSTRA", "RSTRB");
    private static final int EXCEPTION_SLA_HOURS = 24;

    private static final String CONTROL_MAX_ORDER_NOTIONAL = "MAX_ORDER_NOTIONAL";
    private static final String CONTROL_MAX_POSITION_NOTIONAL = "MAX_POSITION_NOTIONAL";
    private static final String CONTROL_RESTRICTED_SYMBOL = "RESTRICTED_SYMBOL";
    private static final String CONTROL_FAT_FINGER = "FAT_FINGER";
    private static final int CONTROL_COUNT = 4;

    private static final int SEEDED_POSITIONS = 5;
    private static final int DUPLICATE_SUBMITTERS = 8;
    private static final int LATCH_TIMEOUT_SECONDS = 30;

    //Comfortably above the longest refusal the service composes and far below the megabyte a
    //rendered out-of-range amount would run to, so the assertion tells the two apart.
    private static final int MAX_REFUSAL_MESSAGE_LENGTH = 300;

    //Written as an escape so this source file stays ASCII whatever encoding an editor saves it in,
    //while the assertions still pin the U+2003 character a client can send.
    private static final String EM_SPACE = "\u2003";

    private OrderStore orderStore;
    private ReferenceDataStore referenceData;
    private AuditTimeline auditTimeline;
    private OrderLifecycleService service;


    @BeforeEach
    void buildCollaboratorGraph() {
        orderStore = new OrderStore();
        referenceData = new ReferenceDataStore();
        auditTimeline = new AuditTimeline();

        //The exception store is only a collaborator of PostTradeService here: no test in this
        //class asserts on it, because the exception workflow is not the order state machine.
        service = lifecycleService(orderStore, new SettlementExceptionStore(), referenceData,
                auditTimeline);
        seedReferenceData(referenceData);
    }

    @Test
    void testSubmitOrderUnderAllLimitsIsExecuted() {
        Order order = service.submit(
                request("API-001", "INST-001", "SYNA", "BUY", 100L, "100.00"), ACTOR);

        assertEquals("ORD-000001", order.getOrderId(), "the first order of a fresh store");
        assertEquals(OrderStatus.EXECUTED, order.getStatus(), "terminal order status");
        /* INST-001's firm and counterparty instructions agree, so affirmation finds no break and
           submit answers with what PostTradeService.onExecuted returned - already settlement-ready
           rather than merely pending. */
        assertEquals(PostTradeStatus.SETTLEMENT_READY, order.getPostTradeStatus(),
                "terminal post-trade status for a client whose settlement instructions agree");
        assertEquals("SYNA", order.getSymbol(), "stored symbol");
        assertEquals(100L, order.getQuantity(), "stored quantity");
        assertAmount("10000.00", order.getNotional(), "notional is quantity x limitPrice");
        assertEquals(ACTOR, order.getSubmittedBy(), "submittedBy is the actor the caller passed");
        assertEquals(FIXED_INSTANT, order.getSubmittedAt(), "submittedAt comes from the injected clock");
        assertEquals(RecordSource.API, order.getSource(),
                "the two-argument overload is the REST entry point and always records API");
        assertTrue(order.isSimulated(), "every order labels itself simulated");
        assertFalse(order.getDisclaimer().isBlank(), "every order carries a non-blank disclaimer");
        assertNull(order.getRejectionReason(), "an accepted order carries no rejection reason");

        Execution execution = order.getExecution();
        assertNotNull(execution, "an executed order carries its simulated fill");
        assertEquals("EXE-000001", execution.getExecutionId(), "the first execution of a fresh store");
        assertAmount("100.00", execution.getFillPrice(), "the fill is priced at the order's own limitPrice");
        assertEquals(100L, execution.getFilledQuantity(), "the whole order quantity is filled");
        assertEquals(FIXED_INSTANT, execution.getExecutedAt(), "executedAt comes from the injected clock");
        assertEquals("SIMULATED", execution.getVenue(), "no exchange exists, so the venue is SIMULATED");
        assertTrue(execution.isSimulated(), "every execution labels itself simulated");
        assertFalse(execution.getDisclaimer().isBlank(),
                "every execution carries a non-blank disclaimer of its own, not only its order");

        List<ControlResult> results = order.getControlResults();
        assertEquals(CONTROL_COUNT, results.size(), "all four controls record a result on every order");
        for (ControlResult result : results) {
            assertTrue(result.isPassed(), result.getControl() + " must pass: " + result.getReason());
        }

        Position filled = referenceData.findPosition("INST-001", "SYNA");
        assertEquals(10100L, filled.getQuantity(), "the fill is applied to the client's position");
        assertAmount("1010000.00", filled.getPositionNotional(),
                "the whole resulting quantity is marked at the fill price");
        /* A position carries a third label the order and the execution do not: the holding itself
           was invented, not merely the activity against it, so reference data is synthetic as well
           as simulated. A fill rewrites the position, which is why the labels are asserted on the
           filled object rather than only on the seeded one. */
        assertTrue(filled.isSynthetic(), "every position labels itself synthetic");
        assertTrue(filled.isSimulated(), "every position labels itself simulated");
        assertFalse(filled.getDisclaimer().isBlank(), "every position carries a non-blank disclaimer");
        assertEquals(OrderStatus.EXECUTED, service.get(order.getOrderId()).getStatus(),
                "the stored order is the one submit returned");
    }

    @Test
    void testRejectOrderExceedingMaxNotional() {
        /* 1,500,000 breaches MAX_ORDER_NOTIONAL alone: the fat-finger ceiling of 2,500,000 and the
           resulting position of 25000 x 100.00 = 2,500,000 both sit exactly at or under their
           limits, so one result fails, the reason has nothing to be joined with, and the
           evaluation still reports the other three. */
        Order order = service.submit(
                request("API-002", "INST-001", "SYNA", "BUY", 15000L, "100.00"), ACTOR);

        assertEquals(OrderStatus.REJECTED, order.getStatus(), "terminal order status");
        assertNull(order.getExecution(), "a rejected order is never filled");
        assertNull(order.getPostTradeStatus(),
                "post-trade state begins at execution, so a rejected order has none");
        assertEquals(CONTROL_COUNT, order.getControlResults().size(),
                "a rejection records every control result, not just the failing one");
        assertFalse(control(order, CONTROL_MAX_ORDER_NOTIONAL).isPassed(),
                "1500000.00 must breach MAX_ORDER_NOTIONAL");
        assertTrue(control(order, CONTROL_MAX_POSITION_NOTIONAL).isPassed(),
                "2500000.00 is inside MAX_POSITION_NOTIONAL");
        assertTrue(control(order, CONTROL_FAT_FINGER).isPassed(),
                "1500000.00 is inside FAT_FINGER_NOTIONAL_THRESHOLD");
        assertTrue(control(order, CONTROL_RESTRICTED_SYMBOL).isPassed(), "SYNA is not restricted");

        String reason = order.getRejectionReason();
        assertTrue(reason.contains("exceeds MAX_ORDER_NOTIONAL 1000000.00"), "rejectionReason: " + reason);
        assertFalse(reason.contains("; "), "a single failing control leaves nothing to join: " + reason);

        assertEquals(10000L, referenceData.findPosition("INST-001", "SYNA").getQuantity(),
                "a rejected order leaves the position exactly as it was");
    }

    @Test
    void testRestrictedSymbolIsRejectedAndLeavesNoPosition() {
        Order order = service.submit(
                request("API-003", "INST-001", "RSTRA", "BUY", 10L, "10.00"), ACTOR);

        assertEquals(OrderStatus.REJECTED, order.getStatus(), "a restricted symbol is refused");
        assertFalse(control(order, CONTROL_RESTRICTED_SYMBOL).isPassed(),
                "RSTRA is on the configured restricted list");
        assertTrue(order.getRejectionReason().contains("is on the restricted list RESTRICTED_SYMBOLS"),
                "rejectionReason: " + order.getRejectionReason());

        /* A refused order answers evaluateAndFill with the position it was handed, so an absent
           position stays absent: only a fill may bring one into existence. */
        assertEquals(SEEDED_POSITIONS, referenceData.positionCount(),
                "a rejected order creates no position");
        assertNull(referenceData.findPosition("INST-001", "RSTRA"),
                "no position exists in a symbol that was never filled");

        List<AuditEvent> events = service.events(order.getOrderId());
        assertEquals(2, events.size(),
                "a rejected order records its submission and its rejection and nothing more");
        assertEdge(events.get(0), StateMachine.ORDER, LifecycleTransitions.NONE,
                OrderStatus.SUBMITTED.name());
        assertEdge(events.get(1), StateMachine.ORDER, OrderStatus.SUBMITTED.name(),
                OrderStatus.REJECTED.name());
        assertEquals(order.getRejectionReason(), events.get(1).getReason(),
                "the rejection event carries the failing control reasons verbatim");
        for (AuditEvent event : events) {
            assertEquals(StateMachine.ORDER, event.getStateMachine(),
                    "a rejected order never enters the post-trade state machine");
        }
    }

    @Test
    void testEveryTransitionAppendsTimestampedAuditEvent() {
        Order order = service.submit(
                request("API-004", "INST-001", "SYNA", "BUY", 100L, "100.00"), ACTOR);
        String orderId = order.getOrderId();
        String executionId = order.getExecution().getExecutionId();

        List<AuditEvent> events = service.events(orderId);
        assertEquals(5, events.size(),
                "three order edges and two post-trade edges are recorded for a clean execution");

        assertEdge(events.get(0), StateMachine.ORDER, LifecycleTransitions.NONE,
                OrderStatus.SUBMITTED.name());
        assertTrue(events.get(0).getReason().contains("API-004"),
                "the submission event names the clientOrderId: " + events.get(0).getReason());

        assertEdge(events.get(1), StateMachine.ORDER, OrderStatus.SUBMITTED.name(),
                OrderStatus.ACCEPTED.name());
        assertEquals("all pre-trade controls passed", events.get(1).getReason(), "acceptance reason");

        assertEdge(events.get(2), StateMachine.ORDER, OrderStatus.ACCEPTED.name(),
                OrderStatus.EXECUTED.name());
        assertTrue(events.get(2).getReason().contains(executionId),
                "the execution event names the executionId: " + events.get(2).getReason());

        assertEdge(events.get(3), StateMachine.POST_TRADE, LifecycleTransitions.NONE,
                PostTradeStatus.PENDING_AFFIRMATION.name());
        assertTrue(events.get(3).getReason().contains(executionId),
                "the affirmation event names the executionId: " + events.get(3).getReason());

        assertEdge(events.get(4), StateMachine.POST_TRADE,
                PostTradeStatus.PENDING_AFFIRMATION.name(),
                PostTradeStatus.SETTLEMENT_READY.name());
        assertEquals("SSI affirmed (simulated)", events.get(4).getReason(), "affirmation reason");

        long previousSequence = 0L;
        for (AuditEvent event : events) {
            //Both of an order's state machines are filed under one entity type: POST_TRADE names a
            //machine, never an entity anyone can ask for.
            assertEquals(ENTITY_ORDER, event.getEntityType(), "entityType");
            assertEquals(orderId, event.getEntityId(), "entityId");
            assertEquals(FIXED_INSTANT, event.getTimestamp(), "every event is timestamped");
            assertTrue(event.getSequence() > previousSequence,
                    "sequence must increase strictly, saw " + event.getSequence());
            previousSequence = event.getSequence();
            assertEquals(ACTOR, event.getActor(), "actor");
            assertTrue(event.isSimulated(), "every event labels itself simulated");
            assertFalse(event.getDisclaimer().isBlank(), "every event carries a non-blank disclaimer");
        }

        assertEquals(events.size(), auditTimeline.all().size(),
                "one submission writes exactly these events and no others");
    }

    @Test
    void testDuplicateSubmissionOfOneClientOrderIdIsRefused() {
        service.submit(request("API-005", "INST-001", "SYNA", "BUY", 100L, "100.00"), ACTOR);

        StateConflictException conflict = assertThrows(StateConflictException.class,
                () -> service.submit(request("API-005", "INST-001", "SYNA", "BUY", 100L, "100.00"),
                        ACTOR),
                "a repeated clientOrderId must be refused as a conflict");

        assertTrue(conflict.getMessage().contains("API-005"),
                "the conflict must name the clientOrderId: " + conflict.getMessage());
        /* Idempotency is settled by a putIfAbsent on the client order id before any order object
           exists, so the duplicate leaves behind neither a second order nor a second fill. */
        assertEquals(1, orderStore.count(), "only the first submission is stored");
        assertEquals(10100L, referenceData.findPosition("INST-001", "SYNA").getQuantity(),
                "only the first submission reached the position");
    }

    @Test
    void testUnknownOrderIdIsReportedAsMissing() {
        assertThrows(EntityNotFoundException.class, () -> service.get("ORD-999999"),
                "an unknown order id is a missing resource");
        //events reads through the store first, so an unknown id answers as a missing order rather
        //than as an order that happens to have an empty history.
        assertThrows(EntityNotFoundException.class, () -> service.events("ORD-999999"),
                "an unknown order id has no readable timeline either");
    }

    @Test
    void testInvalidQuantityOrLimitPriceIsRefused() {
        //Zero and negative are invalid, which is a different rule from the control thresholds: a
        //value sitting exactly on a configured ceiling passes, while a quantity of zero never does.
        assertValidationMessage("quantity",
                () -> service.submit(request("API-006", "INST-001", "SYNA", "BUY", 0L, "100.00"),
                        ACTOR));
        assertValidationMessage("quantity",
                () -> service.submit(request("API-006", "INST-001", "SYNA", "BUY", -5L, "100.00"),
                        ACTOR));
        assertValidationMessage("limitPrice",
                () -> service.submit(request("API-006", "INST-001", "SYNA", "BUY", 100L, "0.00"),
                        ACTOR));
        assertValidationMessage("limitPrice",
                () -> service.submit(request("API-006", "INST-001", "SYNA", "BUY", 100L, "-1.00"),
                        ACTOR));

        /* Validation precedes the client order id reservation and every transition, so a refused
           request leaves no order and no audit event behind - which is also why all four attempts
           above could reuse one clientOrderId without any of them conflicting. */
        assertEquals(0, orderStore.count(), "a refused request stores no order");
        assertTrue(auditTimeline.all().isEmpty(), "a refused request records no audit event");
    }

    @Test
    void testSubCentLimitPriceIsRefusedRatherThanRoundedToZero() {
        /* A sub-cent price is positive, so a sign check alone admits it - and the value objects
           hold money in cents, so 0.001 would have been stored as 0.00. That order carries a
           notional of zero, which sits under every configured ceiling, and it fills at a price of
           zero: the whole pre-trade control set is evaluated against an amount nobody submitted.
           The price is therefore refused unless it is a whole number of cents. */
        assertValidationMessage("limitPrice",
                () -> service.submit(request("API-014", "INST-001", "SYNA", "BUY", 100L, "0.001"),
                        ACTOR));
        assertValidationMessage("limitPrice",
                () -> service.submit(request("API-014", "INST-001", "SYNA", "BUY", 100L, "100.005"),
                        ACTOR));
        assertValidationMessage("limitPrice",
                () -> service.submit(request("API-014", "INST-001", "SYNA", "BUY", 100L,
                        "0.0000001"), ACTOR));

        assertEquals(0, orderStore.count(), "a sub-cent price stores no order");
        assertTrue(auditTimeline.all().isEmpty(), "a sub-cent price records no audit event");
        assertEquals(10000L, referenceData.findPosition("INST-001", "SYNA").getQuantity(),
                "a sub-cent price leaves the position untouched");

        //A price already expressed in cents is untouched, including one written with a scale the
        //caller chose rather than the scale the order stores.
        Order executed = service.submit(
                request("API-015", "INST-001", "SYNA", "BUY", 100L, "100.0000"), ACTOR);

        assertEquals(OrderStatus.EXECUTED, executed.getStatus(),
                "a price representable in cents is accepted whatever scale it was written with");
        assertAmount("100.00", executed.getLimitPrice(), "stored limit price");
        assertAmount("10000.00", executed.getNotional(), "derived notional");
        assertAmount("100.00", executed.getExecution().getFillPrice(), "simulated fill price");

        /* The three value objects refuse the same amount rather than rounding it, so the guard does
           not depend on every future caller remembering to come through submit. */
        BigDecimal subCent = new BigDecimal("0.001");
        assertMonetaryGuard("limitPrice", () -> new Order("ORD-000001", "API-014", "INST-001",
                "SYNA", "BUY", 100L, subCent, ACTOR, FIXED_INSTANT, RecordSource.API));
        assertMonetaryGuard("fillPrice",
                () -> new Execution("EXE-000001", subCent, 100L, FIXED_INSTANT));
        assertMonetaryGuard("lastPrice", () -> new Position("INST-001", "SYNA", 100L, subCent));
    }

    @Test
    void testCompactExponentLimitPriceIsRefusedBeforeItCanBeExpanded() {
        /* A BigDecimal carries its exponent as a scale, so each of these prices is a handful of
           characters until something converts or renders it - and the cent conversion inside submit
           is exactly that. At 1E+1000000 it materializes a megabyte of digits; a larger exponent
           exhausts the heap before any pre-trade control gets to refuse the order. The refusal is
           therefore made on the representation, while the value is still narrow. */
        assertBoundedValidationMessage("limitPrice",
                () -> service.submit(request("API-017", "INST-001", "SYNA", "BUY", 100L,
                        "1E+1000000"), ACTOR));
        //The mirror image, and the reason a magnitude check alone is not enough: a scale that large
        //expands inside toPlainString rather than inside setScale, on a value smaller than a cent.
        assertBoundedValidationMessage("limitPrice",
                () -> service.submit(request("API-017", "INST-001", "SYNA", "BUY", 100L,
                        "1E-1000000"), ACTOR));
        //Representable, and still refused: it sits one cent above the absolute ceiling that keeps
        //notional - this price times a share count up to Long.MAX_VALUE - inside the same bounds.
        assertBoundedValidationMessage("limitPrice",
                () -> service.submit(request("API-017", "INST-001", "SYNA", "BUY", 100L,
                        "1000000000000.01"), ACTOR));

        assertEquals(0, orderStore.count(), "an out-of-range price stores no order");
        assertTrue(auditTimeline.all().isEmpty(), "an out-of-range price records no audit event");
        assertEquals(10000L, referenceData.findPosition("INST-001", "SYNA").getQuantity(),
                "an out-of-range price leaves the position untouched");

        /* The ceiling bounds the representation and judges nothing: a price sitting exactly on it
           is admitted, and the configured MAX_ORDER_NOTIONAL is what refuses the order - as a
           recorded control result carrying the breach, which is what a control rejection owes the
           audit trail and a 400 would have thrown away. */
        Order judged = service.submit(
                request("API-018", "INST-001", "SYNA", "BUY", 1L, "1000000000000.00"), ACTOR);

        assertEquals(OrderStatus.REJECTED, judged.getStatus(),
                "a price at the ceiling is judged by the configured controls, not by validation");
        assertFalse(control(judged, CONTROL_MAX_ORDER_NOTIONAL).isPassed(),
                "a notional of 1000000000000.00 must breach MAX_ORDER_NOTIONAL");
        assertAmount("1000000000000.00", judged.getNotional(),
                "the notional derived from a price at the ceiling");

        //The value object holds the same bound, so an amount no submitted order could carry cannot
        //be stored by a caller that bypassed submit either.
        assertMonetaryGuard("limitPrice", () -> new Order("ORD-000002", "API-019", "INST-001",
                "SYNA", "BUY", 100L, new BigDecimal("1E+1000000"), ACTOR, FIXED_INSTANT,
                RecordSource.API));
    }

    @Test
    void testResultingPositionBeyondTheLongShareRangeIsRefused() {
        /* Reachable only where an operator has configured ceilings high enough to permit a position
           no long can hold. Arithmetic past the long range wraps: the stored quantity would flip
           sign and shrink, so the holding would read as a different position than it is. The request
           is refused inside the store's compute step, so the holding is left exactly as it was. */
        ControlLimits permissive = new ControlLimits(new BigDecimal("1E+30"),
                new BigDecimal("1E+30"), new BigDecimal("1E+30"), RESTRICTED_SYMBOLS,
                EXCEPTION_SLA_HOURS);
        OrderLifecycleService unbounded = new OrderLifecycleService(orderStore, referenceData,
                new PreTradeControlService(permissive),
                new PostTradeService(orderStore, new SettlementExceptionStore(), referenceData,
                        auditTimeline, FIXED_CLOCK, permissive),
                auditTimeline, FIXED_CLOCK);

        referenceData.putPosition(new Position("INST-001", "SYNC", Long.MAX_VALUE,
                new BigDecimal("0.01")));

        ValidationException refused = assertThrows(ValidationException.class,
                () -> unbounded.submit(request("API-016", "INST-001", "SYNC", "BUY", 5L, "0.01"),
                        ACTOR),
                "a resulting share count beyond the long range must be refused");
        assertTrue(refused.getMessage().contains("SYNC"),
                "the message must name the position it could not move: " + refused.getMessage());

        assertEquals(Long.MAX_VALUE, referenceData.findPosition("INST-001", "SYNC").getQuantity(),
                "the refused fill leaves the holding exactly as it was");
        assertEquals(OrderStatus.SUBMITTED, orderStore.list().get(0).getStatus(),
                "the order stops at SUBMITTED: no fill, no acceptance and no execution happened");

        /* Math.abs(Long.MIN_VALUE) is itself negative, so deriving a notional through it yields a
           negative figure that sits below every positive ceiling however large the exposure. Exact
           magnitude arithmetic makes a short report the same notional as the equivalent long. */
        Position extremeShort = new Position("INST-001", "SYND", Long.MIN_VALUE,
                new BigDecimal("0.01"));

        assertAmount("92233720368547758.08", extremeShort.getPositionNotional(),
                "a position at Long.MIN_VALUE must report its magnitude, not a negative notional");
        assertTrue(extremeShort.getPositionNotional().signum() > 0,
                "a notional is a magnitude and can never be negative");
    }

    @Test
    void testMissingRequiredFieldIsRefused() {
        assertValidationMessage("clientOrderId", () -> service.submit(null, ACTOR));
        assertValidationMessage("clientOrderId",
                () -> service.submit(request(null, "INST-001", "SYNA", "BUY", 100L, "100.00"),
                        ACTOR));
        assertValidationMessage("clientOrderId",
                () -> service.submit(request("   ", "INST-001", "SYNA", "BUY", 100L, "100.00"),
                        ACTOR));
        assertValidationMessage("clientId",
                () -> service.submit(request("API-007", null, "SYNA", "BUY", 100L, "100.00"), ACTOR));
        assertValidationMessage("symbol",
                () -> service.submit(request("API-007", "INST-001", "  ", "BUY", 100L, "100.00"),
                        ACTOR));
        //The side is checked by value rather than by presence alone, so an absent side and an
        //unrecognised one are refused by the same rule and carry the same message.
        assertValidationMessage("side",
                () -> service.submit(request("API-007", "INST-001", "SYNA", null, 100L, "100.00"),
                        ACTOR));
        assertValidationMessage("side",
                () -> service.submit(request("API-007", "INST-001", "SYNA", "HOLD", 100L, "100.00"),
                        ACTOR));
        //A boxed quantity is what keeps an absent value distinguishable from zero, so the two owe
        //the caller different messages.
        assertValidationMessage("quantity",
                () -> service.submit(request("API-007", "INST-001", "SYNA", "BUY", null, "100.00"),
                        ACTOR));
        assertValidationMessage("limitPrice",
                () -> service.submit(request("API-007", "INST-001", "SYNA", "BUY", 100L, null),
                        ACTOR));

        assertEquals(0, orderStore.count(), "no incomplete request reaches the store");
    }

    @Test
    void testUnknownClientIdIsRefusedAsABadField() {
        /* An unknown client is a bad field in a submitted body, not a resource the caller addressed
           by path, so it answers 400 through ValidationException; EntityNotFoundException stays
           reserved for an identifier asked for by path and would answer 404. */
        ValidationException failure = assertThrows(ValidationException.class,
                () -> service.submit(request("API-008", "INST-999", "SYNA", "BUY", 100L, "100.00"),
                        ACTOR),
                "an order for an unknown client must be refused");

        assertTrue(failure.getMessage().contains("INST-999"),
                "the message must name the unknown clientId: " + failure.getMessage());
        assertEquals(0, orderStore.count(), "an order for an unknown client is never stored");
    }

    @Test
    void testCumulativePositionNotionalRejectsTheSecondOrder() {
        Order first = service.submit(
                request("API-009A", "INST-002", "SYND", "BUY", 3000L, "100.00"), ACTOR);
        Order second = service.submit(
                request("API-009B", "INST-002", "SYND", "BUY", 3000L, "100.00"), ACTOR);

        assertEquals(OrderStatus.EXECUTED, first.getStatus(),
                "48000 x 100.00 = 4,800,000 is inside the 5,000,000 resulting-position ceiling");
        Position afterFirst = referenceData.findPosition("INST-002", "SYND");
        assertEquals(48000L, afterFirst.getQuantity(), "the first fill grows the seeded 45000");
        assertAmount("4800000.00", afterFirst.getPositionNotional(),
                "the stored notional equals the value the control evaluated");

        /* This is the case that proves evaluation sees the prior fill rather than a stale copy:
           PreTradeControlService.evaluate runs only inside the operator handed to
           ReferenceDataStore.evaluateAndFill, under one ConcurrentHashMap.compute for this client
           and symbol, so the second order is valued against 48000 and not against the seeded
           45000. */
        assertEquals(OrderStatus.REJECTED, second.getStatus(),
                "51000 x 100.00 = 5,100,000 crosses that ceiling");
        assertFalse(control(second, CONTROL_MAX_POSITION_NOTIONAL).isPassed(),
                "the resulting position control is the one that fails");
        assertTrue(second.getRejectionReason().contains("exceeds MAX_POSITION_NOTIONAL 5000000.00"),
                "rejectionReason: " + second.getRejectionReason());
        assertEquals(48000L, referenceData.findPosition("INST-002", "SYND").getQuantity(),
                "the rejected order leaves the position as the first one left it");

        List<Order> listed = service.list();
        assertEquals(2, listed.size(), "both orders are listed, executed and rejected alike");
        assertEquals(first.getOrderId(), listed.get(0).getOrderId(), "list is ordered by order id");
        assertEquals(second.getOrderId(), listed.get(1).getOrderId(), "list is ordered by order id");
    }

    @Test
    void testResultingPositionExactlyAtTheCeilingFillsAndStoresThatValue() {
        /* The boundary the threshold rule turns on, taken all the way through to persistence: the
           seeded 45000 plus a filled 5000 is 50000 marked at 100.00, which is 5,000,000.00 - the
           configured ceiling to the cent. Equality passes rather than rejects, so this order fills,
           and the fill is what proves the two figures cannot drift apart: the control values the
           whole resulting quantity at the order's own limitPrice, which is exactly the price the
           stored position then carries, so one number is evaluated and the same number is kept. */
        Order order = service.submit(
                request("API-014", "INST-002", "SYND", "BUY", 5000L, "100.00"), ACTOR);

        assertEquals(OrderStatus.EXECUTED, order.getStatus(),
                "a resulting position sitting exactly on MAX_POSITION_NOTIONAL is inside it");
        ControlResult resultingPosition = control(order, CONTROL_MAX_POSITION_NOTIONAL);
        assertTrue(resultingPosition.isPassed(),
                "5000000.00 is not strictly greater than the limit: " + resultingPosition.getReason());
        assertEquals("5000000.00", resultingPosition.getObservedValue(),
                "the control marks the whole resulting 50000 at the order's 100.00");
        assertEquals("5000000.00", resultingPosition.getConfiguredLimit(),
                "the limit the control read is the configured ceiling");

        Position filled = referenceData.findPosition("INST-002", "SYND");
        assertEquals(50000L, filled.getQuantity(), "the seeded 45000 grows by the filled 5000");
        assertAmount("100.00", filled.getLastPrice(), "the fill price becomes the position's mark");
        assertAmount("5000000.00", filled.getPositionNotional(),
                "the stored notional is the figure the control passed at, to the cent");
    }

    @Test
    void testSymbolAndSideAreCanonicalizedBeforeEveryLookup() {
        Order order = service.submit(
                request("API-010", "INST-001", "  syna  ", "buy", 100L, "100.00"), ACTOR);

        assertEquals("SYNA", order.getSymbol(), "the stored symbol is trimmed and upper-cased");
        assertEquals("BUY", order.getSide(), "the stored side is canonicalized the same way");
        assertEquals(OrderStatus.EXECUTED, order.getStatus(),
                "a canonical symbol resolves its own unrestricted, in-limit position");
        assertEquals(SEEDED_POSITIONS, referenceData.positionCount(),
                "canonicalization reaches the existing position instead of creating a second one");
        assertEquals(10100L, referenceData.findPosition("INST-001", "SYNA").getQuantity(),
                "the fill lands on the pre-existing INST-001 SYNA holding");
    }

    @Test
    void testSellReducesThePositionAndAFirstOrderCreatesOne() {
        Order sold = service.submit(
                request("API-012", "INST-001", "SYNA", "SELL", 400L, "100.00"), ACTOR);

        assertEquals(OrderStatus.EXECUTED, sold.getStatus(), "a sell inside every limit executes");
        assertEquals("SELL", sold.getSide(), "stored side");
        Position reduced = referenceData.findPosition("INST-001", "SYNA");
        assertEquals(9600L, reduced.getQuantity(), "a sell subtracts its quantity from the holding");
        assertAmount("960000.00", reduced.getPositionNotional(),
                "the reduced quantity is marked at the fill price");

        //A client's first order in a symbol is valued at a quantity of zero rather than refused for
        //having no position, and it is the fill that brings the position into existence.
        Order opened = service.submit(
                request("API-013", "INST-002", "SYNE", "BUY", 200L, "25.00"), ACTOR);

        assertEquals(OrderStatus.EXECUTED, opened.getStatus(), "a first order in a symbol executes");
        Position created = referenceData.findPosition("INST-002", "SYNE");
        assertNotNull(created, "the fill creates the position that did not exist before it");
        assertEquals(200L, created.getQuantity(), "the new position holds the filled quantity");
        assertEquals(SEEDED_POSITIONS + 1, referenceData.positionCount(),
                "exactly one position is added");
    }

    @Test
    void testSeedDataLoaderProducesTheFixedSyntheticSet() {
        //A graph of its own, so the seeded identifiers are the first the stores hand out and the
        //loader's own reference data is the only reference data in play.
        OrderStore seededOrders = new OrderStore();
        SettlementExceptionStore seededExceptions = new SettlementExceptionStore();
        ReferenceDataStore seededReference = new ReferenceDataStore();
        AuditTimeline seededTimeline = new AuditTimeline();
        OrderLifecycleService seededLifecycle =
                lifecycleService(seededOrders, seededExceptions, seededReference, seededTimeline);

        SeedDataLoader loader = new SeedDataLoader(seededReference, seededLifecycle);
        loader.load();

        assertTrue(loader.isLoaded(), "the loader reports completion for the readiness probe");
        assertEquals(3, seededReference.clientCount(), "three synthetic institutional clients");
        assertEquals(SEEDED_POSITIONS, seededReference.positionCount(), "five synthetic positions");

        Order northwind = seededOrders.findByClientOrderId("SEED-001");
        assertNotNull(northwind, "SEED-001 must be stored");
        assertEquals("ORD-000001", northwind.getOrderId(), "the seed submits SEED-001 first");
        assertEquals(OrderStatus.EXECUTED, northwind.getStatus(), "SEED-001 passes every control");
        assertEquals(PostTradeStatus.SETTLEMENT_READY, northwind.getPostTradeStatus(),
                "INST-001's settlement instructions agree");
        assertEquals(RecordSource.SEED, northwind.getSource(),
                "the three-argument overload marks a seeded order SEED");

        Order restricted = seededOrders.findByClientOrderId("SEED-002");
        assertNotNull(restricted, "SEED-002 must be stored");
        assertEquals("ORD-000002", restricted.getOrderId(), "the seed submits SEED-002 second");
        assertEquals(OrderStatus.REJECTED, restricted.getStatus(),
                "SEED-002 is an order in the restricted symbol RSTRA");
        assertNull(restricted.getPostTradeStatus(), "a rejected seed order has no post-trade state");
        assertEquals(RecordSource.SEED, restricted.getSource(), "seeded source");

        Order mismatching = seededOrders.findByClientOrderId("SEED-003");
        assertNotNull(mismatching, "SEED-003 must be stored");
        assertEquals("ORD-000003", mismatching.getOrderId(), "the seed submits SEED-003 third");
        assertEquals(OrderStatus.EXECUTED, mismatching.getStatus(), "SEED-003 passes every control");
        assertEquals(PostTradeStatus.EXCEPTION, mismatching.getPostTradeStatus(),
                "INST-003's counterparty safekeeping account differs from the firm's");
        assertEquals(RecordSource.SEED, mismatching.getSource(), "seeded source");

        SettlementException opened = seededExceptions.find("EXC-000001");
        assertNotNull(opened, "the mismatching execution opens the one seeded exception");
        assertEquals(ExceptionStatus.OPEN, opened.getStatus(), "the seeded exception is left open");
        assertEquals(RecordSource.SEED, opened.getSource(), "the exception inherits its order's source");
        assertEquals(mismatching.getOrderId(), opened.getOrderId(), "it references SEED-003's order");

        assertEquals(10100L, seededReference.findPosition("INST-001", "SYNA").getQuantity(),
                "SEED-001 fills 100 shares onto the seeded 10000");
        assertEquals(2500L, seededReference.findPosition("INST-003", "SYNA").getQuantity(),
                "SEED-003 fills 500 shares onto the seeded 2000");
        assertEquals(5000L, seededReference.findPosition("INST-001", "SYNB").getQuantity(),
                "no seed order touches INST-001 SYNB");
        assertEquals(20000L, seededReference.findPosition("INST-002", "SYNC").getQuantity(),
                "no seed order touches INST-002 SYNC");
        assertEquals(45000L, seededReference.findPosition("INST-002", "SYND").getQuantity(),
                "the rejected SEED-002 leaves INST-002 untouched");

        assertEquals(13, seededTimeline.all().size(),
                "five events for SEED-001, two for the rejected SEED-002, five for SEED-003 "
                        + "and one opening EXC-000001");
    }

    @Test
    void testConcurrentDuplicateSubmissionsLeaveExactlyOneOrder() throws InterruptedException {
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        /* Two gates rather than one, because `execute` only queues the task: a release latch opened
           as soon as the eight are submitted would let a worker that the pool had not yet scheduled
           arrive at an already-open gate and submit on its own, which the reservation refuses for
           the ordinary sequential reason and proves nothing about simultaneity. Each worker
           therefore reports its arrival on `ready` and blocks on `release`, and the gate opens only
           once all eight have arrived - so the eight submissions genuinely race for the one
           clientOrderId instead of possibly running one after another. */
        CountDownLatch ready = new CountDownLatch(DUPLICATE_SUBMITTERS);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(DUPLICATE_SUBMITTERS);
        ExecutorService submitters = Executors.newFixedThreadPool(DUPLICATE_SUBMITTERS);

        try {
            for (int submitter = 0; submitter < DUPLICATE_SUBMITTERS; submitter++) {
                submitters.execute(() -> {
                    try {
                        ready.countDown();
                        release.await();
                        service.submit(
                                request("API-011", "INST-001", "SYNA", "BUY", 100L, "100.00"),
                                ACTOR);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        failures.add(interrupted);
                    } catch (RuntimeException refused) {
                        failures.add(refused);
                    } finally {
                        finished.countDown();
                    }
                });
            }

            //Bounded like every other wait here: a pool that never scheduled all eight is a failed
            //test rather than a hung one, and the gate is never opened on a partial field.
            assertTrue(ready.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "every submitter must reach the gate before it opens");
            release.countDown();
            assertTrue(finished.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "every submitter must finish inside the timeout");
        } finally {
            submitters.shutdown();
        }

        assertTrue(submitters.awaitTermination(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "the executor must terminate rather than leave a thread running");

        /* The count is exact rather than approximate because the client order id is claimed with a
           single putIfAbsent before any order object exists: exactly one submitter is told to
           proceed whatever the scheduling order, and the other seven are refused before they can
           create an order, append an event or move the position. */
        assertEquals(DUPLICATE_SUBMITTERS - 1, failures.size(),
                "seven of eight submitters must be refused, saw " + failures);
        for (Throwable failure : failures) {
            assertTrue(failure instanceof StateConflictException,
                    "a losing submitter must be refused with a conflict, not " + failure);
        }

        assertEquals(1, orderStore.count(), "exactly one order survives");
        Order survivor = service.list().get(0);
        assertEquals(OrderStatus.EXECUTED, survivor.getStatus(), "the surviving order executed");
        assertEquals(10100L, referenceData.findPosition("INST-001", "SYNA").getQuantity(),
                "the position is filled exactly once");
    }

    @Test
    void testInsertRefusesAnOrderIdThatIsAlreadyStored() {
        Order executed = service.submit(
                request("API-014", "INST-001", "SYNA", "BUY", 100L, "100.00"), ACTOR);
        int eventsBefore = auditTimeline.all().size();

        /* insert is the creation write and nothing more. A second write under a live order id
           would replace an audited order without passing assertLegal or appending an event, which
           is exactly the gap that would make the audit trail a convention rather than a property
           of the store - so the store refuses it and the stored order stands. */
        Order replacement = new Order(executed.getOrderId(), "API-015", "INST-001", "SYNA", "SELL",
                1L, new BigDecimal("1.00"), ACTOR, FIXED_INSTANT, RecordSource.API);
        assertThrows(IllegalStateException.class, () -> orderStore.insert(replacement),
                "a duplicate order id must be refused rather than overwrite the stored order");

        Order stored = service.get(executed.getOrderId());
        assertEquals(OrderStatus.EXECUTED, stored.getStatus(), "the stored order keeps its status");
        assertEquals(100L, stored.getQuantity(), "the stored order keeps its quantity");
        assertEquals("BUY", stored.getSide(), "the stored order keeps its side");
        assertEquals(1, orderStore.count(), "the refused write adds no order");
        assertEquals(eventsBefore, auditTimeline.all().size(),
                "a refused write appends no audit event");
    }

    @Test
    void testUnicodeWhitespaceOnlyFieldIsRefusedAndStoresNothing() {
        /* U+2003 (EM SPACE) is whitespace that trim() does not remove - trim stops at U+0020 - so
           a trim-based check passes a value made only of it as present, and an order would be
           stored under a client order id that reads as blank and can never be typed back. EM SPACE
           is deliberately the character used here: U+00A0 is not whitespace by Java's definition,
           so isBlank and strip leave it alone on purpose. */
        assertValidationMessage("clientOrderId",
                () -> service.submit(request(EM_SPACE, "INST-001", "SYNA", "BUY", 100L, "100.00"),
                        ACTOR));
        assertValidationMessage("symbol",
                () -> service.submit(request("API-017", "INST-001", EM_SPACE, "BUY", 100L,
                        "100.00"), ACTOR));
        assertValidationMessage("clientId",
                () -> service.submit(request("API-017", EM_SPACE, "SYNA", "BUY", 100L, "100.00"),
                        ACTOR));

        assertEquals(0, orderStore.count(), "a blank-by-Unicode field stores no order");
        assertTrue(auditTimeline.all().isEmpty(),
                "a blank-by-Unicode field records no audit event");
        assertEquals(SEEDED_POSITIONS, referenceData.positionCount(),
                "a refused submission creates no position");
    }

    @Test
    void testUnicodePaddedFieldsAreStoredStripped() {
        Order order = service.submit(request(EM_SPACE + "API-020" + EM_SPACE, "INST-001",
                EM_SPACE + "syna" + EM_SPACE, "BUY", 100L, "100.00"), ACTOR);

        assertEquals("API-020", order.getClientOrderId(),
                "the stored clientOrderId is stripped of Unicode padding");
        assertEquals("SYNA", order.getSymbol(), "the stored symbol is stripped and upper-cased");
        assertEquals(OrderStatus.EXECUTED, order.getStatus(),
                "a padded symbol still resolves its own unrestricted, in-limit position");

        //Stripping in the validation helper is what makes the idempotency key canonical: the
        //padded and unpadded forms have to claim the same key, or one client order id could be
        //submitted twice by varying invisible characters.
        assertNotNull(orderStore.findByClientOrderId("API-020"),
                "the client order id is reserved in its stripped form");
        assertEquals(SEEDED_POSITIONS, referenceData.positionCount(),
                "stripping reaches the seeded SYNA position instead of creating a second one");
        assertEquals(10100L, referenceData.findPosition("INST-001", "SYNA").getQuantity(),
                "the fill lands on the pre-existing INST-001 SYNA holding");
    }

    @Test
    void testOverLengthOrIllegallyWrittenFieldIsRefused() {
        /* Unbounded strings on a stored, never-expiring entity are the cheapest way to exhaust the
           heap of an in-memory service: one request per key, each carrying megabytes the service
           then keeps. The limits are semantic - what an identifier and a ticker are - so they are
           asserted here rather than left to a control threshold. */
        assertValidationMessage("clientOrderId",
                () -> service.submit(request("A".repeat(65), "INST-001", "SYNA", "BUY", 100L,
                        "100.00"), ACTOR));
        assertValidationMessage("clientId",
                () -> service.submit(request("API-018", "I".repeat(65), "SYNA", "BUY", 100L,
                        "100.00"), ACTOR));
        assertValidationMessage("symbol",
                () -> service.submit(request("API-018", "INST-001", "S".repeat(13), "BUY", 100L,
                        "100.00"), ACTOR));
        //A symbol is a ticker, so anything outside its alphabet - here a character that could
        //carry markup or a line break into a stored order and an audit reason - is refused too.
        assertValidationMessage("symbol",
                () -> service.submit(request("API-018", "INST-001", "SYN$A", "BUY", 100L,
                        "100.00"), ACTOR));

        assertEquals(0, orderStore.count(), "an over-length or ill-formed field stores no order");
        assertTrue(auditTimeline.all().isEmpty(),
                "an over-length or ill-formed field records no audit event");

        //The boundary passes rather than rejects, as everywhere else in this module: a value
        //exactly at the limit is inside it.
        Order atTheLimit = service.submit(request("A".repeat(64), "INST-001", "SYNA", "BUY", 100L,
                "100.00"), ACTOR);
        assertEquals(OrderStatus.EXECUTED, atTheLimit.getStatus(),
                "a clientOrderId of exactly 64 characters is inside the limit");
    }

    @Test
    void testOrderAdmissionCeilingRefusesFurtherSubmissions() {
        /* Claimed directly rather than by submitting ten thousand orders: admission is an exact
           atomic claim taken before the order object exists, so exhausting it through the store is
           the same state the ceiling would reach through the API and costs no allocation. */
        int claimed = 0;
        for (int slot = 0; slot < OrderStore.MAX_ORDERS; slot++) {
            if (orderStore.tryAdmitOrder()) {
                claimed++;
            }
        }

        assertEquals(OrderStore.MAX_ORDERS, claimed, "every slot up to the ceiling is claimable");
        assertFalse(orderStore.tryAdmitOrder(), "the ceiling refuses the next claim");

        CapacityExceededException refused = assertThrows(CapacityExceededException.class,
                () -> service.submit(request("API-021", "INST-001", "SYNA", "BUY", 100L, "100.00"),
                        ACTOR),
                "a submission beyond the order ceiling must be refused");
        assertTrue(refused.getMessage().contains("capacity"),
                "the refusal must say what ran out: " + refused.getMessage());

        /* The refusal is taken before the client order id is reserved and before any transition,
           so it leaves no trace at all: a 503 that had already stored an order or appended an
           event would be indistinguishable from a submission that half happened. */
        assertEquals(0, orderStore.count(), "a refused submission stores no order");
        assertTrue(auditTimeline.all().isEmpty(), "a refused submission records no audit event");
        assertEquals(10000L, referenceData.findPosition("INST-001", "SYNA").getQuantity(),
                "a refused submission leaves the position exactly as it was");
    }

    @Test
    void testReleasedAdmissionSlotAdmitsExactlyOneFurtherSubmission() {
        for (int slot = 0; slot < OrderStore.MAX_ORDERS; slot++) {
            orderStore.tryAdmitOrder();
        }

        //A duplicate client order id is refused after its slot was claimed, so the claim is handed
        //back; without that, every refused duplicate would retire one slot of the ceiling for the
        //life of the process.
        orderStore.releaseOrderAdmission();

        Order admitted = service.submit(
                request("API-022", "INST-001", "SYNA", "BUY", 100L, "100.00"), ACTOR);
        assertEquals(OrderStatus.EXECUTED, admitted.getStatus(),
                "the released slot admits exactly one further submission");
        assertEquals(1, orderStore.count(), "that submission is stored");

        assertThrows(CapacityExceededException.class,
                () -> service.submit(request("API-023", "INST-001", "SYNA", "BUY", 100L, "100.00"),
                        ACTOR),
                "one release frees one slot and no more");
        assertEquals(1, orderStore.count(), "the second submission stores nothing");
    }

    @Test
    void testDuplicateClientOrderIdIsAConflictEvenAtTheOrderCeiling() {
        Order first = service.submit(
                request("API-027", "INST-001", "SYNA", "BUY", 100L, "100.00"), ACTOR);
        assertEquals(OrderStatus.EXECUTED, first.getStatus(), "the first submission executes");

        //Claimed directly rather than by submitting ten thousand orders, as in the ceiling test
        //above: the claim is the same state the ceiling reaches through the API.
        for (int slot = orderStore.count(); slot < OrderStore.MAX_ORDERS; slot++) {
            orderStore.tryAdmitOrder();
        }
        assertFalse(orderStore.tryAdmitOrder(), "the order ceiling is exhausted");

        int ordersBefore = orderStore.count();
        int eventsBefore = auditTimeline.count();

        /* A saturated service must still answer the question the caller asked. A repeat
           clientOrderId can never be accepted however much headroom returns, so answering it 503
           would invite a retry that is certain to fail again and would hide the one fact the
           client needs - that this key is already theirs. */
        StateConflictException refused = assertThrows(StateConflictException.class,
                () -> service.submit(request("API-027", "INST-001", "SYNA", "BUY", 100L, "100.00"),
                        ACTOR),
                "a repeat clientOrderId must be refused as a conflict, not as exhausted capacity");
        assertTrue(refused.getMessage().contains("API-027"),
                "the conflict must name the key: " + refused.getMessage());

        assertEquals(ordersBefore, orderStore.count(), "the refused submission stores no order");
        assertEquals(eventsBefore, auditTimeline.count(),
                "the refused submission records no audit event");
        assertEquals(10100L, referenceData.findPosition("INST-001", "SYNA").getQuantity(),
                "the refused submission leaves the position as the first order left it");
    }

    @Test
    void testConcurrentFirstFillsClaimThePositionCeilingExactly() throws InterruptedException {
        /* Filled through putPosition rather than by submitting: the claim under test is the same
           one whichever path creates the key, and the point of the test is the last free slot. */
        for (int slot = referenceData.positionCount();
                slot < ReferenceDataStore.MAX_POSITIONS - 1; slot++) {
            referenceData.putPosition(
                    new Position("INST-001", "FIL" + slot, 100L, new BigDecimal("1.00")));
        }
        assertEquals(ReferenceDataStore.MAX_POSITIONS - 1, referenceData.positionCount(),
                "exactly one position slot is left free");

        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        //The same two-gate idiom as the duplicate-submission test above: a release latch opened as
        //soon as the eight are queued would let an unscheduled worker arrive at an open gate and
        //run on its own, proving nothing about simultaneity.
        CountDownLatch ready = new CountDownLatch(DUPLICATE_SUBMITTERS);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(DUPLICATE_SUBMITTERS);
        ExecutorService submitters = Executors.newFixedThreadPool(DUPLICATE_SUBMITTERS);

        try {
            for (int submitter = 0; submitter < DUPLICATE_SUBMITTERS; submitter++) {
                //Eight distinct symbols of one client, so every submission is a first fill and
                //each one needs a position key of its own: the eight genuinely contend for one.
                String symbol = "NEW" + submitter;
                submitters.execute(() -> {
                    try {
                        ready.countDown();
                        release.await();
                        service.submit(request("API-" + symbol, "INST-001", symbol, "BUY", 100L,
                                "100.00"), ACTOR);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        failures.add(interrupted);
                    } catch (RuntimeException refused) {
                        failures.add(refused);
                    } finally {
                        finished.countDown();
                    }
                });
            }

            assertTrue(ready.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "every submitter must reach the gate before it opens");
            release.countDown();
            assertTrue(finished.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "every submitter must finish inside the timeout");
        } finally {
            submitters.shutdown();
        }

        assertTrue(submitters.awaitTermination(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "the executor must terminate rather than leave a thread running");

        /* Exact, not approximate: the slot is taken with a compare-and-set claim inside the same
           compute that would create the key, so one submitter is granted it whatever the
           scheduling order and the other seven are refused - whether they were refused by the
           pre-mutation gate or by the claim itself depends only on how they interleaved. */
        assertEquals(DUPLICATE_SUBMITTERS - 1, failures.size(),
                "seven of eight first fills must be refused, saw " + failures);
        for (Throwable failure : failures) {
            assertTrue(failure instanceof CapacityExceededException,
                    "a losing first fill must be refused at the ceiling, not " + failure);
        }

        assertEquals(ReferenceDataStore.MAX_POSITIONS, referenceData.positionCount(),
                "the position ceiling is reached exactly and never passed");
        assertEquals(1, orderStore.list().stream()
                        .filter(order -> order.getStatus() == OrderStatus.EXECUTED).count(),
                "exactly one of the eight submissions filled and executed");
        for (Order order : orderStore.list()) {
            //A submission refused inside the fill step stops at SUBMITTED, exactly where a fill
            //refused by the resulting-share-range check stops: no fill, no acceptance, no
            //execution, and the position it could not open was never created.
            assertTrue(order.getStatus() == OrderStatus.EXECUTED
                            || order.getStatus() == OrderStatus.SUBMITTED,
                    "a refused first fill must rest at SUBMITTED, not at " + order.getStatus());
        }
    }

    @Test
    void testListPagesTheOrderEstate() {
        service.submit(request("API-024", "INST-001", "SYNA", "BUY", 10L, "100.00"), ACTOR);
        service.submit(request("API-025", "INST-001", "SYNA", "BUY", 10L, "100.00"), ACTOR);
        service.submit(request("API-026", "INST-001", "SYNA", "BUY", 10L, "100.00"), ACTOR);

        List<Order> secondOnly = service.list(1, 1);
        assertEquals(1, secondOnly.size(), "a page of one holds one order");
        assertEquals("ORD-000002", secondOnly.get(0).getOrderId(),
                "the page starts at the requested offset of the store's own ordering");

        /* A non-positive limit means "do not cut the page" rather than "return nothing": the REST
           layer clamps a caller's limit to the maximum page size, and an in-process caller must
           not have to restate it to read a small collection. */
        assertEquals(3, service.list(0, 0).size(), "a zero limit yields the whole small set");
        assertEquals(3, service.list(-5, -5).size(),
                "a negative offset and limit are taken as the first, uncut page");
        assertTrue(service.list(3, 2).isEmpty(), "an offset past the end is an empty page");
        assertEquals(3, service.list().size(), "the unpaged read still answers with everything");
    }

    private static OrderLifecycleService lifecycleService(OrderStore orders,
            SettlementExceptionStore exceptions, ReferenceDataStore reference, AuditTimeline timeline) {
        ControlLimits limits = new ControlLimits(MAX_ORDER_NOTIONAL, MAX_POSITION_NOTIONAL,
                FAT_FINGER_THRESHOLD, RESTRICTED_SYMBOLS, EXCEPTION_SLA_HOURS);
        PostTradeService postTrade = new PostTradeService(orders, exceptions, reference, timeline,
                FIXED_CLOCK, limits);

        return new OrderLifecycleService(orders, reference, new PreTradeControlService(limits),
                postTrade, timeline, FIXED_CLOCK);
    }

    /* The same synthetic reference data SeedDataLoader writes at startup, including INST-003's
       deliberately unequal counterparty safekeeping account - the module's only SSI mismatch, and
       therefore the only client whose executions open a settlement exception. */
    private static void seedReferenceData(ReferenceDataStore store) {
        SettlementInstruction northwind =
                new SettlementInstruction("SYNTGB2LXXX", "SAFE-NW-0001", "CASH-NW-0001", "XLON");
        store.putClient(
                new ClientAccount("INST-001", "Northwind Asset Management", northwind, northwind));

        SettlementInstruction contoso =
                new SettlementInstruction("SYNTUS33XXX", "SAFE-CP-0002", "CASH-CP-0002", "XNYS");
        store.putClient(new ClientAccount("INST-002", "Contoso Pension Trust", contoso, contoso));

        store.putClient(new ClientAccount("INST-003", "Fabrikam Capital Partners",
                new SettlementInstruction("SYNTDEFFXXX", "SAFE-FB-0003", "CASH-FB-0003", "XETR"),
                new SettlementInstruction("SYNTDEFFXXX", "SAFE-FB-9903", "CASH-FB-0003", "XETR")));

        store.putPosition(new Position("INST-001", "SYNA", 10000L, new BigDecimal("100.00")));
        store.putPosition(new Position("INST-001", "SYNB", 5000L, new BigDecimal("50.00")));
        store.putPosition(new Position("INST-002", "SYNC", 20000L, new BigDecimal("40.00")));
        //4,500,000 of the 5,000,000 resulting-position ceiling, which is what makes a buy of more
        //than 5000 shares at 100.00 cross it and anything up to 5000 stay under it.
        store.putPosition(new Position("INST-002", "SYND", 45000L, new BigDecimal("100.00")));
        store.putPosition(new Position("INST-003", "SYNA", 2000L, new BigDecimal("100.00")));
    }

    private static OrderRequest request(String clientOrderId, String clientId, String symbol,
            String side, Long quantity, String limitPrice) {
        return new OrderRequest(clientOrderId, clientId, symbol, side, quantity,
                (limitPrice == null) ? null : new BigDecimal(limitPrice));
    }

    //Looked up by control name rather than by list index so the assertions survive a reordering of
    //the fixed evaluation order.
    private static ControlResult control(Order order, String control) {
        for (ControlResult result : order.getControlResults()) {
            if (control.equals(result.getControl())) {
                return result;
            }
        }

        return fail("no " + control + " result was recorded on " + order.getOrderId());
    }

    private static void assertEdge(AuditEvent event, StateMachine stateMachine, String fromState,
            String toState) {
        String edge = fromState + " -> " + toState;
        assertEquals(stateMachine, event.getStateMachine(), edge + " state machine");
        assertEquals(fromState, event.getFromState(), edge + " fromState");
        assertEquals(toState, event.getToState(), edge + " toState");
    }

    //The value objects guard their own monetary invariant, so the failure is an illegal argument
    //rather than a rejected request: reaching them with an unroundable amount means submit was
    //bypassed, which is a defect in the caller and not something a client can provoke.
    private static void assertMonetaryGuard(String field, Executable construction) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, construction,
                "a sub-cent " + field + " must be refused rather than rounded away");
        assertTrue(refused.getMessage().contains(field),
                "the message must name " + field + ": " + refused.getMessage());
    }

    private static void assertValidationMessage(String field, Executable submission) {
        ValidationException failure = assertThrows(ValidationException.class, submission,
                "an invalid or absent " + field + " must be refused");
        assertTrue(failure.getMessage().contains(field),
                "the message must name " + field + ": " + failure.getMessage());
    }

    /* Everything assertValidationMessage asserts, and one thing more: that the refusal never
       rendered the amount it refused. A message carrying the expanded value would be the second
       half of the allocation the bound exists to prevent, so the length of the message is part of
       what makes the refusal cheap. */
    private static void assertBoundedValidationMessage(String field, Executable submission) {
        ValidationException failure = assertThrows(ValidationException.class, submission,
                "an out-of-range " + field + " must be refused");
        assertTrue(failure.getMessage().contains(field),
                "the message must name " + field + ": " + failure.getMessage());
        assertTrue(failure.getMessage().length() < MAX_REFUSAL_MESSAGE_LENGTH,
                "the refusal must describe the amount rather than render it, but its message ran to "
                        + failure.getMessage().length() + " characters");
    }

    //compareTo rather than equals because BigDecimal.equals is scale-sensitive, and the assertion
    //is about the amount rather than about the scale the producer happened to write it with.
    private static void assertAmount(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                message + ": expected " + expected + " but was " + actual);
    }
}
