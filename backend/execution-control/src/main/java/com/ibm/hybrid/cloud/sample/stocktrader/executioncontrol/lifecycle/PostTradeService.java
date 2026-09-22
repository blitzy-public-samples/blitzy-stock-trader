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
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.PostTradeStatus;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.ResolveRequest;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.SettlementException;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.SettlementInstruction;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.StateMachine;

//Time (java.time)
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

//Collections
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

//CDI 4.0
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;


/** Runs the post-trade affirmation flow and the settlement-exception workflow for executed orders */
@ApplicationScoped
public class PostTradeService {

    //The audit entityType an event is filed under, which is not the same axis as
    //StateMachine: one order carries both its ORDER and its POST_TRADE events under the
    //entityType ORDER, and POST_TRADE is no entity anyone can ask for.
    private static final String ENTITY_ORDER = "ORDER";
    private static final String ENTITY_EXCEPTION = "EXCEPTION";

    private static final String SSI_MISMATCH = "SSI_MISMATCH";

    //Published values, not internal labels: these are the Java property names of
    //SettlementInstruction, so re-casing one here changes the exception body an analyst reads.
    private static final String FIELD_CUSTODIAN_BIC = "custodianBic";
    private static final String FIELD_SAFEKEEPING_ACCOUNT = "safekeepingAccount";
    private static final String FIELD_CASH_ACCOUNT = "cashAccount";
    private static final String FIELD_PLACE_OF_SETTLEMENT = "placeOfSettlement";
    private static final int FIELD_COUNT = 4;

    private static final String REASON_SSI_AFFIRMED = "SSI affirmed (simulated)";
    private static final String REASON_MARKED_SETTLEMENT_READY = "marked settlement-ready";
    private static final String OWNER_REQUIRED = "owner is required";
    private static final String RESOLUTION_NOTE_REQUIRED = "resolutionNote is required";

    private OrderStore orderStore;
    private SettlementExceptionStore exceptionStore;
    private ReferenceDataStore referenceData;
    private AuditTimeline auditTimeline;
    private Clock clock;
    private ControlLimits limits;


    @Inject
    public PostTradeService(OrderStore orderStore, SettlementExceptionStore exceptionStore,
            ReferenceDataStore referenceData, AuditTimeline auditTimeline, Clock clock,
            ControlLimits limits) {
        this.orderStore = orderStore;
        this.exceptionStore = exceptionStore;
        this.referenceData = referenceData;
        this.auditTimeline = auditTimeline;
        this.clock = clock;
        this.limits = limits;
    }

    //Exists only so CDI can generate the @ApplicationScoped client proxy, which requires a
    //non-private no-arg constructor; no application code calls it and the proxy reads no field.
    protected PostTradeService() {
    }

    public Order onExecuted(Order order, String actor) {
        //One instant for the whole call: the affirmation event, the exception's openedAt, its
        //SLA deadline and the order's own post-trade event then carry the same timestamp, which
        //is what makes the timeline readable and lets a fixed test clock compare it exactly.
        Instant now = clock.instant();
        String orderId = order.getOrderId();
        Execution execution = order.getExecution();

        requireOrder(orderId, orderStore.transition(orderId, current -> {
            //assertLegal runs before the replacement is built and before the event is appended,
            //so a refused transition leaves both the order and the timeline untouched.
            LifecycleTransitions.assertLegal(current.getPostTradeStatus(),
                    PostTradeStatus.PENDING_AFFIRMATION);
            Order affirming = current.withPostTradeStatus(PostTradeStatus.PENDING_AFFIRMATION, now);
            auditTimeline.append(ENTITY_ORDER, orderId, StateMachine.POST_TRADE,
                    LifecycleTransitions.NONE, PostTradeStatus.PENDING_AFFIRMATION.name(), actor,
                    "awaiting affirmation of " + execution.getExecutionId(), clock);
            return affirming;
        }));

        ClientAccount client = referenceData.findClient(order.getClientId());
        List<MismatchField> mismatches = compareSettlementInstructions(client);

        if (mismatches.isEmpty()) {
            return requireOrder(orderId, orderStore.transition(orderId, current -> {
                LifecycleTransitions.assertLegal(current.getPostTradeStatus(),
                        PostTradeStatus.SETTLEMENT_READY);
                Order affirmed = current.withPostTradeStatus(PostTradeStatus.SETTLEMENT_READY, now);
                auditTimeline.append(ENTITY_ORDER, orderId, StateMachine.POST_TRADE,
                        PostTradeStatus.PENDING_AFFIRMATION.name(),
                        PostTradeStatus.SETTLEMENT_READY.name(), actor, REASON_SSI_AFFIRMED, clock);
                return affirmed;
            }));
        }

        String exceptionId = exceptionStore.nextExceptionId();
        SettlementException opened = new SettlementException(exceptionId, SSI_MISMATCH, orderId,
                execution.getExecutionId(), order.getClientId(), client.getClientName(),
                order.getSymbol(), order.getSide(), order.getQuantity(), order.getNotional(),
                execution.getFillPrice(), execution.getExecutedAt(), execution.getVenue(),
                mismatches, now, now.plus(Duration.ofHours(limits.getExceptionSlaHours())),
                order.getSource());

        //The origin edge is asserted through the same table every later edge goes through, so
        //the one state machine has one definition of what may happen to an exception.
        LifecycleTransitions.assertLegal(null, ExceptionStatus.OPEN);
        exceptionStore.insert(opened);
        auditTimeline.append(ENTITY_EXCEPTION, exceptionId, StateMachine.EXCEPTION,
                LifecycleTransitions.NONE, ExceptionStatus.OPEN.name(), actor,
                "SSI mismatch on " + String.join(", ", mismatchedFieldNames(mismatches)), clock);

        return requireOrder(orderId, orderStore.transition(orderId, current -> {
            LifecycleTransitions.assertLegal(current.getPostTradeStatus(), PostTradeStatus.EXCEPTION);
            Order excepted = current.withPostTradeStatus(PostTradeStatus.EXCEPTION, now);
            auditTimeline.append(ENTITY_ORDER, orderId, StateMachine.POST_TRADE,
                    PostTradeStatus.PENDING_AFFIRMATION.name(), PostTradeStatus.EXCEPTION.name(),
                    actor, "settlement exception " + exceptionId + " opened", clock);
            return excepted;
        }));
    }

    public SettlementException assign(String exceptionId, String owner, String actor) {
        String assignee = requireOwner(owner);
        Instant now = clock.instant();

        SettlementException assigned = exceptionStore.transition(exceptionId, current -> {
            ExceptionStatus from = current.getStatus();
            LifecycleTransitions.assertLegal(from, ExceptionStatus.ASSIGNED);
            SettlementException replacement = current.withAssigned(assignee, now);
            auditTimeline.append(ENTITY_EXCEPTION, exceptionId, StateMachine.EXCEPTION, from.name(),
                    ExceptionStatus.ASSIGNED.name(), actor, "assigned to " + assignee, clock);
            return replacement;
        });

        return withSla(requireException(exceptionId, assigned));
    }

    public SettlementException resolve(String exceptionId, ResolveRequest request, String actor) {
        String note = (request == null) ? null : request.getResolutionNote();
        if (note == null || note.trim().isEmpty()) {
            throw new ValidationException(RESOLUTION_NOTE_REQUIRED);
        }

        String resolutionNote = note.trim();
        String suppliedOwner = request.getOwner();
        Instant now = clock.instant();

        SettlementException resolved = exceptionStore.transition(exceptionId, current -> {
            SettlementException assigned = current;

            if (current.getStatus() == ExceptionStatus.OPEN) {
                //An unassigned exception is assigned and resolved in this one compute, both edges
                //recorded, because the workflow has no direct OPEN -> RESOLVED edge: an exception
                //must never close with no recorded owner, and no concurrent caller may interleave
                //a different owner or note between the two.
                String assignee = requireOwner(suppliedOwner);
                LifecycleTransitions.assertLegal(ExceptionStatus.OPEN, ExceptionStatus.ASSIGNED);
                assigned = current.withAssigned(assignee, now);
                auditTimeline.append(ENTITY_EXCEPTION, exceptionId, StateMachine.EXCEPTION,
                        ExceptionStatus.OPEN.name(), ExceptionStatus.ASSIGNED.name(), actor,
                        "assigned to " + assignee, clock);
            }

            //An owner in the body is deliberately ignored once the exception is already assigned:
            //re-assignment has its own endpoint and its own ASSIGNED -> ASSIGNED event, and a
            //second route to the same change would leave an owner altered with no event naming it.
            ExceptionStatus from = assigned.getStatus();
            LifecycleTransitions.assertLegal(from, ExceptionStatus.RESOLVED);
            SettlementException replacement = assigned.withResolved(resolutionNote, now);
            auditTimeline.append(ENTITY_EXCEPTION, exceptionId, StateMachine.EXCEPTION, from.name(),
                    ExceptionStatus.RESOLVED.name(), actor, resolutionNote, clock);
            return replacement;
        });

        return withSla(requireException(exceptionId, resolved));
    }

    public SettlementException markSettlementReady(String exceptionId, String actor) {
        Instant now = clock.instant();

        SettlementException ready = requireException(exceptionId,
                exceptionStore.transition(exceptionId, current -> {
                    ExceptionStatus from = current.getStatus();
                    LifecycleTransitions.assertLegal(from, ExceptionStatus.SETTLEMENT_READY);
                    SettlementException replacement = current.withSettlementReady(now);
                    auditTimeline.append(ENTITY_EXCEPTION, exceptionId, StateMachine.EXCEPTION,
                            from.name(), ExceptionStatus.SETTLEMENT_READY.name(), actor,
                            REASON_MARKED_SETTLEMENT_READY, clock);
                    return replacement;
                }));

        String orderId = ready.getOrderId();
        requireOrder(orderId, orderStore.transition(orderId, current -> {
            LifecycleTransitions.assertLegal(current.getPostTradeStatus(),
                    PostTradeStatus.SETTLEMENT_READY);
            Order settling = current.withPostTradeStatus(PostTradeStatus.SETTLEMENT_READY, now);
            auditTimeline.append(ENTITY_ORDER, orderId, StateMachine.POST_TRADE,
                    PostTradeStatus.EXCEPTION.name(), PostTradeStatus.SETTLEMENT_READY.name(),
                    actor, "settlement exception " + exceptionId + " marked settlement-ready",
                    clock);
            return settling;
        }));

        return withSla(ready);
    }

    public List<SettlementException> list(ExceptionStatus status, String owner) {
        String ownerFilter = (owner == null || owner.trim().isEmpty()) ? null : owner.trim();
        List<SettlementException> matches = new ArrayList<>();

        for (SettlementException exception : exceptionStore.list()) {
            if (status != null && exception.getStatus() != status) {
                continue;
            }
            if (ownerFilter != null && !ownerFilter.equals(exception.getOwner())) {
                continue;
            }
            matches.add(withSla(exception));
        }

        //No second sort: the store's snapshot is already ordered by exceptionId and this filter
        //preserves that order, so ordering stays the one thing the store owns. The new list exists
        //only because every survivor is handed back with its SLA projection applied.
        return matches;
    }

    public SettlementException get(String exceptionId) {
        return withSla(requireException(exceptionId, exceptionStore.find(exceptionId)));
    }

    public List<AuditEvent> events(String exceptionId) {
        //Read through the store first so an unknown id answers as a missing exception rather than
        //as an exception that happens to have no history.
        requireException(exceptionId, exceptionStore.find(exceptionId));
        return auditTimeline.forEntity(ENTITY_EXCEPTION, exceptionId);
    }

    private static List<MismatchField> compareSettlementInstructions(ClientAccount client) {
        SettlementInstruction firm = client.getFirmSettlementInstruction();
        SettlementInstruction counterparty = client.getCounterpartySettlementInstruction();
        List<MismatchField> mismatches = new ArrayList<>(FIELD_COUNT);

        addMismatch(mismatches, FIELD_CUSTODIAN_BIC, firm.getCustodianBic(),
                counterparty.getCustodianBic());
        addMismatch(mismatches, FIELD_SAFEKEEPING_ACCOUNT, firm.getSafekeepingAccount(),
                counterparty.getSafekeepingAccount());
        addMismatch(mismatches, FIELD_CASH_ACCOUNT, firm.getCashAccount(),
                counterparty.getCashAccount());
        addMismatch(mismatches, FIELD_PLACE_OF_SETTLEMENT, firm.getPlaceOfSettlement(),
                counterparty.getPlaceOfSettlement());

        return mismatches;
    }

    private static void addMismatch(List<MismatchField> mismatches, String field, String firmValue,
            String counterpartyValue) {
        if (!Objects.equals(firmValue, counterpartyValue)) {
            mismatches.add(new MismatchField(field, firmValue, counterpartyValue));
        }
    }

    private static List<String> mismatchedFieldNames(List<MismatchField> mismatches) {
        List<String> names = new ArrayList<>(mismatches.size());
        for (MismatchField mismatch : mismatches) {
            names.add(mismatch.getField());
        }
        return names;
    }

    //Ageing is derived on every read and never written back: stored as state it would be a value
    //that silently went stale the moment the clock moved past it.
    private SettlementException withSla(SettlementException exception) {
        ExceptionStatus status = exception.getStatus();
        //The reference instant stops at resolution time for a closed exception, so a timely
        //resolution freezes both values and an exception that was resolved inside its SLA never
        //reports itself breached later.
        boolean closed = (status == ExceptionStatus.RESOLVED)
                || (status == ExceptionStatus.SETTLEMENT_READY);
        Instant resolvedAt = exception.getResolvedAt();
        Instant reference = (closed && resolvedAt != null) ? resolvedAt : clock.instant();

        long ageHours = Duration.between(exception.getOpenedAt(), reference).toHours();
        boolean slaBreached = reference.isAfter(exception.getSlaDeadline());

        return exception.withSlaSnapshot(ageHours, slaBreached);
    }

    private static String requireOwner(String owner) {
        if (owner == null || owner.trim().isEmpty()) {
            throw new ValidationException(OWNER_REQUIRED);
        }
        return owner.trim();
    }

    //Both stores answer a transition on an absent key with null, leaving it to the caller to say
    //what an unknown identifier means to the request being served: here it is a missing resource.
    private static SettlementException requireException(String exceptionId,
            SettlementException exception) {
        if (exception == null) {
            throw new EntityNotFoundException("No settlement exception exists with id "
                    + exceptionId);
        }
        return exception;
    }

    private static Order requireOrder(String orderId, Order order) {
        if (order == null) {
            throw new EntityNotFoundException("No order exists with id " + orderId);
        }
        return order;
    }
}
