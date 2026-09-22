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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error;

import org.springframework.http.HttpStatus;

// A closed code-to-status taxonomy exists because the legacy program had a single status channel and it
// could not distinguish conditions: WS-RETCODE was cleared to spaces
// (backend/cash-account-cobol/COBOL/CASH00.cbl:L78) and then received the LAST executed SQL statement's
// SQLCODE through "MOVE SQLCODE TO WS-RETCODE" (CASH00.cbl:L104). That numeric-to-alphanumeric MOVE into
// an X(10) field dropped the sign, so -803 and +803 both reached the caller as 000000803, and taking only
// the last statement's code masked an earlier failure in the same paragraph. Binding every condition to
// exactly one status here - and only here - is what makes each one individually observable; no controller,
// service or handler in this module chooses a status of its own.
/** Closed set of error conditions this service reports, each bound to exactly one HTTP status. */
public enum CashAccountErrorCode {

    // Separate statuses for what the legacy return field rendered identically: SQLCODE 100 from the
    // Q/U/X/C/D SELECTs, and -803 from the A INSERT (AAP 0.12.3).
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "Cash account not found."),

    ACCOUNT_ALREADY_EXISTS(HttpStatus.CONFLICT, "Cash account already exists."),

    INVALID_OWNER(HttpStatus.BAD_REQUEST, "Owner must be 1 to 32 characters."),

    INVALID_AMOUNT(HttpStatus.BAD_REQUEST,
            "Amount is missing, not a number, or not permitted for this operation."),

    INVALID_CURRENCY(HttpStatus.BAD_REQUEST, "Currency must be a supported three-letter ISO code."),

    CURRENCY_MISMATCH(HttpStatus.BAD_REQUEST, "Hold currency must equal the account currency."),

    // Deliberate deviations, not parity gaps: WS-CALC is "pic 9(7)V99"
    // (backend/cash-account-cobol/COBOL/CASH00.cbl:L17) - unsigned, nine digits - and the credit/debit
    // COMPUTE statements carry neither ROUNDED nor ON SIZE ERROR (CASH00.cbl:L222, L256), so a negative
    // result was committed as its absolute value and a result of 10,000,000.00 or more silently lost its
    // high-order digits. Both are now rejected with the balance left unchanged.
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

    // Deliberate deviation: with no STOCKTRD.FRANKFURT1 row for the account's currency the inner SELECT
    // set SQLCODE 100, but the UPDATE that followed it reset SQLCODE to 0
    // (backend/cash-account-cobol/COBOL/CASH00.cbl:L214-L231 for credit, L248-L264 for debit), so the
    // program committed arithmetic over an uninitialized RATES host variable
    // (backend/cash-account-cobol/COBOL/DCLFRANK.cpy:L22 declares it with no VALUE clause) and still
    // reported success. Failing the request leaves the balance unchanged and writes no ledger row.
    // Retry-After is populated because the cause is a transient upstream lookup, not a caller error.
    EXCHANGE_RATE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, 5,
            "Exchange rate is unavailable; the balance was not changed."),

    // Stands in for the legacy deadlock, timeout and resource-unavailable codes -911, -913 and -904, which
    // reached the caller as unsigned digits indistinguishable from a validation failure (AAP 0.12.3).
    DATASTORE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, 5, "The datastore is temporarily unavailable."),

    // Deliberate deviation - fail closed. "EVALUATE WS-REQ"
    // (backend/cash-account-cobol/COBOL/CASH00.cbl:L89-L102) recognizes only A/Q/U/X/C/D and has no
    // WHEN OTHER, so an unknown request code ran no SQL, left SQLCODE untouched and therefore
    // success-looking, echoed the caller's own submitted amount back as the balance (CASH00.cbl:L104-L108)
    // and still wrote a history record (CASH00.cbl:L111-L131). Rejecting an unmapped path or verb is
    // intentional behaviour, not a parity gap.
    UNSUPPORTED_PATH(HttpStatus.NOT_FOUND, "No such resource."),

    UNSUPPORTED_METHOD(HttpStatus.METHOD_NOT_ALLOWED, "Method not supported for this resource."),

    INVALID_QUERY(HttpStatus.BAD_REQUEST, "One or more query parameters are invalid."),

    // One second rather than five: the conflict clears as soon as the competing transaction commits or
    // releases its lock, so a longer hint would delay a request that is already retryable.
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, 1,
            "The resource was modified concurrently; retry the request."),

    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication is required."),

    FORBIDDEN(HttpStatus.FORBIDDEN, "The authenticated principal lacks the required role."),

    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected internal error occurred.");

    private final HttpStatus status;
    // Null where the condition is not worth retrying, so the absence of a Retry-After header is carried by
    // the taxonomy itself rather than decided again by each renderer.
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

    // The constant's own name is the wire code: operators and tests match on a stable identifier, so no
    // separate string is stored that could drift from it.
    public String code() {
        return name();
    }

    // JavaBean aliases of the four accessors above. The renderers of this payload shape - the exception
    // handler and the security filter-chain entry point and access-denied handler - are separate classes,
    // and the aliases mean none of them has to be edited over an accessor-naming preference.
    public HttpStatus getStatus() {
        return status;
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    public String getCode() {
        return name();
    }
}
