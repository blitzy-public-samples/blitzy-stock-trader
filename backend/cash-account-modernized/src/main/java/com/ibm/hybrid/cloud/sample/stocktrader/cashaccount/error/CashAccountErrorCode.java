package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error;

import org.springframework.http.HttpStatus;

/** Closed set of error conditions this service reports, each bound here and only here to one HTTP status. */
public enum CashAccountErrorCode {

    // Distinct statuses for what the legacy return field rendered identically: SQLCODE 100 from the
    // Q/U/X/C/D SELECTs and -803 from the A INSERT both reached the caller as unsigned digits in an X(10)
    // field [backend/cash-account-cobol/COBOL/CASH00.cbl:L104] (AAP 0.12.3).
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "Cash account not found."),

    ACCOUNT_ALREADY_EXISTS(HttpStatus.CONFLICT, "Cash account already exists."),

    INVALID_OWNER(HttpStatus.BAD_REQUEST, "Owner must be 1 to 32 characters."),

    INVALID_AMOUNT(HttpStatus.BAD_REQUEST,
            "Amount is missing, not a number, or not permitted for this operation."),

    INVALID_CURRENCY(HttpStatus.BAD_REQUEST, "Currency must be a supported three-letter ISO code."),

    CURRENCY_MISMATCH(HttpStatus.BAD_REQUEST, "Hold currency must equal the account currency."),

    // Deliberate deviations, not parity gaps: the unsigned "pic 9(7)V99" WS-CALC
    // [backend/cash-account-cobol/COBOL/CASH00.cbl:L17] and COMPUTE statements without ON SIZE ERROR
    // [CASH00.cbl:L222, L256] committed a negative result as its absolute value and dropped the high-order
    // digits of a result at or above 10,000,000.00. Both are rejected here, balance unchanged.
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_ENTITY, "Available balance is insufficient for this operation."),

    AMOUNT_OUT_OF_RANGE(HttpStatus.UNPROCESSABLE_ENTITY,
            "Resulting balance is outside the permitted range 0.00 to 9999999.99."),

    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "The Idempotency-Key header is required."),

    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY,
            "The Idempotency-Key was already used with a different payload."),

    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Reservation not found."),

    INVALID_TRANSITION(HttpStatus.CONFLICT,
            "The requested transition is not legal from the reservation's current state."),

    RESERVATIONS_OUTSTANDING(HttpStatus.CONFLICT, "The account has one or more held reservations."),

    // Deliberate deviation: a missing STOCKTRD.FRANKFURT1 rate row set SQLCODE 100, which the following
    // UPDATE reset to 0 [backend/cash-account-cobol/COBOL/CASH00.cbl:L214-L231 credit, L248-L264 debit], so
    // the program committed arithmetic over an uninitialized RATES host variable [DCLFRANK.cpy:L22] and
    // reported success. Retry-After is set because the cause is a transient upstream lookup, not the caller.
    EXCHANGE_RATE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, 5,
            "Exchange rate is unavailable; the balance was not changed."),

    // Stands in for the legacy deadlock, timeout and resource-unavailable codes -911, -913 and -904, which
    // reached the caller as unsigned digits indistinguishable from a validation failure (AAP 0.12.3).
    DATASTORE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, 5, "The datastore is temporarily unavailable."),

    // Deliberate deviation, fail closed: "EVALUATE WS-REQ"
    // [backend/cash-account-cobol/COBOL/CASH00.cbl:L89-L102] has no WHEN OTHER, so an unknown request code
    // was answered success-looking. error/ApiExceptionHandler states the full rationale at the mapping.
    UNSUPPORTED_PATH(HttpStatus.NOT_FOUND, "No such resource."),

    UNSUPPORTED_METHOD(HttpStatus.METHOD_NOT_ALLOWED, "Method not supported for this resource."),

    // The one condition in this enum with no legacy counterpart and no entry in the AAP 0.6.2 error table: a
    // request body larger than any payload this service defines. It exists because a 413 cannot be reported
    // without it. Every other spare failure is mapped ONTO a constant that already exists, but the alternatives
    // here both break the invariant this enum is for - answering 413 while carrying a 400's code would make the
    // code-to-status binding untrue on the wire, and answering 400 INVALID_AMOUNT would tell a caller its amount
    // was wrong when its body was never parsed. The legacy program had no analogue to reuse: a COMMAREA is a
    // fixed-length structure, so an oversized request was unrepresentable rather than rejected.
    // config/RequestBodySizeLimitFilter raises it, error/RequestBodyTooLargeException carries it out of a
    // mid-read stream, and error/FailClosedIT asserts both framings of it.
    REQUEST_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "Request body exceeds the permitted size."),

    INVALID_QUERY(HttpStatus.BAD_REQUEST, "One or more query parameters are invalid."),

    // One second rather than five: the conflict clears as soon as the competing transaction commits or
    // releases its lock, so a longer hint would delay a request that is already retryable.
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, 1,
            "The resource was modified concurrently; retry the request."),

    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication is required."),

    FORBIDDEN(HttpStatus.FORBIDDEN, "The authenticated principal lacks the required role."),

    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected internal error occurred.");

    private final HttpStatus status;
    // Null where the condition is not worth retrying, so whether a Retry-After header appears is fixed by the
    // taxonomy rather than decided again by each renderer.
    private final Integer retryAfterSeconds;
    private final String defaultMessage;

    private CashAccountErrorCode(HttpStatus status, String defaultMessage) {
        this(status, null, defaultMessage);
    }

    private CashAccountErrorCode(HttpStatus status, Integer retryAfterSeconds, String defaultMessage) {
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public boolean hasRetryAfter() {
        return retryAfterSeconds != null;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    // The constant's own name is the wire code, so no separate string is stored that could drift from it.
    public String code() {
        return name();
    }
}
