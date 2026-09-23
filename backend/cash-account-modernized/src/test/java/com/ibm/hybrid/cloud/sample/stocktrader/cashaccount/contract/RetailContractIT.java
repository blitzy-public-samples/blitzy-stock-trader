package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import jakarta.ws.rs.WebApplicationException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.ibm.hybrid.cloud.sample.stocktrader.broker.client.CashAccountClient;
import com.ibm.hybrid.cloud.sample.stocktrader.broker.json.CashAccount;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.audit.LedgerEntryResponse;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.LedgerEntry;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.LedgerEventType;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.Money;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.institutional.InstitutionalAccountResponse;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.institutional.ReservationController;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.CashAccountRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.BrokerClientFactory;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.JwtTestTokens;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.PostgresTestSupport;

/** Drives all six methods of broker's unmodified CashAccountClient against the running service and a real database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RetailContractIT extends PostgresTestSupport {

    // One owner per scenario, because support/PostgresTestSupport starts ONE container for the whole test JVM: a
    // shared owner would make each scenario's outcome depend on which ran first. All are uppercase and within the
    // 1-32 characters domain/OwnerNormalizer accepts, so the stored owner equals the value sent - except
    // CREATE_OWNER_SENT, which is mixed case deliberately.
    private static final String GET_OWNER = "CTGET";
    private static final String CREATE_OWNER_SENT = "ctCreate";
    private static final String CREATE_OWNER_STORED = "CTCREATE";
    private static final String UPDATE_OWNER = "CTUPDATE";
    private static final String DELETE_OWNER = "CTDELETE";
    private static final String DEBIT_OWNER = "CTDEBIT";
    private static final String CREDIT_OWNER = "CTCREDIT";
    private static final String ZERO_OWNER = "CTZERO";
    private static final String OVERDEBIT_OWNER = "CTOVERDEBIT";
    private static final String RACE_OWNER = "CTRACE";
    private static final String PK_GUARD_OWNER = "CTPKGUARD";
    private static final String BODYLESS_OWNER = "CTNOBODY";
    private static final String DEFAULTS_OWNER = "CTDEFAULTS";

    /** Named in another request's body and never in its path, so it proves the body's owner is ignored. */
    private static final String DECOY_OWNER = "CTDECOY";
    private static final String SUBCENT_OWNER = "CTSUBCENT";
    private static final String CEILING_OWNER = "CTCEILING";

    // The two owners whose accounts carry held funds when a retail write arrives - the 409
    // RESERVATIONS_OUTSTANDING pair AAP 0.6.2 declares. One owner each, because a refused PUT and a refused
    // DELETE are each judged by what the account still holds afterwards, which a shared owner would confuse.
    private static final String HELD_PUT_OWNER = "CTHELDPUT";
    private static final String HELD_DELETE_OWNER = "CTHELDDELETE";

    /** Never created by any scenario, which is what makes the 404 assertion mean something. */
    private static final String MISSING_OWNER = "CTMISSING";

    // Every account is USD, the configured fx base, where a same-currency operation short-circuits to a rate of
    // exactly 1 with no outbound call (AAP 0.7.2) - so no scenario here reaches the fx url application-test.yml
    // points at a refused port. It is also broker's own default account currency
    // [backend/broker/src/main/java/com/ibm/hybrid/cloud/sample/stocktrader/broker/BrokerService.java:L357-L365].
    private static final String ACCOUNT_CURRENCY = "USD";

    // Asserted against the raw body, not CashAccount.toString(): that method builds its own JSON WITH spaces
    // [.../broker/json/CashAccount.java:L70], so it would prove the DTO's formatting rather than the service's.
    // WRITE_BIGDECIMAL_AS_PLAIN (config/JacksonConfig) is what keeps the value out of the 1.23456E3 form.
    private static final String PLAIN_DECIMAL_BALANCE_ON_THE_WIRE = "\"balance\":1234.56";

    // Balances are compared within a tolerance because the caller's DTO declares `private double balance`
    // [.../broker/json/CashAccount.java:L23] and that declaration is not ours to change (AAP 0.7.1). Half a cent
    // is below the smallest amount NUMERIC(9,2) can represent, so it absorbs representation error and nothing else.
    private static final double PENNY_TOLERANCE = 0.005;

    // AAP 0.6.2 binds each condition to exactly one status; named here so a red CI log reads as a contract row.
    private static final int ACCOUNT_NOT_FOUND_STATUS = 404;
    private static final int INSUFFICIENT_FUNDS_STATUS = 422;
    private static final int INVALID_AMOUNT_STATUS = 400;
    private static final int AMOUNT_OUT_OF_RANGE_STATUS = 422;
    private static final int RESERVATIONS_OUTSTANDING_STATUS = 409;

    /** The retail seam's published prefix, which the chart hands broker as {@code cashAccount.url}. */
    private static final String RETAIL_BASE = BrokerClientFactory.RETAIL_BASE_PATH;

    // Reached for two purposes only: the ledger is read through the contract's own audit surface (AAP 0.6.2)
    // rather than a repository, and a hold is the sole way to put funds into reserved_balance. Broker's client
    // covers the retail surface alone, so neither can be arranged through the interface under test.
    private static final String INSTITUTIONAL_BASE = RETAIL_BASE + "/institutional";

    private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");

    private static final BigDecimal ZERO_OWNER_BALANCE = new BigDecimal("500.00");

    // Two distinguishable balances, so the surviving row can be traced to the request that created it. Each is
    // declared twice - as the double the caller's interface takes, and as the decimal the ledger stores - because
    // a conversion between the two in an assertion would put a double on a money path (AAP 0.7.1).
    private static final double RACE_FIRST_BALANCE = 1100.00;
    private static final double RACE_SECOND_BALANCE = 2200.00;
    private static final BigDecimal RACE_FIRST_LEDGER_BALANCE = new BigDecimal("1100.00");
    private static final BigDecimal RACE_SECOND_LEDGER_BALANCE = new BigDecimal("2200.00");

    // Generous: it bounds a hung request rather than timing one, and a slow container must not fail the race.
    private static final long RACE_TIMEOUT_SECONDS = 30;

    // An explicit StockTrader token, rather than reliance on
    // cashaccount.security.all-authenticated-hold-stocktrader, keeps this file independent of the role matrix
    // security/RoleEnforcementIT owns - a contract failure here can then never be an authorization finding.
    private static final String TOKEN = JwtTestTokens.stockTraderToken();

    @LocalServerPort
    private int port;

    // Carries the four requests the caller's typed interface cannot express: its write methods take a fully
    // populated CashAccount, so a request with no body and a body omitting fields cannot be sent through it, and
    // none of its six methods reaches an institutional path, so neither the ledger read nor the hold that
    // arranges the credit-ceiling scenario can go through it either.
    @Autowired
    private TestRestTemplate rest;

    // The context's own mapper, so a ledger row and the institutional bodies are read back through JacksonConfig's
    // USE_BIG_DECIMAL_FOR_FLOATS rather than through a default mapper that would bind money to a double.
    @Autowired
    private ObjectMapper objectMapper;

    // The one scenario that reaches past the endpoint, and only because the endpoint cannot express it: whether a
    // fresh account is INSERTed rather than merged is decidable at the repository, and invisible through HTTP.
    @Autowired
    private CashAccountRepository accounts;

    @Autowired
    private TransactionTemplate transactions;

    // A proxy over the byte-identical copy of broker's own interface, never a test client: a MockMvc,
    // WebTestClient or TestRestTemplate request would carry the paths, Accept/Content-Type pair, JSON binding and
    // double-valued ?amount= this test intends rather than the ones the unmodified caller actually emits
    // [backend/broker/src/main/java/com/ibm/hybrid/cloud/sample/stocktrader/broker/client/CashAccountClient.java:L42-L90].
    private CashAccountClient client;

    // application-test.yml leaves the verification key unset so that no key material is checked in (AAP 0.7.5);
    // each *IT points the decoder at the ephemeral per-JVM certificate itself, or the context cannot start.
    @DynamicPropertySource
    static void jwtVerificationKey(DynamicPropertyRegistry registry) {
        registry.add("cashaccount.security.jwt.public-key-location", JwtTestTokens::publicKeyLocation);
    }

    @BeforeEach
    void buildClientForTheRandomPort() {
        // Built here rather than in a static initializer because the port is only known once the server is up.
        client = BrokerClientFactory.client(port, TOKEN);
    }

    @Test
    void getCashAccountReturnsTheAccountAndAPlainDecimalBalanceOnTheWire() {
        seedAccount(GET_OWNER, 1234.56);

        AtomicReference<String> responseBody = new AtomicReference<>();
        CashAccountClient capturingClient = capturingClient(responseBody::set);

        CashAccount read = capturingClient.getCashAccount(GET_OWNER);

        assertAccount(read, GET_OWNER, 1234.56, "GET /cash-account/{owner} (legacy Q)");
        assertThat(responseBody.get())
                .as("GET /cash-account/{owner} response body must carry balance as plain decimal text,"
                        + " which is what broker's double-typed field parses")
                .isNotNull()
                .contains(PLAIN_DECIMAL_BALANCE_ON_THE_WIRE);
    }

    @Test
    void createCashAccountStoresTheOwnerUppercasedAndReturnsThatForm() {
        // Mixed case on purpose: legacy casing depended on which paragraph answered - Q returned the database
        // OWNER [backend/cash-account-cobol/COBOL/CASH00.cbl:L144], A echoed CUST-NAME-TEXT [CASH00.cbl:L158] -
        // and the replacement always answers the stored uppercase form (AAP 0.4.2). Broker reads only balance and
        // currency out of the response [.../broker/BrokerService.java:L364-L365], so no caller behaviour changes.
        CashAccount created = client.createCashAccount(CREATE_OWNER_SENT,
                new CashAccount(CREATE_OWNER_SENT, 1000.00, ACCOUNT_CURRENCY));

        assertAccount(created, CREATE_OWNER_STORED, 1000.00, "POST /cash-account/{owner} (legacy A)");
    }

    @Test
    void updateCashAccountOverwritesTheBalanceAbsolutely() {
        seedAccount(UPDATE_OWNER, 100.00);

        CashAccount updated =
                client.updateCashAccount(UPDATE_OWNER, new CashAccount(UPDATE_OWNER, 250.00, ACCOUNT_CURRENCY));

        // 250.00 and not 350.00: the request body is the new absolute balance, as the legacy UPDATE set both columns
        // outright - SET BALANCE=:BALANCE, CURRENCYC=:WS-CURRENCY [CASH00.cbl:L176-L177] - never a delta.
        assertAccount(updated, UPDATE_OWNER, 250.00, "PUT /cash-account/{owner} (legacy U)");
    }

    @Test
    void deleteCashAccountReturnsTheDeletedAccountAndLeavesNothingToRead() {
        seedAccount(DELETE_OWNER, 500.00);

        CashAccount deleted = client.deleteCashAccount(DELETE_OWNER);

        // The body of the account as it stood immediately before removal (AAP 0.6.2), which is why the service
        // snapshots it while the row is still loaded.
        assertAccount(deleted, DELETE_OWNER, 500.00, "DELETE /cash-account/{owner} (legacy X)");

        assertThatExceptionOfType(WebApplicationException.class)
                .isThrownBy(() -> client.getCashAccount(DELETE_OWNER))
                .satisfies(rejection -> assertThat(rejection.getResponse().getStatus())
                        .as("GET /cash-account/{owner} after DELETE must answer 404 ACCOUNT_NOT_FOUND")
                        .isEqualTo(ACCOUNT_NOT_FOUND_STATUS));
    }

    @Test
    void debitReducesTheAvailableBalance() {
        seedAccount(DEBIT_OWNER, 1000.00);

        // The caller's parameter is @QueryParam("amount") double [.../client/CashAccountClient.java:L83], so the
        // wire carries whatever the provider renders for it - "250.5" here, the 1.0E7 form for large values. The
        // controller binds it as text and parses with new BigDecimal(String) so both forms arrive intact.
        CashAccount debited = client.debit(DEBIT_OWNER, 250.50);

        assertAccount(debited, DEBIT_OWNER, 749.50, "PUT /cash-account/{owner}/debit?amount= (legacy D)");
    }

    @Test
    void creditIncreasesTheAvailableBalance() {
        seedAccount(CREDIT_OWNER, 1000.00);

        CashAccount credited = client.credit(CREDIT_OWNER, 250.50);

        assertAccount(credited, CREDIT_OWNER, 1250.50, "PUT /cash-account/{owner}/credit?amount= (legacy C)");
    }

    @Test
    void unknownOwnerIsRejectedWithFourHundredFourAndNoEchoedBody() {
        // The legacy read of an absent owner left SQLCODE 100 in a return field that dropped the sign
        // [CASH00.cbl:L104] and copied the COMMAREA back verbatim [CASH00.cbl:L105-L108], handing the caller its
        // own submitted amount as the "balance" of an account that did not exist; a 404 with no account body is
        // the deliberate replacement (AAP 0.4.5). Asserted on the base type, because which
        // WebApplicationException subclass the MicroProfile mapper produces is the client runtime's business.
        assertThatExceptionOfType(WebApplicationException.class)
                .isThrownBy(() -> client.getCashAccount(MISSING_OWNER))
                .satisfies(rejection -> assertThat(rejection.getResponse().getStatus())
                        .as("GET /cash-account/{owner} for a never-created owner must answer"
                                + " 404 ACCOUNT_NOT_FOUND")
                        .isEqualTo(ACCOUNT_NOT_FOUND_STATUS));
    }

    @Test
    void zeroAmountIsAcceptedAndStillWritesItsLedgerRow() throws Exception {
        seedAccount(ZERO_OWNER, 500.00);

        // The legacy accepted a zero credit or debit, updated the row and still wrote a history record
        // [CASH00.cbl:L222, L256], so the transaction counts reconciliation compares are equal only if the
        // replacement accepts it too (AAP 0.4.5). Broker skips lastTrade == 0
        // [.../broker/BrokerService.java:L486-L501], so only another caller can exercise it.
        CashAccount unchanged = client.credit(ZERO_OWNER, 0.0);

        // A returned entity IS the 200: the client proxy raises a WebApplicationException on any non-2xx, so the
        // status cannot be read separately through this interface - and the entity is all broker ever sees.
        assertAccount(unchanged, ZERO_OWNER, 500.00, "PUT /cash-account/{owner}/credit?amount=0 (legacy C)");

        // An unchanged balance is also what a service that short-circuited the whole operation would return, so
        // the ledger row is the only evidence the transaction happened at all - and without it reconciliation
        // would count one legacy C against zero target transactions (AAP 0.10.3).
        List<LedgerEntryResponse> rows = ledger(ZERO_OWNER);
        List<LedgerEntryResponse> credits = rows.stream()
                .filter(row -> row.eventType() == LedgerEventType.CREDIT)
                .toList();

        assertThat(credits).as("a zero-amount credit must write exactly one CREDIT ledger row").hasSize(1);

        LedgerEntryResponse zeroCredit = credits.get(0);
        assertThat(zeroCredit.amount()).as("zero-amount CREDIT row amount")
                .isEqualByComparingTo(ZERO_AMOUNT);
        assertThat(zeroCredit.availableAfter()).as("zero-amount CREDIT row availableAfter")
                .isEqualByComparingTo(ZERO_OWNER_BALANCE);
        assertThat(zeroCredit.reservedAfter()).as("zero-amount CREDIT row reservedAfter")
                .isEqualByComparingTo(ZERO_AMOUNT);
        assertThat(zeroCredit.source()).as("a credit issued through the retail seam is a RETAIL row")
                .isEqualTo(LedgerEntry.Source.RETAIL);
    }

    @Test
    void creatingAnAccountInsertsSoTheOwnerPrimaryKeySettlesAContestedCreate() throws Exception {
        // The service's existsByOwner pre-read is not atomic, so two callers can pass it together and the loser
        // has to be refused by the owner primary key - which only an INSERT can do. The first half asserts that
        // through the repository because the endpoint cannot express it: existsByOwner answers first, and no test
        // can schedule itself into the window between that check and the flush. The second half asserts the
        // end-to-end outcome, where the loser is usually refused by the pre-read and looks identical from outside.
        seedAccount(PK_GUARD_OWNER, 100.00);

        // Fully qualified because the simple name CashAccount belongs to broker's DTO throughout this file.
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .as("a second INSERT of one owner must be refused by the owner primary key, never merged into the"
                        + " existing row")
                .isThrownBy(() -> transactions.executeWithoutResult(status -> accounts.saveAndFlush(
                        com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.CashAccount.open(
                                PK_GUARD_OWNER, ACCOUNT_CURRENCY, Money.of(new BigDecimal("999.00"))))))
                .satisfies(violation -> assertThat(violation.getMessage())
                        .as("the violation must name the owner primary key, which is what the service translates"
                                + " into 409 ACCOUNT_ALREADY_EXISTS")
                        .contains("pk_cash_account"));

        assertAccount(client.getCashAccount(PK_GUARD_OWNER), PK_GUARD_OWNER, 100.00,
                "the account a refused second create left alone");

        CyclicBarrier bothReady = new CyclicBarrier(2);
        ExecutorService racers = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = racers.submit(() -> createStatusAtBarrier(bothReady, RACE_FIRST_BALANCE));
            Future<Integer> second = racers.submit(() -> createStatusAtBarrier(bothReady, RACE_SECOND_BALANCE));

            int firstStatus = first.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int secondStatus = second.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // Which thread wins is the scheduler's business; that exactly one of them does is the contract.
            assertThat(List.of(firstStatus, secondStatus))
                    .as("two concurrent POSTs of one owner must settle as one 200 and one 409"
                            + " ACCOUNT_ALREADY_EXISTS, never two successes")
                    .containsExactlyInAnyOrder(HttpStatus.OK.value(), HttpStatus.CONFLICT.value());
        } finally {
            racers.shutdownNow();
        }

        CashAccount survivor = client.getCashAccount(RACE_OWNER);
        assertThat(survivor.getBalance()).as("the surviving account keeps the winning request's balance")
                .isIn(RACE_FIRST_BALANCE, RACE_SECOND_BALANCE);

        // Two rows would mean both requests had "created" the account - the overwrite this scenario rules out.
        List<LedgerEntryResponse> created = ledger(RACE_OWNER).stream()
                .filter(row -> row.eventType() == LedgerEventType.ACCOUNT_CREATED)
                .toList();
        assertThat(created).as("a contested create must leave exactly one ACCOUNT_CREATED ledger row").hasSize(1);

        // Compared against decimal literals rather than against the response's double: the caller's DTO is the one
        // place a double is permitted in this subtree (AAP 0.7.1) and converting it back would put one on a money
        // path in the assertion itself.
        assertThat(created.get(0).availableAfter())
                .as("the one ACCOUNT_CREATED row must record one of the two requested opening balances")
                .isIn(RACE_FIRST_LEDGER_BALANCE, RACE_SECOND_LEDGER_BALANCE);
    }

    @Test
    void writeWithNoBodyIsRejectedAndChangesNothing() {
        seedAccount(BODYLESS_OWNER, 500.00);

        // AAP 0.6.2 defines PUT as an absolute overwrite of the balance and currency a body carries, so a request
        // with no body carries no instruction and must be refused rather than read as 0.00 in the base currency -
        // which would empty the account. 400 INVALID_AMOUNT is the closed code set's 400 for an unbindable body.
        ResponseEntity<String> rejected = rest.exchange(url(RETAIL_BASE + "/" + BODYLESS_OWNER), HttpMethod.PUT,
                new HttpEntity<>(jsonHeaders()), String.class);

        assertThat(rejected.getStatusCode()).as("PUT /cash-account/{owner} with no body must be rejected")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejected.getBody()).as("the rejection must carry the ApiError code")
                .isNotNull()
                .contains("\"code\":\"INVALID_AMOUNT\"");

        CashAccount untouched = client.getCashAccount(BODYLESS_OWNER);
        assertAccount(untouched, BODYLESS_OWNER, 500.00, "PUT /cash-account/{owner} with no body");
    }

    @Test
    void writeBodyOwnerIsIgnoredAndAbsentFieldsTakeTheDocumentedDefaults() throws Exception {
        seedAccount(DEFAULTS_OWNER, 500.00);
        seedAccount(DECOY_OWNER, 700.00);

        // The body's owner component is ignored in favour of the path - broker sends the two in agreement and
        // CashAccountErrorCode holds no mismatch condition to report - while an absent balance is 0.00 and an
        // absent currency USD, the values the legacy COMMAREA presented when a caller set neither
        // [CASH00.cbl:L56].
        ResponseEntity<String> overwritten = rest.exchange(url(RETAIL_BASE + "/" + DEFAULTS_OWNER), HttpMethod.PUT,
                new HttpEntity<>("{\"owner\":\"" + DECOY_OWNER + "\"}", jsonHeaders()), String.class);

        assertThat(overwritten.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(overwritten.getBody())
                .as("the path owner decides, and the omitted fields take the documented defaults")
                .isNotNull()
                .contains("\"owner\":\"" + DEFAULTS_OWNER + "\"")
                .contains("\"balance\":0.00")
                .contains("\"currency\":\"" + ACCOUNT_CURRENCY + "\"");

        // The destructive part is deliberate and documented, so it is asserted rather than assumed: the ledger
        // records the reset as an absolute-set ACCOUNT_UPDATED row.
        List<LedgerEntryResponse> updates = ledger(DEFAULTS_OWNER).stream()
                .filter(row -> row.eventType() == LedgerEventType.ACCOUNT_UPDATED)
                .toList();
        assertThat(updates).as("the overwrite must be recorded once").hasSize(1);
        assertThat(updates.get(0).availableAfter()).as("ACCOUNT_UPDATED row availableAfter")
                .isEqualByComparingTo(ZERO_AMOUNT);

        assertAccount(client.getCashAccount(DECOY_OWNER), DECOY_OWNER, 700.00,
                "the owner named in the request body");
    }

    @Test
    void debitBeyondTheAvailableBalanceIsRejectedAsInsufficientFunds() {
        seedAccount(OVERDEBIT_OWNER, 100.00);

        AtomicReference<String> responseBody = new AtomicReference<>();
        CashAccountClient capturingClient = capturingClient(responseBody::set);

        // An authorized deliberate improvement, not a parity gap (AAP 0.4.6, 0.14.2): WS-CALC is unsigned
        // PIC 9(7)V99 [backend/cash-account-cobol/COBOL/CASH00.cbl:L17] and the subtracting COMPUTE carries no
        // ON SIZE ERROR [CASH00.cbl:L256], so the legacy stored 100.00 - 150.00 as 50.00 and answered success.
        assertThatExceptionOfType(WebApplicationException.class)
                .isThrownBy(() -> capturingClient.debit(OVERDEBIT_OWNER, 150.00))
                .satisfies(rejection -> assertThat(rejection.getResponse().getStatus())
                        .as("PUT /cash-account/{owner}/debit?amount= beyond the balance must answer"
                                + " 422 INSUFFICIENT_FUNDS")
                        .isEqualTo(INSUFFICIENT_FUNDS_STATUS));

        // Only the code is asserted. The message is the error code's own wording and may be reworded, and the
        // timestamp is by definition unstable; the code is the single value AAP 0.6.2 binds to the status.
        assertThat(responseBody.get())
                .as("PUT /cash-account/{owner}/debit?amount= rejection must carry the ApiError code")
                .isNotNull()
                .contains("\"code\":\"INSUFFICIENT_FUNDS\"");
    }

    @Test
    void subCentNegativeAmountIsRejectedAsInvalidAmountAndLeavesTheAccountUntouched() {
        seedAccount(SUBCENT_OWNER, 100.00);

        AtomicReference<String> responseBody = new AtomicReference<>();
        CashAccountClient capturingClient = capturingClient(responseBody::set);

        // The sub-cent magnitude is the point, not the sign alone: -0.001 truncates DOWN to 0.00 at scale 2, so a
        // service that judged the sign after normalizing would answer 200 and append a zero-amount DEBIT row for
        // a request the caller never made. domain/Money judges the sign on the value as the caller wrote it, and
        // AAP 0.6.2 binds a negative amount to 400 INVALID_AMOUNT on this route.
        assertThatExceptionOfType(WebApplicationException.class)
                .isThrownBy(() -> capturingClient.debit(SUBCENT_OWNER, -0.001))
                .satisfies(rejection -> assertThat(rejection.getResponse().getStatus())
                        .as("PUT /cash-account/{owner}/debit?amount=-0.001 must answer 400 INVALID_AMOUNT")
                        .isEqualTo(INVALID_AMOUNT_STATUS));

        assertThat(responseBody.get())
                .as("PUT /cash-account/{owner}/debit?amount=-0.001 rejection must carry the ApiError code")
                .isNotNull()
                .contains("\"code\":\"INVALID_AMOUNT\"");

        // The status alone cannot show this: a refused call is also required to have written nothing, so the
        // balance still has to read exactly as the seed left it.
        assertAccount(client.getCashAccount(SUBCENT_OWNER), SUBCENT_OWNER, 100.00,
                "GET /cash-account/{owner} after a rejected sub-cent negative debit");
    }

    @Test
    void creditIsRefusedWhenHeldFundsWouldHaveNoRoomToReturn() throws Exception {
        // The credit below computes an available balance in range on its own - 9,999,750.00 against the
        // NUMERIC(9,2) ceiling of 9,999,999.99 - while available + reserved is not. Accepting it would leave the
        // hold nowhere to return to: a release, settlement or expiry has to hand those 250.00 back onto the
        // available balance, so the reservation could never reach a terminal state while retail PUT/DELETE
        // answered RESERVATIONS_OUTSTANDING for as long as the row existed. The ceiling bounds the pair.
        seedAccount(CEILING_OWNER, 9999000.00);
        placeHold(CEILING_OWNER, "250.00");

        AtomicReference<String> responseBody = new AtomicReference<>();
        CashAccountClient capturingClient = capturingClient(responseBody::set);

        assertThatExceptionOfType(WebApplicationException.class)
                .isThrownBy(() -> capturingClient.credit(CEILING_OWNER, 1000.00))
                .satisfies(rejection -> assertThat(rejection.getResponse().getStatus())
                        .as("PUT /cash-account/{owner}/credit?amount= must answer 422 AMOUNT_OUT_OF_RANGE when"
                                + " the new available balance plus the reserved balance leaves the range")
                        .isEqualTo(AMOUNT_OUT_OF_RANGE_STATUS));

        assertThat(responseBody.get())
                .as("PUT /cash-account/{owner}/credit?amount= rejection must carry the ApiError code")
                .isNotNull()
                .contains("\"code\":\"AMOUNT_OUT_OF_RANGE\"");

        // The exact boundary is still accepted, which is what makes the rule a ceiling on the pair rather than
        // headroom the account is never allowed to use: 9,999,749.99 available plus 250.00 reserved is the
        // ceiling to the cent.
        CashAccount credited = client.credit(CEILING_OWNER, 999.99);
        assertAccount(credited, CEILING_OWNER, 9999749.99,
                "PUT /cash-account/{owner}/credit?amount= at the pair ceiling (legacy C)");

        // The statuses alone cannot show this: the refused credit had to write nothing, so the hold is still
        // intact at its full amount and exactly one CREDIT row exists across both calls.
        InstitutionalAccountResponse account = institutionalAccount(CEILING_OWNER);
        assertThat(account.availableBalance()).isEqualByComparingTo(new BigDecimal("9999749.99"));
        assertThat(account.reservedBalance()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(account.totalBalance()).isEqualByComparingTo(new BigDecimal("9999999.99"));

        List<LedgerEntryResponse> credits = ledgerRows(CEILING_OWNER, LedgerEventType.CREDIT);
        assertThat(credits).hasSize(1);
        assertThat(credits.get(0).amount()).isEqualByComparingTo(new BigDecimal("999.99"));
        assertThat(credits.get(0).availableAfter()).isEqualByComparingTo(new BigDecimal("9999749.99"));
        assertThat(credits.get(0).reservedAfter()).isEqualByComparingTo(new BigDecimal("250.00"));
    }

    @Test
    void updateIsRefusedWhileFundsAreHeldAndLeavesBothHalvesOfThePositionUntouched() throws Exception {
        // PUT is an absolute overwrite of the available balance, so performing it while funds are held would
        // strand the reservation against a balance that no longer backs it - which is why AAP 0.6.2 declares
        // 409 RESERVATIONS_OUTSTANDING for this exact condition, one of only two retail-visible interactions the
        // institutional surface introduces. Both halves of the position are read back because the reserved
        // balance AAP 0.6.3 splits out is the value the overwrite would have silently orphaned.
        seedAccount(HELD_PUT_OWNER, 600.00);
        placeHold(HELD_PUT_OWNER, "200.00");

        AtomicReference<String> responseBody = new AtomicReference<>();
        CashAccountClient capturingClient = capturingClient(responseBody::set);

        assertThatExceptionOfType(WebApplicationException.class)
                .isThrownBy(() -> capturingClient.updateCashAccount(HELD_PUT_OWNER,
                        new CashAccount(HELD_PUT_OWNER, 1.00, ACCOUNT_CURRENCY)))
                .satisfies(rejection -> assertThat(rejection.getResponse().getStatus())
                        .as("PUT /cash-account/{owner} while a reservation is HELD must answer"
                                + " 409 RESERVATIONS_OUTSTANDING")
                        .isEqualTo(RESERVATIONS_OUTSTANDING_STATUS));

        // Only the code is asserted, for the reason the insufficient-funds case states: the message is the error
        // code's own wording and the timestamp is unstable, while the code is what AAP 0.6.2 binds to the status.
        assertThat(responseBody.get())
                .as("PUT /cash-account/{owner} rejection must carry the ApiError code")
                .isNotNull()
                .contains("\"code\":\"RESERVATIONS_OUTSTANDING\"");

        // The status alone cannot show this: a refused overwrite is also required to have written nothing, and a
        // guard evaluated after the write would still answer 409 over an account it had already overwritten.
        InstitutionalAccountResponse account = institutionalAccount(HELD_PUT_OWNER);
        assertThat(account.availableBalance()).as("a refused PUT must not move the available balance")
                .isEqualByComparingTo(new BigDecimal("400.00"));
        assertThat(account.reservedBalance()).as("a refused PUT must leave the hold at its full amount")
                .isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(account.totalBalance()).as("a refused PUT must not change the total position")
                .isEqualByComparingTo(new BigDecimal("600.00"));

        assertThat(ledgerRows(HELD_PUT_OWNER, LedgerEventType.ACCOUNT_UPDATED))
                .as("a refused PUT must write no ACCOUNT_UPDATED ledger row")
                .isEmpty();
    }

    @Test
    void deleteIsRefusedWhileFundsAreHeldAndLeavesTheAccountReadable() throws Exception {
        // AAP 0.6.2 declares the same 409 for DELETE, and the consequence there is worse than a stranded
        // balance: reservation and ledger rows carry no foreign key to cash_account and outlive it (AAP 0.6.3),
        // so removing the account under a HELD hold would leave that hold permanently unable to return its funds
        // to anything. The account therefore has to remain readable afterwards, not merely the request refused.
        seedAccount(HELD_DELETE_OWNER, 800.00);
        placeHold(HELD_DELETE_OWNER, "300.00");

        AtomicReference<String> responseBody = new AtomicReference<>();
        CashAccountClient capturingClient = capturingClient(responseBody::set);

        assertThatExceptionOfType(WebApplicationException.class)
                .isThrownBy(() -> capturingClient.deleteCashAccount(HELD_DELETE_OWNER))
                .satisfies(rejection -> assertThat(rejection.getResponse().getStatus())
                        .as("DELETE /cash-account/{owner} while a reservation is HELD must answer"
                                + " 409 RESERVATIONS_OUTSTANDING")
                        .isEqualTo(RESERVATIONS_OUTSTANDING_STATUS));

        assertThat(responseBody.get())
                .as("DELETE /cash-account/{owner} rejection must carry the ApiError code")
                .isNotNull()
                .contains("\"code\":\"RESERVATIONS_OUTSTANDING\"");

        // 500.00 rather than the seeded 800.00 because retail reports the AVAILABLE balance (AAP 0.6.2) and the
        // hold moved 300.00 out of it; a 404 here would be the signature of the delete having gone through.
        assertAccount(client.getCashAccount(HELD_DELETE_OWNER), HELD_DELETE_OWNER, 500.00,
                "GET /cash-account/{owner} after a DELETE refused for held funds");

        InstitutionalAccountResponse account = institutionalAccount(HELD_DELETE_OWNER);
        assertThat(account.reservedBalance()).as("a refused DELETE must leave the hold at its full amount")
                .isEqualByComparingTo(new BigDecimal("300.00"));
        assertThat(account.totalBalance()).as("a refused DELETE must not change the total position")
                .isEqualByComparingTo(new BigDecimal("800.00"));

        assertThat(ledgerRows(HELD_DELETE_OWNER, LedgerEventType.ACCOUNT_DELETED))
                .as("a refused DELETE must write no ACCOUNT_DELETED ledger row")
                .isEmpty();
    }

    // Seeding goes through the contract's own create endpoint rather than a repository or a JdbcTemplate: a
    // contract test that bypasses the contract to arrange its state proves less about the contract.
    private CashAccount seedAccount(String owner, double openingBalance) {
        return client.createCashAccount(owner, new CashAccount(owner, openingBalance, ACCOUNT_CURRENCY));
    }

    // Each racer builds its own proxy, so the two requests are independent all the way down and the outcome
    // cannot depend on the client runtime's concurrency behaviour. The barrier synchronizes only their launch:
    // whether they overlap in the window between existsByOwner and the flush is the server's scheduling and not
    // this test's to arrange, which is why the repository half above is the deterministic guard.
    private int createStatusAtBarrier(CyclicBarrier bothReady, double openingBalance) throws Exception {
        CashAccountClient racer = BrokerClientFactory.client(port, TOKEN);
        bothReady.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try {
            racer.createCashAccount(RACE_OWNER, new CashAccount(RACE_OWNER, openingBalance, ACCOUNT_CURRENCY));
            return HttpStatus.OK.value();
        } catch (WebApplicationException rejection) {
            return rejection.getResponse().getStatus();
        }
    }

    private List<LedgerEntryResponse> ledger(String owner) throws Exception {
        ResponseEntity<String> response = rest.exchange(url(INSTITUTIONAL_BASE + "/accounts/" + owner + "/ledger"),
                HttpMethod.GET, new HttpEntity<>(jsonHeaders()), String.class);
        assertThat(response.getStatusCode()).as("the ledger query surface must answer 200").isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(response.getBody(), new TypeReference<List<LedgerEntryResponse>>() { });
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        // The same StockTrader token the typed client carries, so a rejection here can never be a role finding -
        // that matrix belongs to security/RoleEnforcementIT.
        headers.set(HttpHeaders.AUTHORIZATION, JwtTestTokens.bearer(TOKEN));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // Written as text rather than serialized from HoldRequest so this file does not compile against an
    // institutional request record it only arranges state with.
    private void placeHold(String owner, String amount) {
        HttpHeaders headers = institutionalHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(ReservationController.IDEMPOTENCY_KEY_HEADER, "CT-CEILING-HOLD-1");
        String body = "{\"orderReference\":\"ORD-CT-CEILING\",\"amount\":\"" + amount + "\",\"currency\":\""
                + ACCOUNT_CURRENCY + "\"}";

        ResponseEntity<String> response = rest.exchange(institutionalUrl("/accounts/" + owner + "/holds"),
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode())
                .as("the hold that arranges reserved funds for the credit-ceiling scenario must be created")
                .isEqualTo(HttpStatus.CREATED);
    }

    private InstitutionalAccountResponse institutionalAccount(String owner) throws Exception {
        ResponseEntity<String> response = rest.exchange(institutionalUrl("/accounts/" + owner), HttpMethod.GET,
                new HttpEntity<>(institutionalHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(response.getBody(), InstitutionalAccountResponse.class);
    }

    private List<LedgerEntryResponse> ledgerRows(String owner, LedgerEventType eventType) throws Exception {
        ResponseEntity<String> response = rest.exchange(institutionalUrl("/accounts/" + owner + "/ledger"),
                HttpMethod.GET, new HttpEntity<>(institutionalHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<LedgerEntryResponse> rows =
                objectMapper.readValue(response.getBody(), new TypeReference<List<LedgerEntryResponse>>() { });
        return rows.stream().filter(row -> row.eventType() == eventType).toList();
    }

    private HttpHeaders institutionalHeaders() {
        HttpHeaders headers = new HttpHeaders();
        // The same StockTrader token the retail calls carry; every institutional path demands that role, and
        // the role matrix itself is security/RoleEnforcementIT's subject rather than this file's.
        headers.set(HttpHeaders.AUTHORIZATION, JwtTestTokens.bearer(TOKEN));
        return headers;
    }

    private String institutionalUrl(String path) {
        return "http://localhost:" + port + INSTITUTIONAL_BASE + path;
    }

    // The interface maps @Path("/{owner}") against the root of whatever base URI it is given, so the published
    // /cash-account prefix has to be part of that URI - the same address the chart hands broker as cashAccount.url.
    private CashAccountClient capturingClient(Consumer<String> rawBodySink) {
        return BrokerClientFactory.client(
                URI.create("http://localhost:" + port + BrokerClientFactory.RETAIL_BASE_PATH), TOKEN, rawBodySink);
    }

    private static void assertAccount(CashAccount actual, String expectedOwner, double expectedBalance,
            String endpoint) {

        assertThat(actual).as("%s returned no entity", endpoint).isNotNull();
        assertThat(actual.getOwner()).as("%s owner", endpoint).isEqualTo(expectedOwner);
        assertThat(actual.getBalance()).as("%s balance", endpoint)
                .isCloseTo(expectedBalance, within(PENNY_TOLERANCE));
        assertThat(actual.getCurrency()).as("%s currency", endpoint).isEqualTo(ACCOUNT_CURRENCY);
    }
}
