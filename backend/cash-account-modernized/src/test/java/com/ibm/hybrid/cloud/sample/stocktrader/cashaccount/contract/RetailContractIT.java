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

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import jakarta.ws.rs.WebApplicationException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.ibm.hybrid.cloud.sample.stocktrader.broker.client.CashAccountClient;
import com.ibm.hybrid.cloud.sample.stocktrader.broker.json.CashAccount;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.BrokerClientFactory;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.JwtTestTokens;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.PostgresTestSupport;

/*
 * WHY THE REQUESTS BELOW ARE ISSUED BY BROKER'S OWN INTERFACE AND NOT BY A TEST CLIENT. The claim this whole
 * refactor rests on is that backend/broker was never edited and satisfies the new service as it stands: its
 * MicroProfile REST Client interface
 * [backend/broker/src/main/java/com/ibm/hybrid/cloud/sample/stocktrader/broker/client/CashAccountClient.java:L42-L90]
 * and its DTO [.../broker/json/CashAccount.java:L22-L24] are copied byte-identically into this module's test tree
 * (contract/CashAccountClientDriftTest fails the build if either copy drifts), and every call here goes through a
 * proxy built from that copy by support/BrokerClientFactory. A MockMvc, WebTestClient or TestRestTemplate request
 * would assert what this test intends rather than what the caller actually emits - its paths, its
 * Accept/Content-Type pair, its JSON binding and its double-valued ?amount= - so none is used for these scenarios.
 * If an assertion here could only be made green by editing broker or the copies, that is the stop-and-flag
 * condition of AAP 0.3.4, never a patch.
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

    // Minted once for the class: the token is valid for 12 hours (support/JwtTestTokens), so re-minting per method
    // would buy nothing. An explicit StockTrader token, rather than reliance on
    // cashaccount.security.all-authenticated-hold-stocktrader, keeps this file independent of the role matrix that
    // security/RoleEnforcementIT owns - a contract failure here can then never be an authorization finding.
    private static final String TOKEN = JwtTestTokens.stockTraderToken();

    @LocalServerPort
    private int port;

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
    void zeroAmountIsAcceptedAndLeavesTheBalanceUnchanged() {
        seedAccount(ZERO_OWNER, 500.00);

        // A zero credit or debit computed stored +/- 0, updated the row, answered SQLCODE 0 and still wrote a
        // history record [CASH00.cbl:L222, L256], so the transaction counts reconciliation compares are only equal
        // if the replacement accepts it too (AAP 0.4.5). Broker never sends it - it skips lastTrade == 0
        // [.../broker/BrokerService.java:L486-L501] - which is exactly why the contract has to honour it for the
        // callers that do. That the resulting ledger row exists is institutional/AuditImmediacyIT's assertion.
        CashAccount unchanged = client.credit(ZERO_OWNER, 0.0);

        // A returned entity IS the 200: the client proxy raises a WebApplicationException on any non-2xx, so the
        // status cannot be read separately through this interface - and the entity is all broker ever sees.
        assertAccount(unchanged, ZERO_OWNER, 500.00, "PUT /cash-account/{owner}/credit?amount=0 (legacy C)");
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

    // Seeding goes through the contract's own create endpoint rather than a repository or a JdbcTemplate: a
    // contract test that bypasses the contract to arrange its state proves less about the contract.
    private CashAccount seedAccount(String owner, double openingBalance) {
        return client.createCashAccount(owner, new CashAccount(owner, openingBalance, ACCOUNT_CURRENCY));
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
