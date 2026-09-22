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

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Objects;

// One closed payload, produced by every renderer in this module, is the replacement for the legacy program's single
// status channel. There, "MOVE SQLCODE TO WS-RETCODE" [backend/cash-account-cobol/COBOL/CASH00.cbl:L104] copied the
// LAST executed statement's SQLCODE into an X(10) alphanumeric field: the numeric-to-alphanumeric MOVE dropped the
// sign, so -803 and +803 both reached the caller as 000000803, and taking only the last code masked any earlier
// failure in the same paragraph. The COMMAREA was then echoed back verbatim [CASH00.cbl:L104-L108], which on a
// dispatch fall-through returned the caller's own submitted amount as the "balance" - a failure indistinguishable
// from a success. This record closes both gaps: exactly one code per condition, an explanation that never depends on
// the caller's own input, and the same shape from the exception handler and from the two security filter-chain
// handlers, so operators and the institutional caller parse one structure everywhere.
/** The single error payload of this service: one code, one explanation, one timestamp. */
// Absent fields are omitted rather than serialized as null: a fixed key set whose values may be null invites a
// consumer to read a key's mere presence as meaning, and in a financial error payload that mis-parse is expensive.
// Presence therefore carries the meaning - this condition names an owner, or a reservation, or neither.
@JsonInclude(JsonInclude.Include.NON_NULL)
// "code" is the enum, not its text: CashAccountErrorCode stays the single authority for the wire vocabulary and for
// the HTTP status bound to it, and a throw site that names a code that does not exist fails to compile instead of
// emitting an unrecognized string. Jackson serializes the constant by name, so the wire value is "ACCOUNT_NOT_FOUND".
public record ApiError(CashAccountErrorCode code, String message, String owner, String reservationId,
        Instant timestamp) {

    // Validated in the canonical constructor so that no construction path - factory, direct call, or Jackson binding
    // a response back in a test - can produce a payload without a code or without a timestamp. A missing code has no
    // sane substitute, since the code is what fixes the status, so it fails fast; a missing message or timestamp does,
    // and defaulting them here keeps the fallback in one place instead of in each factory.
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
