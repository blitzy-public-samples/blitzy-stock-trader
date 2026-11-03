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

package com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.audit;

import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.dao.CapacityLimits;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.AuditEvent;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.StateMachine;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.lifecycle.CapacityExceededException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Unit tests for the append-only guarantees of AuditTimeline */
class AuditTimelineTest {

    /* The timeline takes its time from the clock handed to append, so a fixed clock lets the
       timestamp be compared for equality instead of against a tolerance window: an event that
       carried the wall clock, or no clock at all, fails rather than passing by luck. */
    private static final Instant FIXED = Instant.parse("2025-01-02T03:04:05Z");
    private static final Clock CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);

    //Thirty is the smallest ceiling CapacityLimits admits - ten events for each of the three
    //orders the seed set writes - so it is what a "configured ceiling" test can reach in a loop.
    private static final int SMALL_EVENT_CEILING = 30;

    //The shipped default, restated here rather than read from CapacityLimits alone, so lowering
    //that constant fails this test instead of silently changing what the README documents.
    private static final int DOCUMENTED_EVENT_CEILING = 150_000;

    private AuditTimeline timeline;

    @BeforeEach
    void freshTimeline() {
        //Sequence numbers start at 1 per timeline, so every test gets its own instance.
        timeline = new AuditTimeline();
    }

    @Test
    void testSequenceIsMonotonicAndEveryEventIsTimestampedAndLabelled() {
        AuditEvent submitted = timeline.append("ORDER", "ORD-000001", StateMachine.ORDER,
                "(none)", "SUBMITTED", "stock", "clientOrderId C1 received", CLOCK);
        AuditEvent accepted = timeline.append("ORDER", "ORD-000001", StateMachine.ORDER,
                "SUBMITTED", "ACCEPTED", "stock", "all pre-trade controls passed", CLOCK);
        AuditEvent executed = timeline.append("ORDER", "ORD-000001", StateMachine.ORDER,
                "ACCEPTED", "EXECUTED", "stock", "execution EXE-000001", CLOCK);

        assertEquals(1L, submitted.getSequence(), "first sequence");
        assertTrue(submitted.getSequence() < accepted.getSequence(), "sequence must increase");
        assertTrue(accepted.getSequence() < executed.getSequence(), "sequence must increase");

        for (AuditEvent event : List.of(submitted, accepted, executed)) {
            assertNotNull(event.getTimestamp(), "timestamp of " + event.getEventId());
            assertEquals(FIXED, event.getTimestamp(), "timestamp of " + event.getEventId());
            assertTrue(event.isSimulated(), "simulated flag of " + event.getEventId());
            assertNotNull(event.getDisclaimer(), "disclaimer of " + event.getEventId());
            assertFalse(event.getDisclaimer().isBlank(), "disclaimer of " + event.getEventId());
        }

        //append returns the very record it stored, not a copy the caller could diverge from.
        List<AuditEvent> stored = timeline.all();
        assertSame(submitted, stored.get(0), "first stored event");
        assertSame(accepted, stored.get(1), "second stored event");
        assertSame(executed, stored.get(2), "third stored event");
    }

    @Test
    void testEventIdIsTheSequenceZeroPaddedToSixDigits() {
        /* Appended while the JVM's formatting locale uses a non-Latin numbering system, which is
           reachable with nothing more exotic than -Duser.language=ne -Duser.country=NP: the event
           id is an ASCII rendering of the sequence that the audit endpoint, the README and these
           literals all read directly, so it must not follow the deployment's locale. */
        Locale formatLocale = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ne-NP"));
        try {
            AuditEvent first = timeline.append("ORDER", "ORD-000001", StateMachine.ORDER,
                    "(none)", "SUBMITTED", "stock", "clientOrderId C1 received", CLOCK);
            AuditEvent second = timeline.append("ORDER", "ORD-000002", StateMachine.ORDER,
                    "(none)", "SUBMITTED", "stock", "clientOrderId C2 received", CLOCK);
            AuditEvent third = timeline.append("EXCEPTION", "EXC-000001", StateMachine.EXCEPTION,
                    "(none)", "OPEN", "seed", "safekeepingAccount differs", CLOCK);

            assertEquals("EVT-000001", first.getEventId(), "first eventId");
            assertEquals("EVT-000002", second.getEventId(), "second eventId");
            assertEquals("EVT-000003", third.getEventId(), "third eventId");

            for (AuditEvent event : timeline.all()) {
                assertEquals(String.format(Locale.ROOT, "EVT-%06d", event.getSequence()),
                        event.getEventId(), "eventId must be the zero-padded sequence");
            }
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, formatLocale);
        }
    }

    @Test
    void testForEntityMatchesBothKeysAndSpansStateMachines() {
        AuditEvent orderEvent = timeline.append("ORDER", "ORD-000001", StateMachine.ORDER,
                "ACCEPTED", "EXECUTED", "stock", "execution EXE-000001", CLOCK);
        AuditEvent postTradeEvent = timeline.append("ORDER", "ORD-000001", StateMachine.POST_TRADE,
                "(none)", "PENDING_AFFIRMATION", "stock", "execution EXE-000001", CLOCK);
        timeline.append("ORDER", "ORD-000002", StateMachine.ORDER,
                "SUBMITTED", "REJECTED", "stock", "Symbol RSTRA is on the restricted list", CLOCK);
        timeline.append("EXCEPTION", "EXC-000001", StateMachine.EXCEPTION,
                "(none)", "OPEN", "seed", "safekeepingAccount differs", CLOCK);

        /* One order carries two interleaved state machines, and forEntity narrows on entity
           identity only. That is what lets GET /orders/{orderId}/events show an order's ORDER
           and POST_TRADE history as one timeline; filtering by state machine here would hide
           half of it. */
        List<AuditEvent> history = timeline.forEntity("ORDER", "ORD-000001");
        assertEquals(2, history.size(), "events for ORD-000001");
        assertSame(orderEvent, history.get(0), "first event for ORD-000001");
        assertSame(postTradeEvent, history.get(1), "second event for ORD-000001");
        assertTrue(history.get(0).getSequence() < history.get(1).getSequence(), "sequence order");
        assertEquals(StateMachine.ORDER, history.get(0).getStateMachine(), "first state machine");
        assertEquals(StateMachine.POST_TRADE, history.get(1).getStateMachine(), "second state machine");

        assertTrue(timeline.forEntity("ORDER", "ORD-999999").isEmpty(), "unknown entityId");
        assertTrue(timeline.forEntity("EXCEPTION", "ORD-000001").isEmpty(), "mismatched entityType");
    }

    @Test
    void testAllReturnsEveryEventInSequenceOrder() {
        AuditEvent submitted = timeline.append("ORDER", "ORD-000001", StateMachine.ORDER,
                "(none)", "SUBMITTED", "stock", "clientOrderId C1 received", CLOCK);
        AuditEvent accepted = timeline.append("ORDER", "ORD-000001", StateMachine.ORDER,
                "SUBMITTED", "ACCEPTED", "stock", "all pre-trade controls passed", CLOCK);
        AuditEvent opened = timeline.append("EXCEPTION", "EXC-000001", StateMachine.EXCEPTION,
                "(none)", "OPEN", "seed", "safekeepingAccount differs", CLOCK);
        AuditEvent assigned = timeline.append("EXCEPTION", "EXC-000001", StateMachine.EXCEPTION,
                "OPEN", "ASSIGNED", "stock", "owner ops.analyst", CLOCK);

        List<AuditEvent> everything = timeline.all();
        assertEquals(4, everything.size(), "every appended event is returned");
        assertSame(submitted, everything.get(0), "position 0");
        assertSame(accepted, everything.get(1), "position 1");
        assertSame(opened, everything.get(2), "position 2");
        assertSame(assigned, everything.get(3), "position 3");
        for (int i = 1; i < everything.size(); i++) {
            assertTrue(everything.get(i - 1).getSequence() < everything.get(i).getSequence(),
                    "sequence must ascend at position " + i);
        }
    }

    @Test
    void testReadViewsRejectMutationAndDoNotSeeLaterAppends() {
        AuditEvent recorded = timeline.append("ORDER", "ORD-000001", StateMachine.ORDER,
                "(none)", "SUBMITTED", "stock", "clientOrderId C1 received", CLOCK);

        List<AuditEvent> everything = timeline.all();
        List<AuditEvent> history = timeline.forEntity("ORDER", "ORD-000001");

        /* The timeline offers no update, remove or clear, so a read view that accepted a
           mutation would be the one way to rewrite the audit record after the fact. */
        assertThrows(UnsupportedOperationException.class, () -> everything.remove(0), "all(): remove");
        assertThrows(UnsupportedOperationException.class, () -> everything.clear(), "all(): clear");
        assertThrows(UnsupportedOperationException.class, () -> everything.add(recorded), "all(): add");
        assertThrows(UnsupportedOperationException.class, () -> history.remove(0), "forEntity(): remove");
        assertThrows(UnsupportedOperationException.class, () -> history.clear(), "forEntity(): clear");
        assertThrows(UnsupportedOperationException.class, () -> history.add(recorded), "forEntity(): add");

        //Each read is a snapshot, so a view handed out earlier cannot observe a later append.
        timeline.append("ORDER", "ORD-000001", StateMachine.ORDER,
                "SUBMITTED", "ACCEPTED", "stock", "all pre-trade controls passed", CLOCK);
        assertEquals(1, everything.size(), "all() snapshot must not grow");
        assertEquals(1, history.size(), "forEntity() snapshot must not grow");
        assertEquals(2, timeline.all().size(), "a fresh read sees the later append");
    }

    @Test
    void testCapacityIsReportedAgainstTheCeiling() {
        assertEquals(0, timeline.count(), "a fresh timeline holds nothing");
        assertTrue(timeline.hasCapacityFor(timeline.maxEvents()),
                "an empty timeline has room for the whole ceiling");
        assertFalse(timeline.hasCapacityFor(timeline.maxEvents() + 1),
                "no timeline has room for more than the ceiling");

        /* Filled in one loop with nothing asserted per event: what matters here is the ceiling,
           and the per-event guarantees - ordinal, timestamp, labels, snapshot semantics - are
           each asserted once in the tests above rather than a hundred thousand times. A flow asks
           for the headroom its edges need before it moves anything, which is why the reported
           figure has to be exact at the boundary and not merely close to it. */
        for (int event = timeline.count(); event < timeline.maxEvents(); event++) {
            timeline.append("ORDER", "ORD-000001", StateMachine.ORDER, "SUBMITTED", "ACCEPTED",
                    "stock", "all pre-trade controls passed", CLOCK);
        }

        assertEquals(timeline.maxEvents(), timeline.count(), "the timeline is at its ceiling");
        assertEquals(timeline.maxEvents(), timeline.all().size(),
                "every appended event is still readable at the ceiling");
        assertTrue(timeline.hasCapacityFor(0),
                "a flow that records nothing is never refused, even at the ceiling");
        assertFalse(timeline.hasCapacityFor(1),
                "a full timeline reports no room for one further event");
    }

    @Test
    void testAppendAtTheCeilingIsRefusedAndConsumesNoSequenceNumber() {
        //Filled in the tightest loop the timeline offers, because the ceiling itself is the
        //subject here and the per-event guarantees are each asserted once above.
        for (int event = timeline.count(); event < timeline.maxEvents(); event++) {
            timeline.append("ORDER", "ORD-000001", StateMachine.ORDER, "SUBMITTED", "ACCEPTED",
                    "stock", "all pre-trade controls passed", CLOCK);
        }

        List<AuditEvent> full = timeline.all();
        AuditEvent last = full.get(full.size() - 1);
        assertEquals(timeline.maxEvents(), last.getSequence(),
                "the ordinal of the last event at the ceiling");

        /* The gates the callers ask are reads that two threads can pass against the same headroom,
           so the ceiling has to be enforced here too - under the monitor that fixes the size - or
           it would be exact only when nothing raced. */
        CapacityExceededException refused = assertThrows(CapacityExceededException.class,
                () -> timeline.append("ORDER", "ORD-000002", StateMachine.ORDER, "SUBMITTED",
                        "ACCEPTED", "stock", "one event past the ceiling", CLOCK),
                "an append at the ceiling must be refused rather than recorded");
        assertTrue(refused.getMessage().contains("capacity"),
                "the refusal must say what ran out: " + refused.getMessage());

        /* A refused append takes no ordinal, which at the ceiling cannot be shown by a later
           successful append - there is none to be had - so the evidence is that nothing moved: the
           count stands and the last recorded event still carries the last ordinal issued. */
        assertEquals(timeline.maxEvents(), timeline.count(),
                "a refused append leaves the timeline at its ceiling");
        assertEquals(timeline.maxEvents(), timeline.all().size(),
                "every recorded event is still readable after the refusal");
        AuditEvent lastAfterRefusal = timeline.all().get(timeline.maxEvents() - 1);
        assertSame(last, lastAfterRefusal, "the last recorded event is untouched");
        assertEquals(last.getSequence(), lastAfterRefusal.getSequence(),
                "a refused append consumes no sequence number");
    }

    @Test
    void testPageNarrowsOnEachKeyItWasGivenAndBoundsTheResult() {
        AuditEvent orderEvent = timeline.append("ORDER", "ORD-000001", StateMachine.ORDER,
                "ACCEPTED", "EXECUTED", "stock", "execution EXE-000001", CLOCK);
        AuditEvent postTradeEvent = timeline.append("ORDER", "ORD-000001", StateMachine.POST_TRADE,
                "(none)", "PENDING_AFFIRMATION", "stock", "execution EXE-000001", CLOCK);
        timeline.append("ORDER", "ORD-000002", StateMachine.ORDER,
                "SUBMITTED", "REJECTED", "stock", "Symbol RSTRA is on the restricted list", CLOCK);
        timeline.append("EXCEPTION", "EXC-000001", StateMachine.EXCEPTION,
                "(none)", "OPEN", "seed", "safekeepingAccount differs", CLOCK);

        /* One walk answers every shape of the audit query: a key that was given narrows, a key
           that was not is ignored. That is what lets the audit endpoint stop projecting a
           single-key query from a copy of the whole timeline - the one structure here that has no
           entity ceiling to bound it. */
        assertEquals(2, timeline.page("ORDER", "ORD-000001", 0, 10).size(),
                "both keys narrow to one entity, across both of its state machines");
        assertEquals(3, timeline.page("ORDER", null, 0, 10).size(),
                "an absent entityId narrows on entityType alone");
        assertEquals(1, timeline.page(null, "EXC-000001", 0, 10).size(),
                "an absent entityType narrows on entityId alone");
        assertEquals(4, timeline.page("   ", "   ", 0, 0).size(),
                "a blank key is no key, so neither narrows and the page is uncut");

        List<AuditEvent> firstPage = timeline.page("ORDER", "ORD-000001", 0, 1);
        assertEquals(1, firstPage.size(), "a page of one holds one event");
        assertSame(orderEvent, firstPage.get(0), "the first page starts at the first match");
        assertSame(postTradeEvent, timeline.page("ORDER", "ORD-000001", 1, 1).get(0),
                "the offset is counted in matches, not in timeline positions");

        assertTrue(timeline.page(null, null, 10, 5).isEmpty(),
                "an offset past the end is an empty page, never a failure");
        assertTrue(timeline.page("ORDER", "ORD-999999", 0, 10).isEmpty(),
                "an entity with no history is an empty page");
        assertThrows(UnsupportedOperationException.class,
                () -> timeline.page(null, null, 0, 10).remove(0),
                "a page is as unmodifiable as every other read view here");
    }

    @Test
    void testAConfiguredEventCeilingIsReportedRefusedAtAndConsumesNoSequenceNumber() {
        /* Thirty events rather than the default hundred and fifty thousand, and the ceiling is
           reached in thirty appends instead of a hundred and fifty thousand: the ceiling is now a
           configured value, so the behaviour at it is testable at any size an operator may set.
           Thirty is the smallest legal audit ceiling, because CapacityLimits couples it to ten
           times the order ceiling and three orders is the least the seed set allows. */
        AuditTimeline bounded = new AuditTimeline(new CapacityLimits(CapacityLimits.MIN_MAX_ORDERS,
                CapacityLimits.MIN_MAX_SETTLEMENT_EXCEPTIONS, CapacityLimits.MIN_MAX_POSITIONS,
                SMALL_EVENT_CEILING));

        assertEquals(SMALL_EVENT_CEILING, bounded.maxEvents(),
                "the timeline reports the ceiling configuration sized it to");
        assertEquals(SMALL_EVENT_CEILING, bounded.eventHeadroom(),
                "an empty timeline has the whole ceiling as headroom");

        for (int event = 0; event < SMALL_EVENT_CEILING; event++) {
            bounded.append("ORDER", "ORD-000001", StateMachine.ORDER, "SUBMITTED", "ACCEPTED",
                    "stock", "all pre-trade controls passed", CLOCK);
            //Headroom falls by exactly one per append, which is what makes it a signal an operator
            //can act on before the first refusal rather than a figure that only moves at the end.
            assertEquals(SMALL_EVENT_CEILING - (event + 1), bounded.eventHeadroom(),
                    "headroom after " + (event + 1) + " events");
        }

        assertEquals(0, bounded.eventHeadroom(), "a full timeline reports no headroom");
        assertFalse(bounded.hasCapacityFor(1), "a full timeline reports no room for one more");

        AuditEvent last = bounded.all().get(SMALL_EVENT_CEILING - 1);
        assertEquals(SMALL_EVENT_CEILING, last.getSequence(),
                "the ordinal of the last event at the configured ceiling");

        CapacityExceededException refused = assertThrows(CapacityExceededException.class,
                () -> bounded.append("ORDER", "ORD-000002", StateMachine.ORDER, "SUBMITTED",
                        "ACCEPTED", "stock", "one event past the configured ceiling", CLOCK),
                "an append at a configured ceiling must be refused rather than recorded");
        //The refusal names the key an operator raises, because this text is the 503 body a caller
        //reads and it is the shortest path from the symptom to the remedy.
        assertTrue(refused.getMessage().contains("AUDIT_EVENT_CAPACITY"),
                "the refusal must name the key to raise: " + refused.getMessage());

        assertEquals(SMALL_EVENT_CEILING, bounded.count(),
                "a refused append leaves the timeline at its ceiling");
        assertSame(last, bounded.all().get(SMALL_EVENT_CEILING - 1),
                "the last recorded event is untouched");
        assertEquals(last.getSequence(), bounded.all().get(SMALL_EVENT_CEILING - 1).getSequence(),
                "a refused append consumes no sequence number");
    }

    @Test
    void testTheDefaultEventCeilingIsTheDocumentedFigure() {
        /* The README quotes 150,000 events and the operational runbook extrapolates its sizing
           figure from it, so the shipped default is pinned here: a deployment that supplies no
           AUDIT_EVENT_CAPACITY must behave exactly as this module did before the ceiling became
           configurable. */
        assertEquals(DOCUMENTED_EVENT_CEILING, timeline.maxEvents(),
                "a timeline built with no configuration carries the documented default");
        assertEquals(DOCUMENTED_EVENT_CEILING, CapacityLimits.DEFAULT_MAX_AUDIT_EVENTS,
                "the documented default of AUDIT_EVENT_CAPACITY");
    }
}
