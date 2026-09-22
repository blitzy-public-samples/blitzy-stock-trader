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
//The module's one capacity type, which is what lets one mapper answer 503 wherever a ceiling is
//reached; the class it names is a leaf that imports nothing, so no dependency on lifecycle
//behaviour comes with it.
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.lifecycle.CapacityExceededException;

//Time (java.time)
import java.time.Clock;
import java.time.Instant;

//Collections
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

//Concurrency
import java.util.concurrent.atomic.AtomicLong;

//CDI 4.0
import jakarta.enterprise.context.ApplicationScoped;


/** Append-only, in-memory timeline of every state transition this service records */
@ApplicationScoped
public class AuditTimeline {

    /* The ceiling on how many events this process will hold. It sits strictly above the worst case
       the entity ceilings imply - the README carries that arithmetic - because the record must
       never be the thing that refuses a state change the stores would have accepted: a fully
       worked estate has to be able to record its own last transitions, and an order that moved
       with its transition unrecorded is exactly the outcome this timeline exists to make
       impossible. Admission is therefore refused upstream, while headroom remains, and this
       ceiling is reached only by a caller that got past every gate. */
    public static final int MAX_EVENTS = 150_000;

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
       pin it with Clock.fixed(...) and compare timestamps exactly.
       The event id is formatted under Locale.ROOT rather than the JVM's default, because %d
       follows the default formatting locale: under a non-Latin numbering system EVT-000001
       would come out in localized digits and stop being an ASCII rendering of the sequence
       that readers, the audit endpoint and the tests can match literally. */
    public synchronized AuditEvent append(String entityType, String entityId, StateMachine stateMachine,
                                          String fromState, String toState, String actor, String reason,
                                          Clock clock) {
        /* The ceiling is enforced here and not only by the gates the callers ask, because this
           method holds the monitor that fixes the size: a check taken under it cannot be passed by
           two concurrent appends the way a read taken outside it can. It refuses before the
           ordinal is taken, so a refused append consumes no sequence number and leaves no gap in
           the record that a reader would have to explain. */
        if (events.size() >= MAX_EVENTS) {
            throw new CapacityExceededException("the audit timeline is at capacity, so no further "
                    + "event can be recorded");
        }

        long next = sequence.incrementAndGet();
        Instant timestamp = clock.instant();
        AuditEvent event = new AuditEvent(String.format(Locale.ROOT, "EVT-%06d", next), next,
                timestamp, entityType, entityId, stateMachine, fromState, toState, actor, reason);
        events.add(event);
        return event;
    }

    /* Asked before a flow starts, with the number of edges that flow can write at most, so a
       submission that would run out of timeline part-way through is refused before it has moved
       anything. Read under the same monitor as append, because a size read outside it could be
       taken mid-append. This check and count decide *which* refusal a caller gets - a whole flow
       declined at its first statement rather than a state change abandoned mid-way - while append
       is the authority that cannot be passed: the headroom they report is not reserved, so two
       flows may pass them against the same headroom and only append is exact. */
    public synchronized boolean hasCapacityFor(int eventCount) {
        int requested = Math.max(eventCount, 0);
        return (long) events.size() + requested <= MAX_EVENTS;
    }

    public synchronized int count() {
        return events.size();
    }

    /* Both reads answer with an unmodifiable view over a copy taken under the same monitor as
       append: a caller can neither reach back into the timeline through the list it was given
       nor observe that list mid-append. Insertion order is sequence order, because append fixes
       the ordinal and the list position together under that monitor, so this timeline owns the
       order of what it hands out and a reader returns it as received rather than sorting it
       again. Matching is on entity identity alone - one order's POST_TRADE events carry the same
       entityType and entityId as its ORDER events, so narrowing by state machine here would hide
       half of that order's history. */
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

    /* The bounded read every growing audit response comes through: narrowing and paging happen in
       this one walk, so only the page is ever copied - the timeline is the one structure here that
       grows without an entity to bound it, and a full copy taken merely to discard all but a page
       of it would be the allocation this method exists to avoid. A null or blank key does not
       narrow on that key, which is what lets one method answer the both-key, single-key and
       unfiltered queries without the caller projecting from all(). Insertion order is sequence
       order, as in every other read here. */
    public synchronized List<AuditEvent> page(String entityType, String entityId, int offset,
            int limit) {
        boolean narrowByType = (entityType != null) && !entityType.isBlank();
        boolean narrowById = (entityId != null) && !entityId.isBlank();
        int skip = Math.max(offset, 0);
        //A non-positive limit means "do not cut the page", matching the stores' paged reads: the
        //REST layer clamps a caller's limit, and an in-process caller does not restate it.
        int pageSize = (limit <= 0) ? Integer.MAX_VALUE : limit;

        List<AuditEvent> matches = new ArrayList<>();
        int skipped = 0;
        for (AuditEvent event : events) {
            if (narrowByType && !entityType.equals(event.getEntityType())) {
                continue;
            }
            if (narrowById && !entityId.equals(event.getEntityId())) {
                continue;
            }
            if (skipped < skip) {
                skipped++;
                continue;
            }
            matches.add(event);
            if (matches.size() >= pageSize) {
                break;
            }
        }

        return Collections.unmodifiableList(matches);
    }
}
