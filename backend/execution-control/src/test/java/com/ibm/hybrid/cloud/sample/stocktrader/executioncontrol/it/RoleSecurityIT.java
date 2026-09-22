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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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


    /* Setup rather than a check: all the IT classes share one long-lived server, this class may be
       the first to reach it, and mpHealth legitimately answers 503 for a moment between the WAR
       being deployed and the seed load finishing. A bounded wait tells that transient state apart
       from a service that never becomes healthy, and failing here reports the server rather than
       misattributing the delay to a role constraint. */
    @BeforeAll
    static void awaitReadiness() throws Exception {
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
}
