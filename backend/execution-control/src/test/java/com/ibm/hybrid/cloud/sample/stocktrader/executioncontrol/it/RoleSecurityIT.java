/*
       Copyright 2019-2021 IBM Corp, All Rights Reserved
       Copyright 2023-2024 Kyndryl, All Rights Reserved

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

package com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.it;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import jakarta.json.Json;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;


/** Verify the running container enforces the per-HTTP-method role constraints of the service */
class RoleSecurityIT {

    private static final String PORT = System.getProperty("liberty.test.port");
    private static final String WAR_CONTEXT = System.getProperty("war.context");

    private static final String ORDERS_URL =
            "http://localhost:" + PORT + "/" + WAR_CONTEXT + "/orders";

    /* mpHealth is served by the Liberty runtime at the server root, outside the WAR's context
       root, so web.xml's constraints never reach it. That is what lets the gate below wait
       without credentials, and equally why no role assertion in this class may be made against
       health: an endpoint nobody guards proves nothing about who is allowed through. */
    private static final String READY_URL = "http://localhost:" + PORT + "/health/ready";

    private static final int MAX_RETRY_COUNT = 5;
    private static final int SLEEP_TIMEOUT = 3000;

    /* Liberty serves its own "Context Root Not Found" page, with a 404, for every path under a
       context root whose application is not installed, so any other status - including the 401 an
       anonymous caller draws from a constrained resource - proves the application itself is
       answering. That is why the gate below sends no credentials: the predicate presupposes no
       role, so the gate can never stand in for one of the four assertions this class exists to
       make. */
    private static final int CONTEXT_ROOT_NOT_FOUND = 404;

    /* Several samples spread over a couple of seconds rather than one, because the application can
       be up at the instant it is first sampled and still be torn down a moment later: deploying a
       rebuilt WAR onto an already-running server restarts the application, and Liberty's
       application monitor can take up to a second to notice the new file. A single sample taken
       inside that gap would satisfy the gate and leave the checks themselves to meet the 404. */
    private static final int APP_ROOT_CONFIRMATIONS = 3;
    private static final int APP_ROOT_SETTLE_TIMEOUT = 1000;
    private static final int APP_ROOT_MAX_ATTEMPTS = 20;

    private static final String AUTHORIZATION_HEADER = "Authorization";

    /* Two identities, and deliberately only these two. Under AUTH_TYPE=none the basicRegistry of
       includes/none.xml puts "stock" in the StockTrader group, which is what makes the 201 below
       reachable, and "read" in StockViewer and in no other group. That viewer-only membership is
       what makes the 403 below meaningful: an identity carrying any StockTrader membership would
       be granted the POST, so only an account holding StockViewer alone can show a mutating verb
       refused for the role the caller resolves rather than for the credentials it presented. */
    private static final String TRADER_IDENTITY = "stock:trader";
    private static final String VIEWER_IDENTITY = "read:only";
    private static final String ANONYMOUS_IDENTITY = "anonymous (no Authorization header)";

    //Assembled by hand rather than through a client-specific authentication API, so nothing here
    //depends on which Jakarta REST implementation happens to be on the test classpath.
    private static final String TRADER_AUTHORIZATION = basicAuthorization(TRADER_IDENTITY);
    private static final String VIEWER_AUTHORIZATION = basicAuthorization(VIEWER_IDENTITY);

    /* One valid, comfortably in-limit order serves all three POSTs. INST-001's notional of 100.00
       clears MAX_ORDER_NOTIONAL (1,000,000.00) and FAT_FINGER_NOTIONAL_THRESHOLD (2,500,000.00)
       by orders of magnitude, and this class's single-share fill leaves the resulting SYNA
       position comfortably within the configured MAX_POSITION_NOTIONAL however many times the
       suite fills it. The body is kept valid for the 401 and 403 cases on purpose: the container
       refuses those before JAX-RS ever reads the entity, so a valid body guarantees a validation
       400 can never be mistaken for the security status under test. */
    private static final String CLIENT_ID = "INST-001";
    private static final String SYMBOL = "SYNA";
    private static final String BUY = "BUY";
    private static final String LIMIT_PRICE = "100.00";

    /* Read with a default, because the two ports reach the tests differently: Failsafe is
       configured to forward liberty.test.port and war.context and nothing else, so the HTTPS
       port arrives only as whatever -Dliberty.var.default.https.port the build was given and is
       absent from a plain `mvn verify`. 9443 is the defaultValue server.xml declares for the
       same variable, so the fallback and the listener cannot disagree. */
    private static final String HTTPS_PORT =
            System.getProperty("liberty.var.default.https.port", "9443");

    /* Liberty's own JWT builder endpoint, published because mpJwt-2.1 requires the jwt-1.0
       feature. It is TLS-only - over cleartext it answers 404 CWWKS6052E - so these two URLs are
       https and nothing else will exercise them. */
    private static final String TOKEN_URL =
            "https://localhost:" + HTTPS_PORT + "/jwt/ibm/api/defaultJWT/token";
    private static final String JWK_URL =
            "https://localhost:" + HTTPS_PORT + "/jwt/ibm/api/defaultJWT/jwk";
    private static final String CLEARTEXT_TOKEN_URL =
            "http://localhost:" + PORT + "/jwt/ibm/api/defaultJWT/token";

    private static final String COOKIE_HEADER = "Cookie";
    private static final String SET_COOKIE_HEADER = "Set-Cookie";
    private static final String SSO_COOKIE_NAME = "StockTraderSSO";

    /* The estate's issuer and audience, as server.xml defaults them and the build's
       liberty.env entries supply them. A token minted with the estate's signing key would carry
       these two values, which is what makes them the thing to search a token endpoint's answer
       for: a pod-local issuer would be a different defect, but an estate-signed token is the one
       that confers privilege on the sibling services. */
    private static final String ESTATE_ISSUER = "http://stock-trader.ibm.com";
    private static final String ESTATE_AUDIENCE = "stock-trader";

    //Every JOSE object begins with a base64url-encoded "{"…", which is always this prefix.
    private static final String JOSE_PREFIX = "eyJ";

    //Shortest run of base64url characters worth trying to decode as a JWT segment; a JOSE header
    //alone is longer than this, and a shorter run cannot hold either value searched for.
    private static final int MIN_DECODABLE_RUN = 16;


    /* Setup rather than a check: all the IT classes share one long-lived server, this class may be
       the first to reach it, and mpHealth legitimately answers 503 for a moment between the WAR
       being deployed and the seed load finishing. A bounded wait tells that transient state apart
       from a service that never becomes healthy, and failing here reports the server rather than
       misattributing the delay to a role constraint.

       Two stages, because the two observations are different things. mpHealth is served by the
       Liberty runtime, so it answers UP while the WAR itself is stopped and restarting - which is
       exactly what deploying a rebuilt application onto an already-running server does. Waiting on
       health alone therefore proceeds into a window where every path under the context root
       answers 404, and a role constraint that was never reached gets reported as unenforced. The
       application root has to be observed as well, as the two application-level sibling IT classes
       already observe theirs. */
    @BeforeAll
    static void awaitReadiness() throws Exception {
        awaitRuntimeReadiness();
        awaitApplicationRoot();
    }

    private static void awaitRuntimeReadiness() throws Exception {
        RestResult result = get(READY_URL, null);
        for (int i = 0; (result.status != 200) && (i < MAX_RETRY_COUNT); i++) {
            System.out.println(READY_URL + " returned " + result.status + ", retrying ... ("
                    + i + " of " + MAX_RETRY_COUNT + ")");
            Thread.sleep(SLEEP_TIMEOUT);
            result = get(READY_URL, null);
        }

        if (result.status != 200) {
            throw new IllegalStateException("Service never became ready; last status from "
                    + READY_URL + " was " + result.status + ", body: " + result.body);
        }
    }

    //The URL polled is the very one the four checks use, so the gate confirms exactly what they
    //need: that this path is answered by the application and not by Liberty's
    //context-root-not-found page.
    private static void awaitApplicationRoot() throws Exception {
        RestResult result = get(ORDERS_URL, null);
        int confirmations = servingContextRoot(result) ? 1 : 0;

        for (int attempt = 0; (confirmations < APP_ROOT_CONFIRMATIONS)
                && (attempt < APP_ROOT_MAX_ATTEMPTS); attempt++) {
            //An unserved sample waits the full retry interval, a confirming one only the short
            //settle interval, so an application that is already up costs the gate two seconds.
            Thread.sleep((confirmations == 0) ? SLEEP_TIMEOUT : APP_ROOT_SETTLE_TIMEOUT);
            result = get(ORDERS_URL, null);
            //A 404 restarts the count: what matters is not that the application answered once but
            //that it kept answering across the window in which a redeploy would take it down.
            confirmations = servingContextRoot(result) ? (confirmations + 1) : 0;
            System.out.println(ORDERS_URL + " returned " + result.status + " (" + confirmations
                    + " of " + APP_ROOT_CONFIRMATIONS + " confirmations), attempt " + attempt
                    + " of " + APP_ROOT_MAX_ATTEMPTS);
        }

        if (confirmations < APP_ROOT_CONFIRMATIONS) {
            throw new IllegalStateException("Application never served its context root steadily;"
                    + " last status from " + ORDERS_URL + " was " + result.status + ", body: "
                    + result.body);
        }
    }

    private static boolean servingContextRoot(RestResult result) {
        return result.status != CONTEXT_ROOT_NOT_FOUND;
    }

    @Test
    void testUnauthenticatedPostIsRejected() {
        RestResult result = post(ORDERS_URL, null, orderBody());

        /* 401 rather than 403: no identity was presented at all, and includes/none.xml's
           webAppSecurity sets overrideHttpAuthMethod="BASIC", so a constrained resource answers
           an unauthenticated caller with a Basic challenge instead of a refusal. */
        Assertions.assertEquals(401, result.status, diagnostic("POST", ANONYMOUS_IDENTITY, 401,
                result));
    }

    @Test
    void testStockViewerCannotPostOrder() {
        RestResult result = post(ORDERS_URL, VIEWER_AUTHORIZATION, orderBody());

        /* 403 rather than 401, and the distinction is the whole point of this check: the
           credentials authenticate successfully, so the caller is known - it simply resolves
           StockViewer, which web.xml does not grant POST, PUT or DELETE on /*.

           This assertion only holds because server.xml omits the application binding that grants
           StockTrader to ALL_AUTHENTICATED_USERS: with that binding present every authenticated
           caller would resolve StockTrader and this POST would be created. A 201 here therefore
           means the binding was reintroduced, which is exactly the regression worth catching. */
        Assertions.assertEquals(403, result.status, diagnostic("POST", VIEWER_IDENTITY, 403,
                result));
    }

    @Test
    void testStockViewerCanGetOrders() {
        RestResult result = get(ORDERS_URL, VIEWER_AUTHORIZATION);

        //web.xml opens GET on every path to StockViewer as well as StockTrader, so read-only
        //access is a grant to prove and not merely the absence of a refusal.
        Assertions.assertEquals(200, result.status, diagnostic("GET", VIEWER_IDENTITY, 200,
                result));
    }

    @Test
    void testStockTraderCanPostOrder() {
        RestResult result = post(ORDERS_URL, TRADER_AUTHORIZATION, orderBody());

        //201 for either terminal state - the order exists and is retrievable whether the controls
        //accepted or rejected it - so the status alone proves the verb was permitted. What the
        //created order contains belongs to OrderSubmissionIT.
        Assertions.assertEquals(201, result.status, diagnostic("POST", TRADER_IDENTITY, 201,
                result));
    }

    /* This service mints no tokens, and the token endpoint of the runtime's own JWT web app is
       where that is proved. It cannot be unpublished - jwt-1.0 arrives with the mpJwt-2.1 the
       mandated microProfile-7.1 umbrella requires, and its bundles expose no setting that
       withdraws the endpoint - and deleting the builder element does not help either, because
       Liberty auto-provides a defaultJWT builder signing with the same estate key. server.xml
       claims the id and pins it to an alias no keystore holds, so the endpoint has nothing to
       sign with and returns no token at all.

       Asserted for a trading identity and a read-only one, because the original defect granted
       an estate-signed token to every authenticated registry identity regardless of role - a
       viewer could mint a credential the sibling services, which do bind StockTrader to
       ALL_AUTHENTICATED_USERS, would honour as a write privilege. The status is reported rather
       than asserted: what matters is that no token comes back, whether the endpoint answers,
       refuses or challenges. */
    @Test
    void testTokenEndpointMintsNoEstateSignedToken() throws Exception {
        for (String identity : new String[] { TRADER_IDENTITY, VIEWER_IDENTITY }) {
            InspectedResponse response =
                    inspectGet(TOKEN_URL, basicAuthorization(identity), null);
            System.out.println("GET " + TOKEN_URL + " as " + identity + " answered "
                    + response.status + " with " + response.body.length() + " bytes");

            assertNoEstateSignedToken(response.body, TOKEN_URL + " as " + identity
                    + " answered " + response.status);
        }

        //The same endpoint on the cleartext listener, for the same assertion: the runtime refuses
        //to mint over an unencrypted connection, and this records what it answers there.
        InspectedResponse cleartext =
                inspectGet(CLEARTEXT_TOKEN_URL, TRADER_AUTHORIZATION, null);
        System.out.println("GET " + CLEARTEXT_TOKEN_URL + " as " + TRADER_IDENTITY + " answered "
                + cleartext.status + " with body [" + cleartext.body + "]");

        assertNoEstateSignedToken(cleartext.body, CLEARTEXT_TOKEN_URL + " as " + TRADER_IDENTITY
                + " answered " + cleartext.status);
    }

    /* The companion of the check above: the JWK endpoint published the estate signing key's
       public half and its key id, which told an anonymous-adjacent caller which key the estate's
       tokens are signed with and confirmed this service could sign with it. With the builder
       pinned to an absent alias there is no key to publish, so no key id appears. */
    @Test
    void testJwkEndpointPublishesNoKey() throws Exception {
        InspectedResponse response =
                inspectGet(JWK_URL, basicAuthorization(TRADER_IDENTITY), null);
        System.out.println("GET " + JWK_URL + " answered " + response.status + " with "
                + response.body.length() + " bytes");

        Assertions.assertFalse(response.body.contains("\"kid\""), JWK_URL + " answered "
                + response.status + " and published a key id: " + response.body);
        assertNoEstateSignedToken(response.body, JWK_URL + " answered " + response.status);
    }

    /* No SSO cookie is issued at all, which is what makes the audited actor the identity the
       request presented. The cookie the copied includes name StockTraderSSO was a complete
       credential on its own and was evaluated ahead of the Authorization header, so a caller
       holding one identity's cookie while presenting another's credential was recorded as the
       cookie's identity. server.xml disables single sign-on for that reason; this check reads
       every Set-Cookie of a successful authenticated application response and requires that none
       of them names it. */
    @Test
    void testAuthenticatedResponseIssuesNoSsoCookie() throws Exception {
        InspectedResponse response = inspectGet(ORDERS_URL, TRADER_AUTHORIZATION, null);
        System.out.println("GET " + ORDERS_URL + " as " + TRADER_IDENTITY + " answered "
                + response.status + " with Set-Cookie " + response.setCookies);

        Assertions.assertEquals(200, response.status, "GET " + ORDERS_URL + " as "
                + TRADER_IDENTITY + " answered " + response.status + " rather than 200; body: "
                + response.body);
        for (String setCookie : response.setCookies) {
            Assertions.assertFalse(setCookie.contains(SSO_COOKIE_NAME), "GET " + ORDERS_URL
                    + " as " + TRADER_IDENTITY + " issued an SSO cookie: " + setCookie);
        }
    }

    /* And a cookie by that name is not a credential either way round. With single sign-on off
       the collaborator has no cookie to consume, so a request carrying one and no Authorization
       header is exactly as unauthenticated as one carrying nothing - on the read verb and on the
       mutating verb alike, the second being the one the defect turned into a created order. */
    @Test
    void testFabricatedSsoCookieIsNotACredential() throws Exception {
        String fabricated = SSO_COOKIE_NAME + "=" + UUID.randomUUID();

        InspectedResponse read = inspectGet(ORDERS_URL, null, fabricated);
        System.out.println("GET " + ORDERS_URL + " with only a fabricated " + SSO_COOKIE_NAME
                + " cookie answered " + read.status);

        Assertions.assertEquals(401, read.status, "GET " + ORDERS_URL + " with only " + fabricated
                + " answered " + read.status + " rather than 401; body: " + read.body);

        InspectedResponse write = inspectPost(ORDERS_URL, null, fabricated, orderBody());
        System.out.println("POST " + ORDERS_URL + " with only a fabricated " + SSO_COOKIE_NAME
                + " cookie answered " + write.status);

        Assertions.assertEquals(401, write.status, "POST " + ORDERS_URL + " with only "
                + fabricated + " answered " + write.status + " rather than 401; body: "
                + write.body);
    }

    /* Two assertions in one, because a token can hide in a body two ways. The literal "eyJ"
       catches any JOSE object whatever it claims, and decoding every base64url run long enough
       to hold one catches a segment whose claims name the estate issuer or audience even if it
       is wrapped in something this test did not anticipate. An empty body satisfies both, which
       is what the pinned builder produces. */
    private static void assertNoEstateSignedToken(String body, String where) {
        Assertions.assertFalse(body.contains(JOSE_PREFIX), where
                + " and carried something shaped like a JSON Web Token: " + body);

        for (String run : body.split("[^A-Za-z0-9_-]+")) {
            if (run.length() < MIN_DECODABLE_RUN) {
                continue;
            }
            String decoded = decodeBase64Url(run);
            Assertions.assertFalse(decoded.contains(ESTATE_ISSUER), where
                    + " and carried the estate issuer in a base64url segment: " + body);
            Assertions.assertFalse(decoded.contains(ESTATE_AUDIENCE), where
                    + " and carried the estate audience in a base64url segment: " + body);
        }
    }

    //A run that is not valid base64url decodes to nothing rather than failing the test: the
    //assertion is about what a decodable segment contains, and an undecodable run holds no claim.
    private static String decodeBase64Url(String run) {
        try {
            return new String(Base64.getUrlDecoder().decode(padded(run)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException undecodable) {
            return "";
        }
    }

    //JWT segments are unpadded base64url; the decoder requires the padding, so it is restored.
    private static String padded(String run) {
        int remainder = run.length() % 4;
        return (remainder == 0) ? run : run + "=".repeat(4 - remainder);
    }

    /* Test-scope transport trust, and deliberately nothing more. The TLS listener presents the
       estate's shared self-signed demonstration certificate, which no trust store on this host
       validates and which carries no subject alternative name, so a client that verified either
       would refuse to connect and the two TLS-only checks above could not run at all. This
       trust-all context and permissive verifier therefore exist to reach the endpoint under test;
       they assert nothing about the peer, they are confined to this test source, and nothing in
       the service's own configuration is relaxed by them. */
    private static Client secureClient() throws Exception {
        SSLContext trustAll = SSLContext.getInstance("TLS");
        trustAll.init(null, new TrustManager[] { new TrustEveryCertificate() }, null);
        return ClientBuilder.newBuilder()
                .sslContext(trustAll)
                .hostnameVerifier((hostname, session) -> true)
                .build();
    }

    private static Client clientFor(String url) throws Exception {
        return url.startsWith("https://") ? secureClient() : ClientBuilder.newClient();
    }

    private static final class TrustEveryCertificate implements X509TrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            //Nothing to check: this manager exists so the handshake with the module's own
            //self-signed test listener completes, and it makes no trust decision.
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            //Nothing to check, for the same reason.
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    private static InspectedResponse inspectGet(String url, String authorization, String cookie)
            throws Exception {
        Client client = clientFor(url);
        try {
            return inspect(inspectable(client, url, authorization, cookie).get());
        } finally {
            client.close();
        }
    }

    private static InspectedResponse inspectPost(String url, String authorization, String cookie,
            String jsonBody) throws Exception {
        Client client = clientFor(url);
        try {
            return inspect(inspectable(client, url, authorization, cookie)
                    .post(Entity.entity(jsonBody, MediaType.APPLICATION_JSON)));
        } finally {
            client.close();
        }
    }

    //Kept separate from request(...) above rather than folded into it, so the four checks added
    //here carry their own header handling and the four original checks keep theirs unchanged.
    private static Invocation.Builder inspectable(Client client, String url, String authorization,
            String cookie) {
        Invocation.Builder builder = client.target(url).request();
        if (authorization != null) {
            builder = builder.header(AUTHORIZATION_HEADER, authorization);
        }
        if (cookie != null) {
            builder = builder.header(COOKIE_HEADER, cookie);
        }
        return builder;
    }

    private static InspectedResponse inspect(Response response) {
        try {
            List<String> setCookies = new ArrayList<>();
            for (Map.Entry<String, List<String>> header
                    : response.getStringHeaders().entrySet()) {
                //Header names are case-insensitive on the wire, so the comparison is too rather
                //than trusting the client's map to have normalized them.
                if (SET_COOKIE_HEADER.equalsIgnoreCase(header.getKey())) {
                    setCookies.addAll(header.getValue());
                }
            }
            //close() releases the entity stream, so the body has to be read ahead of it.
            return new InspectedResponse(response.getStatus(),
                    response.readEntity(String.class), setCookies);
        } finally {
            response.close();
        }
    }

    /** One response read three ways: its status, its body and every cookie it tried to set */
    private static final class InspectedResponse {

        private final int status;
        private final String body;
        private final List<String> setCookies;

        private InspectedResponse(int status, String body, List<String> setCookies) {
            this.status = status;
            //An absent entity is normalised for the same reason RestResult normalises one: a
            //challenge and an empty 200 both carry no body, and a body assertion should report
            //what it found rather than fail with a NullPointerException.
            this.body = (body == null) ? "" : body;
            this.setCookies = List.copyOf(setCookies);
        }
    }

    //The only diagnostic a CI failure leaves behind, so it names the verb, the URL, the identity
    //that made the call and the status actually observed.
    private static String diagnostic(String method, String identity, int expected,
            RestResult result) {
        return method + " " + ORDERS_URL + " as " + identity + " answered " + result.status
                + " rather than the expected " + expected + "; body: " + result.body;
    }

    private static String basicAuthorization(String credentials) {
        return "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /* A fresh key per submission: the IT classes run against one long-lived server and Failsafe
       pins neither class nor method order, so a literal client order id would already have been
       claimed by an earlier run or a sibling class and would answer 409 where 201 is expected. */
    private static String orderBody() {
        return Json.createObjectBuilder()
                .add("clientOrderId", "IT-ROLE-" + UUID.randomUUID())
                .add("clientId", CLIENT_ID)
                .add("symbol", SYMBOL)
                .add("side", BUY)
                .add("quantity", 1L)
                //Built through the JSON-P builder rather than concatenated text, so quantity and
                //limitPrice are JSON numbers by construction.
                .add("limitPrice", new BigDecimal(LIMIT_PRICE))
                .build()
                .toString();
    }

    private static RestResult get(String url, String authorization) {
        Client client = ClientBuilder.newClient();
        try {
            return capture(request(client, url, authorization).get());
        } finally {
            client.close();
        }
    }

    private static RestResult post(String url, String authorization, String jsonBody) {
        Client client = ClientBuilder.newClient();
        try {
            return capture(request(client, url, authorization)
                    .post(Entity.entity(jsonBody, MediaType.APPLICATION_JSON)));
        } finally {
            client.close();
        }
    }

    //A null authorization sends no header at all, which is what makes the unauthenticated case a
    //genuinely anonymous request rather than one carrying an empty or malformed credential.
    private static Invocation.Builder request(Client client, String url, String authorization) {
        Invocation.Builder builder = client.target(url).request(MediaType.APPLICATION_JSON);
        return (authorization == null) ? builder : builder.header(AUTHORIZATION_HEADER,
                authorization);
    }

    //The Response-returning forms of get() and post() are used deliberately: they hand back 4xx
    //and 5xx instead of throwing, which is what lets three of the four checks observe their
    //status at all.
    private static RestResult capture(Response response) {
        try {
            //close() releases the entity stream, so the body has to be read ahead of it.
            return new RestResult(response.getStatus(), response.readEntity(String.class));
        } finally {
            response.close();
        }
    }

    /** Both halves of one REST answer: the status code and the body that explains it */
    private static final class RestResult {

        private final int status;
        private final String body;

        private RestResult(int status, String body) {
            this.status = status;
            //An absent entity is normalised so a failure message reports the status it found
            //rather than printing a bare null - a 401 challenge carries no body at all.
            this.body = (body == null) ? "" : body;
        }
    }


    /* The expected policy and the check that measures it are kept together here rather than split
       between the constants at the top of the class and a method among the role checks, because
       they are one statement about one thing: the entries of the two <headers> blocks in
       server.xml. */
    private static final String FRAME_OPTIONS_HEADER = "X-Frame-Options";
    private static final String FRAME_OPTIONS_VALUE = "DENY";
    private static final String REFERRER_POLICY_HEADER = "Referrer-Policy";
    private static final String REFERRER_POLICY_VALUE = "no-referrer";
    private static final String CONTENT_SECURITY_POLICY_HEADER = "Content-Security-Policy";
    /* frame-ancestors, base-uri and form-action are named explicitly because default-src does not
       cover them, so a policy of 'none' without them would leave three ways in open. */
    private static final String CONTENT_SECURITY_POLICY_VALUE =
            "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
    /* Asserted absent rather than present: mpTelemetry puts the request's own trace and span id on
       every response the application produces, and server.xml removes it for the reason it removes
       $WSEP - it names the runtime, and it hands a caller a correlation id into the server's own
       traces, including a caller whose request was refused. */
    private static final String TRACE_HEADER = "io.openliberty.trace";

    /* The policy belongs to the listener rather than to a resource method, so it is proved on the
       three answers this class already draws from one URL: a granted read, a challenge, and a
       refusal. The refusal is the reason the policy exists at all - every response this service
       decides is JSON, but the container renders that 403 as an HTML document from the same
       origin, and an HTML document is what framing and referrer policies govern. The TLS listener
       carries the same entries plus HSTS; it is checked by the review command in the README
       instead of here, because these tests address the cleartext port the build publishes as
       liberty.test.port. */
    @Test
    void testEveryAnswerCarriesTheDocumentHeaderPolicy() {
        assertDocumentPolicy("GET " + ORDERS_URL + " as " + VIEWER_IDENTITY, 200,
                headersOfGet(ORDERS_URL, VIEWER_AUTHORIZATION));
        assertDocumentPolicy("GET " + ORDERS_URL + " as " + ANONYMOUS_IDENTITY, 401,
                headersOfGet(ORDERS_URL, null));
        assertDocumentPolicy("POST " + ORDERS_URL + " as " + VIEWER_IDENTITY, 403,
                headersOfPost(ORDERS_URL, VIEWER_AUTHORIZATION, orderBody()));
    }

    private static void assertDocumentPolicy(String call, int expectedStatus, Answer answer) {
        //The status is asserted first so a policy failure is never reported against an answer
        //that is not the one the check means to measure.
        Assertions.assertEquals(expectedStatus, answer.status, call + " answered " + answer.status
                + " rather than " + expectedStatus + ", so its headers are not the ones this check"
                + " measures");
        assertHeader(call, answer, FRAME_OPTIONS_HEADER, List.of(FRAME_OPTIONS_VALUE));
        assertHeader(call, answer, REFERRER_POLICY_HEADER, List.of(REFERRER_POLICY_VALUE));
        assertHeader(call, answer, CONTENT_SECURITY_POLICY_HEADER,
                List.of(CONTENT_SECURITY_POLICY_VALUE));
        assertHeader(call, answer, TRACE_HEADER, List.of());
    }

    /* Each header is asserted as its whole value list, which covers in one assertion the three
       ways the policy can be wrong - absent, carrying another value, and asserted twice. The
       third matters as much as the first: a browser given two Content-Security-Policy headers
       enforces the intersection of both and neither as written. The diagnostic names the headers
       that did arrive but not their values, because a granted read carries the caller's SSO
       cookie and a CI log is not the place for it. */
    private static void assertHeader(String call, Answer answer, String header,
            List<String> expected) {
        Assertions.assertEquals(expected, answer.values(header), call + " answered with " + header
                + " " + answer.values(header) + " rather than " + expected + "; the headers it did"
                + " carry were " + answer.headers.keySet());
    }

    private static Answer headersOfGet(String url, String authorization) {
        Client client = ClientBuilder.newClient();
        try {
            return captureHeaders(request(client, url, authorization).get());
        } finally {
            client.close();
        }
    }

    private static Answer headersOfPost(String url, String authorization, String jsonBody) {
        Client client = ClientBuilder.newClient();
        try {
            return captureHeaders(request(client, url, authorization)
                    .post(Entity.entity(jsonBody, MediaType.APPLICATION_JSON)));
        } finally {
            client.close();
        }
    }

    //The headers are copied out ahead of close(), for the same reason capture() reads the body
    //ahead of it.
    private static Answer captureHeaders(Response response) {
        try {
            return new Answer(response.getStatus(), response.getStringHeaders());
        } finally {
            response.close();
        }
    }

    /** The status and the headers of one REST answer, keyed for case-insensitive lookup */
    private static final class Answer {

        private final int status;
        private final Map<String, List<String>> headers;

        private Answer(int status, Map<String, List<String>> received) {
            this.status = status;
            Map<String, List<String>> copied = new LinkedHashMap<>();
            //Lower-cased on the way in because an HTTP field name is case-insensitive and which
            //case the runtime sends is no part of the policy under test.
            received.forEach((name, values) ->
                    copied.put(name.toLowerCase(Locale.ROOT), List.copyOf(values)));
            this.headers = Map.copyOf(copied);
        }

        //An absent header answers as an empty list, so a policy that is missing and one that is
        //wrong are asserted the same way.
        private List<String> values(String header) {
            return headers.getOrDefault(header.toLowerCase(Locale.ROOT), List.of());
        }
    }
}
