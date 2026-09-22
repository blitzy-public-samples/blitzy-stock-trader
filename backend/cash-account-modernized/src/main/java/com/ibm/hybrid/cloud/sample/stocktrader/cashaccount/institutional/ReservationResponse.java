/*
       Copyright 2025 Kyndryl, All Rights Reserved

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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.institutional;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.CashReservation;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.ReservationState;

/*
 * WHY incarnationId, idempotencyKey, requestHash AND version ARE ABSENT although all four are real
 * cash_reservation columns. They are the mechanics of the hold idempotency guard, not state a client acts on
 * (AAP 0.7.3): incarnation_id scopes a key to one life of an account, request_hash is the SHA-256 that
 * separates a replay from key reuse, and version backs the optimistic lock. Publishing the idempotency key
 * would be the worst of the four - anyone holding it can replay the hold it belongs to, so it is a credential,
 * and this record reaches logs and stored runbook evidence. The payload AAP 0.6.2 fixes contains none of them,
 * and the ten components below are that payload exactly: the names are the wire contract, asserted field by
 * field by institutional/ReservationLifecycleIT and AuditImmediacyIT, whose replay case additionally requires
 * the body of a replayed hold to be byte-identical to the original.
 *
 * WHY THIS TYPE EXISTS AT ALL RATHER THAN THE ENTITY BEING RETURNED. domain/CashReservation is never
 * serialized (AAP 0.6.2), so this is the whole serialization boundary of a reservation. The direction is
 * strictly outbound: the entity's applyTransition is package-private and domain/ReservationStateMachine is the
 * single authority on which transitions are legal, so there is deliberately no toEntity, no builder and no
 * setter here through which a caller could attempt a state change.
 */
/** The wire form of one reservation: the only serialized representation of a hold. */
// Null-bearing deliberately, and this annotation is what makes it so. application.yml sets
// spring.jackson.default-property-inclusion: non_null service-wide, which is right for error/ApiError but wrong
// here: settledAmount is null for the whole of HELD, and letting it vanish would make the field set depend on the
// reservation's state, so a client would have to distinguish "absent because unsettled" from "absent because an
// older service did not send it". Rendering it as an explicit null keeps one stable key set across HELD, SETTLED,
// RELEASED and EXPIRED. ALWAYS overrides the global default for this type alone; NON_NULL would be the defect.
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReservationResponse(
        UUID reservationId,

        String owner,

        String orderReference,

        // WHY THE TWO MONETARY COMPONENTS ARE BigDecimal AND NOT THE MODULE'S Money TYPE. Money is a class
        // wrapping a single amount field, so Jackson would render each as a nested object -
        // {"amount":{"amount":500.00}} - while the shape this surface publishes is flat (AAP 0.6.2). Unwrapping
        // costs no precision: every value Money hands out is already at scale 2 under RoundingMode.DOWN, and
        // this record neither computes nor re-scales anything. float and double are prohibited on every money
        // path (AAP 0.7.1) and appear nowhere here, as a component type or as a conversion, for the reason
        // domain/Money records: 0.01 has no exact binary representation, and a fraction of a cent introduced on
        // the way out reconciles against COBOL packed decimal as data corruption rather than as arithmetic.
        // Plain decimal text rather than exponent notation comes from WRITE_BIGDECIMAL_AS_PLAIN, enabled
        // centrally in config/JacksonConfig.
        BigDecimal amount,

        // Null until the reservation is settled, which is a different fact from a settled amount of zero: a zero
        // settlement is legal and stores 0.00 while releasing the whole hold (AAP 0.6.2), so the two readings
        // must stay distinguishable on the wire as they are in the nullable NUMERIC(9,2) column behind it.
        BigDecimal settledAmount,

        String currency,

        // The enum, never its name as a String: Jackson serializes it by constant name, which is exactly the
        // literal set of CHECK (state IN ('HELD','SETTLED','RELEASED','EXPIRED')) on cash_reservation.state, so
        // the wire vocabulary and the database vocabulary cannot drift apart. A String component would also let
        // a value outside that set be constructed without anything failing.
        ReservationState state,

        OffsetDateTime expiresAt,

        OffsetDateTime createdAt,

        OffsetDateTime updatedAt) {

    /**
     * Renders {@code reservation} as its wire form.
     *
     * @param reservation the reservation to project; it is read, never modified
     * @return the flat ten-field view of that reservation
     * @throws IllegalArgumentException if {@code reservation} is null, which is a wiring defect rather than a
     *         request condition and so carries no {@code CashAccountErrorCode}
     */
    public static ReservationResponse from(CashReservation reservation) {
        if (reservation == null) {
            throw new IllegalArgumentException("reservation is required");
        }
        return new ReservationResponse(
                reservation.reservationId(),
                reservation.owner(),
                reservation.orderReference(),
                reservation.amount().amount(),
                // The null check is load-bearing, not defensive: CashReservation.newHold leaves settledAmount
                // null and every hold begins in HELD, so an unconditional .amount() here would throw on the
                // response of the create-hold endpoint itself - the most exercised path on this surface.
                reservation.settledAmount() == null ? null : reservation.settledAmount().amount(),
                reservation.currency(),
                reservation.state(),
                reservation.expiresAt(),
                reservation.createdAt(),
                reservation.updatedAt());
    }
}
