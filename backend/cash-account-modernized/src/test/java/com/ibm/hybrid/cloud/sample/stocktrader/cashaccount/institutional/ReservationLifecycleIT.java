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

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.audit.LedgerEntryResponse;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.LedgerEntry;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.LedgerEventType;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.ReservationState;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.ApiError;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.retail.CashAccountResponse;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.JwtTestTokens;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.PostgresTestSupport;

/** Drives holds, settlements, releases, expiry and the idempotency races over HTTP against a real PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReservationLifecycleIT extends PostgresTestSupport {

    private static final String RETAIL_BASE = "/cash-account";

    private static final String INSTITUTIONAL_BASE = "/cash-account/institutional";

    // Every account here is USD, the configured FX base, where a same-currency rate short-circuits to exactly 1
    // with no outbound call - so nothing in this class can reach the exchange-rate endpoint that
    // application-test.yml deliberately points at a refused port. Institutional holds never convert anyway.
    private static final String CURRENCY = "USD";

    private static final String OPENING_BALANCE = "1000.00";

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    // A fixed literal, not a computed instant: the replay cases have to send two byte-identical payloads, and an
    // expiry derived from the clock would differ between the two calls and turn a replay into key reuse.
    private static final OffsetDateTime FIXED_EXPIRY = OffsetDateTime.parse("2099-06-01T12:00:00Z");

    private static final long RACE_TIMEOUT_SECONDS = 30;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    // The context's own mapper, so every body is read back through JacksonConfig's USE_BIG_DECIMAL_FOR_FLOATS
    // and WRITE_BIGDECIMAL_AS_PLAIN; a default mapper would bind money to a primitive numeric type instead.
    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private JdbcTemplate jdbc;

    // application-test.yml omits this property on purpose: the signer key pair is ephemeral per test JVM, so its
    // certificate can only be published at context-refresh time.
    @DynamicPropertySource
    static void jwtSignerCertificate(DynamicPropertyRegistry registry) {
        registry.add("cashaccount.security.jwt.public-key-location", JwtTestTokens::publicKeyLocation);
    }

    /*
     * WHY EVERY TEST OWNS ITS OWN ACCOUNT INSTEAD OF THE CLASS CLEANING UP. ledger_entry carries a BEFORE UPDATE
     * OR DELETE trigger (schema/cash-account-schema.sql) and LedgerEntryRepository declares no delete method, so
     * the rows this suite writes cannot be removed between tests by design - that immutability is the audit
     * guarantee under test (AAP 0.7.4), not an obstacle to work around. Distinct short uppercase owners, each
     * inside the 32-character cash_account.owner width, are therefore the isolation mechanism, and no assertion
     * in this class may assume an empty table.
     */

    @Test
    void holdMovesFundsIntoReservedAndWritesOneHoldRow() throws Exception {
        String owner = "HOLD1";
        openAccount(owner);

        ResponseEntity<String> response = postHold(owner, "IDEM-HOLD-1",
                holdBody("ORD-HOLD-1", "250.00", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ReservationResponse hold = reservationOf(response);
        assertThat(hold.reservationId()).isNotNull();
        assertThat(hold.owner()).isEqualTo(owner);
        assertThat(hold.orderReference()).isEqualTo("ORD-HOLD-1");
        assertThat(hold.state()).isEqualTo(ReservationState.HELD);
        assertThat(hold.amount()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(hold.settledAmount()).isNull();
        assertThat(hold.currency()).isEqualTo(CURRENCY);
        assertThat(hold.expiresAt()).isNotNull();
        assertThat(hold.createdAt()).isNotNull();

        InstitutionalAccountResponse account = account(owner);
        assertThat(account.availableBalance()).isEqualByComparingTo(new BigDecimal("750.00"));
        assertThat(account.reservedBalance()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(account.totalBalance()).isEqualByComparingTo(new BigDecimal("1000.00"));

        List<LedgerEntryResponse> holdRows = rowsOf(ledger(owner), LedgerEventType.HOLD, hold.reservationId());
        assertThat(holdRows).hasSize(1);
        LedgerEntryResponse row = holdRows.get(0);
        assertThat(row.amount()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(row.availableAfter()).isEqualByComparingTo(new BigDecimal("750.00"));
        assertThat(row.reservedAfter()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(row.currency()).isEqualTo(CURRENCY);
        assertThat(row.orderReference()).isEqualTo("ORD-HOLD-1");
        assertThat(row.source()).isEqualTo(LedgerEntry.Source.INSTITUTIONAL);
        assertThat(row.recordedAt()).isNotNull();
    }

    @Test
    void replayOfAnIdenticalPayloadReturnsTheStoredHoldAndMovesNothingTwice() throws Exception {
        String owner = "REPLAY1";
        openAccount(owner);
        String key = "IDEM-REPLAY-1";
        String body = holdBody("ORD-REPLAY-1", "250.00", FIXED_EXPIRY);

        ResponseEntity<String> first = postHold(owner, key, body);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> replay = postHold(owner, key, body);

        assertReplayedFrom(first, replay);
        assertHeldExactlyOnce(owner, reservationOf(first).reservationId(), "250.00", "750.00");
    }

    @Test
    void replayWithTheExpiryOmittedReturnsTheStoredHold() throws Exception {
        String owner = "REPLAYDEFAULT";
        openAccount(owner);
        String key = "IDEM-REPLAY-DEFAULT";
        // The canonical hash contributes the literal DEFAULT for an absent expiry (AAP 0.7.3), so the
        // server-generated default-ttl expiry - which differs by the time between the two calls - cannot turn
        // this replay into key reuse.
        String body = holdBody("ORD-REPLAY-DEFAULT", "250.00", null);

        ResponseEntity<String> first = postHold(owner, key, body);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> replay = postHold(owner, key, body);

        assertReplayedFrom(first, replay);
        assertHeldExactlyOnce(owner, reservationOf(first).reservationId(), "250.00", "750.00");
    }

    @Test
    void replayIsInsensitiveToTheScaleTheAmountWasWrittenWith() throws Exception {
        String owner = "REPLAYSCALE";
        openAccount(owner);
        String key = "IDEM-REPLAY-SCALE";

        // Two genuinely different wire literals, sent as raw text so no client-side normalisation can hide the
        // difference. They replay because the canonical hash fixes the amount to scale 2 before hashing
        // (AAP 0.7.3), which is what makes 10, 10.0 and 10.00 one and the same request.
        ResponseEntity<String> first = postHold(owner, key, holdBody("ORD-REPLAY-SCALE", "10.0", FIXED_EXPIRY));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> replay = postHold(owner, key, holdBody("ORD-REPLAY-SCALE", "10.00", FIXED_EXPIRY));

        assertReplayedFrom(first, replay);
        assertHeldExactlyOnce(owner, reservationOf(first).reservationId(), "10.00", "990.00");
    }

    @Test
    void theSameKeyCarryingADifferentPayloadIsRejectedAsReuse() throws Exception {
        String owner = "REUSE1";
        openAccount(owner);
        String key = "IDEM-REUSE-1";

        ResponseEntity<String> first = postHold(owner, key, holdBody("ORD-REUSE-1", "10.00", FIXED_EXPIRY));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> reused = postHold(owner, key, holdBody("ORD-REUSE-1", "20.00", FIXED_EXPIRY));

        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(errorOf(reused).code()).isEqualTo(CashAccountErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertHeldExactlyOnce(owner, reservationOf(first).reservationId(), "10.00", "990.00");
    }

    @Test
    void fullSettlementConsumesTheHoldAndReleasesNothing() throws Exception {
        String owner = "SETTLE1";
        ReservationResponse hold = openAccountAndHold(owner, "IDEM-SETTLE-1", "ORD-SETTLE-1", "250.00",
                FIXED_EXPIRY);

        ResponseEntity<String> response = postSettle(hold.reservationId(), new SettleRequest(null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ReservationResponse settled = reservationOf(response);
        assertThat(settled.state()).isEqualTo(ReservationState.SETTLED);
        assertThat(settled.settledAmount()).isEqualByComparingTo(new BigDecimal("250.00"));

        InstitutionalAccountResponse account = account(owner);
        assertThat(account.availableBalance()).isEqualByComparingTo(new BigDecimal("750.00"));
        assertThat(account.reservedBalance()).isEqualByComparingTo(ZERO);
        assertThat(account.totalBalance()).isEqualByComparingTo(new BigDecimal("750.00"));

        List<LedgerEntryResponse> rows = ledger(owner);
        List<LedgerEntryResponse> settlements =
                rowsOf(rows, LedgerEventType.SETTLEMENT, hold.reservationId());
        assertThat(settlements).hasSize(1);
        assertThat(settlements.get(0).amount()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(settlements.get(0).availableAfter()).isEqualByComparingTo(new BigDecimal("750.00"));
        assertThat(settlements.get(0).reservedAfter()).isEqualByComparingTo(ZERO);
        assertThat(rowsOf(rows, LedgerEventType.RELEASE, hold.reservationId())).isEmpty();
    }

    @Test
    void partialSettlementSettlesItsAmountAndReleasesTheRemainder() throws Exception {
        String owner = "PARTIAL1";
        ReservationResponse hold = openAccountAndHold(owner, "IDEM-PARTIAL-1", "ORD-PARTIAL-1", "250.00",
                FIXED_EXPIRY);

        ResponseEntity<String> response =
                postSettle(hold.reservationId(), new SettleRequest(new BigDecimal("100.00")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ReservationResponse settled = reservationOf(response);
        assertThat(settled.state()).isEqualTo(ReservationState.SETTLED);
        assertThat(settled.settledAmount()).isEqualByComparingTo(new BigDecimal("100.00"));

        InstitutionalAccountResponse account = account(owner);
        assertThat(account.availableBalance()).isEqualByComparingTo(new BigDecimal("900.00"));
        assertThat(account.reservedBalance()).isEqualByComparingTo(ZERO);

        // Both rows belong to one transaction: the settled portion leaves the account and the 150.00 remainder
        // returns to available funds, which is why a partial settlement is two ledger rows and not one.
        List<LedgerEntryResponse> rows = ledger(owner);
        List<LedgerEntryResponse> settlements =
                rowsOf(rows, LedgerEventType.SETTLEMENT, hold.reservationId());
        List<LedgerEntryResponse> releases = rowsOf(rows, LedgerEventType.RELEASE, hold.reservationId());
        assertThat(settlements).hasSize(1);
        assertThat(settlements.get(0).amount()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(releases).hasSize(1);
        assertThat(releases.get(0).amount()).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(releases.get(0).availableAfter()).isEqualByComparingTo(new BigDecimal("900.00"));
        assertThat(releases.get(0).reservedAfter()).isEqualByComparingTo(ZERO);
    }

    @Test
    void releaseReturnsTheWholeHoldToAvailableFunds() throws Exception {
        String owner = "RELEASE1";
        ReservationResponse hold = openAccountAndHold(owner, "IDEM-RELEASE-1", "ORD-RELEASE-1", "250.00",
                FIXED_EXPIRY);

        ResponseEntity<String> response = postRelease(hold.reservationId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reservationOf(response).state()).isEqualTo(ReservationState.RELEASED);

        InstitutionalAccountResponse account = account(owner);
        assertThat(account.availableBalance()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(account.reservedBalance()).isEqualByComparingTo(ZERO);

        List<LedgerEntryResponse> releases =
                rowsOf(ledger(owner), LedgerEventType.RELEASE, hold.reservationId());
        assertThat(releases).hasSize(1);
        assertThat(releases.get(0).amount()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(releases.get(0).availableAfter()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(releases.get(0).reservedAfter()).isEqualByComparingTo(ZERO);
    }

    @Test
    void theSweepExpiresAnOverdueHoldAndReturnsItsFunds() throws Exception {
        String owner = "EXPIRY1";
        // Accepted as handed in: the hold error set carries no code for a past expiry, so the reservation is
        // created HELD and already overdue - which is the only way to reach the sweep without waiting on a clock.
        ReservationResponse hold = openAccountAndHold(owner, "IDEM-EXPIRY-1", "ORD-EXPIRY-1", "250.00",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));

        // Called directly, never waited for: application-test.yml moves the @Scheduled interval out to PT1H
        // precisely so the only sweep that runs during a test is the one the test asked for.
        assertThat(reservationService.sweepExpiredReservations()).isGreaterThanOrEqualTo(1);

        ResponseEntity<String> reread = getReservation(hold.reservationId());
        assertThat(reread.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reservationOf(reread).state()).isEqualTo(ReservationState.EXPIRED);

        InstitutionalAccountResponse account = account(owner);
        assertThat(account.availableBalance()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(account.reservedBalance()).isEqualByComparingTo(ZERO);

        List<LedgerEntryResponse> expiries =
                rowsOf(ledger(owner), LedgerEventType.EXPIRY, hold.reservationId());
        assertThat(expiries).hasSize(1);
        assertThat(expiries.get(0).amount()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(expiries.get(0).availableAfter()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(expiries.get(0).reservedAfter()).isEqualByComparingTo(ZERO);
        // SYSTEM rather than INSTITUTIONAL: the expiry is the service acting on its own schedule with no caller.
        assertThat(expiries.get(0).source()).isEqualTo(LedgerEntry.Source.SYSTEM);
    }

    @Test
    @Timeout(60)
    void concurrentIdenticalHoldsCreateExactlyOneReservation() throws Exception {
        String owner = "RACEHOLD1";
        openAccount(owner);
        String key = "IDEM-RACE-HOLD";
        String body = holdBody("ORD-RACE-HOLD", "250.00", FIXED_EXPIRY);

        // The loser of UNIQUE (incarnation_id, idempotency_key) has its transaction aborted by PostgreSQL, so it
        // cannot read the winner's row where the violation happened; it must re-read in a fresh transaction and
        // replay the winner's answer. Which thread loses is unknowable, hence the order-independent assertion.
        RaceOutcome<ResponseEntity<String>, ResponseEntity<String>> outcome =
                race(() -> postHold(owner, key, body), () -> postHold(owner, key, body));
        List<ResponseEntity<String>> responses = List.of(outcome.first(), outcome.second());

        assertThat(statusesOf(responses)).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.OK);
        UUID reservationId = reservationOf(withStatus(responses, HttpStatus.CREATED)).reservationId();
        ReservationResponse replayed = reservationOf(withStatus(responses, HttpStatus.OK));
        assertThat(replayed.reservationId()).isEqualTo(reservationId);
        assertThat(replayed.amount()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(replayed.state()).isEqualTo(ReservationState.HELD);
        assertHeldExactlyOnce(owner, reservationId, "250.00", "750.00");
    }

    @Test
    @Timeout(60)
    void concurrentSameKeyHoldsWithDifferentPayloadsYieldOneReuseRejection() throws Exception {
        String owner = "RACEREUSE1";
        openAccount(owner);
        String key = "IDEM-RACE-REUSE";

        RaceOutcome<ResponseEntity<String>, ResponseEntity<String>> outcome = race(
                () -> postHold(owner, key, holdBody("ORD-RACE-REUSE", "250.00", FIXED_EXPIRY)),
                () -> postHold(owner, key, holdBody("ORD-RACE-REUSE", "125.00", FIXED_EXPIRY)));
        List<ResponseEntity<String>> responses = List.of(outcome.first(), outcome.second());

        // Order-independent again: either payload may win the constraint, and the one that loses is reuse rather
        // than a replay because its hash differs from the stored one.
        assertThat(statusesOf(responses))
                .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.UNPROCESSABLE_ENTITY);
        ResponseEntity<String> rejected = withStatus(responses, HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(errorOf(rejected).code()).isEqualTo(CashAccountErrorCode.IDEMPOTENCY_KEY_REUSED);

        ReservationResponse winner = reservationOf(withStatus(responses, HttpStatus.CREATED));
        assertThat(reservationRowCount(owner)).isEqualTo(1);
        assertThat(rowsOf(ledger(owner), LedgerEventType.HOLD)).hasSize(1);
        assertThat(rowsOf(ledger(owner), LedgerEventType.HOLD, winner.reservationId())).hasSize(1);
    }

    @Test
    @Timeout(60)
    void aSweepRacingASettleLeavesExactlyOneTerminalTransition() throws Exception {
        String owner = "RACESWEEP1";
        ReservationResponse hold = openAccountAndHold(owner, "IDEM-RACE-SWEEP", "ORD-RACE-SWEEP", "250.00",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));

        // Both paths take the cash_account row lock before the reservation row, so one of them finds the hold
        // already terminal and writes nothing. The winner is therefore not asserted - only the invariants that
        // hold whichever way it went, which is what keeps this test truthful instead of merely green.
        RaceOutcome<Integer, ResponseEntity<String>> outcome =
                race(() -> reservationService.sweepExpiredReservations(),
                        () -> postSettle(hold.reservationId(), new SettleRequest(null)));

        ReservationResponse finalState = reservationOf(getReservation(hold.reservationId()));
        assertThat(finalState.state()).isIn(ReservationState.EXPIRED, ReservationState.SETTLED);

        List<LedgerEntryResponse> rows = ledger(owner);
        List<LedgerEntryResponse> terminal = rows.stream()
                .filter(row -> hold.reservationId().equals(row.reservationId()))
                .filter(row -> row.eventType() == LedgerEventType.EXPIRY
                        || row.eventType() == LedgerEventType.SETTLEMENT)
                .toList();
        assertThat(terminal).hasSize(1);
        assertThat(rowsOf(rows, LedgerEventType.RELEASE, hold.reservationId())).isEmpty();

        InstitutionalAccountResponse account = account(owner);
        assertThat(account.reservedBalance()).isEqualByComparingTo(ZERO);
        LedgerEntryResponse row = terminal.get(0);
        if (row.eventType() == LedgerEventType.EXPIRY) {
            assertThat(finalState.state()).isEqualTo(ReservationState.EXPIRED);
            assertThat(account.availableBalance()).isEqualByComparingTo(new BigDecimal("1000.00"));
            assertThat(outcome.second().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(errorOf(outcome.second()).code()).isEqualTo(CashAccountErrorCode.INVALID_TRANSITION);
        } else {
            assertThat(finalState.state()).isEqualTo(ReservationState.SETTLED);
            assertThat(account.availableBalance()).isEqualByComparingTo(new BigDecimal("750.00"));
            assertThat(outcome.second().getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(row.availableAfter()).isEqualByComparingTo(account.availableBalance());
        assertThat(row.reservedAfter()).isEqualByComparingTo(ZERO);
        // This owner holds one overdue reservation, so the pass either performed its expiry or found the
        // settle's own lazy expiry had already committed one; it can never expire the same hold twice.
        assertThat(outcome.first()).isBetween(0, 1);
    }

    private void openAccount(String owner) throws Exception {
        String body = objectMapper
                .writeValueAsString(new CashAccountResponse(owner, new BigDecimal(OPENING_BALANCE), CURRENCY));
        ResponseEntity<String> created = rest.exchange(url(RETAIL_BASE + "/" + owner), HttpMethod.POST,
                new HttpEntity<>(body, authJson()), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ReservationResponse openAccountAndHold(String owner, String idempotencyKey, String orderReference,
            String amount, OffsetDateTime expiresAt) throws Exception {

        openAccount(owner);
        ResponseEntity<String> response =
                postHold(owner, idempotencyKey, holdBody(orderReference, amount, expiresAt));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return reservationOf(response);
    }

    private ResponseEntity<String> postHold(String owner, String idempotencyKey, String jsonBody) {
        HttpHeaders headers = authJson();
        headers.set(ReservationController.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        return rest.exchange(url(INSTITUTIONAL_BASE + "/accounts/" + owner + "/holds"), HttpMethod.POST,
                new HttpEntity<>(jsonBody, headers), String.class);
    }

    private ResponseEntity<String> postSettle(UUID reservationId, SettleRequest request) throws Exception {
        return rest.exchange(url(INSTITUTIONAL_BASE + "/reservations/" + reservationId + "/settle"),
                HttpMethod.POST, new HttpEntity<>(objectMapper.writeValueAsString(request), authJson()),
                String.class);
    }

    private ResponseEntity<String> postRelease(UUID reservationId) {
        return rest.exchange(url(INSTITUTIONAL_BASE + "/reservations/" + reservationId + "/release"),
                HttpMethod.POST, new HttpEntity<>(authJson()), String.class);
    }

    private ResponseEntity<String> getReservation(UUID reservationId) {
        return rest.exchange(url(INSTITUTIONAL_BASE + "/reservations/" + reservationId), HttpMethod.GET,
                new HttpEntity<>(authJson()), String.class);
    }

    private InstitutionalAccountResponse account(String owner) throws Exception {
        ResponseEntity<String> response = rest.exchange(url(INSTITUTIONAL_BASE + "/accounts/" + owner),
                HttpMethod.GET, new HttpEntity<>(authJson()), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(response.getBody(), InstitutionalAccountResponse.class);
    }

    private List<LedgerEntryResponse> ledger(String owner) throws Exception {
        ResponseEntity<String> response = rest.exchange(url(INSTITUTIONAL_BASE + "/accounts/" + owner + "/ledger"),
                HttpMethod.GET, new HttpEntity<>(authJson()), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(response.getBody(), new TypeReference<List<LedgerEntryResponse>>() { });
    }

    private int reservationRowCount(String owner) {
        Integer count = jdbc.queryForObject("select count(*) from cash_reservation where owner = ?",
                Integer.class, owner);
        return count == null ? 0 : count;
    }

    private void assertReplayedFrom(ResponseEntity<String> first, ResponseEntity<String> replay)
            throws Exception {

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getHeaders().getFirst(ReservationController.IDEMPOTENT_REPLAYED_HEADER))
                .isEqualTo("true");
        ReservationResponse original = reservationOf(first);
        ReservationResponse stored = reservationOf(replay);
        assertThat(stored.reservationId()).isEqualTo(original.reservationId());
        assertThat(stored.amount()).isEqualByComparingTo(original.amount());
        assertThat(stored.state()).isEqualTo(original.state());
    }

    private void assertHeldExactlyOnce(String owner, UUID reservationId, String heldAmount,
            String availableAfter) throws Exception {

        assertThat(reservationRowCount(owner)).isEqualTo(1);
        assertThat(rowsOf(ledger(owner), LedgerEventType.HOLD, reservationId)).hasSize(1);
        InstitutionalAccountResponse account = account(owner);
        assertThat(account.availableBalance()).isEqualByComparingTo(new BigDecimal(availableAfter));
        assertThat(account.reservedBalance()).isEqualByComparingTo(new BigDecimal(heldAmount));
        assertThat(account.totalBalance()).isEqualByComparingTo(new BigDecimal(OPENING_BALANCE));
    }

    private ReservationResponse reservationOf(ResponseEntity<String> response) throws Exception {
        return objectMapper.readValue(response.getBody(), ReservationResponse.class);
    }

    private ApiError errorOf(ResponseEntity<String> response) throws Exception {
        return objectMapper.readValue(response.getBody(), ApiError.class);
    }

    private HttpHeaders authJson() {
        HttpHeaders headers = new HttpHeaders();
        // Every institutional path demands ROLE_StockTrader; the role matrix itself belongs to RoleEnforcementIT.
        headers.set(HttpHeaders.AUTHORIZATION, JwtTestTokens.bearer(JwtTestTokens.stockTraderToken()));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // Hand-built rather than serialized from a HoldRequest: the scale case needs two payloads that differ as
    // TEXT on the wire, and a record would normalise both to the same rendering before they were ever sent.
    private static String holdBody(String orderReference, String amount, OffsetDateTime expiresAt) {
        return "{\"orderReference\":\"" + orderReference + "\",\"amount\":" + amount
                + ",\"currency\":\"" + CURRENCY + "\""
                + (expiresAt == null ? "" : ",\"expiresAt\":\"" + expiresAt + "\"") + "}";
    }

    private static List<LedgerEntryResponse> rowsOf(List<LedgerEntryResponse> rows, LedgerEventType eventType) {
        return rows.stream().filter(row -> row.eventType() == eventType).toList();
    }

    private static List<LedgerEntryResponse> rowsOf(List<LedgerEntryResponse> rows, LedgerEventType eventType,
            UUID reservationId) {
        return rowsOf(rows, eventType).stream()
                .filter(row -> reservationId.equals(row.reservationId()))
                .toList();
    }

    private static List<HttpStatusCode> statusesOf(List<ResponseEntity<String>> responses) {
        return responses.stream().map(ResponseEntity::getStatusCode).toList();
    }

    private static ResponseEntity<String> withStatus(List<ResponseEntity<String>> responses, HttpStatus status) {
        return responses.stream()
                .filter(response -> status.equals(response.getStatusCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No response carried " + status + ": "
                        + statusesOf(responses)));
    }

    /** The two results of one race, kept apart so a heterogeneous pair needs no common supertype. */
    private record RaceOutcome<A, B>(A first, B second) {
    }

    private <A, B> RaceOutcome<A, B> race(Callable<A> first, Callable<B> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        // A barrier, not a sequential start: both calls must be in flight at once for the constraint and the
        // row locks to actually contend, which is the whole point of these three cases.
        CyclicBarrier gate = new CyclicBarrier(2);
        try {
            Future<A> firstResult = pool.submit(atBarrier(gate, first));
            Future<B> secondResult = pool.submit(atBarrier(gate, second));
            // Bounded so a deadlock fails the build instead of hanging it, as does the @Timeout on each caller.
            return new RaceOutcome<>(firstResult.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    secondResult.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static <T> Callable<T> atBarrier(CyclicBarrier gate, Callable<T> task) {
        return () -> {
            gate.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return task.call();
        };
    }
}

