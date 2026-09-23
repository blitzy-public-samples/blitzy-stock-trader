package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Objects;

/**
 * The single error payload of this service: one code, one explanation, one timestamp.
 *
 * @param code          the condition, which alone fixes the HTTP status
 * @param message       the explanation, defaulted from the code when absent
 * @param owner         the account the condition concerns, or null
 * @param reservationId the reservation the condition concerns, or null
 * @param timestamp     when the condition was raised
 */
// Omitted rather than null, so a consumer reads presence as the meaning - this condition names an owner, or a
// reservation, or neither - instead of reading meaning into a key that is always there.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(CashAccountErrorCode code, String message, String owner, String reservationId,
        Instant timestamp) {

    // In the canonical constructor so no path - factory, direct call, or Jackson binding a response back in a test -
    // can produce a payload without a code or a timestamp. A missing code fixes no status, so it fails fast; a
    // missing message or timestamp has one sane default, kept here rather than in each factory.
    public ApiError {
        Objects.requireNonNull(code, "code is required");
        message = message == null || message.isBlank() ? code.defaultMessage() : message;
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }

    public static ApiError of(CashAccountErrorCode code) {
        return new ApiError(code, null, null, null, null);
    }

    public static ApiError of(CashAccountErrorCode code, String message) {
        return new ApiError(code, message, null, null, null);
    }

    public static ApiError of(CashAccountErrorCode code, String message, String owner, String reservationId) {
        return new ApiError(code, message, owner, reservationId, null);
    }

    public static ApiError from(CashAccountException exception) {
        Objects.requireNonNull(exception, "exception is required");
        return new ApiError(exception.errorCode(), exception.getMessage(), exception.owner(),
                exception.reservationId(), null);
    }
}
