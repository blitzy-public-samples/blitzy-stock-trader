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

//Collections
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

//Concurrency
import java.util.concurrent.atomic.AtomicLong;

//CDI 4.0
import jakarta.enterprise.context.ApplicationScoped;


/** Append-only, in-memory timeline of every state transition this service records */
@ApplicationScoped
public class AuditTimeline {

    /* Appending is the only operation offered: nothing here amends or discards a recorded
       event, nothing hands out the live list, and AuditEvent is itself immutable. The
       timeline is this service's audit record, and a record that can be rewritten after
       the fact is not an audit record. That absence is the guarantee. */
    private final List<AuditEvent> events = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    /* The ordinal, the timestamp and the list position are all fixed under this method's
       monitor, so the three can never disagree: an event's ordinal always matches its place
       in the list, and no reader can observe an event out of order or half-recorded. The
       clock arrives as a parameter because this bean is constructed with no dependencies,
       while the service's single injected UTC clock stays the one source of time - tests
       pin it with Clock.fixed(...) and compare timestamps exactly. */
    public synchronized AuditEvent append(String entityType, String entityId, StateMachine stateMachine,
                                          String fromState, String toState, String actor, String reason,
                                          Clock clock) {
        long next = sequence.incrementAndGet();
        Instant timestamp = clock.instant();
        AuditEvent event = new AuditEvent(String.format("EVT-%06d", next), next, timestamp, entityType,
                entityId, stateMachine, fromState, toState, actor, reason);
        events.add(event);
        return event;
    }

    /* Both reads answer with an unmodifiable view over a copy taken under the same monitor as
       append: a caller can neither reach back into the timeline through the list it was given
       nor observe that list mid-append. Matching is on entity identity alone - one order's
       POST_TRADE events carry the same entityType and entityId as its ORDER events, so
       narrowing by state machine here would hide half of that order's history. */
    public synchronized List<AuditEvent> forEntity(String entityType, String entityId) {
        List<AuditEvent> matches = new ArrayList<>();
        for (AuditEvent event : events) {
            if (Objects.equals(entityType, event.getEntityType())
                    && Objects.equals(entityId, event.getEntityId())) {
                matches.add(event);
            }
        }
        return Collections.unmodifiableList(matches);
    }

    public synchronized List<AuditEvent> all() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }
}
