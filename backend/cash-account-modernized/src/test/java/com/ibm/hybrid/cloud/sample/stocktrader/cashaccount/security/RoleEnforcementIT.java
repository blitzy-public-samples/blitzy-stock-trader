package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.config.CashAccountProperties;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.config.JwtDecoderConfig;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.config.SecurityConfig;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.JwtTestTokens;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.PostgresTestSupport;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

/** Asserts broker's role split on the retail and institutional surfaces in both the deployed and strict grant modes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "cashaccount.security.all-authenticated-hold-stocktrader=true")
public class RoleEnforcementIT extends PostgresTestSupport {

    // Owners are private to this class because support/PostgresTestSupport starts ONE container for the whole test
    // JVM: an owner shared with another *IT would make each suite's outcome depend on which ran first. All are
    // uppercase and within the 1-32 characters domain/OwnerNormalizer accepts, so the stored owner equals the
    // value sent and can be asserted literally.
    private static final String READ_OWNER = "RBACREAD";
    private static final String WRITE_OWNER = "RBACWRITE";
    private static final String STRICT_WRITE_OWNER = "RBACSTRICTWRITE";
    private static final String INSTITUTIONAL_OWNER = "RBACINSTITUTIONAL";
    private static final String NO_EXPIRY_OWNER = "RBACNOEXPIRY";
    private static final String EXACT_GROUP_OWNER = "RBACEXACTGROUP";

    // USD is both broker's default account currency and cashaccount.fx.base-currency, so a same-currency
    // operation short-circuits to a rate of exactly 1 and no request here reaches the exchange-rate client
    // application-test.yml points at a refused local port.
    private static final String ACCOUNT_CURRENCY = "USD";

    // Plain decimal TEXT: a Java floating-point literal would put a double on a money path (AAP 0.7.1), and a
    // BigDecimal serialized by a mapper configured differently from the service's own would test that mapper.
    private static final String SEED_BALANCE = "1000.00";
    private static final String WRITE_BALANCE = "2500.00";

    // The two configuration keys the start-up scenarios below drive. Written out rather than imported because
    // config/JwtDecoderConfig keeps its copies private; SecurityConfig.AUTH_TYPE_PROPERTY is public and is used
    // as such, so only the JWKS key needs restating.
    private static final String JWKS_URL_PROPERTY = "cashaccount.security.jwt.jwks-url";

    // A value no legitimate message, reason or diagnostic could contain, so its appearance anywhere in a
    // start-up failure can only mean a rejected JWKS URL was reproduced.
    private static final String CREDENTIAL_SENTINEL = "pa55phrase";

    private static final ResourceLoader RESOURCE_LOADER = new DefaultResourceLoader();

    private static final ObjectMapper JSON = new ObjectMapper();

    // TestRestTemplate, not the MicroProfile client of support/BrokerClientFactory: that client throws on a 4xx,
    // so every rejection this class exists for would have to be recovered from an exception - including the
    // ApiError body four of these scenarios turn on. Driving the real client interface is RetailContractIT's job.
    @Autowired
    private TestRestTemplate rest;

    // The built chain itself, so the CVE-2026-22732 workaround can be read off the running application rather
    // than off config/SecurityConfig's source: an ObjectPostProcessor that never matched would leave the source
    // looking correct and the flag false.
    @Autowired
    private FilterChainProxy securityFilterChain;

    // Needed only by the rejection of a write that carries a body, which has to be issued by a client
    // TestRestTemplate cannot stand in for; see postAccount.
    @LocalServerPort
    private int port;

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
        // neither role, so a 200 can only have come from the ALL_AUTHENTICATED_USERS parity grant broker deploys
        // today [backend/broker/src/main/liberty/config/server.xml:L56-L60]. The verb split
        // [backend/broker/src/main/webapp/WEB-INF/web.xml:L19-L51] is therefore latent in this mode and decisive
        // only in the strict one below, which is why neither mode on its own is proof. "other" is a real registry
        // user in no group [backend/broker/src/main/liberty/config/includes/none.xml:L37].
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

        // Rejected inside the filter chain, beyond the reach of error/ApiExceptionHandler's @RestControllerAdvice,
        // so the full payload is what proves error/ApiErrorAuthenticationEntryPoint is wired: an unwired chain
        // answers 401 with an empty body, leaving a caller two payload shapes to parse instead of one (AAP 0.6.2).
        assertApiError(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
    }

    @Test
    void tokenCarryingNoExpiryClaimIsRefusedOnEverySecuredSurface() throws Exception {
        // Correctly signed, issued and audienced, holding StockTrader, and missing only exp. Spring Security's
        // default JwtTimestampValidator compares exp against the clock only when the claim is PRESENT, so before
        // config/JwtDecoderConfig required its presence this was a credential that never expired and could not be
        // revoked. All three surfaces are asserted in one scenario because each is admitted by a different
        // authorization rule - a read, a write and the institutional space (AAP 0.7.5 rule order) - and token
        // validation has to refuse the request ahead of every one of them.
        seedAccount(READ_OWNER);

        String withoutExpiry = JwtTestTokens.tokenWithoutExpiryFor(
                JwtTestTokens.USER_STOCK_TRADER, JwtTestTokens.GROUP_STOCK_TRADER);

        ResponseEntity<String> read = rest.exchange("/cash-account/{owner}", HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(withoutExpiry)), String.class, READ_OWNER);
        assertApiError(read, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");

        // A create, never PUT .../debit or .../credit, for the same reason as the parity-grant scenario above:
        // those two consult the exchange-rate source the test profile points at a refused port. Its own owner, so
        // that a regression admitting this write cannot leave an account another scenario reads.
        ResponseEntity<String> write = postAccount(NO_EXPIRY_OWNER, withoutExpiry);
        assertApiError(write, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");

        ResponseEntity<String> institutional = rest.exchange("/cash-account/institutional/accounts/{owner}",
                HttpMethod.GET, new HttpEntity<>(jsonHeaders(withoutExpiry)), String.class, INSTITUTIONAL_OWNER);
        assertApiError(institutional, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");

        // The control belongs in this method: the same identity and groups WITH an exp claim is admitted and
        // serves the seeded account, which is what makes the three rejections a property of the absent claim
        // rather than of a decoder that has stopped accepting tokens at all.
        ResponseEntity<String> admitted = rest.exchange("/cash-account/{owner}", HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(JwtTestTokens.tokenFor(
                        JwtTestTokens.USER_STOCK_TRADER, JwtTestTokens.GROUP_STOCK_TRADER))),
                String.class, READ_OWNER);

        assertThat(admitted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(admitted.getBody()).path("owner").asText()).isEqualTo(READ_OWNER);
    }

    @Test
    void securityHeadersReachSuccessAndFilterChainErrorResponses() throws JsonProcessingException {
        // config/SecurityConfig writes Spring Security's default headers EAGERLY as the documented workaround
        // for CVE-2026-22732, under which a response committed through setHeader/setIntHeader/addIntHeader is
        // sent without any of the lazily written ones. Both halves are needed because they commit through
        // different code: the retail 200 is committed by MVC's message converter, while the 401 is committed
        // inside the filter chain by error/ApiErrorAuthenticationEntryPoint.
        seedAccount(READ_OWNER);

        ResponseEntity<String> admitted = rest.exchange("/cash-account/{owner}", HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(JwtTestTokens.stockViewerToken())), String.class, READ_OWNER);
        assertThat(admitted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertSecurityHeaders(admitted.getHeaders());

        ResponseEntity<String> unauthenticated = rest.exchange("/cash-account/{owner}", HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(null)), String.class, READ_OWNER);
        assertApiError(unauthenticated, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        assertSecurityHeaders(unauthenticated.getHeaders());
    }

    @Test
    void headerWriterFilterWritesEagerlyInEveryBuiltChain() {
        // The assertions above prove the headers ARRIVE, which they also do unpatched: CVE-2026-22732 only drops
        // them when the application commits the response through setHeader/setIntHeader/addIntHeader, a path no
        // endpoint of this service takes. So they cannot distinguish the workaround being engaged from it having
        // silently failed to apply - and silent failure is the realistic outcome, because the fix is an
        // ObjectPostProcessor whose type argument SecurityConfigurerAdapter's composite resolves reflectively.
        // This asserts the state that actually closes the CVE, on the filter the running chain holds.
        List<SecurityFilterChain> chains = securityFilterChain.getFilterChains();
        assertThat(chains).isNotEmpty();

        // Every chain, not the first: config/SecurityConfig builds one today, and a second one added later
        // without the post-processor would reopen the CVE on whatever paths it matched.
        for (SecurityFilterChain chain : chains) {
            List<HeaderWriterFilter> headerWriters = chain.getFilters().stream()
                    .filter(HeaderWriterFilter.class::isInstance)
                    .map(HeaderWriterFilter.class::cast)
                    .toList();
            assertThat(headerWriters).as("HeaderWriterFilter present in chain %s", chain).hasSize(1);

            // Read reflectively because Spring Security exposes only the setter. A rename in a future version
            // fails this assertion, which is the correct outcome: the workaround would then need re-verifying
            // against that version rather than being assumed to still hold.
            Object eagerly = ReflectionTestUtils.getField(headerWriters.get(0), "shouldWriteHeadersEagerly");
            assertThat(eagerly).as("shouldWriteHeadersEagerly in chain %s", chain).isEqualTo(Boolean.TRUE);
        }
    }

    @Test
    void authTypeDecidesTheDecoderAsTextAndIsNeverEvaluated() {
        // AUTH_TYPE reaches the pod from a ConfigMap
        // [infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L73-L77], so whoever can
        // write that value must not thereby be able to run code. The payload closes the quote the removed
        // @ConditionalOnExpression template opened, and evaluating that template shape here is what makes the
        // assertion below mean something: it proves the value is genuinely executable, by setting the probe.
        String probe = "cashaccount.test.authtype.expression.probe";
        String payload = "none') or (T(java.lang.System).setProperty('" + probe
                + "','executed') == null and 'x' == 'x";

        System.clearProperty(probe);
        try {
            Boolean templateResult = new SpelExpressionParser()
                    .parseExpression("!'none'.equalsIgnoreCase('" + payload + "'.trim())")
                    .getValue(Boolean.class);

            assertThat(templateResult).isTrue();
            assertThat(System.getProperty(probe)).isEqualTo("executed");

            // The condition that replaced it reads the same value through the Environment, which resolves
            // placeholders and evaluates nothing, so the payload decides only by not being the text "none".
            System.clearProperty(probe);
            assertThat(JwtDecoderConfig.decoderRequired(
                    new MockEnvironment().withProperty(SecurityConfig.AUTH_TYPE_PROPERTY, payload))).isTrue();
            assertThat(System.getProperty(probe)).isNull();
        } finally {
            System.clearProperty(probe);
        }

        // Fail closed, and at start-up: the value is not a mode this service can verify, so both gates that read
        // it stop the context instead of letting the pod serve traffic (AAP 0.6.5).
        assertThatThrownBy(() -> SecurityConfig.authenticating(payload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SecurityConfig.AUTH_TYPE_PROPERTY);
        assertThatThrownBy(() -> new JwtDecoderConfig().jwtDecoder(properties(payload, ""), RESOURCE_LOADER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SecurityConfig.AUTH_TYPE_PROPERTY);
    }

    @Test
    void onlyNoneModeOmitsTheDecoderBean() {
        // The bean's presence IS the none-mode contract - config/SecurityConfig resolves it through an
        // ObjectProvider and configures no resource server when it is absent - so this mapping, not the mechanism
        // that computes it, is what had to survive replacing the expression with a Condition.
        assertThat(decoderRequiredFor(SecurityConfig.MODE_NONE)).isFalse();
        assertThat(decoderRequiredFor("  NONE  ")).isFalse();
        assertThat(decoderRequiredFor("basic")).isTrue();
        assertThat(decoderRequiredFor("ldap")).isTrue();
        assertThat(decoderRequiredFor("oidc")).isTrue();

        // Unset binds to the chart's own default (.../values.yaml:L20), so a deployment that omits global.auth
        // still authenticates rather than silently opening the service.
        assertThat(JwtDecoderConfig.decoderRequired(new MockEnvironment())).isTrue();
    }

    @Test
    void oidcVerificationKeysAreAcceptedOnlyFromAnHttpsKeySet() {
        // In oidc mode the JWKS endpoint is the entire trust anchor
        // [backend/broker/src/main/liberty/config/includes/oidc.xml:L16-L21]: over cleartext http anyone on the
        // path answers for it, substitutes signing keys and mints a token this service accepts, StockTrader
        // included. The transport is therefore a start-up condition, not deployment advice.
        assertThat(oidcDecoderFor("https://keycloak.example.com/realms/stocktrader/protocol/openid-connect/certs"))
                .isNotNull();

        // A list rather than separate methods because each entry is one rejection reason of the same gate, and
        // the AAP's test budget counts scenarios, not assertions (AAP 0.7.6). The last two carry the sentinel
        // password: one is well formed and one is malformed, because the two take different code paths out of the
        // validator and only the malformed one ever reached a URI parser whose own message repeats its input.
        List<String> rejected = List.of(
                "http://keycloak.example.com/realms/stocktrader/protocol/openid-connect/certs",
                "HTTP://keycloak.example.com/certs",
                "keycloak.example.com/certs",
                "/realms/stocktrader/protocol/openid-connect/certs",
                "https:///certs",
                "https://keycloak.example.com/certs#signing",
                "ht tp://keycloak.example.com/certs",
                "",
                "https://operator:" + CREDENTIAL_SENTINEL + "@keycloak.example.com/certs",
                "https://operator:" + CREDENTIAL_SENTINEL + "@keycloak.example.com/cer ts");

        for (String jwksUrl : rejected) {
            assertThatThrownBy(() -> oidcDecoderFor(jwksUrl))
                    .as("JWKS URL '%s' must fail start-up", jwksUrl)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(JWKS_URL_PROPERTY)
                    // Asserted over the rendered stack trace, not the message alone: Spring prints the whole
                    // cause chain when a context fails to start, so an exception attached beneath the redacted
                    // message would put the password in pod logs just as surely as the message would.
                    .satisfies(thrown -> assertThat(rendered(thrown)).doesNotContain(CREDENTIAL_SENTINEL));
        }
    }

    /**
     * Strict mode - where only the token's {@code groups} claim decides - in its own context, because the grant
     * property is read while the filter chain and authority converter are built and cannot be switched mid-suite.
     */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("test")
    @TestPropertySource(properties = "cashaccount.security.all-authenticated-hold-stocktrader=false")
    class StrictRoleMode extends PostgresTestSupport {

        // Its own template because its own context listens on its own random port.
        @Autowired
        private TestRestTemplate strictRest;

        // Declared here as well as in the enclosing class so this context configures itself completely whatever
        // a later change does to nested-configuration inheritance; one key registered twice is a map write.
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
        void onlyTheExactGroupNameGrantsTheTraderRole() throws JsonProcessingException {
            // Strict mode is the only configuration in which this is observable: with the parity grant on, every
            // authenticated caller holds StockTrader whatever the claim says, so each value below would be admitted
            // for a reason that has nothing to do with how it is spelled.
            //
            // One method iterating over the values rather than a parameterized matrix (AAP 0.7.6). Each differs
            // from the estate's group name only by surrounding whitespace or a control character, and every one of
            // them carried the write role while the mapping trimmed the claim before prefixing it with ROLE_ -
            // String.trim() strips every character up to and including U+0020, NUL included (CWE-178). The empty
            // value is included because it granted nothing even then, and that has to stay true.
            List<String> nearMisses = List.of(
                    "StockTrader ", " StockTrader", "StockTrader\t", "StockTrader\n", "StockTrader\u0000", "");

            for (String group : nearMisses) {
                ResponseEntity<String> refused = strictRest.exchange("/cash-account/{owner}", HttpMethod.POST,
                        new HttpEntity<>(accountBody(EXACT_GROUP_OWNER, SEED_BALANCE),
                                jsonHeaders(JwtTestTokens.tokenFor(JwtTestTokens.USER_UNPRIVILEGED, group))),
                        String.class, EXACT_GROUP_OWNER);

                // Described before the payload shape is asserted: assertApiError cannot name the value it was
                // handed, and a tab or a NUL printed raw would not identify it either.
                assertThat(refused.getStatusCode()).as("groups value %s", visible(group))
                        .isEqualTo(HttpStatus.FORBIDDEN);
                assertApiError(refused, HttpStatus.FORBIDDEN, "FORBIDDEN");
            }

            // The control, so the six refusals read as a property of the spelling rather than of a role model that
            // now grants nothing: the exact group name still writes. Either status proves admission - both come
            // from the controller - and a conflict is possible for the reason seedAccount records.
            ResponseEntity<String> admitted = strictRest.exchange("/cash-account/{owner}", HttpMethod.POST,
                    new HttpEntity<>(accountBody(EXACT_GROUP_OWNER, SEED_BALANCE),
                            jsonHeaders(JwtTestTokens.stockTraderToken())),
                    String.class, EXACT_GROUP_OWNER);

            assertThat(admitted.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.CONFLICT);
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

        @Test
        void unqualifiedInstitutionalPathIsAdmittedByTheRetailRuleAndRefusedByTheController()
                throws JsonProcessingException {

            // The exact two-segment path is what Spring MVC dispatches to the retail @GetMapping("/{owner}") - no
            // institutional route is that short - so the read-only rule has to be the one that admits it. A 403
            // here would mean the institutional wildcard was consulted for a path that never reaches an
            // institutional handler (AAP 0.7.5 rule order), which is the property under test and is unchanged.
            ResponseEntity<String> response = strictRest.exchange("/cash-account/institutional", HttpMethod.GET,
                    new HttpEntity<>(jsonHeaders(JwtTestTokens.stockViewerToken())), String.class);

            // What the retail controller then does with it is the reservation: the segment belongs to the
            // institutional surface, so it is refused as an unmapped path instead of being served as an account
            // named INSTITUTIONAL. Before it was reserved, a POST here created that account and the GET served
            // it - one path that was both a namespace and a retail resource. The payload is therefore the
            // ownerless UNSUPPORTED_PATH, which is what assertApiError asserts.
            assertApiError(response, HttpStatus.NOT_FOUND, "UNSUPPORTED_PATH");
        }

        @Test
        void headOnTheRetailAccountIsAuthorizedExactlyAsGet() {
            // Spring MVC answers HEAD from the @GetMapping handler, so a HEAD here IS the retail read with its
            // body suppressed and has to be admitted and refused on exactly the roles GET is - the parity, not
            // either status alone, is the property, which is why both requests sit in one method. Void.class
            // because HttpURLConnection has no stream to offer for a bodiless response and asking for one would
            // fail these requests as an I/O error instead of reporting the status they answered with.
            String withoutAnyGroup = JwtTestTokens.tokenFor(JwtTestTokens.USER_UNPRIVILEGED);

            ResponseEntity<Void> refused = strictRest.exchange("/cash-account/{owner}", HttpMethod.HEAD,
                    new HttpEntity<>(jsonHeaders(withoutAnyGroup)), Void.class, READ_OWNER);

            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            // Seeded through this context so the request under test and its precondition are decided by the same
            // filter chain; a conflict is success for the same reason it is in seedAccount.
            ResponseEntity<String> seed = strictRest.exchange("/cash-account/{owner}", HttpMethod.POST,
                    new HttpEntity<>(accountBody(READ_OWNER, SEED_BALANCE),
                            jsonHeaders(JwtTestTokens.stockTraderToken())),
                    String.class, READ_OWNER);
            assertThat(seed.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.CONFLICT);

            ResponseEntity<Void> admitted = strictRest.exchange("/cash-account/{owner}", HttpMethod.HEAD,
                    new HttpEntity<>(jsonHeaders(JwtTestTokens.stockViewerToken())), Void.class, READ_OWNER);

            assertThat(admitted.getStatusCode()).isEqualTo(HttpStatus.OK);

            MediaType contentType = admitted.getHeaders().getContentType();
            assertThat(contentType).isNotNull();
            assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        }

        @Test
        void securityHeadersReachAForbiddenResponse() throws JsonProcessingException {
            // A 403 is committed by error/ApiErrorAccessDeniedHandler inside the filter chain, one route
            // further along than the 401 the enclosing class covers, and strict mode is the only mode in which
            // the authorization rules can produce one at all.
            ResponseEntity<String> refused = strictRest.exchange("/cash-account/{owner}", HttpMethod.POST,
                    new HttpEntity<>(accountBody(STRICT_WRITE_OWNER, SEED_BALANCE),
                            jsonHeaders(JwtTestTokens.stockViewerToken())),
                    String.class, STRICT_WRITE_OWNER);

            assertApiError(refused, HttpStatus.FORBIDDEN, "FORBIDDEN");
            assertSecurityHeaders(refused.getHeaders());
        }
    }

    // A retail create issued through the JDK client rather than through TestRestTemplate, because a 401 answered to
    // a request that carries a body is unobservable through the latter: HttpURLConnection, which sits behind its
    // default request factory, streams the body and then throws HttpRetryException("cannot retry due to server
    // authentication, in streaming mode") rather than surfacing the challenge response - the same limitation
    // error/FailClosedIT records for its unauthenticated oversized-body case. The response is adapted to a
    // ResponseEntity so that one assertApiError judges every rejection in this class, whichever client issued it.
    private ResponseEntity<String> postAccount(String owner, String token) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/cash-account/" + owner))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.AUTHORIZATION, JwtTestTokens.bearer(token))
                .POST(HttpRequest.BodyPublishers.ofString(accountBody(owner, SEED_BALANCE)))
                .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            HttpHeaders headers = new HttpHeaders();
            response.headers().firstValue(HttpHeaders.CONTENT_TYPE)
                    .ifPresent(value -> headers.set(HttpHeaders.CONTENT_TYPE, value));
            return new ResponseEntity<>(response.body(), headers, HttpStatusCode.valueOf(response.statusCode()));
        }
    }

    private void seedAccount(String owner) {
        ResponseEntity<String> response = rest.exchange("/cash-account/{owner}", HttpMethod.POST,
                new HttpEntity<>(accountBody(owner, SEED_BALANCE), jsonHeaders(JwtTestTokens.stockTraderToken())),
                String.class, owner);

        // Setup, so a conflict is success: the container outlives this context and an owner created by an earlier
        // run in the same JVM is the state this test wants. Any other status is a broken precondition, surfaced
        // here rather than mis-read later as an authorization result.
        assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.CONFLICT);
    }

    private static boolean decoderRequiredFor(String authType) {
        return JwtDecoderConfig.decoderRequired(
                new MockEnvironment().withProperty(SecurityConfig.AUTH_TYPE_PROPERTY, authType));
    }

    // Calls the bean method directly rather than booting a context per URL: the value is read while the decoder is
    // built, so a direct call reaches exactly the code a starting pod reaches, and NimbusJwtDecoder resolves a JWKS
    // endpoint lazily - an accepted URL is therefore never fetched here.
    private static JwtDecoder oidcDecoderFor(String jwksUrl) {
        return new JwtDecoderConfig().jwtDecoder(properties("oidc", jwksUrl), RESOURCE_LOADER);
    }

    // Exactly what a failing context writes to the log, cause chain included, so the assertion reads the text an
    // operator would actually see rather than the one field the thrower chose.
    private static String rendered(Throwable thrown) {
        StringWriter text = new StringWriter();
        thrown.printStackTrace(new PrintWriter(text));
        return text.toString();
    }

    private static CashAccountProperties properties(String authType, String jwksUrl) {
        CashAccountProperties properties = new CashAccountProperties();
        properties.getSecurity().setAuthType(authType);
        properties.getSecurity().getJwt().setJwksUrl(jwksUrl);
        return properties;
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

    // Spring Security's own defaults, asserted rather than a set this test invents: nothing in
    // config/SecurityConfig enumerates, disables or replaces a header writer, so these five are exactly what the
    // framework writes and what the eager-write setting has to keep delivering. Header VALUES are matched by the
    // substring that carries the guarantee - "no-store" inside the full cache directive - so a framework upgrade
    // reordering the directive list is not read as a regression while a lost directive still is. RANDOM_PORT and
    // a real server are what make this provable at all: the headers are written by a servlet filter around a
    // response the container commits, and a MockMvc slice never commits one.
    private static void assertSecurityHeaders(HttpHeaders headers) {
        assertThat(headers.getFirst(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(headers.getFirst(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(headers.getFirst(HttpHeaders.EXPIRES)).isEqualTo("0");
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
    }

    // A rendering for failure text only: the near-miss group values differ from the estate's name by characters a
    // terminal does not show, so a raw %s would report "groups value 'StockTrader'" for five different inputs.
    private static String visible(String value) {
        StringBuilder rendered = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < ' ' || character == 0x7F) {
                rendered.append(String.format("\\u%04x", (int) character));
            } else {
                rendered.append(character);
            }
        }
        return "'" + rendered + "'";
    }

    private static JsonNode json(String body) throws JsonProcessingException {
        // readTree, never a bind to error/ApiError or retail/CashAccountResponse: ApiError carries an Instant,
        // which a plain ObjectMapper cannot read without the JavaTimeModule, and a tree keeps the assertions on
        // the wire text the service actually emitted.
        return JSON.readTree(body);
    }
}
