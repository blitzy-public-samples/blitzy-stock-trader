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
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;


/** Verify the order-submission flow of the running service over REST, including its error mappings */
class OrderSubmissionIT {

    private static final String PORT = System.getProperty("liberty.test.port");
    private static final String WAR_CONTEXT = System.getProperty("war.context");

    //Every application endpoint sits under the WAR's context root, while mpHealth is served by the
    //Liberty runtime at the server root - which is why the readiness gate below is the only URL
    //here built without the context root.
    private static final String APP_ROOT = "http://localhost:" + PORT + "/" + WAR_CONTEXT;
    private static final String ORDERS_URL = APP_ROOT + "/orders";
    private static final String POSITIONS_URL = APP_ROOT + "/positions";
    private static final String READY_URL = "http://localhost:" + PORT + "/health/ready";

    private static final int MAX_RETRY_COUNT = 5;
    private static final int SLEEP_TIMEOUT = 3000;

    //AUTH_TYPE=none activates includes/none.xml, whose basicRegistry puts "stock" in the
    //StockTrader group web.xml requires for POST. The header is assembled by hand rather than
    //through a client-specific authentication API so nothing here depends on which Jakarta REST
    //implementation happens to be on the test classpath.
    private static final String TRADER_AUTHORIZATION = "Basic " + Base64.getEncoder()
            .encodeToString("stock:trader".getBytes(StandardCharsets.UTF_8));
    private static final String AUTHORIZATION_HEADER = "Authorization";

    //INST-001's firm and counterparty settlement instructions agree, so its executions take the
    //clean post-trade path; the mismatching INST-003 belongs to SettlementExceptionIT.
    private static final String CLIENT_ID = "INST-001";
    private static final String SYMBOL = "SYNA";
    private static final String BUY = "BUY";
    private static final String LIMIT_PRICE = "100.00";

    private static final String MAX_ORDER_NOTIONAL = "MAX_ORDER_NOTIONAL";

    //The reason text and its two-decimal rendering are part of the service's contract, so the
    //breach is matched on the literal the control emits.
    private static final String MAX_ORDER_NOTIONAL_BREACH = "exceeds MAX_ORDER_NOTIONAL 1000000.00";

    private static final String ORIGIN_STATE = "(none)";
    private static final String STATE_MACHINE_ORDER = "ORDER";
    private static final String STATE_MACHINE_POST_TRADE = "POST_TRADE";


    /* Setup rather than a check: all four IT classes share one server, this class may be the first
       to reach it, and mpHealth legitimately answers 503 for a moment between the WAR being
       deployed and the seed load finishing. A bounded wait tells that transient state apart from a
       service that never becomes healthy, and failing here reports the server rather than the flow.

       Two conditions, not one, because they observe different things. mpHealth is served by the
       Liberty runtime, so it answers UP while the WAR itself is restarting - which is exactly what
       a rebuild deployed onto an already-running server does. Confirming that an application
       endpoint answers as well is what keeps a redeploy in progress from being reported as a
       failure of the order flow. */
    @BeforeAll
    static void awaitReadiness() throws Exception {
        awaitOk(READY_URL, "Service never became ready");
        awaitOk(POSITIONS_URL, "Application never began serving its context root");
    }

    private static void awaitOk(String url, String failure) throws Exception {
        RestResult result = get(url);
        for (int i = 0; (result.status != 200) && (i < MAX_RETRY_COUNT); i++) {
            System.out.println(url + " returned " + result.status + ", retrying ... (" + i + " of "
                    + MAX_RETRY_COUNT + ")");
            Thread.sleep(SLEEP_TIMEOUT);
            result = get(url);
        }

        if (result.status != 200) {
            throw new IllegalStateException(failure + "; last status from " + url + " was "
                    + result.status + ", body: " + result.body);
        }
    }

    @Test
    void testSubmitOrderUnderAllLimitsIsExecuted() {
        long quantityBefore = positionQuantity(readArray(get(POSITIONS_URL)), CLIENT_ID, SYMBOL);

        RestResult result = post(ORDERS_URL, orderBody(uniqueClientOrderId(), 100L, LIMIT_PRICE));

        Assertions.assertEquals(201, result.status,
                "Unexpected status submitting an in-limit order: " + result.body);

        JsonObject order = readObject(result);
        Assertions.assertEquals("EXECUTED", order.getString("status"),
                "An order inside every control limit must reach EXECUTED: " + result.body);
        Assertions.assertEquals("API", order.getString("source"),
                "An order submitted over REST must be sourced API: " + result.body);
        Assertions.assertTrue(order.getBoolean("simulated"),
                "Order was not labelled simulated: " + result.body);
        Assertions.assertTrue(order.containsKey("postTradeStatus"),
                "An executed order must carry a post-trade status: " + result.body);
        assertNonBlank(order, "orderId", "Order");
        assertNonBlank(order, "submittedBy", "Order");
        assertNonBlank(order, "disclaimer", "Order");
        assertAmountEquals("10000.00", order, "notional", "Order");

        Assertions.assertTrue(order.containsKey("execution"),
                "An executed order must carry its simulated fill: " + result.body);
        JsonObject execution = order.getJsonObject("execution");
        assertAmountEquals(LIMIT_PRICE, execution, "fillPrice", "Execution");
        Assertions.assertEquals(100L, execution.getJsonNumber("filledQuantity").longValue(),
                "The simulated fill did not cover the whole order quantity: " + result.body);
        Assertions.assertEquals("SIMULATED", execution.getString("venue"),
                "Execution venue must name the simulation: " + result.body);
        Assertions.assertTrue(execution.getBoolean("simulated"),
                "Execution was not labelled simulated: " + result.body);
        assertNonBlank(execution, "executionId", "Execution");
        assertNonBlank(execution, "executedAt", "Execution");
        //The nested fill is labelled in its own right rather than inheriting the order's labels,
        //so a reader who quotes the execution alone still carries the disclaimer with it.
        assertNonBlank(execution, "disclaimer", "Execution");

        /* All four controls are recorded whatever the verdict, in the fixed order MAX_ORDER_NOTIONAL,
           MAX_POSITION_NOTIONAL, RESTRICTED_SYMBOL, FAT_FINGER and never short-circuited, so an
           accepted order carries four passes rather than an empty list. Every one of the four has
           room to spare here - notional 10,000 against 1,000,000 and 2,500,000, SYNA absent from the
           restricted list, and a suite whose fills against this position are bounded to a few
           hundred shares at 100.00, leaving it comfortably below MAX_POSITION_NOTIONAL 5,000,000 -
           so no sibling class's fills can flip a verdict this test pins. */
        JsonArray controlResults = order.getJsonArray("controlResults");
        Assertions.assertEquals(4, controlResults.size(),
                "Expected one result per pre-trade control: " + result.body);
        for (int i = 0; i < controlResults.size(); i++) {
            JsonObject controlResult = controlResults.getJsonObject(i);
            Assertions.assertTrue(controlResult.getBoolean("passed"),
                    "Control " + controlResult.getString("control") + " did not pass: " + result.body);
        }

        JsonArray positions = readArray(get(POSITIONS_URL));
        for (int i = 0; i < positions.size(); i++) {
            JsonObject position = positions.getJsonObject(i);
            Assertions.assertTrue(position.getBoolean("synthetic"),
                    "Reference data was not labelled synthetic: " + position);
            Assertions.assertTrue(position.getBoolean("simulated"),
                    "Reference data was not labelled simulated: " + position);
            assertNonBlank(position, "disclaimer", "Position");
        }

        /* A delta rather than an absolute quantity: the startup seed already moved this position,
           and the sibling IT classes fill it too on the shared server, so the only figure this test
           owns is the change its own order caused. */
        Assertions.assertEquals(quantityBefore + 100L,
                positionQuantity(positions, CLIENT_ID, SYMBOL),
                "The simulated fill did not move the " + CLIENT_ID + " " + SYMBOL
                        + " position by the order quantity");
    }

    @Test
    void testRejectedOrderIsRecordedWithReason() {
        //15,000 x 100.00 = 1,500,000, strictly above the configured 1,000,000 ceiling; equality
        //would have passed, so the quantity is chosen well clear of the boundary.
        RestResult result = post(ORDERS_URL, orderBody(uniqueClientOrderId(), 15000L, LIMIT_PRICE));

        /* 201 rather than a 4xx: the control verdict was recorded on an order that now exists, is
           retrievable at its own URL and carries its own audit timeline, so the submission itself
           succeeded even though the trade did not. Answering 4xx would blame the caller for a
           decision the mandate made and disown the auditable record it produced. */
        Assertions.assertEquals(201, result.status,
                "A control rejection must still create the order: " + result.body);

        JsonObject order = readObject(result);
        Assertions.assertEquals("REJECTED", order.getString("status"),
                "An order over MAX_ORDER_NOTIONAL must be REJECTED: " + result.body);
        Assertions.assertEquals("API", order.getString("source"),
                "An order submitted over REST must be sourced API: " + result.body);
        Assertions.assertTrue(order.getBoolean("simulated"),
                "Order was not labelled simulated: " + result.body);
        assertNonBlank(order, "rejectionReason", "Rejected order");
        Assertions.assertTrue(order.getString("rejectionReason").contains(MAX_ORDER_NOTIONAL_BREACH),
                "rejectionReason did not name the breached control: " + result.body);

        //JSON-B omits a null property, so an order that never executed reports no execution and no
        //post-trade state at all rather than null ones: both keys are absent.
        Assertions.assertFalse(order.containsKey("execution"),
                "A rejected order must record no simulated fill: " + result.body);
        Assertions.assertFalse(order.containsKey("postTradeStatus"),
                "A rejected order starts no post-trade state machine: " + result.body);

        JsonArray controlResults = order.getJsonArray("controlResults");
        Assertions.assertEquals(4, controlResults.size(),
                "Every control is recorded on a rejection too: " + result.body);

        JsonObject breached = controlResult(controlResults, MAX_ORDER_NOTIONAL);
        Assertions.assertNotNull(breached,
                "No " + MAX_ORDER_NOTIONAL + " result was recorded: " + result.body);
        Assertions.assertFalse(breached.getBoolean("passed"),
                MAX_ORDER_NOTIONAL + " should have failed: " + result.body);
        Assertions.assertTrue(breached.getString("reason").contains(MAX_ORDER_NOTIONAL_BREACH),
                MAX_ORDER_NOTIONAL + " reason did not carry the configured limit: " + result.body);
        //The other three verdicts are deliberately not pinned: MAX_POSITION_NOTIONAL is valued
        //against the position the shared server has accumulated, which no single test owns.
    }

    @Test
    void testDuplicateOrderSubmissionIsRejected() {
        //The one place a client order id is reused on purpose: the same body is sent twice, so the
        //second submission is a genuine duplicate rather than a different order.
        String clientOrderId = uniqueClientOrderId();
        String body = orderBody(clientOrderId, 100L, LIMIT_PRICE);

        RestResult first = post(ORDERS_URL, body);
        Assertions.assertEquals(201, first.status,
                "The first submission of " + clientOrderId + " should have been created: " + first.body);

        RestResult duplicate = post(ORDERS_URL, body);
        Assertions.assertEquals(409, duplicate.status,
                "A repeated clientOrderId must conflict: " + duplicate.body);

        JsonObject error = readObject(duplicate);
        Assertions.assertEquals(409, error.getInt("status"),
                "ErrorResponse status did not repeat the HTTP status: " + duplicate.body);
        Assertions.assertTrue(error.getString("message", "").contains(clientOrderId),
                "The conflict did not name the duplicated clientOrderId: " + duplicate.body);
    }

    @Test
    void testInvalidQuantityOrLimitPriceIsRejected() {
        //A fresh client order id per attempt: a key reused across these four would be claimed by
        //the first request that got far enough to reserve it, and the expected 400 would then be
        //masked by a 409 raised before validation was even reached.
        assertBadRequest(orderBody(uniqueClientOrderId(), 0L, LIMIT_PRICE), "quantity");
        assertBadRequest(orderBody(uniqueClientOrderId(), -5L, LIMIT_PRICE), "quantity");
        assertBadRequest(orderBody(uniqueClientOrderId(), 100L, "0"), "limitPrice");
        assertBadRequest(orderBody(uniqueClientOrderId(), 100L, "-1.00"), "limitPrice");
    }

    @Test
    void testSubCentLimitPriceIsRejected() {
        /* A sub-cent price passes a sign check and is then held in cents, so 0.001 would have been
           stored as 0.00: the order's notional falls to zero, every configured notional ceiling
           clears it and the simulated fill costs nothing. Over the wire it must answer 400 and name
           limitPrice, like any other unusable field in the body. */
        assertBadRequest(orderBody(uniqueClientOrderId(), 100L, "0.001"), "limitPrice");
        assertBadRequest(orderBody(uniqueClientOrderId(), 100L, "100.005"), "limitPrice");
    }

    @Test
    void testCompactExponentLimitPriceIsRejected() {
        /* One ordinary-sized JSON body whose limitPrice is 1E+1000000: the exponent lives in the
           number's scale, so the body stays small and the digits appear only when the price is
           converted to cents or rendered. Over the wire the service must answer 400 and name
           limitPrice, having refused the representation rather than allocated the number - a larger
           exponent in the same body would otherwise exhaust the server's heap. */
        assertBadRequest(orderBody(uniqueClientOrderId(), 100L, "1E+1000000"), "limitPrice");
        //The same shape with the exponent the other way round, which expands while being rendered
        //rather than while being converted.
        assertBadRequest(orderBody(uniqueClientOrderId(), 100L, "1E-1000000"), "limitPrice");
        //Representable, and one cent above the absolute ceiling that keeps a notional bounded for
        //every share count a long can hold.
        assertBadRequest(orderBody(uniqueClientOrderId(), 100L, "1000000000000.01"), "limitPrice");
    }

    @Test
    void testUnknownOrderIdReturnsNotFound() {
        //Identifiers are zero-padded to six digits, so this is a well-formed id that names nothing.
        //An unknown id asked for by path is a missing resource (404); an unknown clientId inside a
        //submitted body is a bad field (400) and never reaches this mapping.
        RestResult result = get(ORDERS_URL + "/ORD-999999");

        Assertions.assertEquals(404, result.status,
                "An unknown order id must answer 404: " + result.body);

        JsonObject error = readObject(result);
        Assertions.assertEquals(404, error.getInt("status"),
                "ErrorResponse status did not repeat the HTTP status: " + result.body);
        Assertions.assertTrue(error.getString("message", "").contains("ORD-999999"),
                "The 404 did not name the missing order id: " + result.body);
    }

    @Test
    void testOrderEventsArePresent() {
        RestResult submission = post(ORDERS_URL, orderBody(uniqueClientOrderId(), 100L, LIMIT_PRICE));
        Assertions.assertEquals(201, submission.status,
                "Unexpected status submitting an in-limit order: " + submission.body);

        String orderId = readObject(submission).getString("orderId");
        RestResult result = get(ORDERS_URL + "/" + orderId + "/events");

        Assertions.assertEquals(200, result.status,
                "Unexpected status reading the events of " + orderId + ": " + result.body);

        /* Both of the order's state machines are filed under the same entity, so one clean-path
           order carries five events: three on the ORDER machine and two on POST_TRADE, which
           INST-001 reaches because its firm and counterparty instructions agree. The (none) origin
           marker therefore appears once per machine rather than once per order. */
        String[][] expected = {
            {ORIGIN_STATE, "SUBMITTED", STATE_MACHINE_ORDER},
            {"SUBMITTED", "ACCEPTED", STATE_MACHINE_ORDER},
            {"ACCEPTED", "EXECUTED", STATE_MACHINE_ORDER},
            {ORIGIN_STATE, "PENDING_AFFIRMATION", STATE_MACHINE_POST_TRADE},
            {"PENDING_AFFIRMATION", "SETTLEMENT_READY", STATE_MACHINE_POST_TRADE}
        };

        JsonArray events = readArray(result);
        Assertions.assertEquals(expected.length, events.size(),
                "Unexpected number of audit events for " + orderId + ": " + result.body);

        long previousSequence = Long.MIN_VALUE;
        for (int i = 0; i < events.size(); i++) {
            JsonObject event = events.getJsonObject(i);
            String position = "Event " + i + " of " + orderId;

            Assertions.assertEquals(expected[i][0], event.getString("fromState"),
                    position + " left the wrong state: " + event);
            Assertions.assertEquals(expected[i][1], event.getString("toState"),
                    position + " entered the wrong state: " + event);
            Assertions.assertEquals(expected[i][2], event.getString("stateMachine"),
                    position + " was filed under the wrong state machine: " + event);
            Assertions.assertEquals(STATE_MACHINE_ORDER, event.getString("entityType"),
                    position + " was filed under the wrong entity type: " + event);
            Assertions.assertEquals(orderId, event.getString("entityId"),
                    position + " was filed against the wrong entity: " + event);
            Assertions.assertTrue(event.getBoolean("simulated"),
                    position + " was not labelled simulated: " + event);
            assertNonBlank(event, "timestamp", position);
            assertNonBlank(event, "disclaimer", position);
            assertNonBlank(event, "actor", position);
            //A substring match on the Basic user name, so a realm-qualified principal still passes.
            Assertions.assertTrue(event.getString("actor").contains("stock"),
                    position + " did not attribute the submitting trader: " + event);

            long sequence = event.getJsonNumber("sequence").longValue();
            Assertions.assertTrue(sequence > previousSequence,
                    position + " did not advance the timeline sequence: " + event);
            previousSequence = sequence;
        }
    }

    @Test
    void testMalformedRequestBodyIsRejected() {
        /* Raw body text, never orderBody(): the JSON-P builder cannot express truncated JSON, a
           payload that is not JSON at all, an empty entity, a string where a number belongs, a
           number JSON-P itself refuses to hold, or an array where the object belongs - and those are
           precisely the bodies that fail inside the deserializer, before the service's own
           validation is ever reached. They must answer 400 like any other unusable request, and the
           refusal must describe none of how the deserializer failed. */
        assertMalformedBodyRejected("{\"clientOrderId\":");
        assertMalformedBodyRejected("not json at all");
        assertMalformedBodyRejected("");
        //A fresh key in the two well-formed-JSON shapes below, for the reason the invalid-field
        //tests use one: a reused key that got as far as being reserved would answer 409 and mask
        //the 400 under test.
        assertMalformedBodyRejected("{\"clientOrderId\":\"" + uniqueClientOrderId() + "\",\"clientId\":\""
                + CLIENT_ID + "\",\"symbol\":\"" + SYMBOL + "\",\"side\":\"" + BUY
                + "\",\"quantity\":\"abc\",\"limitPrice\":" + LIMIT_PRICE + "}");
        assertMalformedBodyRejected("{\"clientOrderId\":\"" + uniqueClientOrderId() + "\",\"clientId\":\""
                + CLIENT_ID + "\",\"symbol\":\"" + SYMBOL + "\",\"side\":\"" + BUY
                + "\",\"quantity\":100,\"limitPrice\":1e99999999999999999}");
        assertMalformedBodyRejected("[1,2,3]");
    }

    //One POST, one set of assertions: the four invalid bodies differ only in the field they spoil,
    //and a shared assertion keeps that the only difference between them.
    private static void assertBadRequest(String jsonBody, String expectedField) {
        RestResult result = post(ORDERS_URL, jsonBody);

        Assertions.assertEquals(400, result.status,
                "An invalid " + expectedField + " must answer 400: " + result.body);

        JsonObject error = readObject(result);
        Assertions.assertEquals(400, error.getInt("status"),
                "ErrorResponse status did not repeat the HTTP status: " + result.body);
        Assertions.assertTrue(error.getString("message", "").contains(expectedField),
                "The 400 did not name the offending field " + expectedField + ": " + result.body);
    }

    /* Separate from assertBadRequest rather than a parameter of it: that helper asserts the refusal
       NAMES the offending field, while an unreadable body has no field to name - the deserializer
       failed before any field existed - so the message here is one fixed string, and the assertions
       below are the opposite ones. Every body is reported back in the failure text, since the six
       shapes fail for six different reasons inside the deserializer. */
    private static void assertMalformedBodyRejected(String rawBody) {
        String posted = "malformed body [" + rawBody + "]";
        RestResult result = post(ORDERS_URL, rawBody);

        Assertions.assertEquals(400, result.status, posted + " must answer 400: " + result.body);

        JsonObject error = readObject(result);
        Assertions.assertEquals(400, error.getInt("status"),
                "ErrorResponse status did not repeat the HTTP status for " + posted + ": " + result.body);
        Assertions.assertEquals("request body is not valid JSON", error.getString("message", ""),
                posted + " did not answer the fixed malformed-body message: " + result.body);
        assertNonBlank(error, "path", posted);

        /* The reason the mapping exists: the deserializer's own text names the property it could not
           fill, the Java type it could not fill it with and - for a body of the wrong JSON shape -
           this application's own request class, and a 500 carrying any of that hands a caller the
           service's internals (CWE-209). Asserted on the whole response body, not just the message,
           so a leak through any other field of the error would fail here too. */
        String[] internalTextMarkers = {"Internal error", "deserialize", "java.lang", "java.math",
                "com.ibm.hybrid", "Yasson"};
        for (String marker : internalTextMarkers) {
            Assertions.assertFalse(result.body.contains(marker),
                    posted + " leaked internal text (" + marker + "): " + result.body);
        }
    }

    /* A fresh key per submission: the four IT classes run against one long-lived server and Failsafe
       pins neither class nor method order, so a literal client order id would already be claimed by
       a sibling class or by an earlier run of this one and would answer 409 where 201 is expected. */
    private static String uniqueClientOrderId() {
        return "IT-ORD-" + UUID.randomUUID();
    }

    //Built through the JSON-P builder rather than concatenated text, so quantity and limitPrice are
    //JSON numbers by construction and no escaping mistake can reach the wire. The order carries no
    //source field: the service decides that, never the client.
    private static String orderBody(String clientOrderId, long quantity, String limitPrice) {
        return Json.createObjectBuilder()
                .add("clientOrderId", clientOrderId)
                .add("clientId", CLIENT_ID)
                .add("symbol", SYMBOL)
                .add("side", BUY)
                .add("quantity", quantity)
                .add("limitPrice", new BigDecimal(limitPrice))
                .build()
                .toString();
    }

    private static JsonObject readObject(RestResult result) {
        return Json.createReader(new StringReader(result.body)).readObject();
    }

    private static JsonArray readArray(RestResult result) {
        return Json.createReader(new StringReader(result.body)).readArray();
    }

    private static JsonObject controlResult(JsonArray controlResults, String control) {
        for (int i = 0; i < controlResults.size(); i++) {
            JsonObject controlResult = controlResults.getJsonObject(i);
            if (control.equals(controlResult.getString("control"))) {
                return controlResult;
            }
        }
        return null;
    }

    //Zero for a symbol the client holds nothing in, which is the same starting quantity the service
    //itself uses when a fill creates a position that did not exist.
    private static long positionQuantity(JsonArray positions, String clientId, String symbol) {
        for (int i = 0; i < positions.size(); i++) {
            JsonObject position = positions.getJsonObject(i);
            if (clientId.equals(position.getString("clientId"))
                    && symbol.equals(position.getString("symbol"))) {
                return position.getJsonNumber("quantity").longValue();
            }
        }
        return 0L;
    }

    //containsKey first: JSON-B omits null properties, and reading an absent key outright would
    //throw instead of reporting which label or timestamp the response failed to carry.
    private static void assertNonBlank(JsonObject json, String key, String context) {
        Assertions.assertTrue(json.containsKey(key) && !json.getString(key, "").trim().isEmpty(),
                context + " carried no " + key + ": " + json);
    }

    //compareTo, never equals: BigDecimal.equals is scale-sensitive, so a rendered 100.0 would fail
    //against an expected 100.00 even though the two are the same amount of money.
    private static void assertAmountEquals(String expected, JsonObject json, String key,
            String context) {
        Assertions.assertTrue(json.containsKey(key), context + " carried no " + key + ": " + json);
        Assertions.assertEquals(0,
                json.getJsonNumber(key).bigDecimalValue().compareTo(new BigDecimal(expected)),
                context + " " + key + " was not " + expected + ": " + json);
    }

    private static RestResult get(String url) {
        Client client = ClientBuilder.newClient();
        try {
            return capture(authorized(client, url).get());
        } finally {
            client.close();
        }
    }

    private static RestResult post(String url, String jsonBody) {
        Client client = ClientBuilder.newClient();
        try {
            return capture(authorized(client, url)
                    .post(Entity.entity(jsonBody, MediaType.APPLICATION_JSON)));
        } finally {
            client.close();
        }
    }

    private static Invocation.Builder authorized(Client client, String url) {
        return client.target(url).request(MediaType.APPLICATION_JSON)
                .header(AUTHORIZATION_HEADER, TRADER_AUTHORIZATION);
    }

    //The Response-returning forms of get() and post() are used throughout, deliberately: they hand
    //back 4xx and 5xx instead of throwing, which is what lets the status assertions on 400, 404 and
    //409 observe a status at all.
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
            //An absent entity is normalised so a body assertion reports the mismatch it found
            //rather than failing with a NullPointerException that names nothing.
            this.body = (body == null) ? "" : body;
        }
    }
}
