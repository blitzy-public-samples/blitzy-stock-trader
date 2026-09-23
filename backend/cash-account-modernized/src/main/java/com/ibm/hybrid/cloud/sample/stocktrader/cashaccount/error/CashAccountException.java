package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error;

import java.util.Objects;
import java.util.UUID;

/**
 * The single exception this module throws; it carries the error code that fixes the response status, and it is
 * unchecked so that every rejection rolls the caller's transaction back before a balance change or ledger row
 * can commit (AAP 0.12.3), unlike the legacy paths that committed and then echoed the caller's own submitted
 * amount back as the balance [backend/cash-account-cobol/COBOL/CASH00.cbl:L104-L108].
 */
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

    // UUID overloads so reservation-scoped callers need no conversion of their own, while the identifier stays text
    // here and this package stays free of any domain or persistence type.
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

    public CashAccountErrorCode getErrorCode() {
        return errorCode;
    }

    public String getOwner() {
        return owner;
    }

    public String getReservationId() {
        return reservationId;
    }

    // The null check runs inside the super() argument so the throw site fails rather than a status-less exception
    // reaching the renderers, which derive status and Retry-After from the code alone. A blank message falls back to
    // the code's default so the payload never surfaces an empty explanation.
    private static String resolveMessage(CashAccountErrorCode code, String message) {
        Objects.requireNonNull(code, "errorCode is required");
        return message == null || message.isBlank() ? code.defaultMessage() : message;
    }

    private static String asText(UUID reservationId) {
        return reservationId == null ? null : reservationId.toString();
    }
}
