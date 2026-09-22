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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.institutional;

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
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.OwnerNormalizer;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;

/*
 * WHY THE /cash-account PREFIX SITS ON THIS MAPPING RATHER THAN IN A SERVLET CONTEXT PATH. The deployment
 * probes /actuator/startup, /actuator/health/readiness and /actuator/health/liveness at the ROOT of port 8080
 * [infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L204-L222] while publishing
 * the service URL with the /cash-account suffix [.../values.yaml:L146]. A server.servlet.context-path of
 * /cash-account would move the probe paths under it and every pod would fail its startup probe, so the prefix
 * is carried by the controllers instead - here /cash-account/institutional, which config/SecurityConfig claims
 * as its own matcher ahead of the retail rules so that "institutional" is never read as an owner name.
 */
/** The institutional hold, settlement, release and audit-query surface of the cash ledger. */
@RestController
@RequestMapping(path = "/cash-account/institutional", produces = MediaType.APPLICATION_JSON_VALUE)
public class ReservationController {

    /** The idempotency header a hold must carry; public so the integration tests spell it once. */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** The header that marks a hold response as a replay of an earlier, identical call. */
    public static final String IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed";

    private static final String REPLAYED_HEADER_VALUE = "true";

    private final ReservationService reservations;
    private final LedgerService ledger;

    /**
     * @param reservations the transactional owner of holds, settlements, releases and expiry
     * @param ledger the audit query surface; injected directly because the ledger query, its {@code 1..1000}
     *     bound, its default page size and its ordering all belong to the audit package
     */
    public ReservationController(ReservationService reservations, LedgerService ledger) {
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    /*
     * WHY A FIRST WRITE ANSWERS 201 AND A REPLAY ANSWERS 200 WITH Idempotent-Replayed: true. The two are
     * different facts about the world and a caller retrying after a timeout has to be able to tell them apart:
     * 201 means this call created the hold, 200 with the header means an earlier identical call already did and
     * nothing moved this time. The replay body is the stored reservation verbatim - the same reservationId,
     * amount and state the first caller received - because an answer derived afresh could differ from what was
     * already acknowledged, which is precisely what the key exists to prevent (AAP 0.7.3).
     *
     * WHY A MANDATORY HEADER IS READ WITH required = false. Spring's own missing-header failure is a
     * MissingRequestHeaderException, which would surface as a code outside the closed error set AAP 0.6.2 fixes
     * for this endpoint. Letting the header arrive as null hands the decision to ReservationService, which
     * rejects it with 400 IDEMPOTENCY_KEY_REQUIRED - the code the contract names - and which also owns the
     * blank and over-length checks, so none of that is duplicated here.
     */
    /**
     * Places a hold on the owner's available funds.
     *
     * @param owner the account owner
     * @param idempotencyKey the caller's {@code Idempotency-Key}
     * @param request the hold to place
     * @return {@code 201} with the new hold, or {@code 200} with the stored hold and
     *     {@code Idempotent-Replayed: true} when the key and payload replay an earlier call
     * @throws CashAccountException {@code 400} INVALID_OWNER / IDEMPOTENCY_KEY_REQUIRED / INVALID_AMOUNT /
     *     INVALID_CURRENCY / CURRENCY_MISMATCH, {@code 404} ACCOUNT_NOT_FOUND, {@code 422} INSUFFICIENT_FUNDS
     *     or IDEMPOTENCY_KEY_REUSED, {@code 409} CONCURRENT_MODIFICATION
     */
    @PostMapping(path = "/accounts/{owner}/holds", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReservationResponse> hold(@PathVariable("owner") String owner,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody HoldRequest request) {

        ReservationService.HoldOutcome outcome =
                reservations.hold(OwnerNormalizer.normalize(owner), idempotencyKey, request);

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
        return reservations.settle(parseReservationId(reservationId), request);
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

    /*
     * WHY reservationId, since AND limit ARE PARSED HERE INSTEAD OF BEING DECLARED AS UUID, OffsetDateTime AND
     * Integer. Typed path variables and request parameters fail conversion with a
     * MethodArgumentTypeMismatchException, which error/ApiExceptionHandler can only resolve by inspecting the
     * PARAMETER NAME - a heuristic that yields INVALID_AMOUNT or INVALID_QUERY. Neither belongs to the closed
     * error set AAP 0.6.2 fixes for the four reservation paths, and an unparsable identifier there is a 404
     * RESERVATION_NOT_FOUND: no such reservation can exist, whatever the caller meant. Parsing the raw strings
     * keeps every status this controller can produce inside the contract's sets and independent of that
     * heuristic. The range check on limit is deliberately NOT repeated - audit/LedgerService is the single
     * authority on 1..1000 and on the default page size.
     */
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
