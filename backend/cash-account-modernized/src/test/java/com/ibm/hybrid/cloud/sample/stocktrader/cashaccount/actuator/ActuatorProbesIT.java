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

/** Proves the three chart-probed actuator paths and both metrics scrape routes answer 200 at the server root. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // The startup endpoint is registered only while the CONTEXT's ApplicationStartup is a
        // BufferingApplicationStartup, which this service installs inside CashAccountApplication.main(). A
        // context built without main() answers 404 there, leaving a green test over the endpoint it exists for.
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
class ActuatorProbesIT extends PostgresTestSupport {

    // The chart's three probe paths, verbatim and root-relative
    // [infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L204-L222], on the same
    // port 8080 that carries the retail surface [.../values.yaml:L146]. Both hold only while the service
    // declares no servlet context path, so asserting them at the root is what fails if one is ever introduced.
    private static final String STARTUP_PROBE_PATH = "/actuator/startup";
    private static final String READINESS_PROBE_PATH = "/actuator/health/readiness";
    private static final String LIVENESS_PROBE_PATH = "/actuator/health/liveness";

    // Both scrape routes onto the one registry: the chart's annotation carries prometheus.io/port with no path
    // [.../templates/cash-account.yaml:L51-L54], so config/MetricsScrapeController answers /metrics, while
    // Actuator publishes the canonical /actuator/prometheus. A Micrometer or exposition-format change can break
    // either one alone, so neither stands in for the other.
    private static final String METRICS_SCRAPE_PATH = "/metrics";
    private static final String ACTUATOR_PROMETHEUS_PATH = "/actuator/prometheus";

    // The meter the cutover rollback criterion is computed from - a 5xx ratio over
    // http_server_requests_seconds_count for /cash-account/.* sustained over 10 minutes (AAP 0.3.3, Step 3).
    // Asserting the series by name is what makes a Micrometer upgrade that silently dropped the HTTP-server
    // instrumentation a test failure here rather than an unreadable rollback criterion during a change window.
    private static final String ROLLBACK_CRITERION_METER = "http_server_requests_seconds_count";

    @LocalServerPort
    private int port;

    // A throwing client would raise where the regression under test is a status: a 404 on the startup path and
    // a 503 on readiness both have to arrive as assertable values.
    @Autowired
    private TestRestTemplate probeClient;

    @Test
    void chartProbedActuatorPathsAnswer200AtRoot() {
        // Softly, because a context path or a lost permitAll breaks all three probes at once and an operator
        // reading the failure needs the full set, not whichever path happens to be asserted first.
        SoftAssertions.assertSoftly(probes -> {
            probes.assertThat(statusOf(STARTUP_PROBE_PATH)).as(STARTUP_PROBE_PATH).isEqualTo(HttpStatus.OK);
            // The readiness group is readinessState,db (application.yml), so this answers 503 unless the
            // PostgreSQL the base class publishes through @ServiceConnection is reachable.
            probes.assertThat(statusOf(READINESS_PROBE_PATH)).as(READINESS_PROBE_PATH).isEqualTo(HttpStatus.OK);
            probes.assertThat(statusOf(LIVENESS_PROBE_PATH)).as(LIVENESS_PROBE_PATH).isEqualTo(HttpStatus.OK);
        });
    }

    @Test
    void scrapeSurfacesPublishTheRollbackCriterionMeter() {
        // One recorded request first, in this method rather than relying on another test having run: the timer
        // is observed when a request COMPLETES, so a scrape taken as the very first request of the JVM would
        // legitimately carry no http.server.requests series at all. The liveness path is the cheapest
        // instrumented one, and no test method may depend on another having run first.
        HttpStatusCode recorded = statusOf(LIVENESS_PROBE_PATH);

        ResponseEntity<String> shim = responseAt(METRICS_SCRAPE_PATH);
        ResponseEntity<String> canonical = responseAt(ACTUATOR_PROMETHEUS_PATH);

        // Softly for the same reason as the probe assertion above: an upgrade that moves the exposition format
        // tends to break status and content together, and the operator needs both routes in one failure.
        SoftAssertions.assertSoftly(scrape -> {
            scrape.assertThat(recorded).as(LIVENESS_PROBE_PATH).isEqualTo(HttpStatus.OK);
            scrape.assertThat(shim.getStatusCode()).as(METRICS_SCRAPE_PATH).isEqualTo(HttpStatus.OK);
            scrape.assertThat(shim.getBody()).as(METRICS_SCRAPE_PATH + " body")
                    .contains(ROLLBACK_CRITERION_METER);
            scrape.assertThat(canonical.getStatusCode()).as(ACTUATOR_PROMETHEUS_PATH).isEqualTo(HttpStatus.OK);
            scrape.assertThat(canonical.getBody()).as(ACTUATOR_PROMETHEUS_PATH + " body")
                    .contains(ROLLBACK_CRITERION_METER);
        });
    }

    // No credential, because the kubelet sends none and config/SecurityConfig's first matcher permits
    // /actuator/** unconditionally: a token here would stay green after that matcher was lost while every pod
    // failed its startup probe. GET rather than the startup endpoint's POST, which drains the buffered timeline.
    private HttpStatusCode statusOf(String probePath) {
        ResponseEntity<String> response =
                probeClient.getForEntity("http://localhost:" + port + probePath, String.class);
        return response.getStatusCode();
    }

    // The whole response, because a scrape assertion turns on the body as well as the status: an empty or
    // JSON-typed body on a 200 is exactly the regression a registry or exposition-format change produces.
    private ResponseEntity<String> responseAt(String path) {
        return probeClient.getForEntity("http://localhost:" + port + path, String.class);
    }
}
