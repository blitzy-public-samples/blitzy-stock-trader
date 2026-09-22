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

import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.AuditEvent;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.StateMachine;

//Time (java.time)
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

//Collections
import java.util.List;
import java.util.Locale;

//JUnit 5 Jupiter
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

        //An entity with no history is an empty timeline, never a failure.
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
}
