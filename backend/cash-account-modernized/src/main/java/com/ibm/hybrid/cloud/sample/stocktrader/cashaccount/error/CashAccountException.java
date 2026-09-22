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

import java.util.Objects;
import java.util.UUID;

// One unchecked type for every expected condition, rather than a checked exception per condition, because this is the
// direct replacement for the legacy program's single status channel: WS-RETCODE was cleared to spaces
// [backend/cash-account-cobol/COBOL/CASH00.cbl:L78] and then received the LAST executed statement's SQLCODE through
// "MOVE SQLCODE TO WS-RETCODE" [CASH00.cbl:L104], a numeric-to-alphanumeric MOVE that dropped the sign, so an earlier
// failure in the same paragraph was invisible and several paths reported success anyway. Carrying an error code instead
// makes each condition individually observable, and one type means no service signature has to enumerate conditions it
// merely propagates.
//
// Unchecked is also what enforces the AAP's "every failure path throws before the transaction commits" (0.12.3):
// @Transactional rolls back on an unchecked throw by default, so a rejected operation can leave neither a half-applied
// balance change nor an orphan ledger row - unlike the legacy paths that committed and then returned the caller's own
// submitted amount back as the balance [CASH00.cbl:L104-L108]. The stack trace is deliberately not suppressed:
// ApiExceptionHandler decides what to log per status, and a suppressed trace would make a genuine 500 undiagnosable.
/** The single runtime exception this module throws; it carries the error code that fixes the response status. */
public final class CashAccountException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final CashAccountErrorCode errorCode;
    private final String owner;
    private final String reservationId;

    private CashAccountException(CashAccountErrorCode code, String message, String owner, String reservationId,
            Throwable cause) {
        super(resolveMessage(code, message), cause);
        this.errorCode = code;
        this.owner = owner;
        this.reservationId = reservationId;
    }

    public static CashAccountException of(CashAccountErrorCode code) {
        return new CashAccountException(code, null, null, null, null);
    }

    public static CashAccountException of(CashAccountErrorCode code, String message) {
        return new CashAccountException(code, message, null, null, null);
    }

    public static CashAccountException of(CashAccountErrorCode code, String message, Throwable cause) {
        return new CashAccountException(code, message, null, null, cause);
    }

    public static CashAccountException of(CashAccountErrorCode code, String message, String owner, String reservationId,
            Throwable cause) {
        return new CashAccountException(code, message, owner, reservationId, cause);
    }

    public static CashAccountException forOwner(CashAccountErrorCode code, String owner) {
        return new CashAccountException(code, null, owner, null, null);
    }

    public static CashAccountException forOwner(CashAccountErrorCode code, String owner, String message) {
        return new CashAccountException(code, message, owner, null, null);
    }

    public static CashAccountException forOwner(CashAccountErrorCode code, String owner, String message,
            Throwable cause) {
        return new CashAccountException(code, message, owner, null, cause);
    }

    public static CashAccountException forReservation(CashAccountErrorCode code, String reservationId) {
        return new CashAccountException(code, null, null, reservationId, null);
    }

    public static CashAccountException forReservation(CashAccountErrorCode code, String reservationId, String message) {
        return new CashAccountException(code, message, null, reservationId, null);
    }

    // UUID overloads so the reservation-scoped callers need no conversion of their own while this package stays free of
    // any domain or persistence type: the identifier is held as text here.
    public static CashAccountException forReservation(CashAccountErrorCode code, UUID reservationId) {
        return new CashAccountException(code, null, null, asText(reservationId), null);
    }

    public static CashAccountException forReservation(CashAccountErrorCode code, UUID reservationId, String message) {
        return new CashAccountException(code, message, null, asText(reservationId), null);
    }

    public CashAccountErrorCode errorCode() {
        return errorCode;
    }

    public String owner() {
        return owner;
    }

    public String reservationId() {
        return reservationId;
    }

    // JavaBean aliases of the three accessors above, mirroring CashAccountErrorCode: the renderers of this condition -
    // the exception handler and the two security filter-chain handlers - are separate classes, and the aliases mean
    // none of them is edited over an accessor-naming preference.
    public CashAccountErrorCode getErrorCode() {
        return errorCode;
    }

    public String getOwner() {
        return owner;
    }

    public String getReservationId() {
        return reservationId;
    }

    // A null code is rejected here, inside the super() argument, so the throw site fails instead of a
    // status-less exception reaching the renderers: they derive the HTTP status and the Retry-After hint from the code
    // alone, and without one there is no status to choose. A blank message falls back for the same reason the code
    // carries a default at all - the payload must never surface an empty explanation.
    private static String resolveMessage(CashAccountErrorCode code, String message) {
        Objects.requireNonNull(code, "errorCode is required");
        return message == null || message.isBlank() ? code.defaultMessage() : message;
    }

    private static String asText(UUID reservationId) {
        return reservationId == null ? null : reservationId.toString();
    }
}
