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

//Jakarta REST client 3.1
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

//JSON-P 2.1, for building request bodies and reading response bodies
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

//JUnit 5 Jupiter
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;


/** Verify the simulated SSI-mismatch exception workflow of the running service end to end over REST */
class SettlementExceptionIT {

    private static final String PORT = System.getProperty("liberty.test.port");
    private static final String WAR_CONTEXT = System.getProperty("war.context");

    //Every application endpoint sits under the WAR's context root, while mpHealth is served by the
    //Liberty runtime at the server root - which is why the readiness gate below is the only URL
    //here built without the context root.
    private static final String APP_ROOT = "http://localhost:" + PORT + "/" + WAR_CONTEXT;
    private static final String ORDERS_URL = APP_ROOT + "/orders";
    private static final String EXCEPTIONS_URL = APP_ROOT + "/exceptions";
    private static final String CONTROLS_URL = APP_ROOT + "/controls";
    private static final String CLIENTS_URL = APP_ROOT + "/clients";
    private static final String READY_URL = "http://localhost:" + PORT + "/health/ready";

    //A status filter that matches nothing answers with an empty 200 array rather than a 404, so
    //this URL is safe to poll as a readiness signal as well as to read exceptions from.
    private static final String OPEN_EXCEPTIONS_URL = EXCEPTIONS_URL + "?status=OPEN";

    private static final int MAX_RETRY_COUNT = 5;
    private static final int SLEEP_TIMEOUT = 3000;

    //AUTH_TYPE=none activates includes/none.xml, whose basicRegistry puts "stock" in the
    //StockTrader group web.xml requires for every mutating verb - which is every assign, resolve
    //and settlement-ready call below. The header is assembled by hand rather than through a
    //client-specific authentication API so nothing here depends on which Jakarta REST
    //implementation happens to be on the test classpath.
    private static final String TRADER_AUTHORIZATION = "Basic " + Base64.getEncoder()
            .encodeToString("stock:trader".getBytes(StandardCharsets.UTF_8));
    private static final String AUTHORIZATION_HEADER = "Authorization";

    //INST-003 is the only seeded client whose firm and counterparty settlement instructions
    //disagree, so it is the sole source of a simulated SSI mismatch: every one of its executions
    //opens an exception, which is what lets each test here open its own.
    private static final String CLIENT_ID = "INST-003";
    private static final String CLIENT_NAME = "Fabrikam Capital Partners";
    private static final String SYMBOL = "SYNA";
    private static final String BUY = "BUY";
    private static final long QUANTITY = 300L;
    private static final String LIMIT_PRICE = "100.00";
    private static final String NOTIONAL = "30000.00";

    private static final String CLEAN_CLIENT_ONE = "INST-001";
    private static final String CLEAN_CLIENT_TWO = "INST-002";
    private static final int SEEDED_CLIENT_COUNT = 3;

    //The four comparable settlement-instruction fields, in the order the service compares them.
    private static final String CUSTODIAN_BIC = "custodianBic";
    private static final String SAFEKEEPING_ACCOUNT = "safekeepingAccount";
    private static final String CASH_ACCOUNT = "cashAccount";
    private static final String PLACE_OF_SETTLEMENT = "placeOfSettlement";

    private static final String FIRM_INSTRUCTION = "firmSettlementInstruction";
    private static final String COUNTERPARTY_INSTRUCTION = "counterpartySettlementInstruction";

    private static final String OWNER = "ops.analyst";
    private static final String RESOLUTION_NOTE =
        "Counterparty safekeeping account corrected (simulated)";

    //The one exception id safe to name literally: it is written by the startup seed, no test may
    //work it, and it is the evidence that seeded activity stays distinguishable from live activity.
    private static final String SEEDED_EXCEPTION_ID = "EXC-000001";

    //Well-formed - identifiers are zero-padded to six digits - but names nothing.
    private static final String UNKNOWN_EXCEPTION_ID = "EXC-999999";

    private static final long EXCEPTION_SLA_HOURS = 24L;
    private static final String RESTRICTED_SYMBOL_ONE = "RSTRA";
    private static final String RESTRICTED_SYMBOL_TWO = "RSTRB";

    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_ASSIGNED = "ASSIGNED";
    private static final String STATUS_RESOLVED = "RESOLVED";
    private static final String STATUS_SETTLEMENT_READY = "SETTLEMENT_READY";

    private static final String RESOLUTION_NOTE_REQUIRED = "resolutionNote is required";

    /* The arrow is written as an escape so this source file stays ASCII whatever encoding an editor
       saves it in, while the assertion still pins the U+2192 character LifecycleTransitions emits
       and StateConflictExceptionMapper relays verbatim. Should this ever fail, the fault is a
       charset in the pipeline rather than the message, and the fix belongs there. */
    private static final String DUPLICATE_RESOLVE_CONFLICT =
        "RESOLVED \u2192 RESOLVED is not a legal transition";


    /* Setup rather than a check: all four IT classes share one server, this class may be the first
       to reach it, and mpHealth legitimately answers 503 for a moment between the WAR being
       deployed and the seed load finishing. A bounded wait tells that transient state apart from a
       service that never becomes healthy, and failing here reports the server rather than the flow.

       Two conditions, not one, because they observe different things. mpHealth is served by the
       Liberty runtime, so it answers UP while the WAR itself is restarting - which is exactly what
       a rebuild deployed onto an already-running server does. Confirming that an application
       endpoint answers as well is what keeps a redeploy in progress from being reported as a
       failure of the exception workflow. */
    @BeforeAll
    static void awaitReadiness() throws Exception {
        awaitOk(READY_URL, "Service never became ready");
        awaitOk(OPEN_EXCEPTIONS_URL, "Application never began serving its context root");
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
    void testExecutedOrderOpensSsiMismatchException() {
        RestResult submission = post(ORDERS_URL, orderBody(uniqueClientOrderId()));

        Assertions.assertEquals(201, submission.status,
                "Unexpected status submitting an " + CLIENT_ID + " order: " + submission.body);

        JsonObject order = readObject(submission);
        Assertions.assertEquals("EXECUTED", order.getString("status"),
                "An order inside every control limit must reach EXECUTED: " + submission.body);
        Assertions.assertEquals("EXCEPTION", order.getString("postTradeStatus", ""),
                "An execution for the mismatching client must enter EXCEPTION: " + submission.body);

        String orderId = order.getString("orderId");
        String executionId = order.getJsonObject("execution").getString("executionId");

        RestResult listing = get(OPEN_EXCEPTIONS_URL);
        Assertions.assertEquals(200, listing.status,
                "Unexpected status listing open exceptions: " + listing.body);

        JsonArray openExceptions = readArray(listing);
        JsonObject exception = exceptionForOrder(openExceptions, orderId);
        Assertions.assertNotNull(exception,
                "No open exception was opened for " + orderId + ": " + listing.body);

        Assertions.assertEquals("SSI_MISMATCH", exception.getString("exceptionType"),
                "The exception did not record an SSI mismatch: " + exception);
        Assertions.assertEquals(executionId, exception.getString("executionId"),
                "The exception did not reference the execution that opened it: " + exception);
        Assertions.assertEquals(STATUS_OPEN, exception.getString("status"),
                "A newly opened exception must be OPEN: " + exception);
        Assertions.assertEquals(CLIENT_ID, exception.getString("clientId"),
                "The exception named the wrong client: " + exception);
        Assertions.assertEquals(CLIENT_NAME, exception.getString("clientName"),
                "The exception did not carry the client's name: " + exception);
        Assertions.assertEquals(SYMBOL, exception.getString("symbol"),
                "The exception named the wrong symbol: " + exception);
        Assertions.assertEquals(BUY, exception.getString("side"),
                "The exception named the wrong side: " + exception);
        Assertions.assertEquals(QUANTITY, exception.getJsonNumber("quantity").longValue(),
                "The exception did not carry the traded quantity: " + exception);
        Assertions.assertEquals("SIMULATED", exception.getString("venue"),
                "The exception's venue must name the simulation: " + exception);
        Assertions.assertEquals("API", exception.getString("source"),
                "An exception opened by a REST submission must be sourced API: " + exception);
        Assertions.assertTrue(exception.getBoolean("simulated"),
                "The exception was not labelled simulated: " + exception);
        assertNonBlank(exception, "exceptionId", "Exception");
        assertNonBlank(exception, "executedAt", "Exception");
        assertNonBlank(exception, "disclaimer", "Exception");
        assertAmountEquals(NOTIONAL, exception, "notional", "Exception");
        assertAmountEquals(LIMIT_PRICE, exception, "fillPrice", "Exception");

        /* Only the safekeeping account is seeded to differ, so the enriched break carries exactly
           the field an analyst has to correct rather than a whole instruction to compare by eye. */
        JsonArray mismatchFields = exception.getJsonArray("mismatchFields");
        Assertions.assertFalse(mismatchFields.isEmpty(),
                "An SSI mismatch must name the fields that differ: " + exception);

        JsonObject mismatch = mismatchFields.getJsonObject(0);
        Assertions.assertEquals(SAFEKEEPING_ACCOUNT, mismatch.getString("field"),
                "The mismatch named an unexpected field: " + mismatch);
        assertNonBlank(mismatch, "firmValue", "Mismatch");
        assertNonBlank(mismatch, "counterpartyValue", "Mismatch");
        Assertions.assertNotEquals(mismatch.getString("firmValue"),
                mismatch.getString("counterpartyValue"),
                "A recorded mismatch must carry two differing values: " + mismatch);

        assertNonBlank(exception, "openedAt", "Exception");
        assertNonBlank(exception, "slaDeadline", "Exception");
        //Ageing is derived on every read from the reference instant, which is "now" while the
        //exception is open: seconds after opening that rounds to a whole zero hours.
        Assertions.assertEquals(0L, exception.getJsonNumber("ageHours").longValue(),
                "A just-opened exception cannot have aged: " + exception);
        Assertions.assertFalse(exception.getBoolean("slaBreached"),
                "A just-opened exception cannot have breached its SLA: " + exception);

        //The deadline is fixed at opening time, so the window is the configured SLA exactly and
        //does not drift with the clock between opening and this read.
        Instant openedAt = Instant.parse(exception.getString("openedAt"));
        Instant slaDeadline = Instant.parse(exception.getString("slaDeadline"));
        Assertions.assertEquals(EXCEPTION_SLA_HOURS,
                Duration.between(openedAt, slaDeadline).toHours(),
                "slaDeadline was not openedAt plus EXCEPTION_SLA_HOURS: " + exception);

        //JSON-B omits a null property, so an unassigned exception reports no owner at all rather
        //than a null one: the key is absent.
        Assertions.assertFalse(exception.containsKey("owner"),
                "A newly opened exception must carry no owner: " + exception);

        /* The seeded exception is read here and never worked: it is the only evidence in the API
           that startup activity stays distinguishable from live activity, and assigning or
           resolving it in any test would make that fact depend on which test ran first. */
        JsonObject seeded = exceptionById(openExceptions, SEEDED_EXCEPTION_ID);
        Assertions.assertNotNull(seeded,
                "The seeded exception " + SEEDED_EXCEPTION_ID + " was not open: " + listing.body);
        Assertions.assertEquals(STATUS_OPEN, seeded.getString("status"),
                "The seeded exception must still be OPEN: " + seeded);
        Assertions.assertEquals("SEED", seeded.getString("source"),
                "The seeded exception must be sourced SEED: " + seeded);
        Assertions.assertTrue(seeded.getBoolean("simulated"),
                "The seeded exception was not labelled simulated: " + seeded);
        assertNonBlank(seeded, "disclaimer", "Seeded exception");
    }

    @Test
    void testAssignResolveAndMarkSettlementReady() {
        JsonObject opened = openOwnException();
        String exceptionId = opened.getString("exceptionId");
        String orderId = opened.getString("orderId");
        String exceptionUrl = EXCEPTIONS_URL + "/" + exceptionId;

        RestResult assigned = put(exceptionUrl + "/assign", assignBody(OWNER));
        Assertions.assertEquals(200, assigned.status,
                "Unexpected status assigning " + exceptionId + ": " + assigned.body);

        JsonObject afterAssign = readObject(assigned);
        Assertions.assertEquals(STATUS_ASSIGNED, afterAssign.getString("status"),
                "An assigned exception must report ASSIGNED: " + assigned.body);
        Assertions.assertEquals(OWNER, afterAssign.getString("owner"),
                "The assignment did not record the owner: " + assigned.body);
        assertNonBlank(afterAssign, "assignedAt", "Assigned exception");
        //The two later timestamps are still absent rather than null, so the workflow's progress is
        //readable from which keys the entity carries.
        Assertions.assertFalse(afterAssign.containsKey("resolvedAt"),
                "An assigned exception is not yet resolved: " + assigned.body);
        Assertions.assertFalse(afterAssign.containsKey("settlementReadyAt"),
                "An assigned exception is not yet settlement-ready: " + assigned.body);

        RestResult resolved = put(exceptionUrl + "/resolve", resolveBody(RESOLUTION_NOTE));
        Assertions.assertEquals(200, resolved.status,
                "Unexpected status resolving " + exceptionId + ": " + resolved.body);

        JsonObject afterResolve = readObject(resolved);
        Assertions.assertEquals(STATUS_RESOLVED, afterResolve.getString("status"),
                "A resolved exception must report RESOLVED: " + resolved.body);
        Assertions.assertEquals(RESOLUTION_NOTE, afterResolve.getString("resolutionNote", ""),
                "The resolution did not record the note it was given: " + resolved.body);
        Assertions.assertEquals(OWNER, afterResolve.getString("owner", ""),
                "Resolving must not disturb the recorded owner: " + resolved.body);
        assertNonBlank(afterResolve, "assignedAt", "Resolved exception");
        assertNonBlank(afterResolve, "resolvedAt", "Resolved exception");
        Assertions.assertFalse(afterResolve.containsKey("settlementReadyAt"),
                "A resolved exception is not yet settlement-ready: " + resolved.body);

        //Sent with no request entity at all: the endpoint takes no body, and fabricating one would
        //test a contract the service does not offer.
        RestResult settled = putWithoutBody(exceptionUrl + "/settlement-ready");
        Assertions.assertEquals(200, settled.status,
                "Unexpected status marking " + exceptionId + " settlement-ready: " + settled.body);

        JsonObject afterSettlement = readObject(settled);
        Assertions.assertEquals(STATUS_SETTLEMENT_READY, afterSettlement.getString("status"),
                "A cleared exception must report SETTLEMENT_READY: " + settled.body);
        assertNonBlank(afterSettlement, "settlementReadyAt", "Settlement-ready exception");
        assertNonBlank(afterSettlement, "assignedAt", "Settlement-ready exception");
        assertNonBlank(afterSettlement, "resolvedAt", "Settlement-ready exception");

        //Clearing the exception is what releases the trade, so the parent order's post-trade state
        //machine moves with it: the two records can never disagree about whether the trade settles.
        RestResult parentOrder = get(ORDERS_URL + "/" + orderId);
        Assertions.assertEquals(200, parentOrder.status,
                "Unexpected status reading " + orderId + ": " + parentOrder.body);
        Assertions.assertEquals(STATUS_SETTLEMENT_READY,
                readObject(parentOrder).getString("postTradeStatus", ""),
                "The parent order did not follow its exception to SETTLEMENT_READY: "
                        + parentOrder.body);

        assertEffectiveControlsAreReported();
        assertSeededClientsAreReported();
    }

    @Test
    void testResolveWithoutNoteIsRejected() {
        String exceptionId = openOwnException().getString("exceptionId");

        /* Assigned first, and the owner repeated in the resolve body, deliberately: resolving
           validates both a non-blank note and an owner, and nothing pins which check reports
           first. Leaving the missing note as the only possible cause is what makes the exact
           message below the service's answer rather than a coincidence of validation order. */
        RestResult assigned = put(EXCEPTIONS_URL + "/" + exceptionId + "/assign", assignBody(OWNER));
        Assertions.assertEquals(200, assigned.status,
                "Unexpected status assigning " + exceptionId + ": " + assigned.body);

        String bodyWithoutNote = Json.createObjectBuilder().add("owner", OWNER).build().toString();
        RestResult result = put(EXCEPTIONS_URL + "/" + exceptionId + "/resolve", bodyWithoutNote);

        Assertions.assertEquals(400, result.status,
                "A resolution with no note must answer 400: " + result.body);

        JsonObject error = readObject(result);
        Assertions.assertEquals(400, error.getInt("status"),
                "ErrorResponse status did not repeat the HTTP status: " + result.body);
        Assertions.assertEquals(RESOLUTION_NOTE_REQUIRED, error.getString("message", ""),
                "The 400 did not name the missing resolution note: " + result.body);
    }

    @Test
    void testDuplicateResolveIsRejected() {
        String exceptionId = openOwnException().getString("exceptionId");
        String resolveUrl = EXCEPTIONS_URL + "/" + exceptionId + "/resolve";

        RestResult assigned = put(EXCEPTIONS_URL + "/" + exceptionId + "/assign", assignBody(OWNER));
        Assertions.assertEquals(200, assigned.status,
                "Unexpected status assigning " + exceptionId + ": " + assigned.body);

        String body = resolveBody(RESOLUTION_NOTE);
        RestResult first = put(resolveUrl, body);
        Assertions.assertEquals(200, first.status,
                "Unexpected status resolving " + exceptionId + ": " + first.body);
        Assertions.assertEquals(STATUS_RESOLVED, readObject(first).getString("status"),
                "The first resolution did not take effect: " + first.body);

        /* ASSIGNED -> ASSIGNED is the workflow's only self-edge, so a repeated resolution is a
           conflict rather than an idempotent no-op: it is refused before the stored exception is
           replaced and before any audit event is written, which is what keeps the timeline a
           record of what happened rather than of what was asked for. */
        RestResult duplicate = put(resolveUrl, body);
        Assertions.assertEquals(409, duplicate.status,
                "A repeated resolution must conflict: " + duplicate.body);

        JsonObject error = readObject(duplicate);
        Assertions.assertEquals(409, error.getInt("status"),
                "ErrorResponse status did not repeat the HTTP status: " + duplicate.body);
        Assertions.assertEquals(DUPLICATE_RESOLVE_CONFLICT, error.getString("message", ""),
                "The 409 did not name the refused transition: " + duplicate.body);
    }

    @Test
    void testUnknownExceptionIdReturnsNotFound() {
        RestResult result = put(EXCEPTIONS_URL + "/" + UNKNOWN_EXCEPTION_ID + "/assign",
                assignBody(OWNER));

        Assertions.assertEquals(404, result.status,
                "An unknown exception id must answer 404: " + result.body);

        JsonObject error = readObject(result);
        Assertions.assertEquals(404, error.getInt("status"),
                "ErrorResponse status did not repeat the HTTP status: " + result.body);
        Assertions.assertTrue(error.getString("message", "").contains(UNKNOWN_EXCEPTION_ID),
                "The 404 did not name the missing exception id: " + result.body);
    }

    /* The two reference-data reads live inside the workflow test rather than in @Test methods of
       their own: they are the configuration and the settlement instructions this workflow was
       driven by, and reading them back here proves the run above was governed by the documented
       defaults rather than by whatever a previous deployment left behind. */
    private static void assertEffectiveControlsAreReported() {
        RestResult result = get(CONTROLS_URL);
        Assertions.assertEquals(200, result.status,
                "Unexpected status reading the effective controls: " + result.body);

        JsonObject controls = readObject(result);
        //CONFIG rather than SEED or API: the effective limits are resolved from configuration once
        //per start-up and never stored, so they have no record origin to report.
        Assertions.assertEquals("CONFIG", controls.getString("source"),
                "The control limits must report their configuration origin: " + result.body);
        Assertions.assertTrue(controls.getBoolean("simulated"),
                "The control limits were not labelled simulated: " + result.body);
        assertNonBlank(controls, "disclaimer", "Control limits");
        assertAmountEquals("1000000.00", controls, "maxOrderNotional", "Control limits");
        assertAmountEquals("5000000.00", controls, "maxPositionNotional", "Control limits");
        assertAmountEquals("2500000.00", controls, "fatFingerNotionalThreshold", "Control limits");
        Assertions.assertEquals(EXCEPTION_SLA_HOURS,
                controls.getJsonNumber("exceptionSlaHours").longValue(),
                "The reported SLA window is not the configured one: " + result.body);

        //The configured order is preserved on the way out, so the list is asserted element by
        //element rather than as an unordered membership test.
        JsonArray restrictedSymbols = controls.getJsonArray("restrictedSymbols");
        Assertions.assertEquals(2, restrictedSymbols.size(),
                "Unexpected number of restricted symbols: " + result.body);
        Assertions.assertEquals(RESTRICTED_SYMBOL_ONE, restrictedSymbols.getString(0),
                "Unexpected first restricted symbol: " + result.body);
        Assertions.assertEquals(RESTRICTED_SYMBOL_TWO, restrictedSymbols.getString(1),
                "Unexpected second restricted symbol: " + result.body);
    }

    private static void assertSeededClientsAreReported() {
        RestResult result = get(CLIENTS_URL);
        Assertions.assertEquals(200, result.status,
                "Unexpected status reading the seeded clients: " + result.body);

        JsonArray clients = readArray(result);
        //An absolute count is safe here where it is not on the exception list: no endpoint creates
        //a client, so the three seeded accounts are all there will ever be.
        Assertions.assertEquals(SEEDED_CLIENT_COUNT, clients.size(),
                "Unexpected number of seeded clients: " + result.body);

        for (int i = 0; i < clients.size(); i++) {
            JsonObject client = clients.getJsonObject(i);
            Assertions.assertTrue(client.getBoolean("synthetic"),
                    "Reference data was not labelled synthetic: " + client);
            Assertions.assertTrue(client.getBoolean("simulated"),
                    "Reference data was not labelled simulated: " + client);
            assertNonBlank(client, "clientId", "Client");
            assertNonBlank(client, "clientName", "Client");
            assertNonBlank(client, "disclaimer", "Client");

            for (String instruction : new String[] {FIRM_INSTRUCTION, COUNTERPARTY_INSTRUCTION}) {
                Assertions.assertTrue(client.containsKey(instruction),
                        "Client carried no " + instruction + ": " + client);
                Assertions.assertTrue(client.getJsonObject(instruction).getBoolean("synthetic"),
                        instruction + " was not labelled synthetic: " + client);
            }
        }

        /* Direct corroboration of this class's premise, read from the same reference data the
           service compares: only INST-003's safekeeping account diverges, which is why only its
           executions open an exception and why the other two clients settle cleanly. */
        JsonObject mismatching = clientById(clients, CLIENT_ID);
        Assertions.assertNotNull(mismatching, CLIENT_ID + " was not seeded: " + result.body);
        JsonObject firm = mismatching.getJsonObject(FIRM_INSTRUCTION);
        JsonObject counterparty = mismatching.getJsonObject(COUNTERPARTY_INSTRUCTION);
        Assertions.assertNotEquals(firm.getString(SAFEKEEPING_ACCOUNT),
                counterparty.getString(SAFEKEEPING_ACCOUNT),
                CLIENT_ID + " must disagree on its safekeeping account: " + mismatching);

        String[] comparedFields =
            {CUSTODIAN_BIC, SAFEKEEPING_ACCOUNT, CASH_ACCOUNT, PLACE_OF_SETTLEMENT};

        for (String clientId : new String[] {CLEAN_CLIENT_ONE, CLEAN_CLIENT_TWO}) {
            JsonObject clean = clientById(clients, clientId);
            Assertions.assertNotNull(clean, clientId + " was not seeded: " + result.body);
            JsonObject cleanFirm = clean.getJsonObject(FIRM_INSTRUCTION);
            JsonObject cleanCounterparty = clean.getJsonObject(COUNTERPARTY_INSTRUCTION);

            for (String field : comparedFields) {
                Assertions.assertEquals(cleanFirm.getString(field),
                        cleanCounterparty.getString(field),
                        clientId + " must agree on " + field + ": " + clean);
            }
        }
    }

    /* Every method that needs an exception opens its own instead of sharing one: the four IT
       classes run against a single long-lived server and Failsafe pins neither class nor method
       order, so an exception worked by one method would be in an unpredictable state by the time
       another reached it. Opening one costs a single order, and each INST-003 execution opens
       exactly one, so the exception a method works is unambiguously the one it created. */
    private static JsonObject openOwnException() {
        RestResult submission = post(ORDERS_URL, orderBody(uniqueClientOrderId()));
        Assertions.assertEquals(201, submission.status,
                "Unexpected status submitting an " + CLIENT_ID + " order: " + submission.body);

        JsonObject order = readObject(submission);
        Assertions.assertEquals("EXCEPTION", order.getString("postTradeStatus", ""),
                "An execution for the mismatching client must enter EXCEPTION: " + submission.body);

        String orderId = order.getString("orderId");
        RestResult listing = get(OPEN_EXCEPTIONS_URL);
        Assertions.assertEquals(200, listing.status,
                "Unexpected status listing open exceptions: " + listing.body);

        JsonObject exception = exceptionForOrder(readArray(listing), orderId);
        Assertions.assertNotNull(exception,
                "No open exception was opened for " + orderId + ": " + listing.body);
        return exception;
    }

    /* A fresh key per submission: the four IT classes run against one long-lived server and Failsafe
       pins neither class nor method order, so a literal client order id would already be claimed by
       a sibling class or by an earlier run of this one and would answer 409 where 201 is expected. */
    private static String uniqueClientOrderId() {
        return "IT-EXC-" + UUID.randomUUID();
    }

    //Built through the JSON-P builder rather than concatenated text, so quantity and limitPrice are
    //JSON numbers by construction and no escaping mistake can reach the wire. The order carries no
    //source field: the service decides that, never the client.
    private static String orderBody(String clientOrderId) {
        return Json.createObjectBuilder()
                .add("clientOrderId", clientOrderId)
                .add("clientId", CLIENT_ID)
                .add("symbol", SYMBOL)
                .add("side", BUY)
                .add("quantity", QUANTITY)
                .add("limitPrice", new BigDecimal(LIMIT_PRICE))
                .build()
                .toString();
    }

    private static String assignBody(String owner) {
        return Json.createObjectBuilder().add("owner", owner).build().toString();
    }

    //No owner: the exception is already assigned everywhere this is used, and an owner in the body
    //is ignored once it is - re-assignment has its own endpoint and its own audit event.
    private static String resolveBody(String resolutionNote) {
        return Json.createObjectBuilder().add("resolutionNote", resolutionNote).build().toString();
    }

    private static JsonObject readObject(RestResult result) {
        return Json.createReader(new StringReader(result.body)).readObject();
    }

    private static JsonArray readArray(RestResult result) {
        return Json.createReader(new StringReader(result.body)).readArray();
    }

    //Matched on the order this test submitted rather than on a position in the list: the list is
    //shared, grows as other methods and classes run, and is ordered by exception id.
    private static JsonObject exceptionForOrder(JsonArray exceptions, String orderId) {
        for (int i = 0; i < exceptions.size(); i++) {
            JsonObject exception = exceptions.getJsonObject(i);
            if (orderId.equals(exception.getString("orderId", ""))) {
                return exception;
            }
        }
        return null;
    }

    private static JsonObject exceptionById(JsonArray exceptions, String exceptionId) {
        for (int i = 0; i < exceptions.size(); i++) {
            JsonObject exception = exceptions.getJsonObject(i);
            if (exceptionId.equals(exception.getString("exceptionId", ""))) {
                return exception;
            }
        }
        return null;
    }

    private static JsonObject clientById(JsonArray clients, String clientId) {
        for (int i = 0; i < clients.size(); i++) {
            JsonObject client = clients.getJsonObject(i);
            if (clientId.equals(client.getString("clientId", ""))) {
                return client;
            }
        }
        return null;
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

    private static RestResult put(String url, String jsonBody) {
        Client client = ClientBuilder.newClient();
        try {
            return capture(authorized(client, url)
                    .put(Entity.entity(jsonBody, MediaType.APPLICATION_JSON)));
        } finally {
            client.close();
        }
    }

    //method("PUT") sends no request entity at all, which is the shape the settlement-ready
    //endpoint expects: it declares no body parameter, so an invented one would be discarded and
    //would misrepresent the contract a caller has to satisfy.
    private static RestResult putWithoutBody(String url) {
        Client client = ClientBuilder.newClient();
        try {
            return capture(authorized(client, url).method("PUT"));
        } finally {
            client.close();
        }
    }

    private static Invocation.Builder authorized(Client client, String url) {
        return client.target(url).request(MediaType.APPLICATION_JSON)
                .header(AUTHORIZATION_HEADER, TRADER_AUTHORIZATION);
    }

    //The Response-returning forms are used throughout, deliberately: they hand back 4xx and 5xx
    //instead of throwing, which is what lets the status assertions on 400, 404 and 409 observe a
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
            //An absent entity is normalised so a body assertion reports the mismatch it found
            //rather than failing with a NullPointerException that names nothing.
            this.body = (body == null) ? "" : body;
        }
    }
}
