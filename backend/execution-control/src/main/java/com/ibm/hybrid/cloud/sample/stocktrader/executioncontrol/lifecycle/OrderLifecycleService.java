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
import java.math.RoundingMode;

//Time (java.time)
import java.time.Clock;
import java.time.Instant;

//Collections
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
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
    private static final int AMOUNT_SCALE = 2;

    //One trillion of currency per share: far above any price this simulation is asked to fill and
    //far below the point where the price times a long share count leaves the amount bounds every
    //stored and rendered value is held to. See price().
    private static final BigDecimal MAX_LIMIT_PRICE = new BigDecimal("1000000000000.00");

    /* Semantic lengths, held as code constants rather than configuration: these are what the
       fields mean - an identifier a caller's own system issued and an exchange ticker - not a
       tuning knob, and a client that could raise them could store a megabyte under a key the
       service then keeps for the life of the process. */
    private static final int MAX_CLIENT_ORDER_ID_LENGTH = 64;
    private static final int MAX_CLIENT_ID_LENGTH = 64;
    private static final int MAX_SYMBOL_LENGTH = 12;

    /* Precompiled once: this runs on every submission, and Pattern.matches would recompile the
       expression each time. The alphabet is the one a ticker is written in, so a symbol that
       passes here cannot carry control characters, a newline or bidirectional text into a stored
       order, an audit reason or a position key. */
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("^[A-Z0-9.-]+$");

    //The most audit events one submission can write: SUBMITTED, then either REJECTED or
    //ACCEPTED and EXECUTED, then the post-trade PENDING_AFFIRMATION and either SETTLEMENT_READY
    //or an exception's OPEN plus the order's EXCEPTION edge.
    private static final int MAX_EVENTS_PER_SUBMISSION = 6;

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
        //One instant for the whole submission, so the entity fields this method writes - the
        //order's submittedAt and updatedAt and the simulated fill's executedAt - are all built
        //from it rather than from separate reads of the clock as the submission progresses.
        Instant now = clock.instant();

        SubmittedOrder submitted = validate(request);
        requireUnsubmitted(submitted.clientOrderId);
        requireCapacity(submitted);
        String orderId = reserveOrderId(submitted.clientOrderId);
        Order stored = insertSubmitted(submitted, orderId, actor, now, source);

        List<ControlResult> results = evaluateAndFill(submitted, stored).getControlResults();
        String rejectionReason = rejectionReason(results);
        if (!rejectionReason.isEmpty()) {
            return reject(orderId, results, rejectionReason, actor, now);
        }

        accept(orderId, results, actor, now);

        return postTrade.onExecuted(execute(orderId, submitted, actor, now), actor);
    }

    private SubmittedOrder validate(OrderRequest request) {
        String clientOrderId = required((request == null) ? null : request.getClientOrderId(),
                "clientOrderId", MAX_CLIENT_ORDER_ID_LENGTH);
        String clientId = required(request.getClientId(), "clientId", MAX_CLIENT_ID_LENGTH);
        String symbol = canonical(required(request.getSymbol(), "symbol", MAX_SYMBOL_LENGTH));

        //Measured again after canonicalization, because upper-casing can lengthen a value - U+00DF
        //upper-cases to "SS" - so the length that was checked on the way in is not necessarily the
        //length of the symbol this order is stored, keyed and audited under.
        if (symbol.length() > MAX_SYMBOL_LENGTH) {
            throw new ValidationException("symbol must not exceed " + MAX_SYMBOL_LENGTH
                    + " characters");
        }
        if (!SYMBOL_PATTERN.matcher(symbol).matches()) {
            throw new ValidationException("symbol must contain only A-Z, 0-9, '.' or '-'");
        }

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

        BigDecimal limitPrice = price(request.getLimitPrice());

        //An unknown client is a bad field in a submitted body rather than a missing addressed
        //resource, so it answers 400; EntityNotFoundException stays reserved for an identifier
        //the caller asked for by path.
        if (referenceData.findClient(clientId) == null) {
            throw new ValidationException("clientId " + clientId + " is not a known client");
        }

        return new SubmittedOrder(clientOrderId, clientId, symbol, side, quantity, limitPrice);
    }

    /* A price is money, so it has to be a whole number of cents and it has to be positive. The sign
       is read from the value as submitted, and the cent check is a separate refusal rather than a
       rounding: 0.001 is positive, and rounding it to 0.00 would give the order a notional of zero,
       clear every configured notional ceiling and fill at no cost. The value returned here is the
       one the order, its simulated fill and the position it moves are all built from, so the
       amount the caller is held to is the amount they sent. */
    private static BigDecimal price(BigDecimal limitPrice) {
        if (limitPrice == null) {
            throw new ValidationException("limitPrice is required");
        }
        //signum() reads the sign off the magnitude the caller already parsed, so it is safe to ask
        //before the bounds below; nothing above this line expands the value.
        if (limitPrice.signum() <= 0) {
            throw new ValidationException("limitPrice must be greater than zero");
        }

        /* The representation is bounded before the cent conversion below, because that conversion
           is where a compact exponent stops being compact: a body whose limitPrice reads
           1e200000000 costs a dozen characters to send, and setScale would turn it into a
           two-hundred-million digit number and exhaust the heap before any control could refuse the
           order. The bound is Order's rather than this file's so the model and the services cannot
           drift apart, and the message reports the scale and digit count instead of the amount -
           rendering the amount is the other half of the allocation being refused. */
        if (!Order.isAmountWithinBounds(limitPrice)) {
            throw new ValidationException("limitPrice is outside the supported amount range ("
                    + Order.describeAmount(limitPrice) + "): at most "
                    + Order.MAX_AMOUNT_DECIMAL_PLACES + " decimal places, "
                    + Order.MAX_AMOUNT_SIGNIFICANT_DIGITS
                    + " significant digits and a magnitude below 1E+"
                    + (Order.MAX_AMOUNT_ADJUSTED_EXPONENT + 1) + " are supported");
        }

        /* The absolute ceiling is what extends that bound to every amount derived from the price.
           A notional is this price times a share count no larger than Long.MAX_VALUE, so a price
           within the ceiling can never produce an order or position notional outside the bounds
           above - which is why the order's own notional guard is unreachable from a submitted body.
           It is a representation ceiling and not a control: a price sitting on it is admitted and
           then judged by the configured pre-trade limits like any other. */
        if (limitPrice.compareTo(MAX_LIMIT_PRICE) > 0) {
            throw new ValidationException("limitPrice must not exceed "
                    + MAX_LIMIT_PRICE.toPlainString());
        }

        BigDecimal cents = limitPrice.setScale(AMOUNT_SCALE, RoundingMode.DOWN);
        if (cents.compareTo(limitPrice) != 0) {
            throw new ValidationException("limitPrice must be a whole number of cents, not "
                    + limitPrice.toPlainString());
        }

        return cents;
    }

    /* Asked before the capacity gates so a saturated service still answers the question the
       caller actually asked: a repeat clientOrderId is a conflict whatever the ceilings hold, and
       refusing it with 503 would invite a retry of a key that can never be accepted. This peek is
       read-only and decides only which refusal is given - reserveClientOrderId's putIfAbsent
       remains the authority, so two concurrent submissions of one key that both pass here still
       leave exactly one order. */
    private void requireUnsubmitted(String clientOrderId) {
        if (orderStore.findByClientOrderId(clientOrderId) != null) {
            throw duplicateClientOrderId(clientOrderId);
        }
    }

    //One factory for both refusal paths - this peek and the reservation below - so the 409 body a
    //client reads is identical whichever of them produced it.
    private static StateConflictException duplicateClientOrderId(String clientOrderId) {
        return new StateConflictException(
                "clientOrderId " + clientOrderId + " has already been submitted");
    }

    /* Every capacity this submission could consume is settled here, before the client order id is
       reserved and before any store or the timeline is written, so a refusal at a ceiling leaves
       no order, no position movement and no audit event behind: a 503 that had already changed
       state would be indistinguishable from a submission that half happened. The three read-only
       checks run first and the exact order claim last, which is what leaves exactly one claim to
       release and no claim taken for a check that then refused. */
    private void requireCapacity(SubmittedOrder submitted) {
        if (!auditTimeline.hasCapacityFor(MAX_EVENTS_PER_SUBMISSION)) {
            throw new CapacityExceededException(
                    "the audit timeline is at capacity, so no further order can be recorded");
        }

        //Asked of every submission although most orders open no break: any execution may find one,
        //and an order must not reach EXECUTED with nowhere to record it.
        if (!postTrade.hasCapacityToOpenException()) {
            throw new CapacityExceededException("the settlement-exception store is at capacity, so "
                    + "no order that might open a break can be admitted");
        }

        if (!referenceData.hasPositionCapacityFor(submitted.clientId, submitted.symbol)) {
            throw new CapacityExceededException("the position store is at capacity, so no new "
                    + submitted.symbol + " position can be opened for clientId "
                    + submitted.clientId);
        }

        if (!orderStore.tryAdmitOrder()) {
            throw new CapacityExceededException(
                    "the order store is at capacity, so no further order can be admitted");
        }
    }

    private String reserveOrderId(String clientOrderId) {
        String orderId = orderStore.nextOrderId();
        //The client order id is claimed before an order object exists, so exactly one of any
        //number of concurrent submissions carrying the same key proceeds and every loser writes
        //neither an order nor an audit event.
        if (!orderStore.reserveClientOrderId(clientOrderId, orderId)) {
            //The admission claim taken a moment ago is handed back before the refusal: this
            //submission stores no order, and a claim left behind would retire one slot of the
            //ceiling for the life of the process.
            orderStore.releaseOrderAdmission();
            throw duplicateClientOrderId(clientOrderId);
        }

        return orderId;
    }

    private Order insertSubmitted(SubmittedOrder submitted, String orderId, String actor,
            Instant now, RecordSource source) {
        Order stored = new Order(orderId, submitted.clientOrderId, submitted.clientId,
                submitted.symbol, submitted.side, submitted.quantity, submitted.limitPrice, actor,
                now, source);

        //The origin edge is asserted through the same table every later edge goes through, so no
        //order state reaches the store or the timeline without having passed assertLegal.
        LifecycleTransitions.assertLegal(null, OrderStatus.SUBMITTED);
        orderStore.insert(stored);
        auditTimeline.append(ORDER, orderId, StateMachine.ORDER, LifecycleTransitions.NONE,
                OrderStatus.SUBMITTED.name(), actor,
                "order received for clientOrderId " + submitted.clientOrderId, clock);

        return stored;
    }

    private ReferenceDataStore.FillDecision evaluateAndFill(SubmittedOrder submitted, Order stored) {
        /* Evaluating the controls and applying the fill are one atomic step per client and symbol:
           a second order in the same symbol has to be valued against the position the first one
           left behind rather than a stale copy, and a rejected order has to leave that position
           exactly as it was. Nothing outside this operator reads or writes the position. */
        return referenceData.evaluateAndFill(submitted.clientId, submitted.symbol, position -> {
            List<ControlResult> outcomes = controls.evaluate(stored, position);
            if (!outcomes.stream().allMatch(ControlResult::isPassed)) {
                return ReferenceDataStore.FillDecision.unchanged(outcomes);
            }

            long resulting = submitted.resultingQuantity(
                    (position == null) ? 0L : position.getQuantity());

            return ReferenceDataStore.FillDecision.filled(new Position(submitted.clientId,
                    submitted.symbol, resulting, submitted.limitPrice), outcomes);
        });
    }

    private static String rejectionReason(List<ControlResult> results) {
        return results.stream()
                .filter(result -> !result.isPassed())
                .map(ControlResult::getReason)
                .collect(Collectors.joining(REASON_SEPARATOR));
    }

    private Order reject(String orderId, List<ControlResult> results, String rejectionReason,
            String actor, Instant now) {
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

    private Order accept(String orderId, List<ControlResult> results, String actor, Instant now) {
        return requireOrder(orderId, orderStore.transition(orderId, current -> {
            LifecycleTransitions.assertLegal(current.getStatus(), OrderStatus.ACCEPTED);
            Order accepted = current.withControlResults(results, null)
                    .withStatus(OrderStatus.ACCEPTED, now);
            auditTimeline.append(ORDER, orderId, StateMachine.ORDER, OrderStatus.SUBMITTED.name(),
                    OrderStatus.ACCEPTED.name(), actor, REASON_CONTROLS_PASSED, clock);
            return accepted;
        }));
    }

    private Order execute(String orderId, SubmittedOrder submitted, String actor, Instant now) {
        //Allocated before the transition so the operator running inside the store's compute does
        //nothing but assert the edge, build the replacement and append the event.
        String executionId = orderStore.nextExecutionId();

        return requireOrder(orderId, orderStore.transition(orderId, current -> {
            LifecycleTransitions.assertLegal(current.getStatus(), OrderStatus.EXECUTED);
            //The fill is priced at the order's own limitPrice - the same price the position was
            //marked at above - because this service reaches no exchange, venue or market-data
            //source: the execution is simulated.
            Order filled = current
                    .withExecution(new Execution(executionId, submitted.limitPrice,
                            submitted.quantity, now))
                    .withStatus(OrderStatus.EXECUTED, now);
            auditTimeline.append(ORDER, orderId, StateMachine.ORDER, OrderStatus.ACCEPTED.name(),
                    OrderStatus.EXECUTED.name(), actor,
                    "simulated fill recorded as " + executionId, clock);
            return filled;
        }));
    }

    public Order get(String orderId) {
        return requireOrder(orderId, orderStore.find(orderId));
    }

    //The store owns the order of a full read and answers with an unmodifiable snapshot, so that
    //snapshot is what goes back: one layer copying and sorting once, rather than two doing both on
    //a history that only ever grows.
    public List<Order> list() {
        return orderStore.list();
    }

    //The paged read a REST caller comes through: the store cuts the page under the same ordering
    //it gives a full read, so page boundaries cannot shift between calls and an order estate at
    //its ceiling is never serialized whole into one response.
    public List<Order> list(int offset, int limit) {
        return orderStore.list(offset, limit);
    }

    public List<AuditEvent> events(String orderId) {
        //Read through the store first so an unknown id answers as a missing order rather than as
        //an order that happens to have no history.
        get(orderId);

        //Both of the order's state machines come back, ORDER and POST_TRADE, because both are
        //filed under this entity; sequence order is what interleaves them readably, and the
        //timeline assigns each ordinal and its list position together, so its snapshot is already
        //in that order.
        return auditTimeline.forEntity(ORDER, orderId);
    }

    /* isBlank and strip rather than trim: trim only removes characters up to U+0020, so a value of
       U+2003 (EM SPACE) would pass as present and Unicode padding would survive into the stored
       order and into the idempotency key. The stripped value is what this method returns, so the
       caller cannot accidentally store the raw one. Length is measured after stripping, because
       padding is not content the caller asked to store. */
    private static String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(field + " is required");
        }

        String stripped = value.strip();
        if (stripped.length() > maxLength) {
            throw new ValidationException(field + " must not exceed " + maxLength + " characters");
        }

        return stripped;
    }

    /* Canonicalized once, on the way in, so the client's position key, the control evaluation, the
       evaluateAndFill key and the stored order all see one form of the ticker. Locale.ROOT rather
       than the default locale: a Turkish locale upper-cases "i" to a dotted capital, and the
       symbol would then miss both its own position and its own restricted-list entry. */
    private static String canonical(String value) {
        return (value == null) ? null : value.strip().toUpperCase(Locale.ROOT);
    }

    private static Order requireOrder(String orderId, Order order) {
        if (order == null) {
            throw new EntityNotFoundException("No order exists with id " + orderId);
        }
        return order;
    }

    /** The validated, canonical form of one submit body */
    private static final class SubmittedOrder {
        //Every later step reads these fields instead of the request, so the stored order, the
        //control evaluation, the position key, the fill and the simulated execution are all built
        //from the stripped and canonical values rather than from a mutable request POJO.
        private final String clientOrderId;
        private final String clientId;
        private final String symbol;
        private final String side;
        private final long quantity;
        private final BigDecimal limitPrice;

        private SubmittedOrder(String clientOrderId, String clientId, String symbol, String side,
                long quantity, BigDecimal limitPrice) {
            this.clientOrderId = clientOrderId;
            this.clientId = clientId;
            this.symbol = symbol;
            this.side = side;
            this.quantity = quantity;
            this.limitPrice = limitPrice;
        }

        /* Checked arithmetic, because the sum of a stored holding and an order quantity is the one
           value here that a caller can push past what a long holds. Wrapping it would store a
           position whose sign and magnitude are both wrong, so a share count that cannot be
           represented is refused as a bad request instead - inside the store's compute step, which
           leaves the position exactly as it was. */
        private long resultingQuantity(long existingQuantity) {
            try {
                return BUY.equals(side)
                        ? Math.addExact(existingQuantity, quantity)
                        : Math.subtractExact(existingQuantity, quantity);
            } catch (ArithmeticException beyondLongRange) {
                throw new ValidationException("quantity " + quantity + " would move the " + clientId
                        + " " + symbol + " position beyond the supported share range");
            }
        }
    }
}
