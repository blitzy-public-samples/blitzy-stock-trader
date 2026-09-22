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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/*
 * WHY THE REQUESTS BELOW ARE ISSUED BY BROKER'S OWN INTERFACE AND NOT BY A TEST CLIENT. The claim this whole
 * refactor rests on is that backend/broker was never edited and satisfies the new service as it stands: its
 * MicroProfile REST Client interface
 * [backend/broker/src/main/java/com/ibm/hybrid/cloud/sample/stocktrader/broker/client/CashAccountClient.java:L42-L90]
 * and its DTO [.../broker/json/CashAccount.java:L22-L24] are copied byte-identically into this module's test tree
 * (contract/CashAccountClientDriftTest fails the build if either copy drifts), and every one of the six retail
 * operations is exercised here through a proxy built from that copy by support/BrokerClientFactory. A MockMvc,
 * WebTestClient or TestRestTemplate request would assert what this test intends rather than what the caller
 * actually emits - its paths, its Accept/Content-Type pair, its JSON binding and its double-valued ?amount= - so
 * none of them stands in for the interface on any scenario the interface can express. If an assertion here could
 * only be made green by editing broker or the copies, that is the stop-and-flag condition of AAP 0.3.4, never a
 * patch.
 *
 * THREE THINGS THE INTERFACE CANNOT EXPRESS, and how each is issued instead. Its write methods take a fully
 * populated CashAccount, so a request with NO body and a request whose body omits fields cannot be sent through
 * it at all, and none of its six methods reaches an institutional path, so the ledger a transition writes cannot
 * be read through it either. Those three go out on an injected TestRestTemplate with the same StockTrader token, and one
 * scenario additionally reaches the repository directly, because whether a fresh account is INSERTed or merged is
 * decidable there and invisible over HTTP (see the contested-create test). Everything asserted about the contract
 * ITSELF - path, verb, query parameter, payload and response shape - still comes from the caller's own interface.
 *
 * WHY EVERY ACCOUNT IS DENOMINATED IN USD. credit and debit are the only retail operations that consult an
 * exchange rate, and a same-currency operation short-circuits to a rate of exactly 1 with no outbound call
 * (AAP 0.7.2), which is also what makes parity with the legacy arithmetic exact. cashaccount.fx.base-currency is
 * USD - broker's own default account currency
 * [backend/broker/src/main/java/com/ibm/hybrid/cloud/sample/stocktrader/broker/BrokerService.java:L357-L365] - and
 * src/test/resources/application-test.yml points cashaccount.fx.url at a refused local port so that no test can
 * reach the public rate API. A non-USD account on either path would therefore answer 503
 * EXCHANGE_RATE_UNAVAILABLE, which is fx/CurrencyConversionTest's subject and not this file's.
 *
 * WHY BALANCES ARE COMPARED WITHIN A TOLERANCE. The caller's DTO declares `private double balance`
 * [.../broker/json/CashAccount.java:L23] and that declaration is not ours to change, so it is the one permitted
 * floating-point value in this subtree (AAP 0.7.1). Its compensating control is in this class: the getCashAccount
 * scenario captures the raw response body and asserts the balance as literal plain-decimal TEXT, which is the
 * contract broker actually consumes, while the typed comparisons stay two orders of magnitude inside a penny.
 */
/** Drives all six methods of broker's unmodified CashAccountClient against the running service and a real database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RetailContractIT extends PostgresTestSupport {

    // One owner per scenario, because support/PostgresTestSupport starts ONE container for the whole test JVM: a
    // shared owner would make each scenario's outcome depend on which ran first. All are already uppercase and
    // inside the 1-32 characters domain/OwnerNormalizer accepts, so the stored owner equals the value sent - except
    // CREATE_OWNER_SENT, which is mixed case deliberately (see createCashAccount below).
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

    /** Never created by any scenario, which is what makes the 404 assertion mean something. */
    private static final String MISSING_OWNER = "CTMISSING";

    private static final String ACCOUNT_CURRENCY = "USD";

    // The exact text the wire must carry for a balance of 1234.56. Jackson writes an object member without a space
    // after the colon, and WRITE_BIGDECIMAL_AS_PLAIN (config/JacksonConfig) is what keeps the value out of the
    // 1.23456E3 scientific form a plain BigDecimal serializer would emit for some scales. Asserting this against
    // CashAccount.toString() instead would be meaningless: that method builds its own JSON WITH spaces
    // [.../broker/json/CashAccount.java:L70], so it would prove the DTO's formatting rather than the service's.
    private static final String PLAIN_DECIMAL_BALANCE_ON_THE_WIRE = "\"balance\":1234.56";

    // Half a cent: far below the smallest amount NUMERIC(9,2) can represent, so the tolerance can absorb a double's
    // representation error and nothing else.
    private static final double PENNY_TOLERANCE = 0.005;

    // AAP 0.6.2 binds each condition to exactly one status; named here so a red CI log reads as a contract row.
    private static final int ACCOUNT_NOT_FOUND_STATUS = 404;
    private static final int INSUFFICIENT_FUNDS_STATUS = 422;
    private static final int INVALID_AMOUNT_STATUS = 400;
    private static final int AMOUNT_OUT_OF_RANGE_STATUS = 422;

    /** The retail seam's published prefix, which the chart hands broker as {@code cashAccount.url}. */
    private static final String RETAIL_BASE = BrokerClientFactory.RETAIL_BASE_PATH;

    // The institutional path space, reached for two purposes only: the ledger is queried through the contract's own
    // audit surface (AAP 0.6.2) rather than through a repository, and a hold is the sole way to put funds into
    // reserved_balance - broker's client covers the retail surface alone, so that arrangement has to be made
    // through the service's own institutional endpoints rather than through the interface under test.
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

    // Minted once for the class: the token is valid for 12 hours (support/JwtTestTokens), so re-minting per method
    // would buy nothing. An explicit StockTrader token, rather than reliance on
    // cashaccount.security.all-authenticated-hold-stocktrader, keeps this file independent of the role matrix that
    // security/RoleEnforcementIT owns - a contract failure here can then never be an authorization finding.
    private static final String TOKEN = JwtTestTokens.stockTraderToken();

    @LocalServerPort
    private int port;

    // Carries the requests the caller's typed interface cannot express, listed in the class comment above: a
    // write with no body, a write whose body omits fields, a read of the institutional ledger, and the
    // institutional hold that arranges the credit-ceiling scenario. Every scenario the interface CAN express
    // still goes through broker's own client proxy, which is the point of the file.
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
        // The owner is sent in mixed case on purpose. The legacy response casing depended on which paragraph
        // answered - Q returned the database OWNER, already uppercase
        // [backend/cash-account-cobol/COBOL/CASH00.cbl:L144], while A echoed the caller's own spelling of
        // CUST-NAME-TEXT [CASH00.cbl:L158] - and the replacement always answers with the stored uppercase form
        // (AAP 0.4.2). Broker reads only balance and currency out of the response
        // [.../broker/BrokerService.java:L364-L365], so the change reaches no caller behaviour.
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

        // The caller's parameter is @QueryParam("amount") double [.../client/CashAccountClient.java:L83], so what
        // reaches the wire is whatever the provider renders for that double - "250.5" here, and the 1.0E7 form for
        // large values. The controller binds the parameter as text and parses it with new BigDecimal(String)
        // precisely so both forms arrive intact; this scenario is the end-to-end proof of that binding.
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
        // The legacy read of an absent owner left SQLCODE 100 in a 10-character return field that dropped the sign
        // [CASH00.cbl:L104] and copied the COMMAREA back verbatim [CASH00.cbl:L105-L108], so the caller received its
        // own submitted amount as the "balance" of an account that did not exist. A 404 carrying no account body is
        // the deliberate replacement (AAP 0.4.5): the client raises rather than returning a fabricated entity.
        //
        // The assertion is on the base type and the status, never on NotFoundException: which WebApplicationException
        // subclass the MicroProfile default response-exception mapper produces is the client runtime's business.
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

        // A zero credit or debit computed stored +/- 0, updated the row, answered SQLCODE 0 and still wrote a
        // history record [CASH00.cbl:L222, L256], so the transaction counts reconciliation compares are only equal
        // if the replacement accepts it too (AAP 0.4.5). Broker never sends it - it skips lastTrade == 0
        // [.../broker/BrokerService.java:L486-L501] - which is exactly why the contract has to honour it for the
        // callers that do.
        CashAccount unchanged = client.credit(ZERO_OWNER, 0.0);

        // A returned entity IS the 200: the client proxy raises a WebApplicationException on any non-2xx, so the
        // status cannot be read separately through this interface - and the entity is all broker ever sees.
        assertAccount(unchanged, ZERO_OWNER, 500.00, "PUT /cash-account/{owner}/credit?amount=0 (legacy C)");

        // THE HALF THAT MATTERS FOR PARITY, asserted here rather than left to another file: an unchanged balance
        // is also what a service that short-circuited the whole operation would return, so the row is the only
        // evidence that the transaction happened at all. Without it, reconciliation would count one legacy C
        // against zero target transactions and report a TRANSACTION_COUNT variance nothing in the test suite had
        // predicted (AAP 0.10.3). The ledger is read through the institutional query surface because that is the
        // contract's own way to read it, and in the request immediately after the credit, with no wait.
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
        /*
         * TWO HALVES, AND BOTH ARE NEEDED. The service's existsByOwner pre-read is not atomic, so two callers can
         * pass it together; whichever loses has to be refused by the owner primary key. Under the entity's earlier
         * primitive @Version, Spring Data judged a fresh account already-persisted and routed its creation through
         * EntityManager.merge, which SELECTed the row the winner had just committed and UPDATED it - answering 200
         * and silently re-opening the account with the loser's balance and a new incarnation. A nullable @Version
         * makes creation an INSERT instead, so the constraint decides.
         *
         * The first half asserts that mechanism where it is decidable: saving a fresh entity for an owner that
         * already has a row must raise the constraint violation, because an INSERT is the only statement that can.
         * It goes through the repository deliberately - the endpoint cannot express it, since existsByOwner
         * answers first and no test can schedule itself into the window between that check and the flush. The
         * second half then asserts the end-to-end outcome two real requests must produce. On its own the second
         * half is not a guard: the loser is usually refused by the pre-read, which looks identical from outside.
         */
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

        // The surviving account belongs to whichever request won, and its balance is one of the two sent - never a
        // blend, and never the loser's value written over the winner's row.
        CashAccount survivor = client.getCashAccount(RACE_OWNER);
        assertThat(survivor.getBalance()).as("the surviving account keeps the winning request's balance")
                .isIn(RACE_FIRST_BALANCE, RACE_SECOND_BALANCE);

        // One account was opened, so one ACCOUNT_CREATED row exists. Two would mean both requests had "created"
        // the account, which is the overwrite this scenario exists to rule out.
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

        // AAP 0.6.2 defines PUT as an absolute overwrite of the balance and the currency carried by a body. An
        // optional body made a request carrying no instruction at all indistinguishable from one asking for 0.00
        // in the base currency, so a body-less PUT emptied the account and answered 200 with "balance":0.00. It is
        // now 400 INVALID_AMOUNT - the closed code set's designated 400 for a body that cannot be bound
        // (AAP 0.6.2) - and the account is untouched.
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

        // Both halves of the documented body contract (README.md, retail contract table), asserted together
        // because they are one payload's worth of behaviour: the body's owner component is ignored in favour of
        // the path - broker sends the two in agreement and CashAccountErrorCode holds no mismatch condition to
        // report - while an absent balance is 0.00 and an absent currency is USD, the values the legacy COMMAREA
        // presented when a caller set neither [CASH00.cbl:L56]. The account named in the body must therefore be
        // untouched and the one named in the path reset.
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

        // And the owner the body named, which the service never looked at, still holds its own opening balance.
        assertAccount(client.getCashAccount(DECOY_OWNER), DECOY_OWNER, 700.00,
                "the owner named in the request body");
    }

    @Test
    void debitBeyondTheAvailableBalanceIsRejectedAsInsufficientFunds() {
        seedAccount(OVERDEBIT_OWNER, 100.00);

        AtomicReference<String> responseBody = new AtomicReference<>();
        CashAccountClient capturingClient = capturingClient(responseBody::set);

        // An authorized deliberate improvement, not a parity gap (AAP 0.4.6, 0.14.2): WS-CALC is unsigned
        // PIC 9(7)V99 [backend/cash-account-cobol/COBOL/CASH00.cbl:L17] and the COMPUTE that subtracts carries no
        // ON SIZE ERROR [CASH00.cbl:L256], so the legacy stored 100.00 - 150.00 as the absolute value 50.00 and
        // answered success. This is the target side of the RAUNAK seeded shadow mismatch in AAP 0.10.3.
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
        // service that judged the sign after normalizing would answer 200 here and append a zero-amount DEBIT
        // ledger row for a request the caller never made. AAP 0.6.2 binds a negative amount to 400 INVALID_AMOUNT
        // on this route, and domain/Money judges the sign on the value as the caller wrote it. The value reaches
        // the wire through the caller's own @QueryParam("amount") double
        // [.../client/CashAccountClient.java:L83], so this is the text an unmodified broker would emit for it.
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
        // The account is opened near the NUMERIC(9,2) ceiling and 250.00 of it is then held, so the credit
        // below computes an available balance that is perfectly in range on its own - 9,999,750.00 against a
        // 9,999,999.99 ceiling - while available + reserved is not. Accepting it would leave the hold with
        // nowhere to return to: a release, settlement or expiry has to hand those 250.00 back onto the
        // available balance, and the reservation could then never reach a terminal state while retail
        // PUT/DELETE answered RESERVATIONS_OUTSTANDING for as long as the row existed. The ceiling therefore
        // bounds the pair, and this is that refusal seen from the caller's side of the seam.
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

        // What the two statuses alone cannot show. The refused credit had to write nothing, so the hold is
        // still intact at its full amount, the available balance is the accepted credit's and no other, and
        // exactly one CREDIT row exists across both calls - the accepted one.
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

    // Seeding goes through the contract's own create endpoint rather than a repository or a JdbcTemplate: a
    // contract test that bypasses the contract to arrange its state proves less about the contract.
    private CashAccount seedAccount(String owner, double openingBalance) {
        return client.createCashAccount(owner, new CashAccount(owner, openingBalance, ACCOUNT_CURRENCY));
    }

    // Each racer builds its own proxy: the two requests have to be independent all the way down, and a shared
    // one would make the test's outcome depend on the client runtime's concurrency behaviour rather than the
    // service's. The barrier synchronizes only the LAUNCH of the two requests - it is released before either is
    // sent, so it cannot place them on either side of any server-side step, and whether they overlap in the
    // window between existsByOwner and the flush is the server's scheduling and not this test's to arrange. That
    // is precisely why the caller's half of this scenario is an invariant check rather than the regression guard,
    // and why the deterministic repository half is what proves INSERT rather than merge.
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

    // The body is written as text rather than serialized from HoldRequest so this file does not compile against
    // the institutional request record it only arranges state with; the retail wire shapes are the ones under
    // test here, and they all travel through the copied client above.
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
