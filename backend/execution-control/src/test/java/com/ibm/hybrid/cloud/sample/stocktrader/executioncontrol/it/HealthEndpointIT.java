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
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.Response;

//JUnit 5 Jupiter
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


/** Verify the liveness, readiness and startup endpoints of the running server report UP */
class HealthEndpointIT {

    private static final String PORT = System.getProperty("liberty.test.port");

    //mpHealth is served by the Liberty runtime rather than by the WAR, so all three paths sit at
    //the server root: prefixing them with the /execution-control context root would miss them
    //against a perfectly healthy server, since the WAR serves no such path and web.xml's role
    //constraints - which never reach these endpoints - would answer such a request first.
    private static final String LIVE_URL = "http://localhost:" + PORT + "/health/live";
    private static final String READY_URL = "http://localhost:" + PORT + "/health/ready";
    private static final String STARTED_URL = "http://localhost:" + PORT + "/health/started";

    //mpHealth answers 503 while any check is still DOWN, which readiness and startup legitimately
    //are for a moment after the WAR is deployed and before the seed load finishes; a bounded retry
    //tells that transient state apart from a service that never becomes healthy.
    private static final int MAX_RETRY_COUNT = 5;
    private static final int SLEEP_TIMEOUT = 3000;

    //mpHealth-4.0 wraps the per-check results in a top-level document, so the overall verdict is
    //matched as a fragment instead of pinning the whole payload, its checks order or its data keys.
    private static final String UP_FRAGMENT = "\"status\":\"UP\"";

    @Test
    void testLiveEndpoint() throws Exception {
        System.out.println("Testing endpoint " + LIVE_URL);

        HealthResult result = makeRequest(LIVE_URL);
        for (int i = 0; (result.status != 200) && (i < MAX_RETRY_COUNT); i++) {
            System.out.println("Response code : " + result.status + ", retrying ... (" + i + " of " + MAX_RETRY_COUNT + ")");
            Thread.sleep(SLEEP_TIMEOUT);
            result = makeRequest(LIVE_URL);
        }

        Assertions.assertEquals(200, result.status, "Unexpected status from " + LIVE_URL + ", body: " + result.body);
        Assertions.assertTrue(result.body.contains(UP_FRAGMENT), "Body from " + LIVE_URL + " did not report UP: " + result.body);
    }

    @Test
    void testReadyEndpoint() throws Exception {
        System.out.println("Testing endpoint " + READY_URL);

        HealthResult result = makeRequest(READY_URL);
        for (int i = 0; (result.status != 200) && (i < MAX_RETRY_COUNT); i++) {
            System.out.println("Response code : " + result.status + ", retrying ... (" + i + " of " + MAX_RETRY_COUNT + ")");
            Thread.sleep(SLEEP_TIMEOUT);
            result = makeRequest(READY_URL);
        }

        Assertions.assertEquals(200, result.status, "Unexpected status from " + READY_URL + ", body: " + result.body);
        Assertions.assertTrue(result.body.contains(UP_FRAGMENT), "Body from " + READY_URL + " did not report UP: " + result.body);
    }

    @Test
    void testStartedEndpoint() throws Exception {
        System.out.println("Testing endpoint " + STARTED_URL);

        HealthResult result = makeRequest(STARTED_URL);
        for (int i = 0; (result.status != 200) && (i < MAX_RETRY_COUNT); i++) {
            System.out.println("Response code : " + result.status + ", retrying ... (" + i + " of " + MAX_RETRY_COUNT + ")");
            Thread.sleep(SLEEP_TIMEOUT);
            result = makeRequest(STARTED_URL);
        }

        Assertions.assertEquals(200, result.status, "Unexpected status from " + STARTED_URL + ", body: " + result.body);
        Assertions.assertTrue(result.body.contains(UP_FRAGMENT), "Body from " + STARTED_URL + " did not report UP: " + result.body);
    }

    //The Response-returning form of get() is used deliberately: it hands back 4xx and 5xx instead
    //of throwing, which is what lets the retry loop observe a status at all.
    private static HealthResult makeRequest(String urlToTest) {
        Client client = ClientBuilder.newClient();
        try {
            Invocation.Builder invoBuild = client.target(urlToTest).request();
            Response response = invoBuild.get();
            try {
                //close() releases the entity stream, so the body has to be read ahead of it.
                return new HealthResult(response.getStatus(), response.readEntity(String.class));
            } finally {
                response.close();
            }
        } finally {
            client.close();
        }
    }

    /** Both halves of one health answer: the status code and the document that explains it */
    private static final class HealthResult {

        private final int status;
        private final String body;

        private HealthResult(int status, String body) {
            this.status = status;
            //An absent entity is normalised so a body assertion reports the mismatch it found
            //rather than failing with a NullPointerException that names nothing.
            this.body = (body == null) ? "" : body;
        }
    }
}
