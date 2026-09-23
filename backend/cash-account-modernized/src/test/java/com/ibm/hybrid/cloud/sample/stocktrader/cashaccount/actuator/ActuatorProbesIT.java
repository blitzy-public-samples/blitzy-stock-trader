package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.actuator;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.config.DatastoreHealthIndicator;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.config.ScheduledTaskErrorHandlingConfig;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.PostgresTestSupport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import jakarta.persistence.PersistenceException;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

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

    // The startup probe's body is asserted as well as its status, because this path is permitAll - a kubelet
    // presents no credential - so whatever CashAccountApplication's recorder buffers is readable by any peer that
    // reaches the port, and a GET re-serves it on every probe. The lifecycle step below is the positive half: a
    // Boot release that renamed it would leave an endpoint answering 200 with an empty timeline, which no status
    // assertion can distinguish from a working one.
    private static final String STARTUP_LIFECYCLE_STEP = "spring.boot.application.ready";

    // The negative half: the tag and the step that carried the internal inventory. Unfiltered, this payload
    // measured 170,993 bytes with 447 beanName tags and 221 fully-qualified class names.
    private static final String STARTUP_BEAN_TAG = "beanName";
    private static final String STARTUP_BEAN_STEP = "spring.beans.instantiate";

    // A ceiling rather than an exact size: the filtered payload is a little over 1KB of step names and durations,
    // so 8KB fails on a returning inventory while staying indifferent to timings and to a step gained or lost.
    private static final int STARTUP_PAYLOAD_CEILING_BYTES = 8 * 1024;

    // The bean name is the whole mechanism, so it is asserted rather than assumed: it is what Spring Boot's
    // DataSourceHealthContributorAutoConfiguration stands down for, and stripped of its healthIndicator suffix it
    // is also the contributor name `db` that application.yml's readiness group includes (AAP 0.6.1). Rename the
    // bean and the framework's unbounded indicator silently takes the readiness slot back.
    private static final String DATASTORE_CONTRIBUTOR_BEAN = "dbHealthIndicator";

    private static final String DATASTORE_CONTRIBUTOR_PATH = "db";

    // A real PostgreSQL URL that cannot answer: port 1 refuses at TCP, the same device application-test.yml uses
    // to keep the FX endpoint offline. The failure path is therefore exercised without stopping the JVM-wide
    // container every other *IT borrows from, which no test here may do.
    private static final String UNREACHABLE_DATASTORE_URL = "jdbc:postgresql://127.0.0.1:1/cash_account_absent";

    private static final String UNREACHABLE_DATASTORE_HOST = "127.0.0.1";

    // Asserted as text because the record is the deliverable: an operator's alert rule matches on it, so a record
    // that changed shape silently would be a change to the interface this test protects.
    private static final String BOUNDED_FAILURE_RECORD = "Datastore readiness check failed";

    private static final String BOUNDED_SCHEDULED_RECORD = "Scheduled pass skipped";

    // Distinguishable text on the two failures the scheduler is handed, so a record from any other task in the
    // context cannot be mistaken for one of them.
    private static final String SCHEDULED_OUTAGE_PROBE = "datastore unreachable, as during an outage";

    private static final String SCHEDULED_ROLLBACK_PROBE = "connection killed mid-transaction";

    private static final String SCHEDULED_DEFECT_PROBE = "a defect in a scheduled pass";

    // Generous, because the assertion is about what the scheduler records rather than how fast: a loaded host
    // shares its cores with up to 64 of these suites at once.
    private static final Duration SCHEDULED_RECORD_TIMEOUT = Duration.ofSeconds(10);

    @LocalServerPort
    private int port;

    // A throwing client would raise where the regression under test is a status: a 404 on the startup path and
    // a 503 on readiness both have to arrive as assertable values.
    @Autowired
    private TestRestTemplate probeClient;

    // The endpoint rather than the bean, so the readiness group's contributor is resolved the way the probe
    // resolves it: by the name application.yml includes.
    @Autowired
    private HealthEndpoint healthEndpoint;

    @Autowired
    private ApplicationContext context;

    @Test
    void chartProbedActuatorPathsAnswer200AtRootAndStartupPublishesNoInternalInventory() {
        // The whole response, not just its status: the startup body is half of what this test protects.
        ResponseEntity<String> startup = responseAt(STARTUP_PROBE_PATH);
        String startupBody = startup.getBody() == null ? "" : startup.getBody();

        // Softly, because a context path or a lost permitAll breaks all three probes at once and an operator
        // reading the failure needs the full set, not whichever path happens to be asserted first.
        SoftAssertions.assertSoftly(probes -> {
            probes.assertThat(startup.getStatusCode()).as(STARTUP_PROBE_PATH).isEqualTo(HttpStatus.OK);
            // The readiness group is readinessState,db (application.yml), so this answers 503 unless the
            // PostgreSQL the base class publishes through @ServiceConnection is reachable.
            probes.assertThat(statusOf(READINESS_PROBE_PATH)).as(READINESS_PROBE_PATH).isEqualTo(HttpStatus.OK);
            probes.assertThat(statusOf(LIVENESS_PROBE_PATH)).as(LIVENESS_PROBE_PATH).isEqualTo(HttpStatus.OK);

            probes.assertThat(startupBody).as(STARTUP_PROBE_PATH + " lifecycle timeline")
                    .contains(STARTUP_LIFECYCLE_STEP);
            probes.assertThat(startupBody).as(STARTUP_PROBE_PATH + " internal inventory")
                    .doesNotContain(STARTUP_BEAN_TAG, STARTUP_BEAN_STEP);
            probes.assertThat(startupBody.length()).as(STARTUP_PROBE_PATH + " payload size")
                    .isLessThan(STARTUP_PAYLOAD_CEILING_BYTES);
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

    // The readiness probe is the one surface a datastore outage drives on a schedule - every fifteen seconds per
    // pod [.../templates/cash-account.yaml:L211-L216] - so what it RECORDS is part of its contract and not a
    // matter of taste. Spring Boot's own DataSourceHealthIndicator rendered the whole connection-failure chain
    // per probe (189 stack frames measured, 945 across five probes), which config/DatastoreHealthIndicator
    // replaces with one bounded line. Both halves are asserted here because either alone is undefended: the
    // contributor could be bounded but no longer named `db`, in which case readiness would silently stop watching
    // the ledger, or still named `db` but answered by the framework's indicator again.
    @Test
    void readinessDatastoreContributorIsBoundedAndRecordsOneStacklessLinePerFailure() {
        HealthComponent contributor = healthEndpoint.healthForPath(DATASTORE_CONTRIBUTOR_PATH);
        Object contributorBean = context.getBean(DATASTORE_CONTRIBUTOR_BEAN);

        // A second indicator over an unreachable datastore, never the wired one: the shared container has to stay
        // up for every other *IT in this JVM, and the failure path is what needs observing.
        Logger indicatorLog = (Logger) LoggerFactory.getLogger(DatastoreHealthIndicator.class);
        ListAppender<ILoggingEvent> records = new ListAppender<>();
        records.start();
        indicatorLog.addAppender(records);
        Health unreachable;
        try {
            unreachable = new DatastoreHealthIndicator(new DriverManagerDataSource(UNREACHABLE_DATASTORE_URL))
                    .health();
        } finally {
            indicatorLog.detachAppender(records);
            records.stop();
        }
        // Only the records an operator's alerting sees: the throwable belongs at DEBUG, where raising the module's
        // level is a deliberate act, so a DEBUG-enabled run must not fail this.
        List<ILoggingEvent> reported = records.list.stream()
                .filter(record -> record.getLevel().isGreaterOrEqual(Level.WARN))
                .toList();

        SoftAssertions.assertSoftly(bounded -> {
            bounded.assertThat(contributorBean).as(DATASTORE_CONTRIBUTOR_BEAN + " bean")
                    .isInstanceOf(DatastoreHealthIndicator.class);
            bounded.assertThat(contributor).as(DATASTORE_CONTRIBUTOR_PATH + " contributor").isNotNull();
            bounded.assertThat(contributor.getStatus()).as(DATASTORE_CONTRIBUTOR_PATH + " status")
                    .isEqualTo(Status.UP);

            // Bounding the record must not cost the verdict: an unreachable ledger still has to take the pod out
            // of the Service endpoints, which is the 503 the readiness group answers on DOWN.
            bounded.assertThat(unreachable.getStatus()).as("unreachable datastore status").isEqualTo(Status.DOWN);
            bounded.assertThat(unreachable.getDetails().toString()).as("DOWN body")
                    .doesNotContain(UNREACHABLE_DATASTORE_HOST);

            bounded.assertThat(reported).as("records per failed check").hasSize(1);
            if (reported.size() == 1) {
                ILoggingEvent record = reported.get(0);
                bounded.assertThat(record.getLevel()).as("record level").isEqualTo(Level.WARN);
                bounded.assertThat(record.getThrowableProxy()).as("stack attached to the record").isNull();
                bounded.assertThat(record.getFormattedMessage()).as("record text")
                        .contains(BOUNDED_FAILURE_RECORD);
            }
        });
    }

    // The readiness probe is one of the two surfaces an outage drives on a clock; the other is
    // institutional/ReservationService's expiry sweep, whose every pass throws while the datastore is unreachable
    // and which Spring's default handler recorded as ERROR with 109 stack frames, once a minute per pod, forever.
    // Both failures are handed to the CONTEXT's own scheduler rather than to a handler built here, because an
    // uninstalled customizer is the regression that matters and it is invisible to a direct call. The second
    // failure is the half that must stay loud: bounding an outage must not bound a defect.
    @Test
    void scheduledPassSkipsBoundedForAnOutageAndKeepsItsStackForADefect() {
        Logger handlerLog = (Logger) LoggerFactory.getLogger(ScheduledTaskErrorHandlingConfig.class);
        ListAppender<ILoggingEvent> records = new ListAppender<>();
        records.start();
        handlerLog.addAppender(records);
        ILoggingEvent outage;
        ILoggingEvent rollbackFailure;
        ILoggingEvent defect;
        try {
            TaskScheduler scheduler = context.getBean(TaskScheduler.class);
            scheduler.schedule(() -> {
                throw new DataAccessResourceFailureException(SCHEDULED_OUTAGE_PROBE);
            }, Instant.now());
            outage = awaitRecord(records, 1);
            // The shape only the START of an outage produces, and the one a taxonomy of unreachable-datastore
            // types alone misses: a connection killed mid-transaction fails the statement and then fails its
            // rollback, which Spring surfaces as this uncategorized exception rather than a resource failure.
            scheduler.schedule(() -> {
                throw new JpaSystemException(new PersistenceException(SCHEDULED_ROLLBACK_PROBE));
            }, Instant.now());
            rollbackFailure = awaitRecord(records, 2);
            scheduler.schedule(() -> {
                throw new IllegalStateException(SCHEDULED_DEFECT_PROBE);
            }, Instant.now());
            defect = awaitRecord(records, 3);
        } finally {
            handlerLog.detachAppender(records);
            records.stop();
        }

        SoftAssertions.assertSoftly(bounded -> {
            bounded.assertThat(outage.getLevel()).as("outage record level").isEqualTo(Level.WARN);
            bounded.assertThat(outage.getThrowableProxy()).as("stack attached to the outage record").isNull();
            bounded.assertThat(outage.getFormattedMessage()).as("outage record text")
                    .contains(BOUNDED_SCHEDULED_RECORD, SCHEDULED_OUTAGE_PROBE);

            bounded.assertThat(rollbackFailure.getLevel()).as("rollback-failure record level")
                    .isEqualTo(Level.WARN);
            bounded.assertThat(rollbackFailure.getThrowableProxy())
                    .as("stack attached to the rollback-failure record").isNull();
            bounded.assertThat(rollbackFailure.getFormattedMessage()).as("rollback-failure record text")
                    .contains(BOUNDED_SCHEDULED_RECORD, SCHEDULED_ROLLBACK_PROBE);

            bounded.assertThat(defect.getLevel()).as("defect record level").isEqualTo(Level.ERROR);
            bounded.assertThat(defect.getThrowableProxy()).as("stack attached to the defect record").isNotNull();
        });
    }

    // Polled rather than slept on: the record is written by the scheduler's own thread, so the test cannot know
    // when it lands and a fixed wait would either be flaky or slow. The failure names what never arrived.
    private static ILoggingEvent awaitRecord(ListAppender<ILoggingEvent> records, int ordinal) {
        long deadline = System.nanoTime() + SCHEDULED_RECORD_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (records.list.size() >= ordinal) {
                return records.list.get(ordinal - 1);
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted awaiting scheduled-task record " + ordinal,
                        interrupted);
            }
        }
        throw new AssertionError("no scheduled-task record " + ordinal + " within " + SCHEDULED_RECORD_TIMEOUT
                + "; captured " + records.list.size());
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
