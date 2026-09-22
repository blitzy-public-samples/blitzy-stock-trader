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
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.audit.LedgerEntryResponse;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.LedgerEntry;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.LedgerEventType;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.ReservationState;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.retail.CashAccountResponse;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.JwtTestTokens;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.PostgresTestSupport;

/** Proves each reservation transition's ledger row is readable in the request that immediately follows it. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditImmediacyIT extends PostgresTestSupport {

    private static final String RETAIL_BASE = "/cash-account";

    private static final String INSTITUTIONAL_BASE = "/cash-account/institutional";

    // USD is the configured FX base, where a same-currency rate short-circuits to exactly 1 with no outbound
    // call, so nothing here can reach the exchange-rate endpoint application-test.yml points at a refused port.
    private static final String CURRENCY = "USD";

    private static final BigDecimal OPENING_BALANCE = new BigDecimal("1000.00");

    private static final BigDecimal HELD_AMOUNT = new BigDecimal("250.00");

    private static final BigDecimal AVAILABLE_WHILE_HELD = new BigDecimal("750.00");

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    // The context's own mapper, so every body is read back through JacksonConfig's USE_BIG_DECIMAL_FOR_FLOATS
    // and WRITE_BIGDECIMAL_AS_PLAIN; a default mapper would bind money to a primitive numeric type instead.
    @Autowired
    private ObjectMapper objectMapper;

    // application-test.yml omits this property on purpose: the signer key pair is ephemeral per test JVM, so its
    // certificate can only be published at context-refresh time.
    @DynamicPropertySource
    static void jwtSignerCertificate(DynamicPropertyRegistry registry) {
        registry.add("cashaccount.security.jwt.public-key-location", JwtTestTokens::publicKeyLocation);
    }

    /*
     * WHY NOTHING IN THIS CLASS RUNS INSIDE A TEST-MANAGED TRANSACTION, AND WHY EACH TEST OWNS ITS OWN ACCOUNT.
     * The property under test is that a transition's ledger row is committed with the balance change and is
     * therefore visible to the very next reader (AAP 0.7.4) - so a test-managed transaction, whose writes are
     * never committed and are rolled back at the end, would assert nothing at all. Committed rows cannot be
     * cleaned up: ledger_entry carries a BEFORE UPDATE OR DELETE trigger (schema/cash-account-schema.sql) and
     * LedgerEntryRepository declares no delete method, by the same audit guarantee. Distinct short uppercase
     * owners, inside the 32-character cash_account.owner width and unused by every other *IT sharing this
     * JVM-wide container, are therefore the isolation mechanism, and no assertion here may assume an empty table.
     */

    @Test
    void holdAndSettleLedgerRowsAreVisibleInTheVeryNextCall() throws Exception {
        String owner = "AUDITSETTLE";
        openAccount(owner);

        ResponseEntity<String> held = postHold(owner, "IDEM-AUDIT-SETTLE", "ORD-AUDIT-SETTLE");
        assertThat(held.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID reservationId = reservationOf(held).reservationId();

        /*
         * The ledger query is deliberately the IMMEDIATELY next request - no sleep, no poll, no retry, no other
         * call in between - because the guarantee is same-transaction visibility and not eventual consistency:
         * LedgerService's append overloads all declare MANDATORY propagation, so the row can only have been
         * written inside the transaction the hold above committed. Anything that gave the write time to land
         * would turn this assertion into a statement about latency (AAP 0.10.5).
         */
        List<LedgerEntryResponse> afterHold = ledger(owner);

        LedgerEntryResponse holdRow = rowOf(afterHold, LedgerEventType.HOLD, reservationId);
        assertThat(holdRow.owner()).isEqualTo(owner);
        assertThat(holdRow.reservationId()).isEqualTo(reservationId);
        assertThat(holdRow.orderReference()).isEqualTo("ORD-AUDIT-SETTLE");
        assertThat(holdRow.amount()).isEqualByComparingTo(HELD_AMOUNT);
        assertThat(holdRow.currency()).isEqualTo(CURRENCY);
        assertThat(holdRow.availableAfter()).isEqualByComparingTo(AVAILABLE_WHILE_HELD);
        assertThat(holdRow.reservedAfter()).isEqualByComparingTo(HELD_AMOUNT);
        assertThat(holdRow.source()).isEqualTo(LedgerEntry.Source.INSTITUTIONAL);
        assertThat(holdRow.recordedAt()).isNotNull();
        assertNewestFirst(afterHold);

        InstitutionalAccountResponse heldAccount = account(owner);
        assertThat(holdRow.availableAfter()).isEqualByComparingTo(heldAccount.availableBalance());
        assertThat(holdRow.reservedAfter()).isEqualByComparingTo(heldAccount.reservedBalance());
        assertThat(heldAccount.availableBalance()).isEqualByComparingTo(AVAILABLE_WHILE_HELD);
        assertThat(heldAccount.reservedBalance()).isEqualByComparingTo(HELD_AMOUNT);
        // totalBalance comes from the aggregate's own accessor rather than being recomputed on the wire, so
        // asserting it here checks the invariant available + reserved == total that the split model rests on.
        assertThat(heldAccount.totalBalance()).isEqualByComparingTo(OPENING_BALANCE);

        ResponseEntity<String> settled = postSettle(reservationId);
        assertThat(settled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reservationOf(settled).state()).isEqualTo(ReservationState.SETTLED);

        List<LedgerEntryResponse> afterSettle = ledger(owner);

        LedgerEntryResponse settlementRow = rowOf(afterSettle, LedgerEventType.SETTLEMENT, reservationId);
        assertThat(settlementRow.amount()).isEqualByComparingTo(HELD_AMOUNT);
        assertThat(settlementRow.currency()).isEqualTo(CURRENCY);
        assertThat(settlementRow.availableAfter()).isEqualByComparingTo(AVAILABLE_WHILE_HELD);
        assertThat(settlementRow.reservedAfter()).isEqualByComparingTo(ZERO);
        assertThat(settlementRow.source()).isEqualTo(LedgerEntry.Source.INSTITUTIONAL);
        assertThat(settlementRow.recordedAt()).isNotNull();
        assertNewestFirst(afterSettle);
        // Identity, not position: the settlement is the newest transition on this owner, so the query's
        // recordedAt DESC, entryId DESC contract has to hand it back first.
        assertThat(afterSettle.get(0).entryId()).isEqualTo(settlementRow.entryId());

        InstitutionalAccountResponse settledAccount = account(owner);
        assertThat(settlementRow.availableAfter()).isEqualByComparingTo(settledAccount.availableBalance());
        assertThat(settlementRow.reservedAfter()).isEqualByComparingTo(settledAccount.reservedBalance());
        assertThat(settledAccount.availableBalance()).isEqualByComparingTo(AVAILABLE_WHILE_HELD);
        assertThat(settledAccount.reservedBalance()).isEqualByComparingTo(ZERO);
    }

    @Test
    void releaseLedgerRowIsVisibleInTheVeryNextCall() throws Exception {
        String owner = "AUDITRELEASE";
        openAccount(owner);

        ResponseEntity<String> held = postHold(owner, "IDEM-AUDIT-RELEASE", "ORD-AUDIT-RELEASE");
        assertThat(held.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID reservationId = reservationOf(held).reservationId();

        ResponseEntity<String> released = postRelease(reservationId);
        assertThat(released.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reservationOf(released).state()).isEqualTo(ReservationState.RELEASED);

        // Immediately next again, for the reason recorded in the first test: the release's own transaction is
        // the only place its ledger row can have been written.
        List<LedgerEntryResponse> afterRelease = ledger(owner);

        LedgerEntryResponse releaseRow = rowOf(afterRelease, LedgerEventType.RELEASE, reservationId);
        assertThat(releaseRow.owner()).isEqualTo(owner);
        assertThat(releaseRow.reservationId()).isEqualTo(reservationId);
        assertThat(releaseRow.orderReference()).isEqualTo("ORD-AUDIT-RELEASE");
        assertThat(releaseRow.amount()).isEqualByComparingTo(HELD_AMOUNT);
        assertThat(releaseRow.currency()).isEqualTo(CURRENCY);
        assertThat(releaseRow.availableAfter()).isEqualByComparingTo(OPENING_BALANCE);
        assertThat(releaseRow.reservedAfter()).isEqualByComparingTo(ZERO);
        assertThat(releaseRow.source()).isEqualTo(LedgerEntry.Source.INSTITUTIONAL);
        assertThat(releaseRow.recordedAt()).isNotNull();
        assertNewestFirst(afterRelease);
        assertThat(afterRelease.get(0).entryId()).isEqualTo(releaseRow.entryId());

        InstitutionalAccountResponse releasedAccount = account(owner);
        assertThat(releaseRow.availableAfter()).isEqualByComparingTo(releasedAccount.availableBalance());
        assertThat(releaseRow.reservedAfter()).isEqualByComparingTo(releasedAccount.reservedBalance());
        assertThat(releasedAccount.availableBalance()).isEqualByComparingTo(OPENING_BALANCE);
        assertThat(releasedAccount.reservedBalance()).isEqualByComparingTo(ZERO);
        assertThat(releasedAccount.totalBalance()).isEqualByComparingTo(OPENING_BALANCE);
    }

    private void openAccount(String owner) throws Exception {
        String body =
                objectMapper.writeValueAsString(new CashAccountResponse(owner, OPENING_BALANCE, CURRENCY));
        ResponseEntity<String> created = rest.exchange(url(RETAIL_BASE + "/" + owner), HttpMethod.POST,
                new HttpEntity<>(body, authJson()), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> postHold(String owner, String idempotencyKey, String orderReference)
            throws Exception {

        HttpHeaders headers = authJson();
        headers.set(ReservationController.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        // expiresAt omitted so the configured default TTL applies: no assertion in this class reads the expiry,
        // and the sweep interval is moved out to PT1H on the test profile, so nothing can expire mid-test.
        String body = objectMapper
                .writeValueAsString(new HoldRequest(orderReference, HELD_AMOUNT, CURRENCY, null));
        return rest.exchange(url(INSTITUTIONAL_BASE + "/accounts/" + owner + "/holds"), HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> postSettle(UUID reservationId) throws Exception {
        // A null amount is the documented full settlement, so the whole hold is consumed and nothing released.
        String body = objectMapper.writeValueAsString(new SettleRequest(null));
        return rest.exchange(url(INSTITUTIONAL_BASE + "/reservations/" + reservationId + "/settle"),
                HttpMethod.POST, new HttpEntity<>(body, authJson()), String.class);
    }

    private ResponseEntity<String> postRelease(UUID reservationId) {
        return rest.exchange(url(INSTITUTIONAL_BASE + "/reservations/" + reservationId + "/release"),
                HttpMethod.POST, new HttpEntity<>(authJson()), String.class);
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

    private ReservationResponse reservationOf(ResponseEntity<String> response) throws Exception {
        return objectMapper.readValue(response.getBody(), ReservationResponse.class);
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

    // Rows are located by event type and reservation, never by list position: opening the account writes its own
    // ACCOUNT_CREATED row from the retail seam, and these owners accumulate history that is never deleted.
    private static LedgerEntryResponse rowOf(List<LedgerEntryResponse> rows, LedgerEventType eventType,
            UUID reservationId) {

        List<LedgerEntryResponse> matches = rows.stream()
                .filter(row -> row.eventType() == eventType)
                .filter(row -> reservationId.equals(row.reservationId()))
                .toList();
        assertThat(matches).as("%s rows for reservation %s", eventType, reservationId).hasSize(1);
        return matches.get(0);
    }

    private static void assertNewestFirst(List<LedgerEntryResponse> rows) {
        for (int index = 1; index < rows.size(); index++) {
            LedgerEntryResponse newer = rows.get(index - 1);
            LedgerEntryResponse older = rows.get(index);
            assertThat(newer.recordedAt().toInstant()).isAfterOrEqualTo(older.recordedAt().toInstant());
            // The entryId tie-break is load-bearing rather than decorative: rows appended in one transaction
            // share a recordedAt - a partial settlement writes two - so the generated identity is the only
            // thing that can order them, and a query without it would return them in an arbitrary order.
            if (newer.recordedAt().toInstant().equals(older.recordedAt().toInstant())) {
                assertThat(newer.entryId()).isGreaterThan(older.entryId());
            }
        }
    }
}
