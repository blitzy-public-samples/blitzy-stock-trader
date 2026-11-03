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
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.Response;

import java.util.Locale;
import java.util.regex.Pattern;

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

    //The three probe paths the platform contract names, as one list, so the credential-shape
    //matrix below covers every one of them rather than a representative.
    private static final String[] PROBE_URLS = { LIVE_URL, READY_URL, STARTED_URL };

    /* A probe path is an unprotected resource, and Liberty's security collaborator would
       otherwise authenticate opportunistically whenever a request merely carries authentication
       data - which in a registry-less MP-JWT deployment dereferenced a null UserRegistry and
       answered 500 with the exception text. server.xml's
       <webAppSecurity useAuthenticationDataForUnprotectedResource="false"/> is what makes each
       shape below indifferent. The four shapes are the ones an ingress, a service mesh or a
       monitoring agent actually produces: none at all, a bearer token that is syntactically a
       JWT and cryptographically nothing, a valid HTTP Basic credential of this service's
       development registry ("stock:trader"), and an authentication scheme the server has never
       heard of. */
    private static final String[] CREDENTIAL_SHAPES = {
            null,
            "Bearer garbage.garbage.garbage",
            "Basic c3RvY2s6dHJhZGVy",
            "Junk xyz"
    };

    private static final String[] CREDENTIAL_SHAPE_NAMES = {
            "no Authorization header",
            "Authorization: Bearer garbage.garbage.garbage",
            "Authorization: Basic c3RvY2s6dHJhZGVy (stock:trader)",
            "Authorization: Junk xyz"
    };

    /* Two paths that belong to no web app: a near-miss of a probe path, which matters because
       matching is case-sensitive, and the server root. Both were answered by a Liberty page
       naming the product and its release until server.xml disabled the welcome page and supplied
       its own missing-context-root text. */
    private static final String MISCASED_READY_URL = "http://localhost:" + PORT + "/HEALTH/ready";
    private static final String SERVER_ROOT_URL = "http://localhost:" + PORT + "/";

    private static final String AUTHORIZATION_HEADER = "Authorization";

    /* What a 404 from those two paths must not contain. The product name and "wlp" cover the
       welcome and context-root-not-found pages, the dotted-number pattern covers any release
       string, and the remaining fragments cover an exception class or a runtime message id
       reaching the body. Matching is case-insensitive, so a differently-cased rendering of the
       same disclosure is caught too. */
    private static final String[] FORBIDDEN_BODY_FRAGMENTS = {
            "liberty", "wlp", "websphere", "exception", "throwable", "java.", "jakarta.", "srve",
            "cwwk", "\tat "
    };
    //Compiled with find() rather than matches() so a multi-line body is screened as thoroughly as
    //a single-line one.
    private static final Pattern VERSION_PATTERN = Pattern.compile("\\d+\\.\\d+\\.\\d+");

    //mpHealth answers 503 while any check is still DOWN, which readiness and startup legitimately
    //are for a moment after the WAR is deployed and before the seed load finishes; a bounded retry
    //tells that transient state apart from a service that never becomes healthy.
    private static final int MAX_RETRY_COUNT = 5;
    private static final int SLEEP_TIMEOUT = 3000;

    //mpHealth-4.0 wraps the per-check results in a top-level document, so the overall verdict is
    //matched as a fragment instead of pinning the whole payload, its checks order or its data keys.
    private static final String UP_FRAGMENT = "\"status\":\"UP\"";

    @Test
    void testLiveEndpoint() {
        System.out.println("Testing endpoint " + LIVE_URL);

        //Liveness is independent of the seed load and of the reference-data counts, so it is asserted
        //on the first response with no retry: a window here would hide a liveness check that only
        //turns UP once data exists, which is exactly the restart-on-empty-data defect this guards.
        HealthResult result = makeRequest(LIVE_URL);

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

    /* The probe contract has to hold for a caller that presents a credential as well as for the
       credential-free kubelet, because an ingress or a service mesh that forwards the caller's
       Authorization header forwards it to the probe paths too, and every 500 there is a failed
       liveness or readiness check.

       Honest about what this proves: the server these ITs run against is started with
       AUTH_TYPE=none (the pom's liberty.env.AUTH_TYPE, which every other IT in this module
       depends on), and that include configures a user registry. The defect this guards against
       was reachable in the registry-less MP-JWT modes - AUTH_TYPE=basic and ldap - where the
       basic-auth authenticator had no registry to read a realm from. These twelve cells are
       therefore a regression guard on the setting that fixes it, not a reproduction of the
       original failure: they fail if server.xml's webAppSecurity element is removed and the
       collaborator starts authenticating unprotected resources again, and they fail for any
       other reason a credential could degrade a probe. The MP-JWT-mode reproduction needs a
       server started in that mode, which this build does not start. */
    @Test
    void testProbesAnswerUpWhateverCredentialIsPresented() throws Exception {
        for (String probeUrl : PROBE_URLS) {
            for (int shape = 0; shape < CREDENTIAL_SHAPES.length; shape++) {
                String presented = CREDENTIAL_SHAPE_NAMES[shape];
                System.out.println("Testing endpoint " + probeUrl + " with " + presented);

                HealthResult result = makeRequestAwaitingUp(probeUrl, CREDENTIAL_SHAPES[shape]);

                Assertions.assertEquals(200, result.status, probeUrl + " with " + presented
                        + " answered " + result.status + " rather than 200; body: " + result.body);
                Assertions.assertTrue(result.body.contains(UP_FRAGMENT), probeUrl + " with "
                        + presented + " did not report UP: " + result.body);
            }
        }
    }

    /* A path no web app claims is answered by the HTTP dispatcher, ahead of every application,
       error page and servlet filter, so what it renders is a server setting rather than
       anything this module's code can intercept. Left at its defaults the server answered the
       root with its welcome page - naming the product and the exact release - and a miscased
       probe path with its "Context Root Not Found" page. Both must now be a bare 404 that
       identifies neither the product, nor its version, nor any internal type. */
    @Test
    void testPathsNoApplicationClaimsDiscloseNothing() {
        for (String url : new String[] { MISCASED_READY_URL, SERVER_ROOT_URL }) {
            System.out.println("Testing endpoint " + url + " for disclosure");

            HealthResult result = makeRequest(url, null);
            System.out.println(url + " answered " + result.status + " with body [" + result.body
                    + "]");

            Assertions.assertEquals(404, result.status, url + " answered " + result.status
                    + " rather than 404; body: " + result.body);

            String lowerCased = result.body.toLowerCase(Locale.ROOT);
            for (String forbidden : FORBIDDEN_BODY_FRAGMENTS) {
                Assertions.assertFalse(lowerCased.contains(forbidden), url
                        + " disclosed \"" + forbidden + "\" in its 404 body: " + result.body);
            }
            Assertions.assertFalse(VERSION_PATTERN.matcher(result.body).find(), url
                    + " disclosed something shaped like a version in its 404 body: "
                    + result.body);
        }
    }

    /* Retries only on 503, and deliberately not on every non-200 as the two probe checks above
       do: 503 is the legitimate transient state while a check is still DOWN, whereas the 500 a
       credential used to provoke is the defect itself and must fail the test on its first
       occurrence rather than be waited out. */
    private static HealthResult makeRequestAwaitingUp(String urlToTest, String authorization)
            throws Exception {
        HealthResult result = makeRequest(urlToTest, authorization);
        for (int i = 0; (result.status == 503) && (i < MAX_RETRY_COUNT); i++) {
            System.out.println("Response code : " + result.status + ", retrying ... (" + i
                    + " of " + MAX_RETRY_COUNT + ")");
            Thread.sleep(SLEEP_TIMEOUT);
            result = makeRequest(urlToTest, authorization);
        }
        return result;
    }

    //The Response-returning form of get() is used deliberately: it hands back 4xx and 5xx instead
    //of throwing, which is what lets the retry loop observe a status at all.
    private static HealthResult makeRequest(String urlToTest) {
        return makeRequest(urlToTest, null);
    }

    //A null authorization sends no header at all, which is what keeps the credential-free cell of
    //the matrix a genuinely anonymous request rather than one carrying an empty header.
    private static HealthResult makeRequest(String urlToTest, String authorization) {
        Client client = ClientBuilder.newClient();
        try {
            Invocation.Builder invoBuild = client.target(urlToTest).request();
            if (authorization != null) {
                invoBuild = invoBuild.header(AUTHORIZATION_HEADER, authorization);
            }
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
