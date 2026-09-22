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
                (limitPrice == null) ? null : limitPrice.setScale(2, RoundingMode.HALF_UP);
        this.limitPrice = normalizedLimitPrice;
        //notional is derived, never a parameter, so no caller can store an order whose notional
        //contradicts its own quantity and limit price
        this.notional = (normalizedLimitPrice == null)
                ? null
                : normalizedLimitPrice.multiply(BigDecimal.valueOf(quantity))
                        .setScale(2, RoundingMode.HALF_UP);
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
