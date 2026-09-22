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

/** An immutable simulated settlement exception raised by an SSI mismatch */
public class SettlementException {
    //No setter of any kind exists here, and that is the audit guarantee: a status can only
    //change by replacing the stored object through SettlementExceptionStore.transition, whose
    //operator asserts the transition is legal and appends the audit event in the same compute
    //step. An in-place mutator would let a state change escape the timeline.

    private final String exceptionId;
    private final String exceptionType;
    private final String orderId;
    private final String executionId;

    private final String clientId;
    private final String clientName;
    private final String symbol;
    private final String side;
    private final long quantity;
    private final BigDecimal notional;
    private final BigDecimal fillPrice;
    private final Instant executedAt;
    private final String venue;

    private final List<MismatchField> mismatchFields;

    private final ExceptionStatus status;
    private final String owner;
    private final String resolutionNote;
    private final Instant openedAt;
    private final Instant slaDeadline;
    private final long ageHours;
    private final boolean slaBreached;
    private final Instant assignedAt;
    private final Instant resolvedAt;
    private final Instant settlementReadyAt;

    private final RecordSource source;
    private final boolean simulated;
    private final String disclaimer;

    public SettlementException(String exceptionId, String exceptionType, String orderId,
            String executionId, String clientId, String clientName, String symbol, String side,
            long quantity, BigDecimal notional, BigDecimal fillPrice, Instant executedAt,
            String venue, List<MismatchField> mismatchFields, ExceptionStatus status, String owner,
            String resolutionNote, Instant openedAt, Instant slaDeadline, long ageHours,
            boolean slaBreached, Instant assignedAt, Instant resolvedAt, Instant settlementReadyAt,
            RecordSource source) {
        this.exceptionId = exceptionId;
        this.exceptionType = exceptionType;
        this.orderId = orderId;
        this.executionId = executionId;
        this.clientId = clientId;
        this.clientName = clientName;
        this.symbol = symbol;
        this.side = side;
        this.quantity = quantity;
        //Scale is pinned because BigDecimal.equals is scale-sensitive - 100.0 does not equal
        //100.00 - so a fixed scale is what makes amount assertions and the rendered JSON stable
        this.notional = (notional == null) ? null : notional.setScale(2, RoundingMode.HALF_UP);
        this.fillPrice = (fillPrice == null) ? null : fillPrice.setScale(2, RoundingMode.HALF_UP);
        this.executedAt = executedAt;
        this.venue = venue;
        this.mismatchFields = (mismatchFields == null)
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(mismatchFields));
        this.status = status;
        this.owner = owner;
        this.resolutionNote = resolutionNote;
        this.openedAt = openedAt;
        this.slaDeadline = slaDeadline;
        this.ageHours = ageHours;
        this.slaBreached = slaBreached;
        this.assignedAt = assignedAt;
        this.resolvedAt = resolvedAt;
        this.settlementReadyAt = settlementReadyAt;
        this.source = source;
        this.simulated = true;
        this.disclaimer = SimulationLabels.DISCLAIMER;
    }

    public SettlementException(String exceptionId, String exceptionType, String orderId,
            String executionId, String clientId, String clientName, String symbol, String side,
            long quantity, BigDecimal notional, BigDecimal fillPrice, Instant executedAt,
            String venue, List<MismatchField> mismatchFields, Instant openedAt, Instant slaDeadline,
            RecordSource source) {
        this(exceptionId, exceptionType, orderId, executionId, clientId, clientName, symbol, side,
                quantity, notional, fillPrice, executedAt, venue, mismatchFields,
                ExceptionStatus.OPEN, null, null, openedAt, slaDeadline, 0L, false, null, null,
                null, source);
    }

    //openedAt and slaDeadline are carried over untouched by every copy method below: the
    //deadline is fixed when the exception opens, so recomputing it on a later transition
    //would make an already-incurred breach disappear
    public SettlementException withAssigned(String owner, Instant assignedAt) {
        return new SettlementException(exceptionId, exceptionType, orderId, executionId, clientId,
                clientName, symbol, side, quantity, notional, fillPrice, executedAt, venue,
                mismatchFields, ExceptionStatus.ASSIGNED, owner, resolutionNote, openedAt,
                slaDeadline, ageHours, slaBreached, assignedAt, resolvedAt, settlementReadyAt,
                source);
    }

    public SettlementException withResolved(String resolutionNote, Instant resolvedAt) {
        return new SettlementException(exceptionId, exceptionType, orderId, executionId, clientId,
                clientName, symbol, side, quantity, notional, fillPrice, executedAt, venue,
                mismatchFields, ExceptionStatus.RESOLVED, owner, resolutionNote, openedAt,
                slaDeadline, ageHours, slaBreached, assignedAt, resolvedAt, settlementReadyAt,
                source);
    }

    public SettlementException withSettlementReady(Instant settlementReadyAt) {
        return new SettlementException(exceptionId, exceptionType, orderId, executionId, clientId,
                clientName, symbol, side, quantity, notional, fillPrice, executedAt, venue,
                mismatchFields, ExceptionStatus.SETTLEMENT_READY, owner, resolutionNote, openedAt,
                slaDeadline, ageHours, slaBreached, assignedAt, resolvedAt, settlementReadyAt,
                source);
    }

    //A read projection: PostTradeService computes ageing against its Clock on every read and
    //hands the caller this copy purely so the two values serialize. The result is never written
    //back to the store, so a stored exception keeps the placeholder 0/false and ageing stays
    //derived rather than authored state that could go stale
    public SettlementException withSlaSnapshot(long ageHours, boolean slaBreached) {
        return new SettlementException(exceptionId, exceptionType, orderId, executionId, clientId,
                clientName, symbol, side, quantity, notional, fillPrice, executedAt, venue,
                mismatchFields, status, owner, resolutionNote, openedAt, slaDeadline, ageHours,
                slaBreached, assignedAt, resolvedAt, settlementReadyAt, source);
    }

    public String getExceptionId() {
        return exceptionId;
    }

    public String getExceptionType() {
        return exceptionType;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getExecutionId() {
        return executionId;
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientName() {
        return clientName;
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

    public BigDecimal getNotional() {
        return notional;
    }

    public BigDecimal getFillPrice() {
        return fillPrice;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public String getVenue() {
        return venue;
    }

    public List<MismatchField> getMismatchFields() {
        return mismatchFields;
    }

    public ExceptionStatus getStatus() {
        return status;
    }

    public String getOwner() {
        return owner;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getSlaDeadline() {
        return slaDeadline;
    }

    public long getAgeHours() {
        return ageHours;
    }

    public boolean isSlaBreached() {
        return slaBreached;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getSettlementReadyAt() {
        return settlementReadyAt;
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

    //Diagnostic only, for assertion-failure messages; JSON-B owns the wire format, so this
    //deliberately emits no JSON - a second serializer could silently drift from the first
    public String toString() {
        return "SettlementException[" + exceptionId + " order=" + orderId + " status=" + status
                + " owner=" + owner + "]";
    }
}
