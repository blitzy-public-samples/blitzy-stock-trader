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
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.dao.CapacityLimits;
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

import java.math.BigDecimal;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    //Written as an escape so this source file stays ASCII whatever encoding an editor saves it in,
    //while the assertions still pin the U+2003 character a client can send.
    private static final String EM_SPACE = "\u2003";

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

    /* A bound, not a pause: the race test's two threads hand over through latches, so this only
       fails a run in which one of them never arrives rather than pacing the test. */
    private static final long RACE_TIMEOUT_SECONDS = 5L;

    /* The smallest ceilings CapacityLimits admits: one settlement exception, and thirty audit
       events because the timeline is coupled to ten events for each of the three orders the seed
       set writes. They are what let the two capacity tests below reach a ceiling in a handful of
       calls, where their default-sized equivalents above fill 150,000 events to do it. */
    private static final int SMALL_EXCEPTION_CEILING = 1;
    private static final int SMALL_EVENT_CEILING = 30;

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

    @Test
    void testExceptionIsPublishedOnlyWithItsParentOrderInException() throws InterruptedException {
        Order executed = storedExecutedOrder(FABRIKAM_ID, "C1", QUANTITY);
        String orderId = executed.getOrderId();
        String exceptionId = "EXC-000001";

        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        AtomicReference<PostTradeStatus> parentAtPublication = new AtomicReference<>();
        CountDownLatch published = new CountDownLatch(1);
        CountDownLatch clearing = new CountDownLatch(1);

        /* insert is the instant an exception becomes workable by anybody, so the parent order's
           state at that instant is the invariant under test. The same hook holds the publishing
           thread there until the analyst thread has reached its settlement-ready call, which is
           the interleaving an uncoordinated publication loses: the analyst clears the exception
           while the order still reads PENDING_AFFIRMATION, the timeline gains an event claiming
           to have left EXCEPTION, and this thread's own transition then fails. */
        SettlementExceptionStore publishing = new SettlementExceptionStore() {
            @Override
            public void insert(SettlementException exception) {
                super.insert(exception);
                parentAtPublication.set(
                        orderStore.find(exception.getOrderId()).getPostTradeStatus());
                published.countDown();
                await(clearing, "the analyst thread never reached settlement-ready", failures);
            }
        };

        PostTradeService racing = new PostTradeService(orderStore, publishing, referenceData,
                auditTimeline, CLOCK, limits);

        Thread analyst = new Thread(() -> {
            try {
                if (await(published, "no exception was ever published", failures)) {
                    racing.assign(exceptionId, OWNER, ACTOR);
                    racing.resolve(exceptionId, new ResolveRequest(null, NOTE), ACTOR);
                    clearing.countDown();
                    racing.markSettlementReady(exceptionId, ACTOR);
                }
            } catch (RuntimeException failure) {
                failures.add(failure);
            } finally {
                //Releases the publishing thread on every exit path, so a failure here surfaces as
                //that failure rather than as a test that hangs until the timeout.
                clearing.countDown();
            }
        }, "ops-analyst");
        analyst.setDaemon(true);

        analyst.start();
        racing.onExecuted(executed, ACTOR);
        analyst.join(TimeUnit.SECONDS.toMillis(RACE_TIMEOUT_SECONDS));

        assertFalse(analyst.isAlive(), "the analyst thread must finish");
        assertTrue(failures.isEmpty(), "the concurrent workflow must not fail: " + failures);

        assertEquals(PostTradeStatus.EXCEPTION, parentAtPublication.get(),
                "an exception must not be reachable before its parent order is in EXCEPTION");
        assertEquals(PostTradeStatus.SETTLEMENT_READY,
                orderStore.find(orderId).getPostTradeStatus(),
                "the order follows its cleared exception");
        assertEquals(ExceptionStatus.SETTLEMENT_READY, publishing.find(exceptionId).getStatus(),
                "the exception is cleared");

        assertContiguousChain(postTradeEvents(orderId),
                PostTradeStatus.PENDING_AFFIRMATION.name(), PostTradeStatus.EXCEPTION.name(),
                PostTradeStatus.SETTLEMENT_READY.name());
        assertContiguousChain(auditTimeline.forEntity(ENTITY_EXCEPTION, exceptionId),
                ExceptionStatus.OPEN.name(), ExceptionStatus.ASSIGNED.name(),
                ExceptionStatus.RESOLVED.name(), ExceptionStatus.SETTLEMENT_READY.name());
    }

    @Test
    void testSettlementReadyIsRefusedUnlessTheParentOrderIsInException() {
        /* Neither state below can be produced through the service - publishing an exception only
           once its order is in EXCEPTION is exactly what forecloses them - so both are built
           directly: a resolved exception standing against an order still awaiting affirmation,
           and one standing against an order whose post-trade machine never started, which is what
           a caller that reaches an exception ahead of its parent's transition would hold. */
        Order pending = buildExecutedOrder(orderStore.nextOrderId(), FABRIKAM_ID, "C1", QUANTITY)
                .withPostTradeStatus(PostTradeStatus.PENDING_AFFIRMATION, T0);
        orderStore.insert(pending);
        String pendingException = resolvedExceptionAgainst(pending);

        int before = auditTimeline.all().size();
        StateConflictException awaiting = assertThrows(StateConflictException.class,
                () -> postTrade.markSettlementReady(pendingException, ACTOR),
                "clearing an exception must not release an order that never reached EXCEPTION");
        assertTrue(awaiting.getMessage().contains(pending.getOrderId())
                        && awaiting.getMessage().contains(PostTradeStatus.PENDING_AFFIRMATION.name()),
                "the refusal must name the order and the state it is in: " + awaiting.getMessage());

        /* The order is inspected before the exception is touched, so the refusal leaves both
           entities and the timeline exactly as they were - the two stores share no rollback. */
        assertEquals(ExceptionStatus.RESOLVED, exceptionStore.find(pendingException).getStatus(),
                "the refused request must leave the exception resolved");
        assertNull(exceptionStore.find(pendingException).getSettlementReadyAt(),
                "no settlement-ready instant may be written");
        assertEquals(PostTradeStatus.PENDING_AFFIRMATION,
                orderStore.find(pending.getOrderId()).getPostTradeStatus(),
                "the refused request must leave the order awaiting affirmation");
        assertEquals(before, auditTimeline.all().size(),
                "a refused precondition appends no event to either entity");

        //An order whose post-trade machine never started renders as the "(none)" origin instead of
        //failing on a null state, so this refusal stays a conflict an analyst can read.
        Order unaffirmed = buildExecutedOrder(orderStore.nextOrderId(), FABRIKAM_ID, "C2", QUANTITY);
        orderStore.insert(unaffirmed);
        String unaffirmedException = resolvedExceptionAgainst(unaffirmed);

        StateConflictException unstarted = assertThrows(StateConflictException.class,
                () -> postTrade.markSettlementReady(unaffirmedException, ACTOR),
                "an order with no post-trade state cannot be released either");
        assertTrue(unstarted.getMessage().contains(LifecycleTransitions.NONE),
                "the refusal must render an absent state as the origin: " + unstarted.getMessage());
    }

    @Test
    void testUnicodeWhitespaceOwnerOrNoteIsRejected() {
        String exceptionId = openException();
        int before = auditTimeline.count();

        /* U+2003 (EM SPACE) is whitespace that trim() does not remove - trim stops at U+0020 - so
           a trim-based check passes a value made only of it as present: an exception could be
           assigned to an owner that reads as nobody, or closed with a note that reads as no
           evidence at all.
           EM SPACE is deliberately the character used here; U+00A0 is not whitespace by Java's
           definition, so isBlank and strip leave it alone on purpose. */
        ValidationException blankOwner = assertThrows(ValidationException.class,
                () -> postTrade.assign(exceptionId, EM_SPACE, ACTOR),
                "an owner of Unicode whitespace must be rejected");
        assertEquals("owner is required", blankOwner.getMessage(), "Unicode-blank owner message");

        ValidationException blankOwnerResolve = assertThrows(ValidationException.class,
                () -> postTrade.resolve(exceptionId, new ResolveRequest(EM_SPACE, NOTE), ACTOR),
                "resolving an open exception with a Unicode-blank owner must be rejected");
        assertEquals("owner is required", blankOwnerResolve.getMessage(),
                "Unicode-blank owner resolve message");

        ValidationException blankNote = assertThrows(ValidationException.class,
                () -> postTrade.resolve(exceptionId, new ResolveRequest(OWNER, EM_SPACE), ACTOR),
                "a resolution note of Unicode whitespace must be rejected");
        assertEquals("resolutionNote is required", blankNote.getMessage(),
                "Unicode-blank note message");

        assertEquals(ExceptionStatus.OPEN, exceptionStore.find(exceptionId).getStatus(),
                "a rejected request leaves the exception open");
        assertNull(exceptionStore.find(exceptionId).getOwner(),
                "no owner may be recorded by a rejected request");
        assertEquals(before, auditTimeline.count(), "a rejected request appends no event");
    }

    @Test
    void testUnicodePaddedOwnerAndNoteAreStoredStripped() {
        String exceptionId = openException();

        SettlementException assigned =
                postTrade.assign(exceptionId, EM_SPACE + OWNER + EM_SPACE, ACTOR);
        assertEquals(OWNER, assigned.getOwner(), "the stored owner is stripped of its padding");

        //The owner filter has to match the stripped value that was stored, or a padded assignment
        //would create an owner nobody can query for.
        assertEquals(1, postTrade.list(null, OWNER).size(),
                "the owner filter matches the stripped owner");
        assertEquals(1, postTrade.list(null, EM_SPACE + OWNER).size(),
                "a padded owner filter matches the same exception");
        assertEquals(1, postTrade.list(null, EM_SPACE).size(),
                "a filter of Unicode whitespace alone is no filter at all");

        SettlementException resolved = postTrade.resolve(exceptionId,
                new ResolveRequest(null, EM_SPACE + NOTE + EM_SPACE), ACTOR);
        assertEquals(NOTE, resolved.getResolutionNote(),
                "the stored resolution note is stripped of its padding");

        List<AuditEvent> events = auditTimeline.forEntity(ENTITY_EXCEPTION, exceptionId);
        assertEquals(NOTE, events.get(events.size() - 1).getReason(),
                "the audit reason carries the stripped note, not the padded one");
    }

    @Test
    void testOverLengthOwnerOrNoteIsRejectedAndLeavesNoTrace() {
        String exceptionId = openException();
        int before = auditTimeline.count();

        /* An owner and a note are stored on an entity that never expires and are repeated in the
           audit reason of every edge, so an unbounded string here is an in-memory exhaustion
           vector one request wide. */
        ValidationException longOwner = assertThrows(ValidationException.class,
                () -> postTrade.assign(exceptionId, "o".repeat(65), ACTOR),
                "an over-length owner must be rejected");
        assertEquals("owner must not exceed 64 characters", longOwner.getMessage(),
                "over-length owner message");

        ValidationException longNote = assertThrows(ValidationException.class,
                () -> postTrade.resolve(exceptionId, new ResolveRequest(OWNER, "n".repeat(1025)),
                        ACTOR),
                "an over-length resolution note must be rejected");
        assertEquals("resolutionNote must not exceed 1024 characters", longNote.getMessage(),
                "over-length note message");

        assertEquals(ExceptionStatus.OPEN, exceptionStore.find(exceptionId).getStatus(),
                "a rejected request leaves the exception open");
        assertNull(exceptionStore.find(exceptionId).getOwner(),
                "no owner may be recorded by a rejected request");
        assertEquals(before, auditTimeline.count(), "a rejected request appends no event");

        //The boundary passes rather than rejects, as everywhere else in this module.
        assertEquals("o".repeat(64), postTrade.assign(exceptionId, "o".repeat(64), ACTOR).getOwner(),
                "an owner of exactly 64 characters is inside the limit");
    }

    @Test
    void testAssignAndResolveAreRefusedWhenTheTimelineIsFull() {
        String exceptionId = openException();

        /* Re-assignment is the module's one unbounded audit vector: ASSIGNED -> ASSIGNED is a
           legal edge, so an analyst could append events to an exception that already exists
           without ever creating an order, an exception or a position. Filling the timeline to its
           ceiling is what proves the gate that closes it. */
        for (int event = auditTimeline.count(); event < auditTimeline.maxEvents(); event++) {
            auditTimeline.append(ENTITY_EXCEPTION, exceptionId, StateMachine.EXCEPTION,
                    ExceptionStatus.ASSIGNED.name(), ExceptionStatus.ASSIGNED.name(), ACTOR,
                    "filling the timeline to its ceiling", CLOCK);
        }

        assertEquals(auditTimeline.maxEvents(), auditTimeline.count(), "the timeline is full");
        assertThrows(CapacityExceededException.class,
                () -> postTrade.assign(exceptionId, OWNER, ACTOR),
                "an assignment that cannot be recorded must be refused");
        assertThrows(CapacityExceededException.class,
                () -> postTrade.resolve(exceptionId, new ResolveRequest(OWNER, NOTE), ACTOR),
                "a resolution that cannot be recorded must be refused");

        //Refused before the transition, so the exception is exactly as it was and the timeline
        //gained nothing: the record can never be the reason a state change went unrecorded.
        assertEquals(ExceptionStatus.OPEN, exceptionStore.find(exceptionId).getStatus(),
                "a refused workflow step leaves the exception open");
        assertNull(exceptionStore.find(exceptionId).getOwner(),
                "no owner may be recorded by a refused assignment");
        assertEquals(auditTimeline.maxEvents(), auditTimeline.count(),
                "a refused workflow step appends no event");
    }

    @Test
    void testCapacityRefusalDoesNotMaskAnUnknownIdOrAnIllegalTransition() {
        String exceptionId = openException();
        postTrade.assign(exceptionId, OWNER, ACTOR);
        postTrade.resolve(exceptionId, new ResolveRequest(null, NOTE), ACTOR);

        for (int event = auditTimeline.count(); event < auditTimeline.maxEvents(); event++) {
            auditTimeline.append(ENTITY_EXCEPTION, exceptionId, StateMachine.EXCEPTION,
                    ExceptionStatus.ASSIGNED.name(), ExceptionStatus.ASSIGNED.name(), ACTOR,
                    "filling the timeline to its ceiling", CLOCK);
        }
        assertEquals(auditTimeline.maxEvents(), auditTimeline.count(), "the timeline is full");

        /* Identity and legality are settled before capacity, so an exhausted service still answers
           the question the caller asked. A 503 for either of these would be a retry invitation for
           a request that can never be accepted, and it would hide the reason. */
        EntityNotFoundException missing = assertThrows(EntityNotFoundException.class,
                () -> postTrade.assign(UNKNOWN_EXCEPTION_ID, OWNER, ACTOR),
                "an unknown exception id stays a missing resource on a saturated service");
        assertTrue(missing.getMessage().contains(UNKNOWN_EXCEPTION_ID),
                "the message must name the identifier: " + missing.getMessage());

        StateConflictException illegal = assertThrows(StateConflictException.class,
                () -> postTrade.resolve(exceptionId, new ResolveRequest(null, "again"), ACTOR),
                "a repeated resolution stays a conflict on a saturated service");
        assertEquals("RESOLVED \u2192 RESOLVED is not a legal transition", illegal.getMessage(),
                "refusal message");

        //Only a step that would otherwise have been taken is refused at the ceiling, and it is
        //refused before either entity moves: the exception and its parent order stay as they were.
        assertThrows(CapacityExceededException.class,
                () -> postTrade.markSettlementReady(exceptionId, ACTOR),
                "a legal settlement-ready step that cannot be recorded must be refused");

        assertEquals(ExceptionStatus.RESOLVED, exceptionStore.find(exceptionId).getStatus(),
                "the refused step leaves the exception resolved");
        assertNull(exceptionStore.find(exceptionId).getSettlementReadyAt(),
                "no settlement-ready instant may be written");
        assertEquals(PostTradeStatus.EXCEPTION, orderStore.find("ORD-000001").getPostTradeStatus(),
                "the parent order stays in exception");
        assertEquals(auditTimeline.maxEvents(), auditTimeline.count(),
                "no refused step appends an event");
    }

    @Test
    void testListAndEventsPageTheFilteredSet() {
        postTrade.onExecuted(storedExecutedOrder(FABRIKAM_ID, "C1", QUANTITY), ACTOR);
        postTrade.onExecuted(storedExecutedOrder(FABRIKAM_ID, "C2", QUANTITY), ACTOR);
        postTrade.onExecuted(storedExecutedOrder(FABRIKAM_ID, "C3", QUANTITY), ACTOR);
        postTrade.assign("EXC-000002", OWNER, ACTOR);

        /* The page is cut from the filtered set rather than from the store, so a status query
           answers with a page of its own matches: paging first would answer mostly empty pages
           once most exceptions were closed. */
        List<SettlementException> firstOpen = postTrade.list(ExceptionStatus.OPEN, null, 0, 1);
        assertEquals(1, firstOpen.size(), "a page of one holds one exception");
        assertEquals("EXC-000001", firstOpen.get(0).getExceptionId(),
                "the first page of the OPEN set starts at the lowest open exception id");
        assertEquals("EXC-000003",
                postTrade.list(ExceptionStatus.OPEN, null, 1, 1).get(0).getExceptionId(),
                "the second page of the OPEN set skips the assigned exception");
        assertEquals(1, postTrade.list(null, OWNER, 0, 500).size(),
                "the owner filter pages its own matches");
        assertEquals(3, postTrade.list(null, null, 0, 0).size(),
                "a zero limit yields the whole small set");
        assertTrue(postTrade.list(null, null, 3, 5).isEmpty(),
                "an offset past the end is an empty page");

        //An exception's own history is paged too, because every re-assignment adds an edge to it.
        assertEquals(1, postTrade.events("EXC-000002", 0, 1).size(), "a page of one event");
        assertEquals(ExceptionStatus.ASSIGNED.name(),
                postTrade.events("EXC-000002", 1, 1).get(0).getToState(),
                "the second page of the history is the assign edge");
        assertEquals(2, postTrade.events("EXC-000002", 0, 0).size(),
                "a zero limit yields the whole short history");
        assertEquals(postTrade.events("EXC-000002").size(),
                postTrade.events("EXC-000002", 0, 0).size(),
                "the unpaged read and the uncut page agree");
    }

    @Test
    void testExceptionCapacityIsReportedAndRefusedAtAConfiguredCeiling() {
        //A store sized to one exception, so the gate the submission path asks - and the claim
        //behind it - are both reached without opening ten thousand breaks.
        SettlementExceptionStore bounded = new SettlementExceptionStore(smallLimits());
        PostTradeService service = new PostTradeService(orderStore, bounded, referenceData,
                auditTimeline, CLOCK, limits);

        assertEquals(SMALL_EXCEPTION_CEILING, bounded.maxExceptions(),
                "the store reports the ceiling configuration sized it to");
        assertTrue(service.hasCapacityToOpenException(),
                "an empty store has room for the break an execution may find");

        service.onExecuted(storedExecutedOrder(FABRIKAM_ID, "C1", QUANTITY), ACTOR);

        assertEquals(SMALL_EXCEPTION_CEILING, bounded.count(), "the ceiling is reached exactly");
        assertEquals(0, bounded.exceptionHeadroom(), "a full store reports no headroom");
        /* This is the gate OrderLifecycleService asks of every submission: it reports false while
           the submission can still be refused whole, which is what keeps an order from reaching
           EXECUTED with nowhere to record the break its execution found. */
        assertFalse(service.hasCapacityToOpenException(),
                "a full store reports no room, so no further order may be admitted");

        CapacityExceededException refused = assertThrows(CapacityExceededException.class,
                () -> service.onExecuted(storedExecutedOrder(FABRIKAM_ID, "C2", QUANTITY), ACTOR),
                "a break that cannot be stored must be refused rather than lost");
        assertTrue(refused.getMessage().contains("SETTLEMENT_EXCEPTION_CAPACITY"),
                "the refusal must name the key to raise: " + refused.getMessage());
        assertEquals(SMALL_EXCEPTION_CEILING, bounded.count(),
                "a refused break stores no exception");
    }

    @Test
    void testTheWorkflowGatesRefuseAtAConfiguredAuditCeiling() {
        //A timeline sized to thirty events, so the assign and resolve gates are reached in a few
        //appends; their default-sized equivalents above have to write 150,000.
        AuditTimeline bounded = new AuditTimeline(smallLimits());
        PostTradeService service = new PostTradeService(orderStore, exceptionStore, referenceData,
                bounded, CLOCK, limits);

        service.onExecuted(storedExecutedOrder(FABRIKAM_ID, "C1", QUANTITY), ACTOR);
        String exceptionId = "EXC-000001";
        assertEquals(SMALL_EVENT_CEILING, bounded.maxEvents(),
                "the timeline reports the ceiling configuration sized it to");

        for (int event = bounded.count(); event < bounded.maxEvents(); event++) {
            bounded.append(ENTITY_EXCEPTION, exceptionId, StateMachine.EXCEPTION,
                    ExceptionStatus.ASSIGNED.name(), ExceptionStatus.ASSIGNED.name(), ACTOR,
                    "filling the configured timeline to its ceiling", CLOCK);
        }
        assertEquals(0, bounded.eventHeadroom(), "the configured timeline is full");

        CapacityExceededException assignRefused = assertThrows(CapacityExceededException.class,
                () -> service.assign(exceptionId, OWNER, ACTOR),
                "an assignment that cannot be recorded must be refused");
        assertTrue(assignRefused.getMessage().contains("AUDIT_EVENT_CAPACITY"),
                "the refusal must name the key to raise: " + assignRefused.getMessage());
        assertThrows(CapacityExceededException.class,
                () -> service.resolve(exceptionId, new ResolveRequest(OWNER, NOTE), ACTOR),
                "a resolution that cannot be recorded must be refused");

        //Refused before the transition at a configured ceiling exactly as at the default one: the
        //record can never be the reason a state change went unrecorded.
        assertEquals(ExceptionStatus.OPEN, exceptionStore.find(exceptionId).getStatus(),
                "a refused workflow step leaves the exception open");
        assertNull(exceptionStore.find(exceptionId).getOwner(),
                "no owner may be recorded by a refused assignment");
        assertEquals(SMALL_EVENT_CEILING, bounded.count(),
                "a refused workflow step appends no event");
    }

    /* The smallest ceilings the validation admits, which is what makes a capacity test instant:
       one exception and thirty audit events, with the order and position minimums the seed set
       needs. Built once here so the two tests above cannot drift apart on what "small" means. */
    private static CapacityLimits smallLimits() {
        return new CapacityLimits(CapacityLimits.MIN_MAX_ORDERS, SMALL_EXCEPTION_CEILING,
                CapacityLimits.MIN_MAX_POSITIONS, SMALL_EVENT_CEILING);
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

    //Builds what the service can no longer produce: an exception resolved against an order that is
    //not in EXCEPTION. It is inserted straight into the store because opening one through the
    //service is precisely what now requires the parent order to be in EXCEPTION first.
    private String resolvedExceptionAgainst(Order order) {
        String exceptionId = exceptionStore.nextExceptionId();
        exceptionStore.insert(new SettlementException(exceptionId, "SSI_MISMATCH",
                order.getOrderId(), order.getExecution().getExecutionId(), FABRIKAM_ID,
                FABRIKAM_NAME, SYMBOL, BUY, QUANTITY, new BigDecimal("30000.00"),
                new BigDecimal(FILL_PRICE), T0, "SIMULATED",
                List.of(new MismatchField("safekeepingAccount", FABRIKAM_FIRM_SAFEKEEPING_ACCOUNT,
                        FABRIKAM_COUNTERPARTY_SAFEKEEPING_ACCOUNT)),
                T0, T0.plus(SLA_HOURS, ChronoUnit.HOURS), RecordSource.API));
        postTrade.resolve(exceptionId, new ResolveRequest(OWNER, NOTE), ACTOR);
        return exceptionId;
    }

    private List<AuditEvent> postTradeEvents(String orderId) {
        //One order files both its machines under the entityType ORDER, so the post-trade chain has
        //to be narrowed by stateMachine before its edges can be read as a chain at all.
        List<AuditEvent> postTrade = new ArrayList<>();
        for (AuditEvent event : auditTimeline.forEntity(ENTITY_ORDER, orderId)) {
            if (event.getStateMachine() == StateMachine.POST_TRADE) {
                postTrade.add(event);
            }
        }
        return postTrade;
    }

    /* A chain in which every fromState is the previous toState is the assertion a false origin
       fails: the race guarded against here left an order at PENDING_AFFIRMATION and then filed an
       event claiming to have left EXCEPTION, a break invisible to any single-event assertion. */
    private void assertContiguousChain(List<AuditEvent> events, String... expectedToStates) {
        assertEquals(expectedToStates.length, events.size(), "recorded edges");
        String previous = LifecycleTransitions.NONE;

        for (int edge = 0; edge < events.size(); edge++) {
            AuditEvent event = events.get(edge);
            assertEquals(previous, event.getFromState(), "fromState of " + event.getEventId());
            assertEquals(expectedToStates[edge], event.getToState(),
                    "toState of " + event.getEventId());
            previous = event.getToState();
        }
    }

    //Bounded so a thread that never arrives fails the run with its own message instead of hanging
    //it, and interruption is recorded rather than swallowed; no checked exception escapes, which is
    //what lets this be called from the overridden store method.
    private static boolean await(CountDownLatch latch, String timeoutMessage,
            Queue<Throwable> failures) {
        try {
            if (latch.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return true;
            }
            failures.add(new IllegalStateException(timeoutMessage));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failures.add(interrupted);
        }
        return false;
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
        /* The clean-SSI client carries two separately constructed instructions of equal field
           values, as SeedDataLoader gives it: one shared instance would let an affirmation
           comparing instruction references rather than their fields pass this fixture, and
           reference equality is not what agreeing settlement instructions mean. INST-003 differs
           in safekeepingAccount alone, which is the only mismatch the seeded contract produces. */
        referenceData.putClient(new ClientAccount(NORTHWIND_ID, NORTHWIND_NAME,
                new SettlementInstruction(NORTHWIND_CUSTODIAN_BIC, NORTHWIND_SAFEKEEPING_ACCOUNT,
                        NORTHWIND_CASH_ACCOUNT, NORTHWIND_PLACE_OF_SETTLEMENT),
                new SettlementInstruction(NORTHWIND_CUSTODIAN_BIC, NORTHWIND_SAFEKEEPING_ACCOUNT,
                        NORTHWIND_CASH_ACCOUNT, NORTHWIND_PLACE_OF_SETTLEMENT)));

        referenceData.putClient(new ClientAccount(FABRIKAM_ID, FABRIKAM_NAME,
                new SettlementInstruction(FABRIKAM_CUSTODIAN_BIC,
                        FABRIKAM_FIRM_SAFEKEEPING_ACCOUNT, FABRIKAM_CASH_ACCOUNT,
                        FABRIKAM_PLACE_OF_SETTLEMENT),
                new SettlementInstruction(FABRIKAM_CUSTODIAN_BIC,
                        FABRIKAM_COUNTERPARTY_SAFEKEEPING_ACCOUNT, FABRIKAM_CASH_ACCOUNT,
                        FABRIKAM_PLACE_OF_SETTLEMENT)));
    }
}
