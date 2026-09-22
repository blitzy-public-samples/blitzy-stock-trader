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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.JwtTestTokens;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.PostgresTestSupport;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/*
 * The grant this outer class runs under is what broker does today, not a relaxation invented here: the siblings
 * bind the StockTrader security-role to the ALL_AUTHENTICATED_USERS special subject
 * [backend/broker/src/main/liberty/config/server.xml:L56-L60], so any authenticated caller may already write,
 * and config/JwtDecoderConfig reproduces that by granting ROLE_StockTrader to every authenticated principal
 * while cashaccount.security.all-authenticated-hold-stocktrader is true. The verb split the rules below
 * otherwise express - GET to StockViewer or StockTrader, POST/PUT/DELETE to StockTrader alone
 * [backend/broker/src/main/webapp/WEB-INF/web.xml:L19-L51] - is therefore latent in the deployed default and
 * decisive only in strict mode, which is why both modes have to be exercised and neither on its own is proof.
 *
 * Two Spring contexts rather than one parameterized class: that single property is bound into
 * config/CashAccountProperties and read while the filter chain and the authority converter are built, so no
 * running context can be switched between the modes mid-suite. @Nested with its own @SpringBootTest and
 * @TestPropertySource is the mechanism that gets a second, independently configured application on a second
 * random port out of one test class.
 */
/** Asserts broker's role split on the retail and institutional surfaces in both the deployed and strict grant modes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "cashaccount.security.all-authenticated-hold-stocktrader=true")
public class RoleEnforcementIT extends PostgresTestSupport {

    // Owners are private to this class because support/PostgresTestSupport starts ONE container for the whole test
    // JVM: an owner shared with another *IT would make each suite's outcome depend on which ran first, and the
    // account this file creates outlives its own context. All are already uppercase and inside the 1-32 characters
    // domain/OwnerNormalizer accepts, so the stored owner equals the value sent and can be asserted literally.
    private static final String READ_OWNER = "RBACREAD";
    private static final String WRITE_OWNER = "RBACWRITE";
    private static final String STRICT_WRITE_OWNER = "RBACSTRICTWRITE";
    private static final String INSTITUTIONAL_OWNER = "RBACINSTITUTIONAL";

    // USD is broker's default account currency, and with cashaccount.fx.base-currency also USD a same-currency
    // operation short-circuits to a rate of exactly 1. That keeps every request below clear of the exchange-rate
    // client, which src/test/resources/application-test.yml deliberately points at a refused local port.
    private static final String ACCOUNT_CURRENCY = "USD";

    // Plain decimal TEXT inside a literal JSON body. A Java floating-point literal would put a double on a money
    // path, which AAP 0.7.1 prohibits outright, and a BigDecimal serialized by a mapper configured differently
    // from the service's own would test the test's mapper rather than the contract.
    private static final String SEED_BALANCE = "1000.00";
    private static final String WRITE_BALANCE = "2500.00";

    private static final ObjectMapper JSON = new ObjectMapper();

    // TestRestTemplate, not the MicroProfile client of support/BrokerClientFactory: that client throws a
    // WebApplicationException on a 4xx, so the rejected-request assertions this class exists for could only be
    // written as exception handling, and the response BODY - the ApiError shape three of these five scenarios
    // turn on - would have to be recovered from the exception. TestRestTemplate returns 401 and 403 as ordinary
    // responses. Driving the real client interface is contract/RetailContractIT's job.
    @Autowired
    private TestRestTemplate rest;

    @DynamicPropertySource
    static void jwtSignerKey(DynamicPropertyRegistry registry) {
        registry.add("cashaccount.security.jwt.public-key-location", JwtTestTokens::publicKeyLocation);
    }

    @Test
    void stockViewerReadsRetailAccount() throws JsonProcessingException {
        seedAccount(READ_OWNER);

        ResponseEntity<String> response = rest.exchange("/cash-account/{owner}", HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(JwtTestTokens.stockViewerToken())), String.class, READ_OWNER);

        // 200 rather than merely "not 403" is the load-bearing part: a 404 would equally prove the read was
        // admitted, but only a body proves it reached the controller and that the read-only role is sufficient
        // for the whole operation [backend/broker/src/main/webapp/WEB-INF/web.xml:L19-L32].
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode account = json(response.getBody());
        assertThat(account.path("owner").asText()).isEqualTo(READ_OWNER);
        assertThat(account.path("balance").isNumber()).isTrue();
        assertThat(account.path("currency").asText()).isEqualTo(ACCOUNT_CURRENCY);
    }

    @Test
    void anyAuthenticatedPrincipalWritesUnderTheParityGrant() throws JsonProcessingException {
        // A token carrying NO groups claim at all is what makes this assertion mean something: the principal holds
        // neither role, so a 200 can only have come from the ALL_AUTHENTICATED_USERS parity grant
        // [backend/broker/src/main/liberty/config/server.xml:L56-L60] and from nothing the token itself asserted.
        // "other" is a real registry user in no group [backend/broker/src/main/liberty/config/includes/none.xml:L37].
        String withoutAnyGroup = JwtTestTokens.tokenFor(JwtTestTokens.USER_UNPRIVILEGED);

        // A create, never PUT .../debit or .../credit: those two are the only retail operations that consult the
        // exchange-rate source, and the test profile's refused fx.url makes them the wrong instrument for a
        // question about authorization.
        ResponseEntity<String> response = rest.exchange("/cash-account/{owner}", HttpMethod.POST,
                new HttpEntity<>(accountBody(WRITE_OWNER, WRITE_BALANCE), jsonHeaders(withoutAnyGroup)),
                String.class, WRITE_OWNER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(response.getBody()).path("owner").asText()).isEqualTo(WRITE_OWNER);
    }

    @Test
    void missingTokenIsRejectedAsApiError() throws JsonProcessingException {
        ResponseEntity<String> response = rest.exchange("/cash-account/{owner}", HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(null)), String.class, READ_OWNER);

        // Spring Security rejects this inside the filter chain, before any controller and therefore beyond the
        // reach of error/ApiExceptionHandler's @RestControllerAdvice. Asserting the full payload - not just the
        // status - is what proves error/ApiErrorAuthenticationEntryPoint is wired, because an unwired chain
        // answers 401 with an empty body and a caller would then need a second parser for authentication
        // failures and one for every other error (AAP 0.6.2).
        assertApiError(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
    }

    /** Strict mode: a supported, documented configuration in which only the token's {@code groups} claim decides. */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("test")
    @TestPropertySource(properties = "cashaccount.security.all-authenticated-hold-stocktrader=false")
    class StrictRoleMode extends PostgresTestSupport {

        // Its own template because its own context listens on its own random port; named apart from the enclosing
        // field so neither reads as the other's.
        @Autowired
        private TestRestTemplate strictRest;

        // Declared here as well as in the enclosing class so this context configures itself completely, whatever
        // a later change does to nested-configuration inheritance. Registering one key twice with the same
        // supplier is a map write, not a conflict.
        @DynamicPropertySource
        static void strictJwtSignerKey(DynamicPropertyRegistry registry) {
            registry.add("cashaccount.security.jwt.public-key-location", JwtTestTokens::publicKeyLocation);
        }

        @Test
        void stockViewerCannotWriteRetailAccount() throws JsonProcessingException {
            // Nothing is seeded on purpose. The write matcher denies in the filter chain, so whether the account
            // exists cannot change the outcome - and a 404 here instead of a 403 would mean the request had
            // reached the controller and the role split had not been applied at all
            // [backend/broker/src/main/webapp/WEB-INF/web.xml:L34-L51].
            ResponseEntity<String> response = strictRest.exchange("/cash-account/{owner}", HttpMethod.POST,
                    new HttpEntity<>(accountBody(STRICT_WRITE_OWNER, SEED_BALANCE),
                            jsonHeaders(JwtTestTokens.stockViewerToken())),
                    String.class, STRICT_WRITE_OWNER);

            assertApiError(response, HttpStatus.FORBIDDEN, "FORBIDDEN");
        }

        @Test
        void tokenWithoutStockTraderCannotReachInstitutionalSurface() throws JsonProcessingException {
            // The institutional path space admits StockTrader only, and this module's own tests are the sole
            // thing that exercises it: wiring an institutional order service to these endpoints is deliberately
            // a follow-on, and doing so here would be a stop-and-flag condition (AAP 0.2.4, 0.3.4).
            ResponseEntity<String> response = strictRest.exchange("/cash-account/institutional/accounts/{owner}",
                    HttpMethod.GET, new HttpEntity<>(jsonHeaders(JwtTestTokens.stockViewerToken())),
                    String.class, INSTITUTIONAL_OWNER);

            assertApiError(response, HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
    }

    private void seedAccount(String owner) {
        ResponseEntity<String> response = rest.exchange("/cash-account/{owner}", HttpMethod.POST,
                new HttpEntity<>(accountBody(owner, SEED_BALANCE), jsonHeaders(JwtTestTokens.stockTraderToken())),
                String.class, owner);

        // Setup, so a conflict is success: the container outlives this context, and an owner already created by an
        // earlier run in the same JVM is the state this test wants. Any OTHER status is a broken precondition and
        // is surfaced here rather than mis-read later as an authorization result.
        assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.CONFLICT);
    }

    private static HttpHeaders jsonHeaders(String tokenOrNull) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        if (tokenOrNull != null) {
            headers.setBearerAuth(tokenOrNull);
        }
        return headers;
    }

    private static String accountBody(String owner, String plainBalance) {
        return "{\"owner\":\"" + owner + "\",\"balance\":" + plainBalance
                + ",\"currency\":\"" + ACCOUNT_CURRENCY + "\"}";
    }

    private static void assertApiError(ResponseEntity<String> response, HttpStatus expectedStatus,
            String expectedCode) throws JsonProcessingException {

        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);

        MediaType contentType = response.getHeaders().getContentType();
        assertThat(contentType).isNotNull();
        assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(response.getBody()).isNotBlank();

        JsonNode error = json(response.getBody());
        assertThat(error.path("code").asText()).isEqualTo(expectedCode);
        assertThat(error.path("message").asText()).isNotBlank();
        assertThat(error.hasNonNull("timestamp")).isTrue();

        // Absent, not null. error/ApiError is @JsonInclude(NON_NULL) and ApiError.of(code) sets neither field, so
        // presence is what carries meaning in this payload; a key appearing with a null value would be a change
        // of contract that an "is null" assertion would let through.
        assertThat(error.has("owner")).isFalse();
        assertThat(error.has("reservationId")).isFalse();
    }

    private static JsonNode json(String body) throws JsonProcessingException {
        // readTree, never a bind to error/ApiError or retail/CashAccountResponse: ApiError carries an Instant,
        // which a plain ObjectMapper cannot read without the JavaTimeModule, and a tree keeps the assertions on
        // the wire text the service actually emitted.
        return JSON.readTree(body);
    }
}
