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

    /*
     * WHY A REPLAY IS NOT RENDERED BY from(...) ABOVE. A replayed hold must answer with the body the original
     * call answered (AAP 0.6.2, 0.7.3): the caller is retrying one request and is owed one answer, whatever has
     * happened to the reservation since. from(...) projects the row as it stands, so once the hold has been
     * settled, released or expired it would report a terminal state, a settled amount and a later updatedAt -
     * a different answer to the same request, and one a retrying client could read as its hold having been
     * created in that state. Current state has its own endpoint, GET /cash-account/institutional/reservations/
     * {reservationId}, and that is where a caller asking "what is it now" is served.
     *
     * WHY RECONSTRUCTING IS EXACT RATHER THAN APPROXIMATE, AND WHY NO SNAPSHOT COLUMN EXISTS. Of the ten
     * components, only three can differ from their creation values, because cash_reservation has exactly three
     * mutable columns: state, settled_amount and updated_at (domain/CashReservation declares every other
     * column updatable = false, and expires_at is assigned in newHold and nowhere else). Their creation values
     * are not guesses: newHold sets state HELD, leaves settled_amount null, and stamps created_at and
     * updated_at from one normalized instant, so updated_at at creation IS created_at. The remaining seven
     * components are read from columns no code path can change. Storing a serialized copy of the first
     * response would add a column AAP 0.6.3 does not enumerate - and ddl-auto=validate makes the entity and
     * schema/cash-account-schema.sql one contract - to hold values the row already determines.
     */
    /**
     * Renders {@code reservation} as the body its creating hold returned, whatever state it has reached since.
     *
     * @param reservation the stored reservation a repeated {@code Idempotency-Key} resolved to
     * @return that reservation as it was first reported: state {@code HELD}, no settled amount
     * @throws IllegalArgumentException if {@code reservation} is null, which is a wiring defect rather than a
     *         request condition and so carries no {@code CashAccountErrorCode}
     */
    public static ReservationResponse originalHold(CashReservation reservation) {
        if (reservation == null) {
            throw new IllegalArgumentException("reservation is required");
        }
        return new ReservationResponse(
                reservation.reservationId(),
                reservation.owner(),
                reservation.orderReference(),
                reservation.amount().amount(),
                null,
                reservation.currency(),
                ReservationState.HELD,
                reservation.expiresAt(),
                reservation.createdAt(),
                reservation.createdAt());
    }
}
