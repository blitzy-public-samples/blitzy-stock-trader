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

package com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.health;

import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.audit.AuditTimeline;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.dao.OrderStore;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.dao.ReferenceDataStore;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.dao.SettlementExceptionStore;

//CDI 4.0
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

//mpHealth 4.0
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;


/** Reports each admission ceiling, its remaining headroom and whether the service is still accepting orders */
@ApplicationScoped
public class AdmissionCapacityReport {

    //The state names the health data carries. ACCEPTING and SATURATED rather than a boolean,
    //because a consumer alerts on the transition and a string is what reads in an alert rule.
    private static final String ACCEPTING = "ACCEPTING";
    private static final String SATURATED = "SATURATED";

    /* The most audit events one submission can write - SUBMITTED, then either REJECTED or ACCEPTED
       and EXECUTED, then the post-trade PENDING_AFFIRMATION and either SETTLEMENT_READY or an
       exception's OPEN plus the order's EXCEPTION edge - and therefore the headroom below which
       the timeline, not the stores, is what refuses the next order. It matches the figure
       OrderLifecycleService gates on, so this report turns SATURATED exactly when that gate would
       refuse rather than one submission early or late. */
    private static final int EVENTS_PER_SUBMISSION = 6;

    private @Inject OrderStore orderStore;
    private @Inject SettlementExceptionStore settlementExceptionStore;
    private @Inject ReferenceDataStore referenceDataStore;
    private @Inject AuditTimeline auditTimeline;


    /* Both probes report the same ten data keys through this one method, so "saturated" is defined
       once rather than drifting between two definitions of it. The ceilings are read off the
       injected stores, which is what keeps the probes free of any configuration read of their own:
       a store already holds the ceiling configuration sized it to. */
    public HealthCheckResponseBuilder describe(HealthCheckResponseBuilder builder) {
        HealthCheckResponseBuilder described = builder
                .withData("orderCapacity", orderStore.maxOrders())
                .withData("orderHeadroom", orderStore.orderHeadroom())
                .withData("exceptionCapacity", settlementExceptionStore.maxExceptions())
                .withData("exceptionHeadroom", settlementExceptionStore.exceptionHeadroom())
                .withData("positionCapacity", referenceDataStore.maxPositions())
                .withData("positionHeadroom", referenceDataStore.positionHeadroom())
                .withData("auditEvents", auditTimeline.count())
                .withData("auditEventCapacity", auditTimeline.maxEvents())
                .withData("auditEventHeadroom", auditTimeline.eventHeadroom());

        return described.withData("admission", isAcceptingOrders() ? ACCEPTING : SATURATED);
    }

    /* Saturated when any structure that gates admission has run out: the order store, the
       exception store an execution may need, the timeline that has to hold one submission's edges,
       or the position store. The position store is the one partial case - an order in a holding
       the client already has stays admissible at that ceiling - and it is still reported as
       saturated, because a caller submitting a symbol the client does not yet hold is refused and
       that is the state an operator has to act on. */
    public boolean isAcceptingOrders() {
        return orderStore.orderHeadroom() > 0
                && settlementExceptionStore.exceptionHeadroom() > 0
                && auditTimeline.eventHeadroom() >= EVENTS_PER_SUBMISSION
                && referenceDataStore.positionHeadroom() > 0;
    }
}
