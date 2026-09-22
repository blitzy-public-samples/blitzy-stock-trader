package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.institutional;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Request body for placing an institutional fund hold against a cash account.
 *
 * @param orderReference the caller's reference for the order the hold backs
 * @param amount         the amount to move from available to reserved
 * @param currency       the hold currency, which must equal the account's
 * @param expiresAt      when the hold lapses, or null for the configured default TTL
 */
public record HoldRequest(
        @NotBlank @Size(max = 64) String orderReference,

        // No fraction constraint: the canonical idempotency hash normalizes with
        // setScale(2, RoundingMode.DOWN), so 10, 10.0 and 10.00 must hash identically.
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,

        // Shape only here: the value must also equal the account's currency, because the institutional path
        // never converts, which domain/ReservationStateMachine refuses with 400 CURRENCY_MISMATCH.
        @NotBlank String currency,

        // Nullable: absent means the configured default TTL applies. A past instant is accepted
        // on purpose; the hold endpoint defines no bad-expiry code, so the sweep terminates it.
        OffsetDateTime expiresAt) {
}
