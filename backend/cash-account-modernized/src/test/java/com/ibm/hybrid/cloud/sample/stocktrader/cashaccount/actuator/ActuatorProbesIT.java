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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.actuator;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.PostgresTestSupport;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

/*
 * useMainMethod = ALWAYS is load-bearing rather than stylistic. The actuator "startup" endpoint is
 * registered only while the CONTEXT's ApplicationStartup is a BufferingApplicationStartup, and this service
 * installs one inside CashAccountApplication.main(). A default @SpringBootTest builds its context from a
 * fresh SpringApplication carrying DefaultApplicationStartup and never calls main(), so
 * StartupEndpointAutoConfiguration's condition would not match and /actuator/startup would answer 404 -
 * leaving a green test over the one endpoint it exists to prove. ALWAYS bootstraps through main() itself,
 * so the startup assertion below is gated on the deployed wiring and not on a rig assembled for the test.
 *
 * Extending PostgresTestSupport is likewise functional: the readiness group is readinessState,db
 * (src/main/resources/application.yml), so its db indicator needs the reachable PostgreSQL that base class
 * publishes through @ServiceConnection, or readiness answers 503 instead of 200.
 */
/** Proves the three actuator paths the chart probes answer 200 at the root of this service's server port. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
class ActuatorProbesIT extends PostgresTestSupport {

    // The chart's three probe paths, verbatim and root-relative
    // [infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L204-L222], probed on the
    // same port 8080 that carries the retail surface the chart publishes to broker as
    // http://<release>-cash-account-service:8080/cash-account [.../values.yaml:L146]. Both can only hold while
    // the service declares no servlet context path, so asserting these at the root is what fails if one is
    // ever introduced - a change that would take out every probe and stop the pod from ever starting.
    private static final String STARTUP_PROBE_PATH = "/actuator/startup";
    private static final String READINESS_PROBE_PATH = "/actuator/health/readiness";
    private static final String LIVENESS_PROBE_PATH = "/actuator/health/liveness";

    @LocalServerPort
    private int port;

    // TestRestTemplate in preference to any throwing client: it surfaces a 404 or a 503 as an assertable
    // status, and a 404 on the startup path is exactly the regression this test is here to catch.
    @Autowired
    private TestRestTemplate probeClient;

    @Test
    void chartProbedActuatorPathsAnswer200AtRoot() {
        // Softly, because a context path or a lost permitAll breaks all three probes at once and an operator
        // reading the failure needs the full set, not whichever path happens to be asserted first.
        SoftAssertions.assertSoftly(probes -> {
            probes.assertThat(statusOf(STARTUP_PROBE_PATH)).as(STARTUP_PROBE_PATH).isEqualTo(HttpStatus.OK);
            probes.assertThat(statusOf(READINESS_PROBE_PATH)).as(READINESS_PROBE_PATH).isEqualTo(HttpStatus.OK);
            probes.assertThat(statusOf(LIVENESS_PROBE_PATH)).as(LIVENESS_PROBE_PATH).isEqualTo(HttpStatus.OK);
        });
    }

    /*
     * No credential is presented here and none may be added: the kubelet sends none, which is why
     * config/SecurityConfig's first matcher permits /actuator/** unconditionally. A token would keep this test
     * green after that matcher was removed, while every pod failed its startup probe.
     *
     * GET, never POST. The startup endpoint exposes both, but POST drains the buffered startup timeline, so
     * POSTing would diverge from the chart's httpGet and destroy the evidence the next read would return. The
     * response body is deliberately unexamined - the buffer's contents depend on how much of the bootstrap was
     * instrumented, and what the probes decide on is the status alone.
     */
    private HttpStatusCode statusOf(String probePath) {
        ResponseEntity<String> response =
                probeClient.getForEntity("http://localhost:" + port + probePath, String.class);
        return response.getStatusCode();
    }
}
