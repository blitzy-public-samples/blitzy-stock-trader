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

    // Every test owns its own account because the rows this suite writes cannot be removed between tests:
    // ledger_entry carries a BEFORE UPDATE OR DELETE trigger (schema/cash-account-schema.sql) and that
    // immutability is the audit guarantee under test (AAP 0.7.4). Distinct owners are therefore the isolation
    // mechanism, and no assertion in this class may assume an empty table.

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
    void replayIsRecognizedFromTheReturnedOrderReferenceAndOnlyFromTheExactPayload() throws Exception {
        String owner = "REPLAYECHO";
        String key = "IDEM-REPLAY-ECHO";
        // A blank-padded order reference and an explicit expiry in a non-UTC offset at nanosecond precision are
        // treated differently: the reference is stored and returned verbatim because the idempotency hash is
        // taken from it exactly (AAP 0.7.3), while the expiry is returned as the same instant in UTC at the
        // microsecond resolution TIMESTAMPTZ keeps, so the body cannot differ from a later read of the row.
        String orderReference = "  ORD-REPLAY-ECHO  ";
        OffsetDateTime expiry = OffsetDateTime.parse("2099-06-01T14:00:00.123456789+02:00");
        ResponseEntity<String> first =
                openAccountAndPostHold(owner, key, holdBody(orderReference, "250.00", expiry));

        ReservationResponse created = reservationOf(first);
        assertThat(created.orderReference()).isEqualTo(orderReference);
        assertThat(created.expiresAt()).isEqualTo(OffsetDateTime.parse("2099-06-01T12:00:00.123456Z"));

        // The audit row records the reference the reservation holds, character for character. The ledger is
        // the half of this pair that can never be corrected - ledger_entry accepts no UPDATE - so a row that
        // dropped the blanks would leave one hold with two permanently different identities, and a later
        // reconciliation joining the two on this value would find nothing (AAP 0.7.3, 0.7.4).
        List<LedgerEntryResponse> holdRows =
                rowsOf(ledger(owner), LedgerEventType.HOLD, created.reservationId());
        assertThat(holdRows).hasSize(1);
        assertThat(holdRows.get(0).orderReference()).isEqualTo(orderReference);

        // The reference exactly as the response handed it back: the same request, so the original body comes
        // back. A reference altered on its way into the column would hash to something else and be refused.
        assertReplayedFrom(first, postHold(owner, key,
                holdBody(created.orderReference(), "250.00", expiry)));

        // Payloads differing only where the canonical form compares exactly - the blanks around the
        // reference, and an expiry one nanosecond away - are different requests under one key, and the
        // contract reports that rather than answering with the stored hold.
        ResponseEntity<String> strippedReference =
                postHold(owner, key, holdBody(orderReference.strip(), "250.00", expiry));
        assertThat(strippedReference.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(errorOf(strippedReference).code()).isEqualTo(CashAccountErrorCode.IDEMPOTENCY_KEY_REUSED);

        ResponseEntity<String> nearbyExpiry =
                postHold(owner, key, holdBody(orderReference, "250.00", expiry.minusNanos(1)));
        assertThat(nearbyExpiry.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(errorOf(nearbyExpiry).code()).isEqualTo(CashAccountErrorCode.IDEMPOTENCY_KEY_REUSED);

        assertHeldExactlyOnce(owner, created.reservationId(), "250.00", "750.00");
    }

    @Test
    void replayAfterATerminalTransitionStillReturnsTheOriginalHeldResponse() throws Exception {
        // One test across the three terminal states rather than three near-identical ones (AAP 0.7.6): the
        // property is single - a repeated key answers with the body its creating call returned - and what has
        // to be shown is that it survives however the hold ended, since the row's own state, settled amount
        // and updated_at have all moved on by the time the retry arrives.
        String settleKey = "IDEM-REPLAY-AFTER-SETTLE";
        String settleBody = holdBody("ORD-REPLAY-AFTER-SETTLE", "250.00", FIXED_EXPIRY);
        ResponseEntity<String> settledFirst = openAccountAndPostHold("REPLAYSETTLED", settleKey, settleBody);
        UUID settledId = reservationOf(settledFirst).reservationId();
        assertThat(postSettle(settledId, new SettleRequest(null)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertReplayedFrom(settledFirst, postHold("REPLAYSETTLED", settleKey, settleBody));

        String releaseKey = "IDEM-REPLAY-AFTER-RELEASE";
        String releaseBody = holdBody("ORD-REPLAY-AFTER-RELEASE", "250.00", FIXED_EXPIRY);
        ResponseEntity<String> releasedFirst = openAccountAndPostHold("REPLAYRELEASED", releaseKey, releaseBody);
        UUID releasedId = reservationOf(releasedFirst).reservationId();
        assertThat(postRelease(releasedId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertReplayedFrom(releasedFirst, postHold("REPLAYRELEASED", releaseKey, releaseBody));

        // An expiry needs an overdue hold, so this one is created with a past expiresAt - accepted as handed
        // in, the hold error set carries no code for it - and the sweep is called directly rather than waited
        // for, as in the sweep case above. The replay re-sends this same body text, so the hash matches.
        String expiryKey = "IDEM-REPLAY-AFTER-EXPIRY";
        String expiryBody = holdBody("ORD-REPLAY-AFTER-EXPIRY", "250.00",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        ResponseEntity<String> expiredFirst = openAccountAndPostHold("REPLAYEXPIRED", expiryKey, expiryBody);
        UUID expiredId = reservationOf(expiredFirst).reservationId();
        assertThat(reservationService.sweepExpiredReservations()).isGreaterThanOrEqualTo(1);
        assertReplayedFrom(expiredFirst, postHold("REPLAYEXPIRED", expiryKey, expiryBody));

        // The three replays reported HELD because that is the answer owed to a retry; the reservations
        // themselves really are terminal, and this is where a caller asking for current state is served.
        assertThat(reservationOf(getReservation(settledId)).state()).isEqualTo(ReservationState.SETTLED);
        assertThat(reservationOf(getReservation(releasedId)).state()).isEqualTo(ReservationState.RELEASED);
        assertThat(reservationOf(getReservation(expiredId)).state()).isEqualTo(ReservationState.EXPIRED);
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
    void aHoldWithNoIdempotencyKeyHeaderIsRefused() throws Exception {
        String owner = "NOKEY1";
        openAccount(owner);

        // The header is declared required = false precisely so this request reaches the service and is answered
        // with the contract's own 400 IDEMPOTENCY_KEY_REQUIRED (AAP 0.6.2) rather than Spring's generic
        // missing-header error, and nothing but a request that omits it can prove that wiring still holds.
        ResponseEntity<String> refused = postHold(owner, null, holdBody("ORD-NO-KEY", "250.00", FIXED_EXPIRY));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorOf(refused).code()).isEqualTo(CashAccountErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        assertNothingHeld(owner);
    }

    @Test
    void aHoldWhoseCurrencyIsMalformedIsRefused() throws Exception {
        String owner = "BADCCY1";
        openAccount(owner);

        // HoldRequest constrains currency with @NotBlank alone, so "US" passes Bean Validation and the code that
        // decides is the service's ^[A-Z]{3}$ check - which is why the assertion is on INVALID_CURRENCY and not
        // on a validation failure. A hold that never reaches the service would pass this test for the wrong
        // reason, so the balance and ledger assertions below fix which layer refused it.
        ResponseEntity<String> refused =
                postHold(owner, "IDEM-BAD-CURRENCY", holdBody("ORD-BAD-CURRENCY", "250.00", FIXED_EXPIRY, "US"));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorOf(refused).code()).isEqualTo(CashAccountErrorCode.INVALID_CURRENCY);
        assertNothingHeld(owner);
    }

    @Test
    void aKeyRetainedFromADeletedAccountsLifeIsReuseInTheNewOne() throws Exception {
        String owner = "REUSEINCARN";
        String key = "IDEM-REUSE-INCARNATION";
        String orderReference = "ORD-REUSE-INCARNATION";

        // Reservation rows carry no foreign key and survive the retail DELETE, so the new account's incarnation_id
        // makes (incarnation_id, idempotency_key) unique again and the database guard cannot refuse this key. If
        // the service did not refuse it either, the identical payload would be answered as a replay of a hold that
        // reserved funds in an account life that no longer exists - or worse, held the money a second time
        // (AAP 0.6.3 cash_reservation, AAP 0.11.1). The release before the DELETE is required: a retail delete is
        // refused with RESERVATIONS_OUTSTANDING while any hold is still HELD.
        ReservationResponse firstLife = openAccountAndHold(owner, key, orderReference, "250.00", FIXED_EXPIRY);
        assertThat(postRelease(firstLife.reservationId()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deleteAccount(owner).getStatusCode()).isEqualTo(HttpStatus.OK);
        openAccount(owner);

        ResponseEntity<String> reused = postHold(owner, key, holdBody(orderReference, "250.00", FIXED_EXPIRY));

        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(errorOf(reused).code()).isEqualTo(CashAccountErrorCode.IDEMPOTENCY_KEY_REUSED);
        // One reservation row and one HOLD row for this owner in total - both the first life's, neither the
        // refused call's - and the new account still holds its opening balance with nothing reserved.
        assertThat(reservationRowCount(owner)).isEqualTo(1);
        assertThat(rowsOf(ledger(owner), LedgerEventType.HOLD)).hasSize(1);
        assertThat(rowsOf(ledger(owner), LedgerEventType.HOLD, firstLife.reservationId())).hasSize(1);
        InstitutionalAccountResponse account = account(owner);
        assertThat(account.availableBalance()).isEqualByComparingTo(new BigDecimal(OPENING_BALANCE));
        assertThat(account.reservedBalance()).isEqualByComparingTo(ZERO);
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
        assertThat(releases).hasSize(1);
        LedgerEntryResponse settlement = settlements.get(0);
        LedgerEntryResponse release = releases.get(0);
        assertThat(settlement.amount()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(release.amount()).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(release.availableAfter()).isEqualByComparingTo(new BigDecimal("900.00"));
        assertThat(release.reservedAfter()).isEqualByComparingTo(ZERO);
        // Every row of one transition carries the same post-transition balances, so the pair is
        // indistinguishable by balance and the event type, the amount and the identity are all that separate
        // them - which is precisely why the query's tie-break has to be the identity.
        assertThat(settlement.availableAfter()).isEqualByComparingTo(new BigDecimal("900.00"));
        assertThat(settlement.reservedAfter()).isEqualByComparingTo(ZERO);

        // The primary half of the ordering contract, on the only transition that writes two rows at once:
        // entry_id is GENERATED ALWAYS AS IDENTITY and the rows are appended as the state machine named them -
        // SETTLEMENT then RELEASE - so under "recordedAt DESC, entryId DESC" (AAP 0.6.2) the RELEASE comes back
        // first. recorded_at is stamped per row, so these two usually differ and the secondary entryId ordering
        // is proved separately by theLedgerQueryBreaksARecordedAtTieOnTheEntryIdentity below.
        assertThat(settlement.entryId()).isNotNull();
        assertThat(release.entryId()).isNotNull();
        assertThat(release.entryId()).isGreaterThan(settlement.entryId());
        assertThat(release.recordedAt().toInstant())
                .isAfterOrEqualTo(settlement.recordedAt().toInstant());
        assertThat(rows.get(0).entryId()).isEqualTo(release.entryId());
        assertThat(rows.get(1).entryId()).isEqualTo(settlement.entryId());
        assertNewestFirst(rows);
    }

    @Test
    void theLedgerQueryBreaksARecordedAtTieOnTheEntryIdentity() throws Exception {
        // The secondary half of "recordedAt DESC, entryId DESC", which no transition can exercise on its own:
        // recorded_at is stamped per row, so even one partial settlement's two rows land microseconds apart and
        // sort correctly without the tie-break. The tie is therefore arranged at INSERT time - ledger_entry
        // refuses UPDATE - with generated identities, so the greater one belongs to the row inserted second.
        String owner = "TIEBREAK1";
        OffsetDateTime tie = OffsetDateTime.parse("2026-03-01T12:00:00Z");
        Long settlementId = insertLedgerRow(owner, tie, LedgerEventType.SETTLEMENT, "100.00");
        Long releaseId = insertLedgerRow(owner, tie, LedgerEventType.RELEASE, "150.00");
        assertThat(releaseId).isGreaterThan(settlementId);

        List<LedgerEntryResponse> rows = ledger(owner);

        assertThat(rows).extracting(LedgerEntryResponse::entryId)
                .containsExactly(releaseId, settlementId);
        // The tie is real rather than assumed: both rows carry the one instant they were written with, so the
        // order above can only have come from the identity.
        assertThat(rows.get(0).recordedAt().toInstant()).isEqualTo(tie.toInstant());
        assertThat(rows.get(1).recordedAt().toInstant()).isEqualTo(tie.toInstant());
        assertNewestFirst(rows);
    }

    @Test
    void aSubCentNegativeSettlementIsRejectedAndLeavesTheHoldIntact() throws Exception {
        String owner = "NEGSETTLE1";
        ReservationResponse hold = openAccountAndHold(owner, "IDEM-NEGSETTLE-1", "ORD-NEGSETTLE-1", "250.00",
                FIXED_EXPIRY);

        // SettleRequest carries no Bean Validation on purpose, because a settlement of zero is legal
        // (AAP 0.6.2), so domain/Money is the only thing between this body and a state transition. The sub-cent
        // magnitude is what makes the case: -0.001 truncates DOWN to 0.00 at scale 2, and a sign judged after
        // that normalization would read the request as the legal zero settlement - settling nothing while
        // releasing the whole hold, on a caller value that was never valid.
        ResponseEntity<String> response =
                postSettle(hold.reservationId(), new SettleRequest(new BigDecimal("-0.001")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorOf(response).code()).isEqualTo(CashAccountErrorCode.INVALID_AMOUNT);

        // The amount is parsed before the lazy-expiry commit and before any lock is taken, so a rejected settle
        // has to leave the reservation, the balance split and the ledger exactly as the hold left them.
        assertThat(reservationOf(getReservation(hold.reservationId())).state())
                .isEqualTo(ReservationState.HELD);

        InstitutionalAccountResponse account = account(owner);
        assertThat(account.availableBalance()).isEqualByComparingTo(new BigDecimal("750.00"));
        assertThat(account.reservedBalance()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(account.totalBalance()).isEqualByComparingTo(new BigDecimal(OPENING_BALANCE));

        // Owner-wide rather than filtered by reservation: this owner holds exactly one reservation, so any
        // settlement or release row at all would be the transition this rejection must have prevented.
        List<LedgerEntryResponse> rows = ledger(owner);
        assertThat(rowsOf(rows, LedgerEventType.SETTLEMENT)).isEmpty();
        assertThat(rowsOf(rows, LedgerEventType.RELEASE)).isEmpty();
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
    void aLapsedHoldIsExpiredAndCommittedByTheRequestThatTouchesIt() throws Exception {
        // The lazy-expiry path, deterministically rather than as the coin-flip half of the race below. A hold
        // created already overdue is expired by the very request that touches it, inside that request's
        // account-locking transaction; the settle is refused only after that transaction commits, which is why
        // the refusal is raised outside it - the EXPIRY row every transition owes the audit trail survives the
        // 409 instead of being rolled back with it. A release reports the expiry as its own answer, because an
        // expiry has already moved the money where a release would.
        String settleOwner = "LAPSESETTLE";
        ReservationResponse lapsedForSettle = openAccountAndHold(settleOwner, "IDEM-LAPSE-SETTLE",
                "ORD-LAPSE-SETTLE", "250.00", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));

        ResponseEntity<String> refused = postSettle(lapsedForSettle.reservationId(), new SettleRequest(null));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorOf(refused).code()).isEqualTo(CashAccountErrorCode.INVALID_TRANSITION);
        assertThat(reservationOf(getReservation(lapsedForSettle.reservationId())).state())
                .isEqualTo(ReservationState.EXPIRED);

        List<LedgerEntryResponse> settleOwnerRows = ledger(settleOwner);
        List<LedgerEntryResponse> expiries =
                rowsOf(settleOwnerRows, LedgerEventType.EXPIRY, lapsedForSettle.reservationId());
        assertThat(expiries).hasSize(1);
        assertThat(expiries.get(0).amount()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(expiries.get(0).availableAfter()).isEqualByComparingTo(new BigDecimal(OPENING_BALANCE));
        assertThat(expiries.get(0).reservedAfter()).isEqualByComparingTo(ZERO);
        // SYSTEM even though a caller's settle is what noticed it: the event is the TTL elapsing, which is
        // what makes a lazily expired hold indistinguishable from one the scheduled sweep reached first.
        assertThat(expiries.get(0).source()).isEqualTo(LedgerEntry.Source.SYSTEM);
        assertThat(rowsOf(settleOwnerRows, LedgerEventType.SETTLEMENT, lapsedForSettle.reservationId()))
                .isEmpty();
        InstitutionalAccountResponse afterRefusal = account(settleOwner);
        assertThat(afterRefusal.availableBalance()).isEqualByComparingTo(new BigDecimal(OPENING_BALANCE));
        assertThat(afterRefusal.reservedBalance()).isEqualByComparingTo(ZERO);

        String releaseOwner = "LAPSERELEASE";
        ReservationResponse lapsedForRelease = openAccountAndHold(releaseOwner, "IDEM-LAPSE-RELEASE",
                "ORD-LAPSE-RELEASE", "250.00", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));

        ResponseEntity<String> released = postRelease(lapsedForRelease.reservationId());

        assertThat(released.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reservationOf(released).state()).isEqualTo(ReservationState.EXPIRED);
        List<LedgerEntryResponse> releaseOwnerRows = ledger(releaseOwner);
        assertThat(rowsOf(releaseOwnerRows, LedgerEventType.EXPIRY, lapsedForRelease.reservationId()))
                .hasSize(1);
        // One terminal row for the hold, not an EXPIRY and a RELEASE: the expiry is the release's answer.
        assertThat(rowsOf(releaseOwnerRows, LedgerEventType.RELEASE, lapsedForRelease.reservationId()))
                .isEmpty();
        InstitutionalAccountResponse afterRelease = account(releaseOwner);
        assertThat(afterRelease.availableBalance()).isEqualByComparingTo(new BigDecimal(OPENING_BALANCE));
        assertThat(afterRelease.reservedBalance()).isEqualByComparingTo(ZERO);
    }

    @Test
    void terminalReservationsStillAnswerAfterTheirAccountIsDeleted() throws Exception {
        // A retail DELETE is refused with 409 RESERVATIONS_OUTSTANDING while any hold is HELD, so settling or
        // releasing and then deleting is the only shape in which a reservation outlives its account - and
        // cash_reservation carries no foreign key so the row survives as the audit record (AAP 0.11.1). A
        // retrying caller is therefore answered from the reservation's own state, never from the account lock:
        // its settle or release moves no money, so the account's absence must not become its error.
        String settledOwner = "GONESETTLED";
        ReservationResponse settledHold = openAccountAndHold(settledOwner, "IDEM-GONE-SETTLED",
                "ORD-GONE-SETTLED", "250.00", FIXED_EXPIRY);
        assertThat(postSettle(settledHold.reservationId(), new SettleRequest(null)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(deleteAccount(settledOwner).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> settleAgain = postSettle(settledHold.reservationId(), new SettleRequest(null));
        assertThat(settleAgain.getStatusCode()).isEqualTo(HttpStatus.OK);
        ReservationResponse stillSettled = reservationOf(settleAgain);
        assertThat(stillSettled.reservationId()).isEqualTo(settledHold.reservationId());
        assertThat(stillSettled.state()).isEqualTo(ReservationState.SETTLED);
        assertThat(stillSettled.settledAmount()).isEqualByComparingTo(new BigDecimal("250.00"));

        // Settled funds have left the account for good, so a release is still the 409 the contract names -
        // decided from the reservation's state, not from whether its account happens to exist.
        ResponseEntity<String> releaseSettled = postRelease(settledHold.reservationId());
        assertThat(releaseSettled.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorOf(releaseSettled).code()).isEqualTo(CashAccountErrorCode.INVALID_TRANSITION);

        String releasedOwner = "GONERELEASED";
        ReservationResponse releasedHold = openAccountAndHold(releasedOwner, "IDEM-GONE-RELEASED",
                "ORD-GONE-RELEASED", "250.00", FIXED_EXPIRY);
        assertThat(postRelease(releasedHold.reservationId()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deleteAccount(releasedOwner).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> releaseAgain = postRelease(releasedHold.reservationId());
        assertThat(releaseAgain.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reservationOf(releaseAgain).state()).isEqualTo(ReservationState.RELEASED);

        ResponseEntity<String> settleReleased =
                postSettle(releasedHold.reservationId(), new SettleRequest(null));
        assertThat(settleReleased.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorOf(settleReleased).code()).isEqualTo(CashAccountErrorCode.INVALID_TRANSITION);

        // None of the four calls above wrote a second terminal row for either hold, and the retained ledger
        // is still each deleted owner's audit path.
        List<LedgerEntryResponse> settledLedger = ledger(settledOwner);
        assertThat(rowsOf(settledLedger, LedgerEventType.SETTLEMENT, settledHold.reservationId())).hasSize(1);
        assertThat(rowsOf(settledLedger, LedgerEventType.RELEASE, settledHold.reservationId())).isEmpty();
        assertThat(rowsOf(settledLedger, LedgerEventType.ACCOUNT_DELETED)).hasSize(1);

        List<LedgerEntryResponse> releasedLedger = ledger(releasedOwner);
        assertThat(rowsOf(releasedLedger, LedgerEventType.RELEASE, releasedHold.reservationId())).hasSize(1);
        assertThat(rowsOf(releasedLedger, LedgerEventType.SETTLEMENT, releasedHold.reservationId()))
                .isEmpty();
    }

    @Test
    void amountsAboveTheStorageCeilingAnswerWithTheContractsOwnCodes() throws Exception {
        String owner = "CEILING1";
        openAccount(owner);

        // 50,000,000.00 is past the NUMERIC(9,2) ceiling of 9,999,999.99, but that is the service's constraint,
        // not the caller's condition: what the caller asked for is more than the account can cover, and
        // INSUFFICIENT_FUNDS is the only 422 the hold contract declares (AAP 0.6.2) - AMOUNT_OUT_OF_RANGE, which
        // the money type raises on its own, is in neither endpoint's error set.
        ResponseEntity<String> overCeilingHold =
                postHold(owner, "IDEM-CEILING-1", holdBody("ORD-CEILING-1", "50000000.00", FIXED_EXPIRY));
        assertThat(overCeilingHold.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(errorOf(overCeilingHold).code()).isEqualTo(CashAccountErrorCode.INSUFFICIENT_FUNDS);
        assertThat(reservationRowCount(owner)).isZero();
        assertThat(rowsOf(ledger(owner), LedgerEventType.HOLD)).isEmpty();
        InstitutionalAccountResponse refused = account(owner);
        assertThat(refused.availableBalance()).isEqualByComparingTo(new BigDecimal(OPENING_BALANCE));
        assertThat(refused.reservedBalance()).isEqualByComparingTo(ZERO);

        ResponseEntity<String> held =
                postHold(owner, "IDEM-CEILING-2", holdBody("ORD-CEILING-2", "250.00", FIXED_EXPIRY));
        assertThat(held.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID reservationId = reservationOf(held).reservationId();

        // Above the ceiling a settle amount necessarily exceeds any held amount, which the settle contract
        // answers with 400 INVALID_AMOUNT - and an ordinary over-held amount answers with the same code from
        // the state machine under the lock, so the caller cannot tell the two routes apart.
        ResponseEntity<String> overCeilingSettle =
                postSettle(reservationId, new SettleRequest(new BigDecimal("50000000.00")));
        assertThat(overCeilingSettle.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorOf(overCeilingSettle).code()).isEqualTo(CashAccountErrorCode.INVALID_AMOUNT);

        ResponseEntity<String> overHeldSettle =
                postSettle(reservationId, new SettleRequest(new BigDecimal("300.00")));
        assertThat(overHeldSettle.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorOf(overHeldSettle).code()).isEqualTo(CashAccountErrorCode.INVALID_AMOUNT);

        // Both refusals happened before anything was written, so the hold is still live and the funds are
        // still split exactly as the hold left them.
        assertThat(reservationOf(getReservation(reservationId)).state()).isEqualTo(ReservationState.HELD);
        assertThat(rowsOf(ledger(owner), LedgerEventType.SETTLEMENT, reservationId)).isEmpty();
        InstitutionalAccountResponse stillHeld = account(owner);
        assertThat(stillHeld.availableBalance()).isEqualByComparingTo(new BigDecimal("750.00"));
        assertThat(stillHeld.reservedBalance()).isEqualByComparingTo(new BigDecimal("250.00"));
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
        // The replay contract is the same one a sequential repeat is held to: the loser of the constraint
        // re-read the winner's row, so it must answer with the winner's body and the replay header, not with
        // a body of its own making.
        ResponseEntity<String> created = withStatus(responses, HttpStatus.CREATED);
        assertReplayedFrom(created, withStatus(responses, HttpStatus.OK));
        assertHeldExactlyOnce(owner, reservationOf(created).reservationId(), "250.00", "750.00");
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

        return reservationOf(openAccountAndPostHold(owner, idempotencyKey,
                holdBody(orderReference, amount, expiresAt)));
    }

    // Hands back the raw response rather than the parsed reservation: the replay cases compare the body a
    // caller received character for character, so the text has to survive the helper.
    private ResponseEntity<String> openAccountAndPostHold(String owner, String idempotencyKey, String jsonBody)
            throws Exception {

        openAccount(owner);
        ResponseEntity<String> created = postHold(owner, idempotencyKey, jsonBody);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created;
    }

    // A null key means the header is ABSENT, not empty: an empty value would be a different request, and the
    // branch under test is the one Spring reaches when required = false and nothing was sent at all.
    private ResponseEntity<String> postHold(String owner, String idempotencyKey, String jsonBody) {
        HttpHeaders headers = authJson();
        if (idempotencyKey != null) {
            headers.set(ReservationController.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        }
        return rest.exchange(url(INSTITUTIONAL_BASE + "/accounts/" + owner + "/holds"), HttpMethod.POST,
                new HttpEntity<>(jsonBody, headers), String.class);
    }

    // The retail seam is used to delete, exactly as a real operator would: it is the only path that writes the
    // ACCOUNT_DELETED ledger row and the only one that enforces the HELD-reservation guard. The response is
    // returned rather than asserted here so a caller can state what it expects of the delete itself.
    private ResponseEntity<String> deleteAccount(String owner) {
        return rest.exchange(url(RETAIL_BASE + "/" + owner), HttpMethod.DELETE,
                new HttpEntity<>(authJson()), String.class);
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

    // The one write in this class that bypasses the service, and the only way to produce two ledger rows sharing
    // an instant: LedgerEntry stamps recorded_at itself and ledger_entry forbids UPDATE, so INSERT - the single
    // mutation the immutability trigger permits - is the only place a tie can be arranged. entry_id is omitted so
    // the identity column still generates it.
    private Long insertLedgerRow(String owner, OffsetDateTime recordedAt, LedgerEventType eventType,
            String amount) {

        return jdbc.queryForObject("insert into ledger_entry (owner, incarnation_id, event_type, amount,"
                        + " currency, available_after, reserved_after, source, recorded_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning entry_id",
                Long.class, owner, UUID.randomUUID(), eventType.name(), new BigDecimal(amount), CURRENCY,
                new BigDecimal("900.00"), ZERO, LedgerEntry.Source.INSTITUTIONAL.name(), recordedAt);
    }

    private int reservationRowCount(String owner) {
        Integer count = jdbc.queryForObject("select count(*) from cash_reservation where owner = ?",
                Integer.class, owner);
        return count == null ? 0 : count;
    }

    // The whole response is compared because a replay owes the caller the answer its original call received
    // (AAP 0.6.2, 0.7.3): an assertion on a few components stays green while the others change underneath it.
    // The parsed records go first because a failure then names the component, and the raw text follows because
    // it catches a rendering difference the parse would smooth over - a timestamp returned at nanosecond
    // precision by the original call and at the microsecond resolution TIMESTAMPTZ keeps by the replay.
    private void assertReplayedFrom(ResponseEntity<String> first, ResponseEntity<String> replay)
            throws Exception {

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getHeaders().getFirst(ReservationController.IDEMPOTENT_REPLAYED_HEADER))
                .isEqualTo("true");

        ReservationResponse original = reservationOf(first);
        ReservationResponse stored = reservationOf(replay);
        assertThat(stored).isEqualTo(original);
        assertThat(replay.getBody()).isEqualTo(first.getBody());

        // Asserted on the replayed body: a projection that dropped a mandatory component would still equal a
        // first response that dropped the same one, so equality alone cannot show the payload is complete.
        assertThat(stored.reservationId()).isNotNull();
        assertThat(stored.owner()).isNotBlank();
        assertThat(stored.orderReference()).isNotBlank();
        assertThat(stored.amount()).isNotNull();
        assertThat(stored.currency()).isEqualTo(CURRENCY);
        assertThat(stored.expiresAt()).isNotNull();
        assertThat(stored.createdAt()).isNotNull();
        assertThat(stored.updatedAt()).isNotNull();
        assertThat(stored.state()).isEqualTo(ReservationState.HELD);
        assertThat(stored.settledAmount()).isNull();
    }

    // The rejection assertions of the two validation cases: a refused hold must leave no reservation row, no
    // HOLD ledger row and the opening balance untouched, because a 400 that still moved money would satisfy a
    // status-only assertion.
    private void assertNothingHeld(String owner) throws Exception {
        assertThat(reservationRowCount(owner)).isZero();
        assertThat(rowsOf(ledger(owner), LedgerEventType.HOLD)).isEmpty();
        InstitutionalAccountResponse account = account(owner);
        assertThat(account.availableBalance()).isEqualByComparingTo(new BigDecimal(OPENING_BALANCE));
        assertThat(account.reservedBalance()).isEqualByComparingTo(ZERO);
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
        return holdBody(orderReference, amount, expiresAt, CURRENCY);
    }

    // The currency is a parameter of this form alone so a malformed code travels as raw text, exactly as a
    // caller would send it, instead of being rejected client-side before the service ever judges it.
    private static String holdBody(String orderReference, String amount, OffsetDateTime expiresAt,
            String currency) {

        return "{\"orderReference\":\"" + orderReference + "\",\"amount\":" + amount
                + ",\"currency\":\"" + currency + "\""
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

    // The list-wide half of the ledger ordering contract. The conditional is not a loophole:
    // theLedgerQueryBreaksARecordedAtTieOnTheEntryIdentity arranges a genuine tie and asserts the identity order
    // unconditionally, so this helper carries that check across a mixed list.
    private static void assertNewestFirst(List<LedgerEntryResponse> rows) {
        for (int index = 1; index < rows.size(); index++) {
            LedgerEntryResponse newer = rows.get(index - 1);
            LedgerEntryResponse older = rows.get(index);
            assertThat(newer.recordedAt().toInstant()).isAfterOrEqualTo(older.recordedAt().toInstant());
            if (newer.recordedAt().toInstant().equals(older.recordedAt().toInstant())) {
                assertThat(newer.entryId()).isGreaterThan(older.entryId());
            }
        }
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

