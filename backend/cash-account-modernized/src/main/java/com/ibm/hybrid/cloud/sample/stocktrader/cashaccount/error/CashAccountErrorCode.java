package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error;

import org.springframework.http.HttpStatus;

/** Closed set of error conditions this service reports, each bound here and only here to one HTTP status. */
public enum CashAccountErrorCode {

    // Distinct statuses for what the legacy return field rendered identically: SQLCODE 100 from the
    // Q/U/X/C/D SELECTs and -803 from the A INSERT both reached the caller as unsigned digits in an X(10)
    // field [backend/cash-account-cobol/COBOL/CASH00.cbl:L104] (AAP 0.12.3).
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "Cash account not found."),

    ACCOUNT_ALREADY_EXISTS(HttpStatus.CONFLICT, "Cash account already exists."),

    // The message names the permitted set rather than only the bound, because that is the whole of the rule a
    // caller has to satisfy and a refusal that states only the length sends the caller to count characters it
    // already got right. The set itself is domain/OwnerNormalizer's, and its rationale is stated there.
    INVALID_OWNER(HttpStatus.BAD_REQUEST,
            "Owner must be 1 to 32 characters of A-Z, 0-9, dot, underscore or hyphen."),

    INVALID_AMOUNT(HttpStatus.BAD_REQUEST,
            "Amount is missing, not a number, or not permitted for this operation."),

    INVALID_CURRENCY(HttpStatus.BAD_REQUEST, "Currency must be a supported three-letter ISO code."),

    // The 400 for a request member that has no code of its own - an institutional hold's orderReference or
    // expiresAt - and the fourth code beyond the AAP 0.6.2 vocabulary, on the same signable register row as
    // REQUEST_TOO_LARGE and the media-type pair (README, decision row D2). It exists because the alternative was
    // untrue rather than merely imprecise: every such failure fell to INVALID_AMOUNT below, so a missing or
    // over-length orderReference was answered "Amount is missing, not a number, or not permitted for this
    // operation" while the amount the caller sent was perfectly valid - a rejection that sends a caller to correct
    // what is already correct, and one an automated client cannot tell from a genuine amount fault. Reusing
    // INVALID_QUERY was rejected for the same reason: a JSON body member is not a query parameter. The status is
    // the 400 the AAP fixes for every validation condition, so no endpoint's status class changes with it.
    INVALID_REQUEST_FIELD(HttpStatus.BAD_REQUEST, "A request field carries a value this service cannot accept."),

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

    // The media-type pair, added for the same reason REQUEST_TOO_LARGE below was: the condition is a caller
    // mistake that has to be reported, and the closed AAP 0.6.2 vocabulary can express it in no other way that
    // stays true. Without them Spring's HttpMediaTypeNotSupportedException and HttpMediaTypeNotAcceptableException
    // reach error/ApiExceptionHandler's catch-all and a wrong Content-Type or an unsatisfiable Accept is answered
    // 500 INTERNAL - a client error reported as a server fault, which is the opposite of failing closed
    // [backend/cash-account-cobol/COBOL/CASH00.cbl:L89-L102 is the fall-through this service exists to replace]
    // and which puts an ERROR record in the log for every mis-configured integration attempt. Mapping them onto an
    // existing 400 was rejected for the reason stated at REQUEST_TOO_LARGE: a 415 or 406 carrying a 400's code
    // makes this enum's one-code-one-status binding untrue on the wire, and INVALID_AMOUNT would tell a caller its
    // amount was wrong when the body was never parsed. error/FailClosedIT asserts both statuses and both codes.
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "Request body media type is not supported; send application/json."),

    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE,
            "No acceptable representation; this service answers application/json."),

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
