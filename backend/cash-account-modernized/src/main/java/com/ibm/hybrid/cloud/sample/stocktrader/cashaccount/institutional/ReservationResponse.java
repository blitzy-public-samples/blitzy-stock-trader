package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.institutional;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.CashReservation;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.ReservationState;

/**
 * The only serialized form of a reservation, carrying the payload AAP 0.6.2 fixes and none of the idempotency
 * mechanics - the key is a credential anyone could replay the hold with, and this body reaches logs and stored
 * runbook evidence.
 *
 * @param reservationId  the reservation's identity
 * @param owner          the stored uppercase owner
 * @param orderReference the caller's reference for the order the hold backs
 * @param amount         the amount held
 * @param settledAmount  the settled portion, null for the whole of HELD
 * @param currency       the hold currency, always the account's
 * @param state          the lifecycle state
 * @param expiresAt      when the hold lapses
 * @param createdAt      when the hold was placed
 * @param updatedAt      when the state last changed
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReservationResponse(
        UUID reservationId,

        String owner,

        String orderReference,

        // BigDecimal, not the module's Money: Money wraps its amount, so Jackson would nest it inside the flat
        // shape AAP 0.6.2 fixes, and unwrapping loses nothing because every value Money hands out is already at
        // scale 2 under RoundingMode.DOWN. Binary floating point is prohibited on every money path (AAP 0.7.1).
        BigDecimal amount,

        // Null for the whole of HELD, which is a different fact from a settled amount of zero - legal, and
        // stored as 0.00 while the whole hold is released - so the type's @JsonInclude(ALWAYS) overrides
        // application.yml's spring.jackson.default-property-inclusion: non_null to keep one stable key set
        // across HELD, SETTLED, RELEASED and EXPIRED rather than a field set that varies with state.
        BigDecimal settledAmount,

        String currency,

        // The enum, not its name as a String: Jackson writes the constant name, which is exactly the literal
        // set of cash_reservation.state's CHECK constraint, so the wire and database vocabularies cannot drift.
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
                // Load-bearing, not defensive: newHold leaves settledAmount null and every hold begins in HELD,
                // so an unconditional .amount() would throw on the create-hold response itself.
                reservation.settledAmount() == null ? null : reservation.settledAmount().amount(),
                reservation.currency(),
                reservation.state(),
                reservation.expiresAt(),
                reservation.createdAt(),
                reservation.updatedAt());
    }

    /**
     * Renders {@code reservation} as the body its creating hold returned, whatever state it has reached since.
     *
     * <p>A retrying caller is owed the answer its first call received, so {@code from} cannot serve a replay:
     * it would report the state, settled amount and {@code updatedAt} the row has reached since. Rebuilding is
     * exact rather than a snapshot column because only {@code state}, {@code settled_amount} and
     * {@code updated_at} are mutable, and {@code newHold} set them to {@code HELD}, null and
     * {@code created_at}. Current state is what the GET reservation endpoint publishes.</p>
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
