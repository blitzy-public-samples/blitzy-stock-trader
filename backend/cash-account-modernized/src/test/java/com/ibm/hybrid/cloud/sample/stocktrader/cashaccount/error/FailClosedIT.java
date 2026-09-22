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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.CashAccountApplication;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.JwtTestTokens;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.PostgresTestSupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;

// The legacy dispatcher had no catch-all: "EVALUATE WS-REQ"
// [backend/cash-account-cobol/COBOL/CASH00.cbl:L89-L102] branches on the six request codes A/Q/U/X/C/D and
// ends at END-EVALUATE with no WHEN OTHER, so an unknown code ran no SQL at all. "MOVE SQLCODE TO
// WS-RETCODE" [CASH00.cbl:L104] then handed back whatever the SQLCA already held - which this task never
// sets - so the status field looked like success; the COMMAREA was copied back verbatim
// [CASH00.cbl:L105-L106, L108], so the caller read its OWN submitted amount as the "balance"; and the
// unconditional history write [CASH00.cbl:L126-L131] recorded the non-event as though it had happened. A
// caller therefore could not distinguish a rejected request from a completed one. Failing closed instead is
// an authorized deliberate improvement, and these two tests are its executable proof: the improvement is
// only real if an unmapped path and an unmapped verb are each observably rejected, with a body that names
// the reason.
/** Proves an unmapped path and an unmapped verb are each rejected explicitly, in the one ApiError shape. */
@SpringBootTest(classes = CashAccountApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Required for the context to start at all, not for anything either test asserts: config/MetricsScrapeController
// takes PrometheusMeterRegistry as a mandatory constructor argument, and Spring Boot's test customizer disables
// metrics export by default, which would leave that bean absent and fail the whole context.
@AutoConfigureObservability
class FailClosedIT extends PostgresTestSupport {

    private static final String OWNER = "JOHN";

    // Both requests are authenticated because SecurityConfig's /cash-account/** rule is authenticated(), and that
    // rule is what lets an authenticated-but-unsupported request through to Spring MVC to be answered 404 or 405.
    // An anonymous request would instead be stopped by ApiErrorAuthenticationEntryPoint with a 401 - asserted in
    // security/RoleEnforcementIT, deliberately not here. StockTrader is used so the outcome cannot depend on the
    // all-authenticated-hold-stocktrader parity grant that RoleEnforcementIT toggles.
    private static final String AUTHORIZATION = JwtTestTokens.bearer(JwtTestTokens.stockTraderToken());

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    // application-test.yml leaves the verification key unset on purpose so that no key material is checked in, so
    // each *IT supplies the ephemeral per-JVM certificate itself; without this the resource-server decoder has
    // nothing to build from and the context fails to start.
    @DynamicPropertySource
    static void jwtVerificationKey(DynamicPropertyRegistry registry) {
        registry.add("cashaccount.security.jwt.public-key-location", JwtTestTokens::publicKeyLocation);
    }

    @Test
    void unmappedPathUnderTheServiceSpaceIsRejectedAsUnsupportedPath() {
        ResponseEntity<String> response = authenticatedGet("/cash-account/" + OWNER + "/unknown-operation");

        assertThat(CashAccountErrorCode.UNSUPPORTED_PATH.status().value()).isEqualTo(404);
        assertThat(response.getStatusCode().value())
                .isEqualTo(CashAccountErrorCode.UNSUPPORTED_PATH.status().value());
        assertApiErrorShape(response, CashAccountErrorCode.UNSUPPORTED_PATH, "UNSUPPORTED_PATH");
    }

    // GET on a PUT-only mapping rather than PATCH on /cash-account/{owner}: HttpURLConnection, behind the request
    // factory TestRestTemplate uses by default, cannot issue PATCH, and swapping the factory would buy nothing.
    // /cash-account/{owner} matches a single segment, so this path cannot be absorbed by it - the request reaches
    // the debit mapping and is rejected on the verb, which is the condition under test.
    @Test
    void unmappedMethodOnAMappedPathIsRejectedAsUnsupportedMethod() {
        ResponseEntity<String> response = authenticatedGet("/cash-account/" + OWNER + "/debit");

        assertThat(CashAccountErrorCode.UNSUPPORTED_METHOD.status().value()).isEqualTo(405);
        assertThat(response.getStatusCode().value())
                .isEqualTo(CashAccountErrorCode.UNSUPPORTED_METHOD.status().value());
        assertApiErrorShape(response, CashAccountErrorCode.UNSUPPORTED_METHOD, "UNSUPPORTED_METHOD");
    }

    private ResponseEntity<String> authenticatedGet(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, AUTHORIZATION);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<Void>(headers), String.class);
    }

    // Read as a tree rather than deserialized into ApiError, because only a tree can prove a field is ABSENT:
    // ApiError is @JsonInclude(NON_NULL), and binding the body back into the record would render a missing owner
    // and a missing reservationId identically to nulls that were actually sent.
    private void assertApiErrorShape(ResponseEntity<String> response, CashAccountErrorCode expected,
            String expectedWireCode) {
        MediaType contentType = response.getHeaders().getContentType();
        assertThat(contentType).isNotNull();
        assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();

        String body = response.getBody();
        assertThat(body).isNotNull();

        JsonNode error = readTree(body);
        assertThat(error.isObject()).isTrue();

        // The enum constant's own name is the wire vocabulary, so renaming it would break every consumer parsing
        // this payload; comparing the constant against the literal makes that a test failure rather than a silent
        // contract change that the body assertion below would happily follow.
        assertThat(expected.name()).isEqualTo(expectedWireCode);
        assertThat(error.has("code")).isTrue();
        assertThat(error.get("code").isTextual()).isTrue();
        assertThat(error.get("code").asText()).isEqualTo(expectedWireCode);

        assertThat(error.has("message")).isTrue();
        assertThat(error.get("message").isTextual()).isTrue();
        assertThat(error.get("message").asText()).isNotBlank();

        assertThat(error.has("timestamp")).isTrue();
        String timestamp = error.get("timestamp").asText();
        assertThatCode(() -> Instant.parse(timestamp)).doesNotThrowAnyException();

        assertThat(error.has("owner")).isFalse();
        assertThat(error.has("reservationId")).isFalse();
        assertThat(error.size()).isEqualTo(3);
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception exception) {
            throw new AssertionError("Response body is not JSON: " + body, exception);
        }
    }
}
