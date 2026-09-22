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
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.dao.OrderStore;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.dao.ReferenceDataStore;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.dao.SettlementExceptionStore;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.AuditEvent;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.ClientAccount;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.ExceptionStatus;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.Execution;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.MismatchField;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.Order;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.OrderStatus;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.PostTradeStatus;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.RecordSource;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.ResolveRequest;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.SettlementException;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.SettlementInstruction;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.StateMachine;

//Arbitrary-precision arithmetic
import java.math.BigDecimal;

//Time (java.time)
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

//Collections
import java.util.List;

//JUnit 5 Jupiter
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Unit tests for the post-trade affirmation flow and the settlement-exception workflow of PostTradeService */
public class PostTradeServiceTest {

    /* Every timestamp the service writes comes from its injected clock, so pinning that clock
       lets openedAt, assignedAt, resolvedAt and every audit timestamp be compared for equality
       rather than against a tolerance window: a value that came from the wall clock fails. */
    private static final Instant T0 = Instant.parse("2026-01-15T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    private static final String ENTITY_ORDER = "ORDER";
    private static final String ENTITY_EXCEPTION = "EXCEPTION";

    private static final String ACTOR = "ops.analyst";
    private static final String OWNER = "settlements.desk";
    private static final String OTHER_OWNER = "custody.desk";
    private static final String NOTE = "Counterparty safekeeping account corrected to the firm instruction";
    private static final String UNKNOWN_EXCEPTION_ID = "EXC-999999";

    /* The clients and their settlement instructions are the literals SeedDataLoader seeds, so a
       change to the seeded SSI contract breaks these tests rather than leaving them asserting
       against strings that no longer exist anywhere in the service. */
    private static final String NORTHWIND_ID = "INST-001";
    private static final String NORTHWIND_NAME = "Northwind Asset Management";
    private static final String NORTHWIND_CUSTODIAN_BIC = "SYNTGB2LXXX";
    private static final String NORTHWIND_SAFEKEEPING_ACCOUNT = "SAFE-NW-0001";
    private static final String NORTHWIND_CASH_ACCOUNT = "CASH-NW-0001";
    private static final String NORTHWIND_PLACE_OF_SETTLEMENT = "XLON";

    private static final String FABRIKAM_ID = "INST-003";
    private static final String FABRIKAM_NAME = "Fabrikam Capital Partners";
    private static final String FABRIKAM_CUSTODIAN_BIC = "SYNTDEFFXXX";
    private static final String FABRIKAM_FIRM_SAFEKEEPING_ACCOUNT = "SAFE-FB-0003";
    private static final String FABRIKAM_COUNTERPARTY_SAFEKEEPING_ACCOUNT = "SAFE-FB-9903";
    private static final String FABRIKAM_CASH_ACCOUNT = "CASH-FB-0003";
    private static final String FABRIKAM_PLACE_OF_SETTLEMENT = "XETR";

    private static final String SYMBOL = "SYNA";
    private static final String BUY = "BUY";
    private static final String FILL_PRICE = "100.00";
    private static final long QUANTITY = 300L;
    private static final int SLA_HOURS = 24;

    private OrderStore orderStore;
    private SettlementExceptionStore exceptionStore;
    private ReferenceDataStore referenceData;
    private AuditTimeline auditTimeline;
    private ControlLimits limits;
    private PostTradeService postTrade;

    @BeforeEach
    void freshGraph() {
        //Identifier sequences and the audit sequence both start at 1 per instance, so the whole
        //graph is rebuilt per test and the ORD-/EXE-/EXC-000001 identifiers stay assertable.
        orderStore = new OrderStore();
        exceptionStore = new SettlementExceptionStore();
        referenceData = new ReferenceDataStore();
        auditTimeline = new AuditTimeline();
        limits = new ControlLimits(new BigDecimal("1000000.00"), new BigDecimal("5000000.00"),
                new BigDecimal("2500000.00"), List.of("RSTRA", "RSTRB"), SLA_HOURS);
        postTrade = new PostTradeService(orderStore, exceptionStore, referenceData, auditTimeline,
                CLOCK, limits);

        seedClients();
    }

    @Test
    void testExecutedOrderOpensSsiMismatchException() {
        Order executed = storedExecutedOrder(FABRIKAM_ID, "C1", QUANTITY);
        assertEquals("ORD-000001", executed.getOrderId(), "first order identifier");
        assertEquals("EXE-000001", executed.getExecution().getExecutionId(),
                "first execution identifier");

        Order afterPostTrade = postTrade.onExecuted(executed, ACTOR);

        assertEquals(PostTradeStatus.EXCEPTION, afterPostTrade.getPostTradeStatus(),
                "returned order post-trade status");
        assertEquals(PostTradeStatus.EXCEPTION,
                orderStore.find(executed.getOrderId()).getPostTradeStatus(),
                "stored order post-trade status");
        assertEquals(1, exceptionStore.count(), "exactly one exception must be opened");

        //Read through the service, not the store: the store answers with the stored object and
        //the SLA snapshot is derived on read.
        SettlementException opened = postTrade.get("EXC-000001");
        assertEquals("EXC-000001", opened.getExceptionId(), "first exception identifier");
        assertEquals(ExceptionStatus.OPEN, opened.getStatus(), "opened status");
        assertEquals("SSI_MISMATCH", opened.getExceptionType(), "exception type");
        assertEquals(executed.getOrderId(), opened.getOrderId(), "parent order identifier");
        assertEquals(executed.getExecution().getExecutionId(), opened.getExecutionId(),
                "parent execution identifier");

        assertEquals(FABRIKAM_ID, opened.getClientId(), "denormalised clientId");
        assertEquals(FABRIKAM_NAME, opened.getClientName(), "denormalised clientName");
        assertEquals(SYMBOL, opened.getSymbol(), "denormalised symbol");
        assertEquals(BUY, opened.getSide(), "denormalised side");
        assertEquals(QUANTITY, opened.getQuantity(), "denormalised quantity");
        assertEquals(0, new BigDecimal("30000.00").compareTo(opened.getNotional()),
                "denormalised notional");
        assertEquals(0, new BigDecimal(FILL_PRICE).compareTo(opened.getFillPrice()),
                "denormalised fill price");
        assertEquals(T0, opened.getExecutedAt(), "denormalised execution instant");
        assertEquals("SIMULATED", opened.getVenue(), "denormalised venue");

        /* Exactly one mismatch is the assertion that matters: custodianBic, cashAccount and
           placeOfSettlement agree on this client, so a comparison that compared whole
           instructions, or stopped at the first field, would not produce this list. */
        ClientAccount client = referenceData.findClient(FABRIKAM_ID);
        String firmSafekeeping = client.getFirmSettlementInstruction().getSafekeepingAccount();
        String counterpartySafekeeping =
                client.getCounterpartySettlementInstruction().getSafekeepingAccount();
        assertNotEquals(firmSafekeeping, counterpartySafekeeping,
                "the seeded instructions must differ for a mismatch to be possible");

        List<MismatchField> mismatches = opened.getMismatchFields();
        assertEquals(1, mismatches.size(), "only safekeepingAccount differs");
        MismatchField mismatch = mismatches.get(0);
        assertEquals("safekeepingAccount", mismatch.getField(), "mismatched field name");
        assertEquals(firmSafekeeping, mismatch.getFirmValue(), "firm value of the mismatch");
        assertEquals(counterpartySafekeeping, mismatch.getCounterpartyValue(),
                "counterparty value of the mismatch");

        assertNull(opened.getOwner(), "a newly opened exception has no owner");
        assertNull(opened.getResolutionNote(), "a newly opened exception has no resolution note");
        assertEquals(T0, opened.getOpenedAt(), "openedAt");
        assertEquals(T0.plus(SLA_HOURS, ChronoUnit.HOURS), opened.getSlaDeadline(), "slaDeadline");
        assertEquals(0L, opened.getAgeHours(), "ageHours at the opening instant");
        assertFalse(opened.isSlaBreached(), "a just-opened exception cannot be breached");

        assertEquals(RecordSource.API, opened.getSource(), "source copied from the parent order");
        assertTrue(opened.isSimulated(), "simulated label");
        assertNotNull(opened.getDisclaimer(), "disclaimer");
        assertFalse(opened.getDisclaimer().isBlank(), "disclaimer must not be blank");

        List<AuditEvent> exceptionEvents = auditTimeline.forEntity(ENTITY_EXCEPTION, "EXC-000001");
        assertEquals(1, exceptionEvents.size(), "the origin edge is the exception's only event");
        AuditEvent origin = exceptionEvents.get(0);
        assertEquals(ENTITY_EXCEPTION, origin.getEntityType(), "origin event entityType");
        assertEquals("EXC-000001", origin.getEntityId(), "origin event entityId");
        assertEquals(StateMachine.EXCEPTION, origin.getStateMachine(), "origin event machine");
        assertEquals(LifecycleTransitions.NONE, origin.getFromState(), "origin event fromState");
        assertEquals(ExceptionStatus.OPEN.name(), origin.getToState(), "origin event toState");
        assertEquals(ACTOR, origin.getActor(), "origin event actor");
        assertTrue(origin.getReason().contains("safekeepingAccount"),
                "the origin reason must name the mismatched field: " + origin.getReason());
        assertEquals(T0, origin.getTimestamp(), "origin event timestamp");
    }

    @Test
    void testAssignResolveAndMarkSettlementReady() {
        Order executed = storedExecutedOrder(FABRIKAM_ID, "C1", QUANTITY);
        postTrade.onExecuted(executed, ACTOR);
        String exceptionId = "EXC-000001";

        assertEquals(1, postTrade.list(null, null).size(), "no filter returns every exception");
        assertEquals(1, postTrade.list(ExceptionStatus.OPEN, null).size(),
                "status filter matches the open exception");
        assertEquals(1, postTrade.list(null, "   ").size(),
                "a blank owner filter is no filter at all");

        SettlementException assigned = postTrade.assign(exceptionId, OWNER, ACTOR);
        assertEquals(ExceptionStatus.ASSIGNED, assigned.getStatus(), "status after assign");
        assertEquals(OWNER, assigned.getOwner(), "owner after assign");
        assertEquals(T0, assigned.getAssignedAt(), "assignedAt");
        assertNull(assigned.getResolvedAt(), "an assigned exception is not resolved");

        //Re-assignment is a legal ASSIGNED -> ASSIGNED edge so the change is recorded rather
        //than refused; the owner filter then has to follow the new owner.
        SettlementException reassigned = postTrade.assign(exceptionId, OTHER_OWNER, ACTOR);
        assertEquals(OTHER_OWNER, reassigned.getOwner(), "owner after re-assignment");
        assertEquals(0, postTrade.list(null, OWNER).size(), "owner filter drops the old owner");
        assertEquals(1, postTrade.list(null, OTHER_OWNER).size(), "owner filter matches the owner");
        assertEquals(0, postTrade.list(ExceptionStatus.OPEN, null).size(),
                "an assigned exception no longer matches the OPEN filter");

        SettlementException listed = postTrade.list(ExceptionStatus.ASSIGNED, OTHER_OWNER).get(0);
        assertEquals(exceptionId, listed.getExceptionId(), "combined filters match");
        assertEquals(0L, listed.getAgeHours(), "every listed element carries the SLA snapshot");
        assertFalse(listed.isSlaBreached(), "every listed element carries the SLA snapshot");
        assertEquals(T0.plus(SLA_HOURS, ChronoUnit.HOURS), listed.getSlaDeadline(),
                "every listed element carries its deadline");

        SettlementException resolved =
                postTrade.resolve(exceptionId, new ResolveRequest(null, NOTE), ACTOR);
        assertEquals(ExceptionStatus.RESOLVED, resolved.getStatus(), "status after resolve");
        assertEquals(NOTE, resolved.getResolutionNote(), "resolution note");
        assertEquals(T0, resolved.getResolvedAt(), "resolvedAt");
        assertEquals(OTHER_OWNER, resolved.getOwner(), "resolve keeps the assigned owner");

        SettlementException ready = postTrade.markSettlementReady(exceptionId, ACTOR);
        assertEquals(ExceptionStatus.SETTLEMENT_READY, ready.getStatus(),
                "status after settlement-ready");
        assertEquals(T0, ready.getSettlementReadyAt(), "settlementReadyAt");
        assertEquals(NOTE, ready.getResolutionNote(), "the resolution note survives");

        //Marking the exception settlement-ready is what releases the trade: the parent order's
        //own post-trade machine has to leave EXCEPTION in the same call.
        assertEquals(PostTradeStatus.SETTLEMENT_READY,
                orderStore.find(executed.getOrderId()).getPostTradeStatus(),
                "parent order post-trade status");
    }

    @Test
    void testEveryTransitionAppendsTimestampedAuditEvent() {
        Order executed = storedExecutedOrder(FABRIKAM_ID, "C1", QUANTITY);
        postTrade.onExecuted(executed, ACTOR);
        String exceptionId = "EXC-000001";
        String orderId = executed.getOrderId();

        int before = auditTimeline.all().size();

        postTrade.assign(exceptionId, OWNER, ACTOR);
        postTrade.resolve(exceptionId, new ResolveRequest(null, NOTE), ACTOR);
        postTrade.markSettlementReady(exceptionId, ACTOR);

        List<AuditEvent> all = auditTimeline.all();
        assertEquals(before + 4, all.size(),
                "assign, resolve and settlement-ready append three exception events and one order event");
        List<AuditEvent> appended = all.subList(before, all.size());

        AuditEvent assignEvent = appended.get(0);
        assertExceptionEvent(assignEvent, exceptionId, ExceptionStatus.OPEN.name(),
                ExceptionStatus.ASSIGNED.name());
        assertTrue(assignEvent.getReason().contains(OWNER),
                "the assign reason must name the owner: " + assignEvent.getReason());

        AuditEvent resolveEvent = appended.get(1);
        assertExceptionEvent(resolveEvent, exceptionId, ExceptionStatus.ASSIGNED.name(),
                ExceptionStatus.RESOLVED.name());
        assertEquals(NOTE, resolveEvent.getReason(), "the resolve reason carries the note");

        assertExceptionEvent(appended.get(2), exceptionId, ExceptionStatus.RESOLVED.name(),
                ExceptionStatus.SETTLEMENT_READY.name());

        /* The fourth event belongs to the order's POST_TRADE machine, not to the exception:
           entityType tells a reader which entity to ask for, stateMachine which of that
           entity's two machines moved, and PostTradeStatus.SETTLEMENT_READY here is a
           different state from the ExceptionStatus.SETTLEMENT_READY above. */
        AuditEvent orderEvent = appended.get(3);
        assertEquals(ENTITY_ORDER, orderEvent.getEntityType(), "order event entityType");
        assertEquals(orderId, orderEvent.getEntityId(), "order event entityId");
        assertEquals(StateMachine.POST_TRADE, orderEvent.getStateMachine(), "order event machine");
        assertEquals(PostTradeStatus.EXCEPTION.name(), orderEvent.getFromState(),
                "order event fromState");
        assertEquals(PostTradeStatus.SETTLEMENT_READY.name(), orderEvent.getToState(),
                "order event toState");

        long previousSequence = 0L;
        for (AuditEvent event : appended) {
            assertNotNull(event.getTimestamp(), "timestamp of " + event.getEventId());
            assertEquals(T0, event.getTimestamp(),
                    "every event takes the performing clock's instant: " + event.getEventId());
            assertTrue(event.getSequence() > previousSequence,
                    "sequence must strictly increase at " + event.getEventId());
            previousSequence = event.getSequence();
            assertEquals(ACTOR, event.getActor(), "actor of " + event.getEventId());
            assertTrue(event.isSimulated(), "simulated label of " + event.getEventId());
            assertNotNull(event.getDisclaimer(), "disclaimer of " + event.getEventId());
            assertFalse(event.getDisclaimer().isBlank(),
                    "disclaimer of " + event.getEventId() + " must not be blank");
        }
    }

    @Test
    void testDuplicateResolutionRequestIsRefused() {
        String exceptionId = openException();
        postTrade.assign(exceptionId, OWNER, ACTOR);
        postTrade.resolve(exceptionId, new ResolveRequest(null, NOTE), ACTOR);

        int before = auditTimeline.all().size();
        StateConflictException thrown = assertThrows(StateConflictException.class,
                () -> postTrade.resolve(exceptionId, new ResolveRequest(null, "again"), ACTOR),
                "resolving a resolved exception must be refused");

        //RESOLVED leads only to SETTLEMENT_READY, so the repeated request is refused by the one
        //edge table rather than by a guard written into the resolve path.
        assertEquals("RESOLVED \u2192 RESOLVED is not a legal transition", thrown.getMessage(),
                "refusal message");
        assertEquals(ExceptionStatus.RESOLVED, exceptionStore.find(exceptionId).getStatus(),
                "the refused request must not change the status");
        assertEquals(NOTE, exceptionStore.find(exceptionId).getResolutionNote(),
                "the refused request must not overwrite the note");
        assertEquals(before, auditTimeline.all().size(),
                "a refused transition appends no event");
    }

    @Test
    void testUnknownExceptionIdIsReportedAsNotFound() {
        EntityNotFoundException thrown = assertThrows(EntityNotFoundException.class,
                () -> postTrade.assign(UNKNOWN_EXCEPTION_ID, OWNER, ACTOR),
                "assigning an unknown exception must be a missing resource");
        assertTrue(thrown.getMessage().contains(UNKNOWN_EXCEPTION_ID),
                "the message must name the identifier: " + thrown.getMessage());

        assertThrows(EntityNotFoundException.class, () -> postTrade.get(UNKNOWN_EXCEPTION_ID),
                "reading an unknown exception");
        assertThrows(EntityNotFoundException.class, () -> postTrade.events(UNKNOWN_EXCEPTION_ID),
                "reading the history of an unknown exception");
        assertThrows(EntityNotFoundException.class,
                () -> postTrade.resolve(UNKNOWN_EXCEPTION_ID, new ResolveRequest(OWNER, NOTE),
                        ACTOR),
                "resolving an unknown exception");
        assertThrows(EntityNotFoundException.class,
                () -> postTrade.markSettlementReady(UNKNOWN_EXCEPTION_ID, ACTOR),
                "marking an unknown exception settlement-ready");

        //An order that never reached the store is the same class of failure: the post-trade flow
        //transitions the order it was handed, and a transition on an absent key answers with null.
        Order neverStored = buildExecutedOrder("ORD-999999", FABRIKAM_ID, "C9", QUANTITY);
        assertThrows(EntityNotFoundException.class, () -> postTrade.onExecuted(neverStored, ACTOR),
                "running post-trade for an order that is not stored");
        assertEquals(0, exceptionStore.count(), "no exception may be opened for a missing order");
    }

    @Test
    void testMarkSettlementReadyFromOpenIsRefusedAndLeavesNoTrace() {
        String exceptionId = openException();
        int before = auditTimeline.all().size();

        StateConflictException thrown = assertThrows(StateConflictException.class,
                () -> postTrade.markSettlementReady(exceptionId, ACTOR),
                "an open exception cannot be settlement-ready");
        assertEquals("OPEN \u2192 SETTLEMENT_READY is not a legal transition", thrown.getMessage(),
                "refusal message");

        /* Both consequences are structural, not incidental: assertLegal is the first statement of
           the operator the store runs inside ConcurrentHashMap.compute, so the refusal happens
           before the replacement object is built and before AuditTimeline.append is reached. */
        assertEquals(ExceptionStatus.OPEN, exceptionStore.find(exceptionId).getStatus(),
                "the exception must still be open");
        assertNull(exceptionStore.find(exceptionId).getSettlementReadyAt(),
                "no settlement-ready instant may be written");
        assertEquals(before, auditTimeline.all().size(), "no audit event may be appended");
        assertEquals(PostTradeStatus.EXCEPTION,
                orderStore.find("ORD-000001").getPostTradeStatus(),
                "the parent order must stay in exception");
    }

    @Test
    void testResolveWithoutResolutionNoteIsRejected() {
        String exceptionId = openException();
        postTrade.assign(exceptionId, OWNER, ACTOR);

        ValidationException missing = assertThrows(ValidationException.class,
                () -> postTrade.resolve(exceptionId, new ResolveRequest(OWNER, null), ACTOR),
                "a null resolution note must be rejected");
        assertEquals("resolutionNote is required", missing.getMessage(), "null note message");

        ValidationException blank = assertThrows(ValidationException.class,
                () -> postTrade.resolve(exceptionId, new ResolveRequest(OWNER, "   "), ACTOR),
                "a blank resolution note must be rejected");
        assertEquals("resolutionNote is required", blank.getMessage(), "blank note message");

        ValidationException noBody = assertThrows(ValidationException.class,
                () -> postTrade.resolve(exceptionId, null, ACTOR),
                "a missing request body must be rejected as a missing note");
        assertEquals("resolutionNote is required", noBody.getMessage(), "missing body message");

        assertEquals(ExceptionStatus.ASSIGNED, exceptionStore.find(exceptionId).getStatus(),
                "a rejected resolve leaves the exception assigned");
    }

    @Test
    void testMissingOwnerIsRejected() {
        String exceptionId = openException();

        ValidationException blankOwner = assertThrows(ValidationException.class,
                () -> postTrade.assign(exceptionId, "   ", ACTOR),
                "a blank owner must be rejected");
        assertEquals("owner is required", blankOwner.getMessage(), "blank owner message");

        ValidationException nullOwner = assertThrows(ValidationException.class,
                () -> postTrade.assign(exceptionId, null, ACTOR),
                "a null owner must be rejected");
        assertEquals("owner is required", nullOwner.getMessage(), "null owner message");

        /* Resolving an open exception carries the owner because the workflow has no direct
           OPEN -> RESOLVED edge: an exception must never close with nobody recorded against it. */
        ValidationException ownerless = assertThrows(ValidationException.class,
                () -> postTrade.resolve(exceptionId, new ResolveRequest(null, NOTE), ACTOR),
                "resolving an unassigned exception without an owner must be rejected");
        assertEquals("owner is required", ownerless.getMessage(), "ownerless resolve message");

        ValidationException blankOwnerResolve = assertThrows(ValidationException.class,
                () -> postTrade.resolve(exceptionId, new ResolveRequest("  ", NOTE), ACTOR),
                "resolving an unassigned exception with a blank owner must be rejected");
        assertEquals("owner is required", blankOwnerResolve.getMessage(),
                "blank owner resolve message");

        assertEquals(ExceptionStatus.OPEN, exceptionStore.find(exceptionId).getStatus(),
                "a rejected request leaves the exception open");
    }

    @Test
    void testResolveWithOwnerOnOpenExceptionAssignsAndResolvesInOneStep() {
        String exceptionId = openException();
        int before = auditTimeline.all().size();

        SettlementException resolved =
                postTrade.resolve(exceptionId, new ResolveRequest(OWNER, NOTE), ACTOR);

        assertEquals(ExceptionStatus.RESOLVED, resolved.getStatus(), "status after composite resolve");
        assertEquals(OWNER, resolved.getOwner(), "owner recorded by the composite resolve");
        assertEquals(T0, resolved.getAssignedAt(), "assignedAt recorded by the composite resolve");
        assertEquals(NOTE, resolved.getResolutionNote(), "resolution note");
        assertEquals(T0, resolved.getResolvedAt(), "resolvedAt");

        /* Both edges are appended by the one operator the store runs under compute, so no
           concurrent caller can interleave a different owner or note between them - and the
           timeline still shows the owner the exception closed under. */
        List<AuditEvent> all = auditTimeline.all();
        assertEquals(before + 2, all.size(), "one call appends both edges");
        assertExceptionEvent(all.get(before), exceptionId, ExceptionStatus.OPEN.name(),
                ExceptionStatus.ASSIGNED.name());
        assertExceptionEvent(all.get(before + 1), exceptionId, ExceptionStatus.ASSIGNED.name(),
                ExceptionStatus.RESOLVED.name());

        assertEquals(3, postTrade.events(exceptionId).size(),
                "the exception's history is its origin plus both edges");
    }

    @Test
    void testCleanSettlementInstructionsReachSettlementReadyWithNoException() {
        Order executed = storedExecutedOrder(NORTHWIND_ID, "C2", 100L);

        Order affirmed = postTrade.onExecuted(executed, ACTOR);

        assertEquals(PostTradeStatus.SETTLEMENT_READY, affirmed.getPostTradeStatus(),
                "agreeing instructions affirm straight through");
        assertEquals(0, exceptionStore.count(), "no exception may be opened");

        List<AuditEvent> events = auditTimeline.forEntity(ENTITY_ORDER, executed.getOrderId());
        assertEquals(2, events.size(), "affirmation writes exactly two post-trade events");

        AuditEvent pending = events.get(0);
        assertEquals(StateMachine.POST_TRADE, pending.getStateMachine(), "first event machine");
        assertEquals(LifecycleTransitions.NONE, pending.getFromState(), "post-trade origin");
        assertEquals(PostTradeStatus.PENDING_AFFIRMATION.name(), pending.getToState(),
                "first event toState");
        assertTrue(pending.getReason().contains(executed.getExecution().getExecutionId()),
                "the affirmation reason must name the execution: " + pending.getReason());

        AuditEvent settled = events.get(1);
        assertEquals(StateMachine.POST_TRADE, settled.getStateMachine(), "second event machine");
        assertEquals(PostTradeStatus.PENDING_AFFIRMATION.name(), settled.getFromState(),
                "second event fromState");
        assertEquals(PostTradeStatus.SETTLEMENT_READY.name(), settled.getToState(),
                "second event toState");
        assertEquals("SSI affirmed (simulated)", settled.getReason(), "affirmed reason");
        assertEquals(T0, settled.getTimestamp(), "second event timestamp");
    }

    @Test
    void testSlaAgeingIsDerivedOnReadAndFrozenAtResolution() {
        Order executed = storedExecutedOrder(FABRIKAM_ID, "C1", QUANTITY);
        postTrade.onExecuted(executed, ACTOR);
        String exceptionId = "EXC-000001";

        /* Two services over the same stores, timeline and limits, differing only in their clock:
           that is what a Clock constructor parameter buys, and it makes the derived ageing
           assertable without a mocking library or a sleeping test. */
        PostTradeService lateReader = serviceAt(T0.plus(30, ChronoUnit.HOURS));
        PostTradeService promptAnalyst = serviceAt(T0.plus(2, ChronoUnit.HOURS));

        SettlementException atOpening = postTrade.get(exceptionId);
        assertEquals(0L, atOpening.getAgeHours(), "age at the opening instant");
        assertFalse(atOpening.isSlaBreached(), "not breached at the opening instant");
        assertEquals(T0.plus(SLA_HOURS, ChronoUnit.HOURS), atOpening.getSlaDeadline(),
                "the deadline is fixed when the exception opens");

        SettlementException aged = lateReader.get(exceptionId);
        assertEquals(30L, aged.getAgeHours(), "an open exception ages against now");
        assertTrue(aged.isSlaBreached(), "30 hours is past the 24 hour deadline");
        assertEquals(T0.plus(SLA_HOURS, ChronoUnit.HOURS), aged.getSlaDeadline(),
                "the deadline does not move with the clock");

        promptAnalyst.assign(exceptionId, OWNER, ACTOR);
        SettlementException assignedLate = lateReader.get(exceptionId);
        assertEquals(30L, assignedLate.getAgeHours(), "an assigned exception still ages against now");
        assertTrue(assignedLate.isSlaBreached(), "an assigned exception can still breach");

        promptAnalyst.resolve(exceptionId, new ResolveRequest(null, NOTE), ACTOR);

        /* Once resolved the reference instant stops at resolvedAt, so a resolution inside the SLA
           freezes both values: a closed exception must never start reporting itself breached
           merely because time kept passing after it was dealt with. */
        SettlementException frozen = lateReader.get(exceptionId);
        assertEquals(2L, frozen.getAgeHours(), "age freezes at the resolution instant");
        assertFalse(frozen.isSlaBreached(), "a timely resolution can never become a breach");

        SettlementException frozenInList = lateReader.list(null, null).get(0);
        assertEquals(2L, frozenInList.getAgeHours(), "the list view carries the same snapshot");
        assertFalse(frozenInList.isSlaBreached(), "the list view carries the same snapshot");

        promptAnalyst.markSettlementReady(exceptionId, ACTOR);
        SettlementException stillFrozen = lateReader.get(exceptionId);
        assertEquals(2L, stillFrozen.getAgeHours(),
                "settlement-ready keeps the frozen resolution reference");
        assertFalse(stillFrozen.isSlaBreached(), "settlement-ready keeps the frozen verdict");
    }

    private void assertExceptionEvent(AuditEvent event, String exceptionId, String fromState,
            String toState) {
        assertEquals(ENTITY_EXCEPTION, event.getEntityType(), "entityType of " + event.getEventId());
        assertEquals(exceptionId, event.getEntityId(), "entityId of " + event.getEventId());
        assertEquals(StateMachine.EXCEPTION, event.getStateMachine(),
                "stateMachine of " + event.getEventId());
        assertEquals(fromState, event.getFromState(), "fromState of " + event.getEventId());
        assertEquals(toState, event.getToState(), "toState of " + event.getEventId());
    }

    private PostTradeService serviceAt(Instant instant) {
        return new PostTradeService(orderStore, exceptionStore, referenceData, auditTimeline,
                Clock.fixed(instant, ZoneOffset.UTC), limits);
    }

    private String openException() {
        postTrade.onExecuted(storedExecutedOrder(FABRIKAM_ID, "C1", QUANTITY), ACTOR);
        return "EXC-000001";
    }

    private Order storedExecutedOrder(String clientId, String clientOrderId, long quantity) {
        Order executed =
                buildExecutedOrder(orderStore.nextOrderId(), clientId, clientOrderId, quantity);
        orderStore.insert(executed);
        return executed;
    }

    /* The post-trade flow starts from an order that is already EXECUTED, carries an Execution and
       has no post-trade status yet - the null that denotes the "(none)" origin to assertLegal.
       Building it here rather than submitting one keeps this class on the post-trade boundary;
       OrderLifecycleServiceTest owns the submit path that produces such an order for real. */
    private Order buildExecutedOrder(String orderId, String clientId, String clientOrderId,
            long quantity) {
        Execution execution = new Execution(orderStore.nextExecutionId(),
                new BigDecimal(FILL_PRICE), quantity, T0);
        return new Order(orderId, clientOrderId, clientId, SYMBOL, BUY, quantity,
                new BigDecimal(FILL_PRICE), ACTOR, T0, RecordSource.API)
                .withExecution(execution)
                .withStatus(OrderStatus.EXECUTED, T0);
    }

    private void seedClients() {
        //One instruction instance on both sides is the clean-SSI case; INST-003 differs in
        //safekeepingAccount alone, which is the only mismatch the seeded contract produces.
        SettlementInstruction northwind = new SettlementInstruction(NORTHWIND_CUSTODIAN_BIC,
                NORTHWIND_SAFEKEEPING_ACCOUNT, NORTHWIND_CASH_ACCOUNT,
                NORTHWIND_PLACE_OF_SETTLEMENT);
        referenceData.putClient(
                new ClientAccount(NORTHWIND_ID, NORTHWIND_NAME, northwind, northwind));

        referenceData.putClient(new ClientAccount(FABRIKAM_ID, FABRIKAM_NAME,
                new SettlementInstruction(FABRIKAM_CUSTODIAN_BIC,
                        FABRIKAM_FIRM_SAFEKEEPING_ACCOUNT, FABRIKAM_CASH_ACCOUNT,
                        FABRIKAM_PLACE_OF_SETTLEMENT),
                new SettlementInstruction(FABRIKAM_CUSTODIAN_BIC,
                        FABRIKAM_COUNTERPARTY_SAFEKEEPING_ACCOUNT, FABRIKAM_CASH_ACCOUNT,
                        FABRIKAM_PLACE_OF_SETTLEMENT)));
    }
}
