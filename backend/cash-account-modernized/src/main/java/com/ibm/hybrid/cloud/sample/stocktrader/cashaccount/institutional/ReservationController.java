package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.institutional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.audit.LedgerEntryResponse;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.audit.LedgerService;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.Money;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.OwnerNormalizer;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;

/**
 * The institutional hold, settlement, release and audit-query surface of the cash ledger, carrying the
 * {@code /cash-account} prefix on its own mapping because a servlet context path would move the chart's
 * root-level actuator probes under it.
 */
@RestController
@RequestMapping(path = "/cash-account/institutional", produces = MediaType.APPLICATION_JSON_VALUE)
public class ReservationController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    public static final String IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed";

    private static final String REPLAYED_HEADER_VALUE = "true";

    private final ReservationService reservations;
    private final LedgerService ledger;

    /**
     * Container constructor.
     *
     * @param reservations the transactional owner of holds, settlements, releases and expiry
     * @param ledger the audit query surface, injected directly because its bound, default page size and
     *     ordering belong to the audit package
     */
    public ReservationController(ReservationService reservations, LedgerService ledger) {
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    /**
     * Places a hold on the owner's available funds.
     *
     * @param owner the account owner
     * @param idempotencyKey the caller's {@code Idempotency-Key}
     * @param request the hold to place
     * @return {@code 201} with the new hold, or {@code 200} with the stored hold and
     *     {@code Idempotent-Replayed: true} when the key and payload replay an earlier call
     * @throws CashAccountException {@code 400} INVALID_OWNER / IDEMPOTENCY_KEY_REQUIRED / INVALID_AMOUNT /
     *     INVALID_CURRENCY / CURRENCY_MISMATCH / INVALID_REQUEST_FIELD (an {@code orderReference} or
     *     {@code expiresAt} this service cannot accept), {@code 404} ACCOUNT_NOT_FOUND, {@code 422}
     *     INSUFFICIENT_FUNDS / AMOUNT_OUT_OF_RANGE / IDEMPOTENCY_KEY_REUSED, {@code 409}
     *     CONCURRENT_MODIFICATION
     */
    @PostMapping(path = "/accounts/{owner}/holds", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReservationResponse> hold(@PathVariable("owner") String owner,
            // The header is mandatory, but required = false on purpose: Spring's own
            // MissingRequestHeaderException would answer outside the closed error set of AAP 0.6.2, so a null
            // key reaches ReservationService, which owns IDEMPOTENCY_KEY_REQUIRED and the length check.
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody HoldRequest request) {

        String normalizedOwner = OwnerNormalizer.normalize(owner);
        boundAmount(request == null ? null : request.amount());

        ReservationService.HoldOutcome outcome = reservations.hold(normalizedOwner, idempotencyKey, request);

        // 200 with the header rather than 201: an earlier identical call created the hold and nothing moved
        // this time, and the body is that call's stored answer, which is what the key exists to guarantee.
        if (outcome.replayed()) {
            return ResponseEntity.ok()
                    .header(IDEMPOTENT_REPLAYED_HEADER, REPLAYED_HEADER_VALUE)
                    .body(outcome.reservation());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(outcome.reservation());
    }

    /**
     * Settles a held reservation, in full by default or partially when an amount is supplied.
     *
     * @param reservationId the reservation to settle
     * @param request the amount to settle; an absent body or a null amount settles the full held amount, and
     *     {@code 0} is legal - it settles nothing and releases the whole hold
     * @return {@code 200} with the reservation as it stands after the settlement
     * @throws CashAccountException {@code 404} RESERVATION_NOT_FOUND, {@code 400} INVALID_AMOUNT when the
     *     amount exceeds the held amount, or {@code 409} INVALID_TRANSITION from a released or expired hold
     */
    @PostMapping("/reservations/{reservationId}/settle")
    public ReservationResponse settle(@PathVariable("reservationId") String reservationId,
            @RequestBody(required = false) SettleRequest request) {
        UUID reservation = parseReservationId(reservationId);
        boundAmount(request == null ? null : request.amount());
        return reservations.settle(reservation, request);
    }

    /**
     * Releases a held reservation, returning the whole held amount to available funds.
     *
     * @param reservationId the reservation to release
     * @return {@code 200} with the reservation as it stands after the release; a reservation already released
     *     or expired answers with its current state
     * @throws CashAccountException {@code 404} RESERVATION_NOT_FOUND or {@code 409} INVALID_TRANSITION from a
     *     settled reservation
     */
    @PostMapping("/reservations/{reservationId}/release")
    public ReservationResponse release(@PathVariable("reservationId") String reservationId) {
        return reservations.release(parseReservationId(reservationId));
    }

    /**
     * Reads a reservation as stored.
     *
     * @param reservationId the reservation to read
     * @return {@code 200} with the reservation
     * @throws CashAccountException {@code 404} RESERVATION_NOT_FOUND
     */
    @GetMapping("/reservations/{reservationId}")
    public ReservationResponse findReservation(@PathVariable("reservationId") String reservationId) {
        return reservations.findReservation(parseReservationId(reservationId));
    }

    /**
     * Reads an owner's available, reserved and total balances.
     *
     * @param owner the account owner
     * @return {@code 200} with the institutional view of the account
     * @throws CashAccountException {@code 400} INVALID_OWNER or {@code 404} ACCOUNT_NOT_FOUND
     */
    @GetMapping("/accounts/{owner}")
    public InstitutionalAccountResponse findAccount(@PathVariable("owner") String owner) {
        return reservations.findAccount(OwnerNormalizer.normalize(owner));
    }

    /**
     * Reads an owner's immutable ledger rows, newest first.
     *
     * @param owner the account owner
     * @param since inclusive lower bound on {@code recordedAt} as ISO-8601, or absent for the most recent rows
     * @param limit rows to return, or absent for the audit service's default of
     *     {@value LedgerService#DEFAULT_LEDGER_LIMIT}
     * @return {@code 200} with the retained rows - which outlive a deleted account - or an empty array for an
     *     owner that has neither rows nor an account
     * @throws CashAccountException {@code 400} INVALID_OWNER, or INVALID_QUERY for an unparsable {@code since}
     *     or a {@code limit} outside {@code 1..}{@value LedgerService#MAX_LEDGER_LIMIT}
     */
    @GetMapping("/accounts/{owner}/ledger")
    public List<LedgerEntryResponse> findLedger(@PathVariable("owner") String owner,
            @RequestParam(name = "since", required = false) String since,
            @RequestParam(name = "limit", required = false) String limit) {
        return ledger.findLedger(OwnerNormalizer.normalize(owner), parseSince(since), parseLimit(limit));
    }

    // The amount's size is judged here and not left to ReservationService: "amount":1e1000000000 is twelve bytes
    // of JSON that Jackson parses into a BigDecimal for nothing, and both service entry points scale the caller's
    // raw value before any comparison can reject it (requireHoldAmount, requireSettleAmount and the idempotency
    // hash), so the only place ahead of that expansion is where the body has just been bound. Bean Validation
    // cannot stand in: HoldRequest's @DecimalMin is a floor, and a floor admits every large value. A null amount
    // passes through untouched because for settle it is the documented full settlement and for a hold it is the
    // service's own 400 INVALID_AMOUNT carrying the owner. The owner and identifier are still resolved first, so
    // an invalid owner stays 400 INVALID_OWNER and an unparsable identifier 404 RESERVATION_NOT_FOUND; the guard
    // answers 400 INVALID_AMOUNT, a code both closed error sets of AAP 0.6.2 contain.
    private static void boundAmount(BigDecimal amount) {
        if (amount != null) {
            Money.requireWithinInputBounds(amount);
        }
    }

    // Raw strings rather than UUID, OffsetDateTime and Integer parameters: a typed conversion failure raises
    // MethodArgumentTypeMismatchException, which error/ApiExceptionHandler can only map by guessing from the
    // parameter name, and the codes below are the ones AAP 0.6.2 fixes for these paths - an unparsable
    // identifier is a 404, because no such reservation can exist whatever the caller meant.
    private UUID parseReservationId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            throw CashAccountException.forReservation(CashAccountErrorCode.RESERVATION_NOT_FOUND, raw,
                    "Reservation identifier is not a UUID.");
        }
    }

    private OffsetDateTime parseSince(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw);
        } catch (DateTimeParseException unparsable) {
            throw CashAccountException.of(CashAccountErrorCode.INVALID_QUERY,
                    "since must be an ISO-8601 timestamp with an offset.");
        }
    }

    // Shape only: audit/LedgerService is the single authority on the 1..1000 bound and the default page size.
    private Integer parseLimit(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException unparsable) {
            throw CashAccountException.of(CashAccountErrorCode.INVALID_QUERY, "limit must be an integer.");
        }
    }
}
