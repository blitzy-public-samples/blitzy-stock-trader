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

    // The institutional surface is a separate, additive path space under /cash-account (AAP 0.6.2), but its first
    // segment is one segment long, so the bare prefix and its debit|credit reach the retail /{owner} mappings.
    // Unreserved, a POST there created a real account named INSTITUTIONAL which the GET and DELETE then served -
    // one path that was both the institutional namespace and a retail resource. Every casing is covered because
    // MVC matches a path segment case-sensitively while the owner is case-folded, so reserving only the
    // lower-case spelling would leave /cash-account/INSTITUTIONAL serving what /cash-account/institutional
    // refuses. The last assertion is the other half of the contract: the reservation must not cost the
    // institutional surface a route.
    @Test
    void theInstitutionalPathSegmentIsNotAvailableAsARetailAccount() {
        String prefix = "/cash-account/institutional";
        String body = "{\"owner\":\"INSTITUTIONAL\",\"balance\":42.00,\"currency\":\"USD\"}";

        assertRejectedAsUnsupportedPath(authenticatedExchange(prefix, HttpMethod.POST, body));
        assertRejectedAsUnsupportedPath(authenticatedExchange(prefix, HttpMethod.GET, null));
        assertRejectedAsUnsupportedPath(authenticatedExchange(prefix, HttpMethod.PUT, body));
        assertRejectedAsUnsupportedPath(authenticatedExchange(prefix, HttpMethod.DELETE, null));
        assertRejectedAsUnsupportedPath(
                authenticatedExchange(prefix + "/debit?amount=1.00", HttpMethod.PUT, null));
        assertRejectedAsUnsupportedPath(
                authenticatedExchange(prefix + "/credit?amount=1.00", HttpMethod.PUT, null));

        // The POST above must not have created anything, so the read is still a refusal rather than an account.
        assertRejectedAsUnsupportedPath(authenticatedExchange("/cash-account/INSTITUTIONAL", HttpMethod.GET, null));

        ResponseEntity<String> ledger =
                authenticatedExchange(prefix + "/accounts/" + OWNER + "/ledger", HttpMethod.GET, null);
        assertThat(ledger.getStatusCode().value()).isEqualTo(200);
    }

    // A wrong Content-Type is the commonest integration mistake against a JSON API, and it was answered 500
    // INTERNAL with an ERROR record - a caller's fault reported as a server fault. Both surfaces are asserted
    // because the condition is raised at different points on each: the hold mapping declares
    // consumes=application/json, so it fails during handler selection, while the retail create declares no
    // consumes at all and fails when no converter can read the body. The control proves the 415 is a media-type
    // decision and nothing else: the same endpoint with JSON parses the body and reaches the service, which
    // answers 404 for an account that does not exist.
    @Test
    void aRequestBodyMediaTypeThisServiceCannotReadIsRejectedAsUnsupportedMediaType() {
        String hold = "/cash-account/institutional/accounts/" + OWNER + "/holds";
        String holdBody = "{\"orderReference\":\"ORD-1\",\"amount\":1.00,\"currency\":\"USD\"}";

        ResponseEntity<String> plainText =
                exchange(hold, HttpMethod.POST, holdBody, MediaType.TEXT_PLAIN_VALUE, "k-415");

        assertThat(CashAccountErrorCode.UNSUPPORTED_MEDIA_TYPE.status().value()).isEqualTo(415);
        assertThat(plainText.getStatusCode().value()).isEqualTo(415);
        assertApiErrorShape(plainText, CashAccountErrorCode.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");

        // The rejection names what this service does read, so the contract is learned from it.
        assertThat(plainText.getHeaders().getAccept()).contains(MediaType.APPLICATION_JSON);

        ResponseEntity<String> xml = exchange("/cash-account/" + OWNER, HttpMethod.POST,
                "<cashAccount/>", MediaType.APPLICATION_XML_VALUE, null);

        assertThat(xml.getStatusCode().value()).isEqualTo(415);
        assertApiErrorShape(xml, CashAccountErrorCode.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");

        ResponseEntity<String> json =
                exchange(hold, HttpMethod.POST, holdBody, MediaType.APPLICATION_JSON_VALUE, "k-415-control");

        assertThat(json.getStatusCode().value())
                .isEqualTo(CashAccountErrorCode.ACCOUNT_NOT_FOUND.status().value());
    }

    // The mirror condition on the response side, which was answered 500 INTERNAL served as application/json -
    // a body the caller had just said it would not accept, carrying the wrong status. 406 is that rejection told
    // truthfully, and it still arrives in the one ApiError shape because the handler sets the content type
    // explicitly instead of renegotiating against an Accept it cannot satisfy.
    @Test
    void anAcceptHeaderThisServiceCannotSatisfyIsRejectedAsNotAcceptable() {
        ResponseEntity<String> xml = acceptingGet("/cash-account/" + OWNER, MediaType.APPLICATION_XML_VALUE);

        assertThat(CashAccountErrorCode.NOT_ACCEPTABLE.status().value()).isEqualTo(406);
        assertThat(xml.getStatusCode().value()).isEqualTo(406);
        assertApiErrorShape(xml, CashAccountErrorCode.NOT_ACCEPTABLE, "NOT_ACCEPTABLE");

        ResponseEntity<String> html = acceptingGet("/cash-account/" + OWNER, MediaType.TEXT_HTML_VALUE);
        assertThat(html.getStatusCode().value()).isEqualTo(406);

        // The control: negotiation itself still works, so the 406 is the caller's Accept and not a broken
        // response path.
        ResponseEntity<String> anything = acceptingGet("/cash-account/" + OWNER, MediaType.ALL_VALUE);
        assertThat(anything.getStatusCode().value())
                .isEqualTo(CashAccountErrorCode.ACCOUNT_NOT_FOUND.status().value());
    }

    // Spring Security's StrictHttpFirewall validates a header VALUE lazily, when the application first reads it,
    // so a value it refuses surfaces inside the dispatch rather than at the filter chain - and unhandled it was
    // answered 500 INTERNAL and logged at ERROR with the caller's own value and a stack beneath it, so one bad
    // header let any caller mint unbounded ERROR records carrying text it chose. The value is a C1 control
    // because that is what the servlet layer makes of a multi-byte character in a header: header bytes are
    // decoded as ISO-8859-1, so an emoji arrives as four characters, three of them controls. It is sent as the
    // control directly because the JDK client refuses to send a code point above 255 at all.
    @Test
    void aHeaderValueTheRequestFirewallRefusesIsRejectedAsABadRequest() {
        String hold = "/cash-account/institutional/accounts/" + OWNER + "/holds";
        String body = "{\"orderReference\":\"ORD-1\",\"amount\":1.00,\"currency\":\"USD\"}";

        ResponseEntity<String> response = exchange(hold, HttpMethod.POST, body,
                MediaType.APPLICATION_JSON_VALUE, "key\uD83D\uDD11");

        assertThat(CashAccountErrorCode.IDEMPOTENCY_KEY_REQUIRED.status().value()).isEqualTo(400);
        assertThat(response.getStatusCode().value())
                .isEqualTo(CashAccountErrorCode.IDEMPOTENCY_KEY_REQUIRED.status().value());
        assertApiErrorShape(response, CashAccountErrorCode.IDEMPOTENCY_KEY_REQUIRED, "IDEMPOTENCY_KEY_REQUIRED");

        // The control, which is what makes the rejection above a statement about the value: the same call with a
        // printable key is parsed and reaches the service, where the missing account decides the answer.
        ResponseEntity<String> printable =
                exchange(hold, HttpMethod.POST, body, MediaType.APPLICATION_JSON_VALUE, "key-plain");
        assertThat(printable.getStatusCode().value())
                .isEqualTo(CashAccountErrorCode.ACCOUNT_NOT_FOUND.status().value());
    }

    // Every member with no code of its own used to fall to INVALID_AMOUNT, so a missing or over-length
    // orderReference and an unparsable expiresAt were all answered "Amount is missing, not a number, or not
    // permitted for this operation" while the amount the caller sent was valid. The message named the field, but
    // the code - which is what an automated client reads - sent the caller to correct a value it had got right.
    // INVALID_REQUEST_FIELD is that condition stated truthfully, at the same 400, and the last case is the
    // control that keeps it from swallowing the members the AAP's vocabulary does name: an amount failure is
    // still INVALID_AMOUNT.
    @Test
    void aValidationFailureNamesTheFieldThatFailed() {
        String hold = "/cash-account/institutional/accounts/" + OWNER + "/holds";

        ResponseEntity<String> missing = exchange(hold, HttpMethod.POST,
                "{\"amount\":10,\"currency\":\"USD\"}", MediaType.APPLICATION_JSON_VALUE, "k-field-1");

        assertThat(CashAccountErrorCode.INVALID_REQUEST_FIELD.status().value()).isEqualTo(400);
        assertApiErrorShape(missing, CashAccountErrorCode.INVALID_REQUEST_FIELD, "INVALID_REQUEST_FIELD");
        assertThat(messageOf(missing)).contains("orderReference");

        ResponseEntity<String> tooLong = exchange(hold, HttpMethod.POST,
                "{\"orderReference\":\"" + "R".repeat(65) + "\",\"amount\":10,\"currency\":\"USD\"}",
                MediaType.APPLICATION_JSON_VALUE, "k-field-2");

        assertApiErrorShape(tooLong, CashAccountErrorCode.INVALID_REQUEST_FIELD, "INVALID_REQUEST_FIELD");
        assertThat(messageOf(tooLong)).contains("orderReference");

        // Bound by Jackson rather than by Bean Validation, which is the other half of the same contract: the
        // member Jackson was reading when it failed is reported exactly as a constraint failure on that member
        // would be, instead of arriving as a claim about the amount.
        ResponseEntity<String> unparsableExpiry = exchange(hold, HttpMethod.POST,
                "{\"orderReference\":\"ORD-1\",\"amount\":10,\"currency\":\"USD\",\"expiresAt\":\"not-a-date\"}",
                MediaType.APPLICATION_JSON_VALUE, "k-field-3");

        assertApiErrorShape(unparsableExpiry, CashAccountErrorCode.INVALID_REQUEST_FIELD,
                "INVALID_REQUEST_FIELD");
        assertThat(messageOf(unparsableExpiry)).contains("expiresAt");

        ResponseEntity<String> zeroAmount = exchange(hold, HttpMethod.POST,
                "{\"orderReference\":\"ORD-2\",\"amount\":0,\"currency\":\"USD\"}",
                MediaType.APPLICATION_JSON_VALUE, "k-field-4");

        assertThat(zeroAmount.getStatusCode().value())
                .isEqualTo(CashAccountErrorCode.INVALID_AMOUNT.status().value());
        assertThat(codeOf(zeroAmount)).isEqualTo(CashAccountErrorCode.INVALID_AMOUNT.code());

        // The same control on the retail seam, where the amount is called "balance"
        // [backend/broker/.../json/CashAccount.java:L22-L24]: a member that reads as the amount must keep
        // answering INVALID_AMOUNT, which is what AAP 0.6.2 fixes for the retail create and update.
        ResponseEntity<String> balanceTypeConfusion = exchange("/cash-account/" + OWNER, HttpMethod.POST,
                "{\"balance\":true,\"currency\":\"USD\"}", MediaType.APPLICATION_JSON_VALUE, null);

        assertThat(balanceTypeConfusion.getStatusCode().value())
                .isEqualTo(CashAccountErrorCode.INVALID_AMOUNT.status().value());
        assertThat(codeOf(balanceTypeConfusion)).isEqualTo(CashAccountErrorCode.INVALID_AMOUNT.code());
    }

    // Spring Security's firewall refuses a request before a single filter of the chain runs, and its default
    // handler answers with sendError(400) and no body. That was neither a 400 nor a body by the time it reached
    // the caller: the container turned it into an ERROR dispatch to /error, the chain ran again on that dispatch
    // with no principal (BearerTokenAuthenticationFilter is a OncePerRequestFilter and error dispatches are
    // skipped), and anyRequest().denyAll() answered 401 UNAUTHORIZED - telling a caller to authenticate for a
    // request already refused on its shape, and doing so on /actuator paths that require no authentication at
    // all. All four cases here are firewall refusals: a path Spring Security reads as traversal-prone, a matrix
    // parameter, and a method outside its allowed set.
    @Test
    void aRequestTheFirewallRefusesIsRenderedAsAnInvalidQueryApiError() throws Exception {
        assertRejectedAsInvalidQuery(send("//cash-account/" + OWNER, "GET", AUTHORIZATION));
        assertRejectedAsInvalidQuery(send("/cash-account/" + OWNER + ";x=y", "GET", AUTHORIZATION));

        // Unauthenticated and on a permitAll path, which is where the 401 was most obviously wrong: a kubelet or
        // a scraper reaching the probe through a proxy that emitted a double slash was told its credentials were
        // missing on a path that has none.
        assertRejectedAsInvalidQuery(send("//actuator/health", "GET", null));
        assertRejectedAsInvalidQuery(send("/cash-account/" + OWNER, "PROPFIND", AUTHORIZATION));

        // The control: the firewall is refusing these request lines, not the paths behind them.
        assertThat(send("/actuator/health/readiness", "GET", null).statusCode()).isEqualTo(200);
    }

    // Tomcat refuses a header block over server.max-http-request-header-size and an encoded path separator
    // before the request is mapped to a servlet context, so no error page can be dispatched and no filter, no
    // controller and no advice of this service ever sees the request: these were answered with Tomcat's own
    // 435-byte HTML page, carrying neither the ApiError body every other rejection carries nor any of the
    // security headers the filter chain writes. The last case is the boundary that admitting the ERROR dispatch
    // in config/SecurityConfig must not move: /error as an ordinary request is a REQUEST dispatch, matches no
    // rule, and stays denied.
    @Test
    void aContainerRejectionThatNeverReachesAServletIsRenderedAsApiError() throws Exception {
        HttpResponse<String> oversizedHeader = send("/cash-account/" + OWNER, "GET", AUTHORIZATION,
                "X-Overlong-Header", "H".repeat(9 * 1024));
        assertRejectedAsInvalidQuery(oversizedHeader);

        HttpResponse<String> encodedSeparator =
                send("/cash-account/..%2F..%2Fetc%2Fpasswd", "GET", AUTHORIZATION);
        assertRejectedAsInvalidQuery(encodedSeparator);

        HttpResponse<String> directError = send("/error", "GET", AUTHORIZATION);
        assertThat(directError.statusCode()).isEqualTo(CashAccountErrorCode.FORBIDDEN.status().value());
        assertApiErrorPayload(contentTypeOf(directError), directError.body(), CashAccountErrorCode.FORBIDDEN,
                "FORBIDDEN");
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

    // The JDK client for every rejection decided outside a handler, and for one reason per case: TestRestTemplate
    // builds its URI through UriComponentsBuilder, which normalises the "//" and the matrix parameter the firewall
    // is supposed to refuse; HttpURLConnection behind its default factory cannot issue an arbitrary method; and a
    // 9KB header has to reach the connector exactly as written. The path is concatenated rather than resolved so
    // it arrives raw.
    private HttpResponse<String> send(String path, String method, String authorization, String... headers)
            throws Exception {

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        for (int index = 0; index + 1 < headers.length; index += 2) {
            request.header(headers[index], headers[index + 1]);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private void assertRejectedAsInvalidQuery(HttpResponse<String> response) {
        assertThat(CashAccountErrorCode.INVALID_QUERY.status().value()).isEqualTo(400);
        assertThat(response.statusCode()).isEqualTo(CashAccountErrorCode.INVALID_QUERY.status().value());
        assertApiErrorPayload(contentTypeOf(response), response.body(), CashAccountErrorCode.INVALID_QUERY,
                "INVALID_QUERY");

        // The headers matter as much as the body here: these rejections are written outside the filter chain, so
        // Spring Security's HeaderWriterFilter - a OncePerRequestFilter, which skips error dispatches - cannot
        // supply them, and without them a refused response was the one response class this service served with
        // no nosniff, no frame denial and no no-store.
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(response.headers().firstValue("X-Frame-Options")).contains("DENY");
        assertThat(response.headers().firstValue(HttpHeaders.CACHE_CONTROL))
                .hasValueSatisfying(value -> assertThat(value).contains("no-store"));
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

    // One exchange for every case that varies only in verb, body or a header, so each test states the request it
    // makes and nothing about how it is issued.
    private ResponseEntity<String> authenticatedExchange(String path, HttpMethod method, String body) {
        return exchange(path, method, body, body == null ? null : MediaType.APPLICATION_JSON_VALUE, null);
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, String body, String contentType,
            String idempotencyKey) {

        HttpHeaders headers = authenticatedHeaders();
        if (contentType != null) {
            headers.set(HttpHeaders.CONTENT_TYPE, contentType);
        }
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    // Set as raw text rather than through setAccept, so the header reaches the server exactly as written - which
    // is the whole subject of the test using it.
    private ResponseEntity<String> acceptingGet(String path, String accept) {
        HttpHeaders headers = authenticatedHeaders();
        headers.set(HttpHeaders.ACCEPT, accept);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<Void>(headers), String.class);
    }

    private void assertRejectedAsUnsupportedPath(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().value())
                .isEqualTo(CashAccountErrorCode.UNSUPPORTED_PATH.status().value());
        assertApiErrorShape(response, CashAccountErrorCode.UNSUPPORTED_PATH, "UNSUPPORTED_PATH");
    }

    private String messageOf(ResponseEntity<String> response) {
        JsonNode error = readTree(response.getBody());
        assertThat(error.has("message")).isTrue();
        return error.get("message").asText();
    }

    private String codeOf(ResponseEntity<String> response) {
        JsonNode error = readTree(response.getBody());
        assertThat(error.has("code")).isTrue();
        return error.get("code").asText();
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
