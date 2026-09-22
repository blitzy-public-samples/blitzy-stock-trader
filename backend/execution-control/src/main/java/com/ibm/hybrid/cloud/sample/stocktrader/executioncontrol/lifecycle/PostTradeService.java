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
import java.util.Collections;
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

    /* Semantic lengths, held as code constants rather than configuration: an owner is a desk or a
       person and a resolution note is one sentence of workflow evidence, so these are what the
       fields mean rather than a tuning knob. Without them an analyst could store an arbitrarily
       large string on an exception and repeat it in the audit reason of every edge. */
    private static final int MAX_OWNER_LENGTH = 64;
    private static final int MAX_RESOLUTION_NOTE_LENGTH = 1024;

    //The most audit events one assign or one resolve can write: an assign records a single edge,
    //while resolving a still-open exception records OPEN -> ASSIGNED and ASSIGNED -> RESOLVED.
    private static final int EVENTS_PER_ASSIGN = 1;
    private static final int EVENTS_PER_RESOLVE = 2;

    //A settlement-ready step records two edges on two entities: the exception's
    //RESOLVED -> SETTLEMENT_READY and its parent order's EXCEPTION -> SETTLEMENT_READY.
    private static final int EVENTS_PER_SETTLEMENT_READY = 2;

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
        //Affirmation moves two entities in two independent maps - the order's post-trade state and
        //the settlement exception it may open - so the whole flow runs under the parent order's
        //lock. Without it a caller can reach the exception between those moves and clear it while
        //the order still reads PENDING_AFFIRMATION, which both files a false audit origin and
        //leaves this thread's own transition to fail on a state that has since moved.
        return orderStore.inOrderLock(order.getOrderId(), () -> affirm(order, actor));
    }

    private Order affirm(Order order, String actor) {
        //One instant for the whole call: the post-trade status stamped onto the order, the
        //exception's openedAt and the SLA deadline derived from it then agree instead of drifting
        //apart within one call. Audit timestamps are not among them - AuditTimeline stamps each
        //event as it appends it, under the same monitor that fixes that event's sequence number.
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
                PostTradeStatus from = current.getPostTradeStatus();
                LifecycleTransitions.assertLegal(from, PostTradeStatus.SETTLEMENT_READY);
                Order affirmed = current.withPostTradeStatus(PostTradeStatus.SETTLEMENT_READY, now);
                //fromState is read off the order as it stands rather than assumed: an event that
                //names a state the entity was not in is worse than no event at all, and the state
                //is only knowable inside the compute that replaces it.
                auditTimeline.append(ENTITY_ORDER, orderId, StateMachine.POST_TRADE, from.name(),
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

        Order excepted = requireOrder(orderId, orderStore.transition(orderId, current -> {
            PostTradeStatus from = current.getPostTradeStatus();
            LifecycleTransitions.assertLegal(from, PostTradeStatus.EXCEPTION);
            Order replacement = current.withPostTradeStatus(PostTradeStatus.EXCEPTION, now);
            auditTimeline.append(ENTITY_ORDER, orderId, StateMachine.POST_TRADE, from.name(),
                    PostTradeStatus.EXCEPTION.name(), actor,
                    "settlement exception " + exceptionId + " opened", clock);
            return replacement;
        }));

        //The origin event is written before the exception is stored, and storing it comes last of
        //everything: storing is what makes an exception workable, so by the time any caller can
        //assign or clear it, its parent order is already in EXCEPTION and its own origin edge is
        //already on the timeline. Published first instead, it could be assigned before its origin
        //was recorded, leaving that exception's history starting mid-chain for good.
        auditTimeline.append(ENTITY_EXCEPTION, exceptionId, StateMachine.EXCEPTION,
                LifecycleTransitions.NONE, ExceptionStatus.OPEN.name(), actor,
                "SSI mismatch on " + String.join(", ", mismatchedFieldNames(mismatches)), clock);
        exceptionStore.insert(opened);

        return excepted;
    }

    public SettlementException assign(String exceptionId, String owner, String actor) {
        String assignee = requireOwner(owner);
        requireLegalNext(exceptionId, ExceptionStatus.ASSIGNED);

        /* ASSIGNED -> ASSIGNED is a legal edge, so re-assignment is the one operation in this
           module that can be repeated without limit on an entity that already exists: it creates
           no order, no exception and no position, and each call appends an event. This check is
           what bounds it. It is made before the transition, so a refusal changes neither the
           exception nor the timeline. */
        if (!auditTimeline.hasCapacityFor(EVENTS_PER_ASSIGN)) {
            //Named here as in every other refusal: the message is the 503 body an analyst reads,
            //and the ceiling is operator-sized, so it carries the remedy rather than only the
            //diagnosis.
            throw new CapacityExceededException("the audit timeline is at capacity, so this "
                    + "assignment cannot be recorded; raise AUDIT_EVENT_CAPACITY or restart to "
                    + "clear");
        }

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
        //isBlank and strip rather than trim: trim only removes characters up to U+0020, so a note
        //of U+2003 (EM SPACE) would close an exception with no readable evidence against it, and
        //Unicode padding would survive into both the stored note and its audit reason.
        if (note == null || note.isBlank()) {
            throw new ValidationException(RESOLUTION_NOTE_REQUIRED);
        }

        String resolutionNote = note.strip();
        if (resolutionNote.length() > MAX_RESOLUTION_NOTE_LENGTH) {
            throw new ValidationException("resolutionNote must not exceed "
                    + MAX_RESOLUTION_NOTE_LENGTH + " characters");
        }

        requireResolvable(exceptionId);

        //Checked before the transition, with the two edges a resolve can write, so an exhausted
        //timeline refuses the whole call rather than closing an exception half-recorded.
        if (!auditTimeline.hasCapacityFor(EVENTS_PER_RESOLVE)) {
            throw new CapacityExceededException("the audit timeline is at capacity, so this "
                    + "resolution cannot be recorded; raise AUDIT_EVENT_CAPACITY or restart to "
                    + "clear");
        }

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
        //The parent is resolved before the lock because this operation is addressed by exception id
        //while the lock is per order, and an exception's parent never changes. An id that names
        //nothing is therefore a missing resource before anything is locked or written.
        String orderId =
                requireException(exceptionId, exceptionStore.find(exceptionId)).getOrderId();
        requireLegalNext(exceptionId, ExceptionStatus.SETTLEMENT_READY);

        //Checked with both edges before anything moves: this one call transitions the exception
        //and its parent order, so a timeline exhausted between them would leave the pair
        //disagreeing with no event naming why. Identity and legality are answered first so a
        //saturated timeline never masks a missing exception or an illegal edge.
        if (!auditTimeline.hasCapacityFor(EVENTS_PER_SETTLEMENT_READY)) {
            throw new CapacityExceededException("the audit timeline is at capacity, so this "
                    + "settlement-ready step cannot be recorded; raise AUDIT_EVENT_CAPACITY or "
                    + "restart to clear");
        }

        return withSla(orderStore.inOrderLock(orderId, () -> release(exceptionId, orderId, actor)));
    }

    //Clearing an exception and releasing its order move two entities in two independent maps, so
    //both run under the parent order's lock and the order is inspected before the exception is
    //touched: the maps share no rollback, so an exception left cleared against an order that could
    //not follow would be a break no later call could repair.
    private SettlementException release(String exceptionId, String orderId, String actor) {
        Instant now = clock.instant();
        PostTradeStatus parentState =
                requireOrder(orderId, orderStore.find(orderId)).getPostTradeStatus();

        //Exactly EXCEPTION, not merely a state the table would allow to reach SETTLEMENT_READY:
        //PENDING_AFFIRMATION -> SETTLEMENT_READY is the clean-affirmation edge and must not become
        //reachable by clearing an exception, which is a different event with a different meaning.
        if (parentState != PostTradeStatus.EXCEPTION) {
            throw new StateConflictException("Settlement exception " + exceptionId
                    + " cannot be marked settlement-ready while order " + orderId
                    + " is in post-trade state "
                    + ((parentState == null) ? LifecycleTransitions.NONE : parentState.name()));
        }

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

        requireOrder(orderId, orderStore.transition(orderId, current -> {
            PostTradeStatus from = current.getPostTradeStatus();
            LifecycleTransitions.assertLegal(from, PostTradeStatus.SETTLEMENT_READY);
            Order settling = current.withPostTradeStatus(PostTradeStatus.SETTLEMENT_READY, now);
            auditTimeline.append(ENTITY_ORDER, orderId, StateMachine.POST_TRADE, from.name(),
                    PostTradeStatus.SETTLEMENT_READY.name(), actor,
                    "settlement exception " + exceptionId + " marked settlement-ready", clock);
            return settling;
        }));

        return ready;
    }

    public List<SettlementException> list(ExceptionStatus status, String owner) {
        String ownerFilter = ownerFilter(owner);
        List<SettlementException> matches = new ArrayList<>();

        for (SettlementException exception : exceptionStore.list()) {
            if (!matchesFilter(exception, status, ownerFilter)) {
                continue;
            }
            matches.add(withSla(exception));
        }

        //No second sort: the store's snapshot is already ordered by exceptionId and this filter
        //preserves that order, so ordering stays the one thing the store owns. The new list exists
        //only because every survivor is handed back with its SLA projection applied.
        return matches;
    }

    /* Filtered first and paged second, so a page is a page of what the caller asked for rather
       than whatever survived a page of the whole store: paging before filtering would answer a
       status query with mostly empty pages. The filtered list is already in the store's
       exceptionId order, which is what makes consecutive pages contiguous. */
    public List<SettlementException> list(ExceptionStatus status, String owner, int offset,
            int limit) {
        List<SettlementException> matches = list(status, owner);
        int from = Math.min(Math.max(offset, 0), matches.size());
        int to = (limit <= 0) ? matches.size()
                : (int) Math.min((long) from + limit, matches.size());

        return Collections.unmodifiableList(new ArrayList<>(matches.subList(from, to)));
    }

    /* How many exceptions match the filter the paged read above was given, so a page can report
       the size of the collection it came from. Counted through the same predicate rather than
       through list().size(), because a total needs no SLA projection: list() builds a new
       exception object for every match to carry its computed ageing, and a count that asked for
       that would double the work of every paged read to learn a number. */
    public int count(ExceptionStatus status, String owner) {
        String ownerFilter = ownerFilter(owner);
        int matches = 0;

        for (SettlementException exception : exceptionStore.list()) {
            if (matchesFilter(exception, status, ownerFilter)) {
                matches++;
            }
        }
        return matches;
    }

    //isBlank and strip, matching requireOwner: the stored owner is stripped, so a filter that
    //trimmed only ASCII space would fail to match the very name it was given, and a filter of
    //Unicode whitespace alone would narrow to nothing instead of meaning "no filter".
    private static String ownerFilter(String owner) {
        return (owner == null || owner.isBlank()) ? null : owner.strip();
    }

    //One predicate for the paged read and the total alike: the two have to agree on what a match
    //is, or a page would be cut from one collection and counted against another.
    private static boolean matchesFilter(SettlementException exception, ExceptionStatus status,
            String ownerFilter) {
        if (status != null && exception.getStatus() != status) {
            return false;
        }
        return ownerFilter == null || ownerFilter.equals(exception.getOwner());
    }

    //Delegated rather than checked inside onExecuted: the headroom has to be established while
    //the submission can still be refused whole, which is before the order is affirmed at all.
    public boolean hasCapacityToOpenException() {
        return exceptionStore.hasCapacity();
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

    /* Paged as well as narrowed, unlike an order's history: an exception's timeline has no bound
       of its own, because every re-assignment adds an edge to the same entity. The store read
       still comes first, so an unknown id answers as a missing exception rather than as an empty
       page. */
    public List<AuditEvent> events(String exceptionId, int offset, int limit) {
        requireException(exceptionId, exceptionStore.find(exceptionId));
        return auditTimeline.page(ENTITY_EXCEPTION, exceptionId, offset, limit);
    }

    //The length of that history, for the paged response's total. The store read comes first here
    //too, so a count for an unknown id is a 404 rather than a zero that reads as "no history".
    public int eventCount(String exceptionId) {
        requireException(exceptionId, exceptionStore.find(exceptionId));
        return auditTimeline.count(ENTITY_EXCEPTION, exceptionId);
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

    /* isBlank and strip rather than trim: trim only removes characters up to U+0020, so an owner
       of U+2003 (EM SPACE) would pass as present and an exception would carry a break with nobody
       readably accountable for it. The stripped value is what this method returns, so the owner
       stored, audited and matched by the owner filter is one form of the same name. */
    private static String requireOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            throw new ValidationException(OWNER_REQUIRED);
        }

        String assignee = owner.strip();
        if (assignee.length() > MAX_OWNER_LENGTH) {
            throw new ValidationException("owner must not exceed " + MAX_OWNER_LENGTH
                    + " characters");
        }

        return assignee;
    }

    /* Identity and legality are settled before a workflow step asks for audit headroom, so an
       exhausted service still answers the question the caller asked: an unknown identifier is 404
       and an illegal edge is 409 whatever the ceilings hold, rather than a 503 that hides both.
       These two pre-checks are advisory - the operator the store runs inside its compute asserts
       the same edge again and remains the authority, so a concurrent transition between the peek
       and the compute is still caught there, on the entity itself. */
    private void requireLegalNext(String exceptionId, ExceptionStatus target) {
        SettlementException current = requireException(exceptionId, exceptionStore.find(exceptionId));
        LifecycleTransitions.assertLegal(current.getStatus(), target);
    }

    private void requireResolvable(String exceptionId) {
        SettlementException current = requireException(exceptionId, exceptionStore.find(exceptionId));

        //OPEN is deliberately not judged against RESOLVED here: resolving an unassigned exception
        //with an owner is the legal composite path, whose two edges are asserted together inside
        //the compute. Every other status is judged, which is what keeps a repeated resolution and
        //a settlement-ready exception answering 409 rather than 503.
        if (current.getStatus() != ExceptionStatus.OPEN) {
            LifecycleTransitions.assertLegal(current.getStatus(), ExceptionStatus.RESOLVED);
        }
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
