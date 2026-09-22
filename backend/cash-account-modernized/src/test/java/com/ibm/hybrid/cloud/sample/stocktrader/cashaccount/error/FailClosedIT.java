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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Proves an unmapped path, an unmapped verb and an auto-answerable OPTIONS each fail closed as one ApiError. */
@SpringBootTest(classes = CashAccountApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FailClosedIT extends PostgresTestSupport {

    private static final String OWNER = "JOHN";

    // Authenticated, because SecurityConfig's /cash-account/** rule is authenticated() and that rule is what
    // lets an unsupported request reach Spring MVC to be answered 404 or 405; an anonymous one would be stopped
    // at 401 by the entry point instead. StockTrader, so no outcome depends on the parity grant
    // security/RoleEnforcementIT toggles.
    private static final String AUTHORIZATION = JwtTestTokens.bearer(JwtTestTokens.stockTraderToken());

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    // Needed only by the chunked case, which has to be issued by a client TestRestTemplate cannot stand in for.
    @LocalServerPort
    private int port;

    // application-test.yml leaves the verification key unset on purpose so that no key material is checked in, so
    // each *IT supplies the ephemeral per-JVM certificate itself; without this the resource-server decoder has
    // nothing to build from and the context fails to start.
    @DynamicPropertySource
    static void jwtVerificationKey(DynamicPropertyRegistry registry) {
        registry.add("cashaccount.security.jwt.public-key-location", JwtTestTokens::publicKeyLocation);
    }

    // The legacy EVALUATE WS-REQ had no WHEN OTHER [backend/cash-account-cobol/COBOL/CASH00.cbl:L89-L102], so an
    // unknown code ran no SQL, returned the SQLCA this task never set [CASH00.cbl:L104] and copied the COMMAREA
    // back [CASH00.cbl:L105-L106, L108] - a rejection a caller could not tell from a completed request.
    @Test
    void unmappedPathUnderTheServiceSpaceIsRejectedAsUnsupportedPath() {
        ResponseEntity<String> response = authenticatedGet("/cash-account/" + OWNER + "/unknown-operation");

        assertThat(CashAccountErrorCode.UNSUPPORTED_PATH.status().value()).isEqualTo(404);
        assertThat(response.getStatusCode().value())
                .isEqualTo(CashAccountErrorCode.UNSUPPORTED_PATH.status().value());
        assertApiErrorShape(response, CashAccountErrorCode.UNSUPPORTED_PATH, "UNSUPPORTED_PATH");
    }

    // GET on a PUT-only mapping rather than PATCH on /cash-account/{owner}: HttpURLConnection, behind the request
    // factory TestRestTemplate uses by default, cannot issue PATCH. /cash-account/{owner} matches a single
    // segment, so this two-segment path reaches the debit mapping and is rejected on the verb under test.
    @Test
    void unmappedMethodOnAMappedPathIsRejectedAsUnsupportedMethod() {
        ResponseEntity<String> response = authenticatedGet("/cash-account/" + OWNER + "/debit");

        assertThat(CashAccountErrorCode.UNSUPPORTED_METHOD.status().value()).isEqualTo(405);
        assertThat(response.getStatusCode().value())
                .isEqualTo(CashAccountErrorCode.UNSUPPORTED_METHOD.status().value());
        assertApiErrorShape(response, CashAccountErrorCode.UNSUPPORTED_METHOD, "UNSUPPORTED_METHOD");

        // The 405 names the verb the path does accept, so a caller learns the contract from the rejection.
        assertThat(response.getHeaders().getAllow()).containsExactly(HttpMethod.PUT);
    }

    // Spring MVC answers OPTIONS for every mapped path on its own with 200 and an Allow header, so it is the one
    // verb by which a request the service does not implement could still be answered as though it had succeeded.
    @Test
    void optionsOnAMappedPathIsRejectedAsUnsupportedMethod() {
        ResponseEntity<String> response = rest.exchange("/cash-account/" + OWNER, HttpMethod.OPTIONS,
                new HttpEntity<Void>(authenticatedHeaders()), String.class);

        assertThat(response.getStatusCode().value())
                .isEqualTo(CashAccountErrorCode.UNSUPPORTED_METHOD.status().value());
        assertApiErrorShape(response, CashAccountErrorCode.UNSUPPORTED_METHOD, "UNSUPPORTED_METHOD");

        // Asserted as a set, because the order MVC computes these in is not part of the contract.
        assertThat(response.getHeaders().getAllow()).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.HEAD,
                HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE);
    }

    // The two framings of one request, because a body-size control that covers one of them is not a control. A
    // declared length is refused by config/RequestBodySizeLimitFilter before a byte is read; a chunked body
    // declares no length at all, so only counting what is read can bound it - and that failure surfaces
    // mid-parse, wrapped by Spring's message converter, which is the path error/ApiExceptionHandler unwraps.
    // Before the limit existed both bodies were materialized by Jackson in full, ahead of any field validation
    // (CWE-400), in a pod limited to 2Gi.
    @Test
    void bodyWithADeclaredLengthOverTheLimitIsRejectedAsRequestTooLarge() throws Exception {
        byte[] oversized = oversizedJson(32 * 1024).getBytes(StandardCharsets.UTF_8);
        HttpRequest.BodyPublisher declaredLength = HttpRequest.BodyPublishers.ofByteArray(oversized);

        // A publisher that knows its length makes the client send Content-Length, which is the branch under test:
        // the body is refused before a single byte of it is read.
        assertThat(declaredLength.contentLength()).isEqualTo(oversized.length);

        HttpResponse<String> response = postJson(declaredLength, AUTHORIZATION);

        assertThat(CashAccountErrorCode.REQUEST_TOO_LARGE.status().value()).isEqualTo(413);
        assertThat(response.statusCode()).isEqualTo(CashAccountErrorCode.REQUEST_TOO_LARGE.status().value());
        assertApiErrorPayload(contentTypeOf(response), response.body(), CashAccountErrorCode.REQUEST_TOO_LARGE,
                "REQUEST_TOO_LARGE");

        // The size control sits INSIDE the authenticated flow, and this is what pins that: the same oversized
        // body without a credential is refused by the security filter chain as 401 rather than answered 413. An
        // anonymous caller therefore learns nothing about the limit, and the authorization decision still comes
        // first - which is what fail-closed means here. That body is never read either.
        HttpResponse<String> unauthenticated =
                postJson(HttpRequest.BodyPublishers.ofByteArray(oversized), null);

        assertThat(unauthenticated.statusCode()).isEqualTo(CashAccountErrorCode.UNAUTHORIZED.status().value());
        assertApiErrorPayload(contentTypeOf(unauthenticated), unauthenticated.body(),
                CashAccountErrorCode.UNAUTHORIZED, "UNAUTHORIZED");
    }

    // A streaming publisher reports no length, which is what makes the request chunked - the request factory
    // behind TestRestTemplate buffers and declares one, so it cannot express this case at all. The body is only
    // modestly over the limit so Tomcat drains the remainder within server.tomcat.max-swallow-size and the 413
    // reaches the client on the same connection.
    @Test
    void chunkedBodyOverTheLimitIsRejectedAsRequestTooLarge() throws Exception {
        byte[] body = oversizedJson(12 * 1024).getBytes(StandardCharsets.UTF_8);
        HttpRequest.BodyPublisher chunked =
                HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body));

        // No Content-Length reaches the server, so the rejection can only come from the counted read.
        assertThat(chunked.contentLength()).isNegative();

        HttpResponse<String> response = postJson(chunked, AUTHORIZATION);

        assertThat(response.statusCode()).isEqualTo(CashAccountErrorCode.REQUEST_TOO_LARGE.status().value());
        assertApiErrorPayload(contentTypeOf(response), response.body(), CashAccountErrorCode.REQUEST_TOO_LARGE,
                "REQUEST_TOO_LARGE");
    }

    // The JDK client rather than TestRestTemplate for both size cases: HttpURLConnection, which sits behind
    // TestRestTemplate's default factory, tries to re-send a request it has streamed when the answer is a 401
    // with a challenge and throws HttpRetryException instead of surfacing the response, so the unauthenticated
    // assertion above is unobservable through it.
    private HttpResponse<String> postJson(HttpRequest.BodyPublisher body, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + "/cash-account/" + OWNER))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(body);
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String contentTypeOf(HttpResponse<String> response) {
        return response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(null);
    }

    // Well-formed JSON of the shape the create endpoint accepts, padded past the limit by one long member value:
    // a body that fails on its SIZE and on nothing else, so the 413 cannot be mistaken for a parse failure.
    private static String oversizedJson(int approximateBytes) {
        String prefix = "{\"owner\":\"" + OWNER + "\",\"balance\":1.00,\"currency\":\"USD\",\"filler\":\"";
        String suffix = "\"}";
        int padding = Math.max(0, approximateBytes - prefix.length() - suffix.length());
        return prefix + "A".repeat(padding) + suffix;
    }

    private ResponseEntity<String> authenticatedGet(String path) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<Void>(authenticatedHeaders()), String.class);
    }

    private static HttpHeaders authenticatedHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, AUTHORIZATION);
        return headers;
    }

    // Read as a tree because only a tree can prove a field is ABSENT: ApiError is @JsonInclude(NON_NULL), and
    // binding the body back into the record would render a missing field identically to a null that was sent.
    private void assertApiErrorShape(ResponseEntity<String> response, CashAccountErrorCode expected,
            String expectedWireCode) {
        MediaType contentType = response.getHeaders().getContentType();
        assertThat(contentType).isNotNull();
        assertApiErrorPayload(contentType.toString(), response.getBody(), expected, expectedWireCode);
    }

    // Split from the assertion above so the payload contract is asserted identically however the response was
    // obtained: one rejection is read through TestRestTemplate and one through the JDK client, and a shape proven
    // for only one of them would leave the other free to answer something else.
    private void assertApiErrorPayload(String contentType, String body, CashAccountErrorCode expected,
            String expectedWireCode) {
        assertThat(contentType).isNotNull();
        assertThat(MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();

        assertThat(body).isNotNull();

        JsonNode error = readTree(body);
        assertThat(error.isObject()).isTrue();

        // The enum constant's own name is the wire vocabulary, so comparing it against the literal makes a rename
        // a test failure rather than a silent contract change the body assertion below would follow.
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
