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
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.control.PreTradeControlService;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.dao.OrderStore;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.dao.ReferenceDataStore;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.AuditEvent;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.ControlResult;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.Execution;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.Order;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.OrderRequest;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.OrderStatus;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.Position;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.RecordSource;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.StateMachine;

//Arbitrary-precision arithmetic
import java.math.BigDecimal;

//Time (java.time)
import java.time.Clock;
import java.time.Instant;

//Collections
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

//CDI 4.0
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

//mpTelemetry 2.1
import io.opentelemetry.instrumentation.annotations.WithSpan;


/** Runs the submit, pre-trade control and simulated execution lifecycle of one institutional order */
@ApplicationScoped
public class OrderLifecycleService {

    //The audit entityType this service files its events under, which is a different axis from
    //StateMachine: an order's ORDER and POST_TRADE events share the entityType ORDER.
    private static final String ORDER = "ORDER";

    private static final String BUY = "BUY";
    private static final String SELL = "SELL";
    private static final String REASON_SEPARATOR = "; ";
    private static final String REASON_CONTROLS_PASSED = "all pre-trade controls passed";

    private OrderStore orderStore;
    private ReferenceDataStore referenceData;
    private PreTradeControlService controls;
    private PostTradeService postTrade;
    private AuditTimeline auditTimeline;
    private Clock clock;


    @Inject
    public OrderLifecycleService(OrderStore orderStore, ReferenceDataStore referenceData,
            PreTradeControlService controls, PostTradeService postTrade, AuditTimeline auditTimeline,
            Clock clock) {
        this.orderStore = orderStore;
        this.referenceData = referenceData;
        this.controls = controls;
        this.postTrade = postTrade;
        this.auditTimeline = auditTimeline;
        this.clock = clock;
    }

    //Exists only so CDI can generate the @ApplicationScoped client proxy, which requires a
    //non-private no-arg constructor; no application code calls it and the proxy reads no field.
    protected OrderLifecycleService() {
    }

    //The span is declared on the overload a REST request enters through, because @WithSpan is
    //realised by a CDI interceptor: the self-invocation below leaves the proxy behind, so the
    //same annotation on the three-argument method would have nothing to intercept.
    @WithSpan
    public Order submit(OrderRequest request, String actor) {
        return submit(request, actor, RecordSource.API);
    }

    public Order submit(OrderRequest request, String actor, RecordSource source) {
        //One instant for the whole submission, so the order's timestamps, the simulated fill's
        //executedAt and every audit event agree instead of drifting apart within one request.
        Instant now = clock.instant();

        String clientOrderId = required((request == null) ? null : request.getClientOrderId(),
                "clientOrderId");
        String clientId = required(request.getClientId(), "clientId");
        String symbol = canonical(required(request.getSymbol(), "symbol"));

        String side = canonical(request.getSide());
        if (!BUY.equals(side) && !SELL.equals(side)) {
            throw new ValidationException("side must be BUY or SELL");
        }

        Long requestedQuantity = request.getQuantity();
        if (requestedQuantity == null) {
            throw new ValidationException("quantity is required");
        }
        long quantity = requestedQuantity.longValue();
        if (quantity <= 0L) {
            throw new ValidationException("quantity must be greater than zero");
        }

        BigDecimal limitPrice = request.getLimitPrice();
        if (limitPrice == null) {
            throw new ValidationException("limitPrice is required");
        }
        if (limitPrice.signum() <= 0) {
            throw new ValidationException("limitPrice must be greater than zero");
        }

        //An unknown client is a bad field in a submitted body rather than a missing addressed
        //resource, so it answers 400; EntityNotFoundException stays reserved for an identifier
        //the caller asked for by path.
        if (referenceData.findClient(clientId) == null) {
            throw new ValidationException("clientId " + clientId + " is not a known client");
        }

        String orderId = orderStore.nextOrderId();
        //The client order id is claimed before an order object exists, so exactly one of any
        //number of concurrent submissions carrying the same key proceeds and every loser writes
        //neither an order nor an audit event.
        if (!orderStore.reserveClientOrderId(clientOrderId, orderId)) {
            throw new StateConflictException(
                    "clientOrderId " + clientOrderId + " has already been submitted");
        }

        Order submitted = new Order(orderId, clientOrderId, clientId, symbol, side, quantity,
                limitPrice, actor, now, source);

        //The origin edge is asserted through the same table every later edge goes through, so no
        //order state reaches the store or the timeline without having passed assertLegal.
        LifecycleTransitions.assertLegal(null, OrderStatus.SUBMITTED);
        orderStore.insert(submitted);
        auditTimeline.append(ORDER, orderId, StateMachine.ORDER, LifecycleTransitions.NONE,
                OrderStatus.SUBMITTED.name(), actor,
                "order received for clientOrderId " + clientOrderId, clock);

        /* Evaluating the controls and applying the fill are one atomic step per client and symbol:
           a second order in the same symbol has to be valued against the position the first one
           left behind rather than a stale copy, and a rejected order has to leave that position
           exactly as it was. Nothing outside this operator reads or writes the position. */
        ReferenceDataStore.FillDecision decision =
                referenceData.evaluateAndFill(clientId, symbol, position -> {
                    List<ControlResult> outcomes = controls.evaluate(submitted, position);
                    if (!outcomes.stream().allMatch(ControlResult::isPassed)) {
                        return ReferenceDataStore.FillDecision.unchanged(outcomes);
                    }
                    long resulting = ((position == null) ? 0L : position.getQuantity())
                            + (BUY.equals(side) ? quantity : -quantity);
                    return ReferenceDataStore.FillDecision.filled(
                            new Position(clientId, symbol, resulting, limitPrice), outcomes);
                });

        List<ControlResult> results = decision.getControlResults();
        String rejectionReason = results.stream()
                .filter(result -> !result.isPassed())
                .map(ControlResult::getReason)
                .collect(Collectors.joining(REASON_SEPARATOR));

        if (!rejectionReason.isEmpty()) {
            return requireOrder(orderId, orderStore.transition(orderId, current -> {
                //assertLegal runs before the replacement is built and before the event is
                //appended, so a refused transition leaves both the order and the timeline untouched.
                LifecycleTransitions.assertLegal(current.getStatus(), OrderStatus.REJECTED);
                Order rejected = current.withControlResults(results, rejectionReason)
                        .withStatus(OrderStatus.REJECTED, now);
                auditTimeline.append(ORDER, orderId, StateMachine.ORDER,
                        OrderStatus.SUBMITTED.name(), OrderStatus.REJECTED.name(), actor,
                        rejectionReason, clock);
                return rejected;
            }));
        }

        requireOrder(orderId, orderStore.transition(orderId, current -> {
            LifecycleTransitions.assertLegal(current.getStatus(), OrderStatus.ACCEPTED);
            Order accepted = current.withControlResults(results, null)
                    .withStatus(OrderStatus.ACCEPTED, now);
            auditTimeline.append(ORDER, orderId, StateMachine.ORDER, OrderStatus.SUBMITTED.name(),
                    OrderStatus.ACCEPTED.name(), actor, REASON_CONTROLS_PASSED, clock);
            return accepted;
        }));

        //Allocated before the transition so the operator running inside the store's compute does
        //nothing but assert the edge, build the replacement and append the event.
        String executionId = orderStore.nextExecutionId();

        Order executed = requireOrder(orderId, orderStore.transition(orderId, current -> {
            LifecycleTransitions.assertLegal(current.getStatus(), OrderStatus.EXECUTED);
            //The fill is priced at the order's own limitPrice - the same price the position was
            //marked at above - because this service reaches no exchange, venue or market-data
            //source: the execution is simulated.
            Order filled = current
                    .withExecution(new Execution(executionId, limitPrice, quantity, now))
                    .withStatus(OrderStatus.EXECUTED, now);
            auditTimeline.append(ORDER, orderId, StateMachine.ORDER, OrderStatus.ACCEPTED.name(),
                    OrderStatus.EXECUTED.name(), actor,
                    "simulated fill recorded as " + executionId, clock);
            return filled;
        }));

        return postTrade.onExecuted(executed, actor);
    }

    public Order get(String orderId) {
        return requireOrder(orderId, orderStore.find(orderId));
    }

    public List<Order> list() {
        List<Order> snapshot = new ArrayList<>(orderStore.list());
        snapshot.sort(Comparator.comparing(Order::getOrderId));
        return snapshot;
    }

    public List<AuditEvent> events(String orderId) {
        //Read through the store first so an unknown id answers as a missing order rather than as
        //an order that happens to have no history.
        get(orderId);

        //Both of the order's state machines come back, ORDER and POST_TRADE, because both are
        //filed under this entity; sequence order is what interleaves them readably.
        List<AuditEvent> timeline = new ArrayList<>(auditTimeline.forEntity(ORDER, orderId));
        timeline.sort(Comparator.comparingLong(AuditEvent::getSequence));
        return timeline;
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new ValidationException(field + " is required");
        }
        return value;
    }

    /* Canonicalized once, on the way in, so the client's position key, the control evaluation, the
       evaluateAndFill key and the stored order all see one form of the ticker. Locale.ROOT rather
       than the default locale: a Turkish locale upper-cases "i" to a dotted capital, and the
       symbol would then miss both its own position and its own restricted-list entry. */
    private static String canonical(String value) {
        return (value == null) ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static Order requireOrder(String orderId, Order order) {
        if (order == null) {
            throw new EntityNotFoundException("No order exists with id " + orderId);
        }
        return order;
    }
}
