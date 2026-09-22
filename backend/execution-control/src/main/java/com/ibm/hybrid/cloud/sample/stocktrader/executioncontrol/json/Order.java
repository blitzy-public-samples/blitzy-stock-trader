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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** An immutable simulated institutional order and its control, execution and post-trade state */
public class Order {
    private static final int AMOUNT_SCALE = 2;

    /* The widest representation any amount in this service may carry, and the reason it is checked
       before arithmetic rather than after: a BigDecimal keeps its exponent as a scale, so a price
       written "1e200000000" arrives as a dozen characters and becomes a two-hundred-million digit
       number only when setScale, multiply or toPlainString expands it - by which point the
       allocation has happened, and one small request body can exhaust the heap. An amount is
       therefore admitted or refused on its representation, while it is still narrow.
       The bounds are generous against money and tight against that expansion: twelve decimal
       places either side of the point, forty significant digits and a magnitude below 1E+38, which
       still leaves room for the largest notional a permitted limit price and a long share count
       can produce. */
    public static final int MAX_AMOUNT_DECIMAL_PLACES = 12;
    public static final int MAX_AMOUNT_SIGNIFICANT_DIGITS = 40;
    public static final int MAX_AMOUNT_ADJUSTED_EXPONENT = 37;

    //Four bits per decimal digit over-estimates the 3.33 a digit actually needs, so this refuses
    //nothing MAX_AMOUNT_SIGNIFICANT_DIGITS accepts. It exists to keep precision(), which walks the
    //whole magnitude, off a value already too wide to be an amount.
    private static final int MAX_AMOUNT_UNSCALED_BITS = MAX_AMOUNT_SIGNIFICANT_DIGITS * 4;

    //No setter of any kind exists here, and that is the audit guarantee: a status can only change
    //by replacing the stored object through OrderStore.transition, whose operator asserts the
    //transition is legal and appends the audit event in the same compute step. An in-place mutator
    //would let a state change escape the timeline.

    private final String orderId;
    private final String clientOrderId;
    private final String clientId;
    private final String symbol;
    private final String side;
    private final long quantity;
    private final BigDecimal limitPrice;
    private final BigDecimal notional;
    private final OrderStatus status;
    private final PostTradeStatus postTradeStatus;
    private final List<ControlResult> controlResults;
    private final String rejectionReason;
    private final Execution execution;
    private final String submittedBy;
    private final Instant submittedAt;
    private final Instant updatedAt;
    private final RecordSource source;
    private final boolean simulated;
    private final String disclaimer;

    public Order(String orderId, String clientOrderId, String clientId, String symbol, String side,
            long quantity, BigDecimal limitPrice, OrderStatus status, PostTradeStatus postTradeStatus,
            List<ControlResult> controlResults, String rejectionReason, Execution execution,
            String submittedBy, Instant submittedAt, Instant updatedAt, RecordSource source) {
        this.orderId = orderId;
        this.clientOrderId = clientOrderId;
        this.clientId = clientId;
        this.symbol = symbol;
        this.side = side;
        this.quantity = quantity;
        //Scale is pinned because BigDecimal.equals is scale-sensitive - 100.0 does not equal
        //100.00 even though compareTo returns 0 - so a fixed scale is what makes the rendered
        //amount and every value assertion on it deterministic
        BigDecimal normalizedLimitPrice =
                (limitPrice == null) ? null : cents(limitPrice, "limitPrice");
        this.limitPrice = normalizedLimitPrice;
        //notional is derived, never a parameter, so no caller can store an order whose notional
        //contradicts its own quantity and limit price
        this.notional = (normalizedLimitPrice == null)
                ? null
                : cents(normalizedLimitPrice.multiply(BigDecimal.valueOf(quantity)), "notional");
        this.status = status;
        this.postTradeStatus = postTradeStatus;
        this.controlResults = (controlResults == null)
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(controlResults));
        this.rejectionReason = rejectionReason;
        this.execution = execution;
        this.submittedBy = submittedBy;
        this.submittedAt = submittedAt;
        this.updatedAt = updatedAt;
        this.source = source;
        this.simulated = true;
        this.disclaimer = SimulationLabels.DISCLAIMER;
    }

    public Order(String orderId, String clientOrderId, String clientId, String symbol, String side,
            long quantity, BigDecimal limitPrice, String submittedBy, Instant submittedAt,
            RecordSource source) {
        this(orderId, clientOrderId, clientId, symbol, side, quantity, limitPrice,
                OrderStatus.SUBMITTED, null, null, null, null, submittedBy, submittedAt,
                submittedAt, source);
    }

    public Order withStatus(OrderStatus newStatus, Instant updatedAt) {
        return new Order(orderId, clientOrderId, clientId, symbol, side, quantity, limitPrice,
                newStatus, postTradeStatus, controlResults, rejectionReason, execution, submittedBy,
                submittedAt, updatedAt, source);
    }

    public Order withPostTradeStatus(PostTradeStatus newPostTradeStatus, Instant updatedAt) {
        return new Order(orderId, clientOrderId, clientId, symbol, side, quantity, limitPrice,
                status, newPostTradeStatus, controlResults, rejectionReason, execution, submittedBy,
                submittedAt, updatedAt, source);
    }

    public Order withExecution(Execution execution) {
        return new Order(orderId, clientOrderId, clientId, symbol, side, quantity, limitPrice,
                status, postTradeStatus, controlResults, rejectionReason, execution, submittedBy,
                submittedAt, updatedAt, source);
    }

    public Order withControlResults(List<ControlResult> controlResults, String rejectionReason) {
        return new Order(orderId, clientOrderId, clientId, symbol, side, quantity, limitPrice,
                status, postTradeStatus, controlResults, rejectionReason, execution, submittedBy,
                submittedAt, updatedAt, source);
    }

    /* Is this amount safe to compute with and to render? Every check is O(1) on what the caller
       already parsed and none of them expands the value, which is the whole point: scale() is a
       stored int, bitLength() reads the magnitude's word count, precision() then counts digits of
       something already known to be narrow, and the adjusted exponent - precision - 1 - scale, the
       power of ten the value sits at - bounds the magnitude without materializing a single digit
       of it. The scale bound is two comparisons rather than Math.abs because
       Math.abs(Integer.MIN_VALUE) is itself negative and would slip through, and the exponent
       subtraction is done in long so that it stays correct for an extreme negative scale even if
       the checks above it are ever reordered - in int it would overflow and report a small
       magnitude for an enormous one. */
    public static boolean isAmountWithinBounds(BigDecimal value) {
        if (value == null) {
            return false;
        }

        int scale = value.scale();
        if (scale > MAX_AMOUNT_DECIMAL_PLACES || scale < -MAX_AMOUNT_DECIMAL_PLACES) {
            return false;
        }
        if (value.unscaledValue().bitLength() > MAX_AMOUNT_UNSCALED_BITS) {
            return false;
        }

        int digits = value.precision();
        if (digits > MAX_AMOUNT_SIGNIFICANT_DIGITS) {
            return false;
        }

        return ((long) digits - 1L - (long) scale) <= MAX_AMOUNT_ADJUSTED_EXPONENT;
    }

    //Reports the representation and never the amount: rendering an out-of-bounds value is the
    //allocation the bounds exist to refuse, while its scale and digit count are plain ints.
    public static String describeAmount(BigDecimal value) {
        return (value == null) ? "null"
                : ("scale " + value.scale() + " with " + value.precision() + " significant digits");
    }

    /* Pinning the scale must never change the amount. A limit price of 0.001 rounded to 0.00 here
       would give the order a notional of zero, which clears every configured notional ceiling and
       fills at no cost, so a price that cannot be held in cents is refused rather than rounded
       away. The derived notional is exact at this scale - a cent price times a whole share count -
       so the guard fires only on the price a caller supplied. */
    private static BigDecimal cents(BigDecimal value, String field) {
        //The bounds come first because the two operations below are the expansion they exist to
        //prevent, and they come first here rather than only in OrderLifecycleService.submit so that
        //no caller - the seed loader, a test, a future resource - can store an amount this module
        //cannot compute with.
        if (!isAmountWithinBounds(value)) {
            throw new IllegalArgumentException(field + " is outside the supported amount range ("
                    + describeAmount(value) + "): at most " + MAX_AMOUNT_DECIMAL_PLACES
                    + " decimal places, " + MAX_AMOUNT_SIGNIFICANT_DIGITS
                    + " significant digits and a magnitude below 1E+"
                    + (MAX_AMOUNT_ADJUSTED_EXPONENT + 1) + " are supported");
        }

        BigDecimal atCents = value.setScale(AMOUNT_SCALE, RoundingMode.DOWN);
        if (atCents.compareTo(value) != 0) {
            throw new IllegalArgumentException(
                    field + " must be a whole number of cents, not " + value.toPlainString());
        }

        return atCents;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getClientOrderId() {
        return clientOrderId;
    }

    public String getClientId() {
        return clientId;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getSide() {
        return side;
    }

    public long getQuantity() {
        return quantity;
    }

    public BigDecimal getLimitPrice() {
        return limitPrice;
    }

    public BigDecimal getNotional() {
        return notional;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public PostTradeStatus getPostTradeStatus() {
        return postTradeStatus;
    }

    public List<ControlResult> getControlResults() {
        return controlResults;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Execution getExecution() {
        return execution;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public RecordSource getSource() {
        return source;
    }

    public boolean isSimulated() {
        return simulated;
    }

    public String getDisclaimer() {
        return disclaimer;
    }

    public String toString() {
        return "Order[" + orderId + " " + clientId + " " + side + " " + quantity + " " + symbol
                + " " + status + "]";
    }
}
