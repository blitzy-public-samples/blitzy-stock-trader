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

package com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json;

import java.time.Instant;

/** An immutable, append-only audit entry for one state transition */
public class AuditEvent {
    /* No setter and no copy method: AuditTimeline is append-only, so a recorded event is
       never amended. It also never derives its own sequence or timestamp - the timeline's
       synchronized append assigns both together, which is what keeps sequence order and
       timestamp order in agreement and lets tests pin a fixed Clock. */
    private final String eventId;
    private final long sequence;
    private final Instant timestamp;
    private final String entityType;
    private final String entityId;
    private final StateMachine stateMachine;
    /* String, not an enum: the two state fields span the order, post-trade and exception
       state machines plus the literal "(none)" origin of each machine's first edge, and no
       single enum holds all four cases. Callers pass Enum.name() or "(none)". */
    private final String fromState;
    private final String toState;
    private final String actor;
    private final String reason;
    private final boolean simulated;
    private final String disclaimer;

    public AuditEvent(String eventId, long sequence, Instant timestamp, String entityType,
            String entityId, StateMachine stateMachine, String fromState, String toState,
            String actor, String reason) {
        this.eventId = eventId;
        this.sequence = sequence;
        this.timestamp = timestamp;
        this.entityType = entityType;
        this.entityId = entityId;
        this.stateMachine = stateMachine;
        this.fromState = fromState;
        this.toState = toState;
        this.actor = actor;
        this.reason = reason;
        this.simulated = true;
        this.disclaimer = SimulationLabels.DISCLAIMER;
    }

    public String getEventId() {
        return eventId;
    }

    public long getSequence() {
        return sequence;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public StateMachine getStateMachine() {
        return stateMachine;
    }

    public String getFromState() {
        return fromState;
    }

    public String getToState() {
        return toState;
    }

    public String getActor() {
        return actor;
    }

    public String getReason() {
        return reason;
    }

    public boolean isSimulated() {
        return simulated;
    }

    public String getDisclaimer() {
        return disclaimer;
    }
}
