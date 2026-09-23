package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.config;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.LogSafeText;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;

/** The readiness group's {@code db} contributor, bounded to one log record per failed probe. */
// Registered under the bean name "dbHealthIndicator" for two reasons, both load-bearing. It is the name Spring
// Boot's DataSourceHealthContributorAutoConfiguration#dbHealthContributor stands down for -
// @ConditionalOnMissingBean(name = {"dbHealthIndicator", "dbHealthContributor"}) - so this indicator REPLACES the
// auto-configured DataSourceHealthIndicator rather than sitting beside it; and HealthContributorNameFactory strips
// the "healthIndicator" suffix, so the contributor is still named `db` and application.yml's
// `management.endpoint.health.group.readiness.include: readinessState,db` (AAP 0.6.1) needs no change.
//
// WHY it replaces the framework's indicator at all: the one it replaces extends AbstractHealthIndicator, whose
// health() hands the caught exception to Health.Builder#down(Throwable) and then logs it as
// logger.warn(message, throwable), gated on nothing but isWarnEnabled(). With the datastore unreachable that
// renders the whole CannotGetJdbcConnectionException chain on EVERY readiness probe: measured at 189 stack frames
// per probe, 945 frames across five probes, which at the chart's periodSeconds: 15
// [infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L211-L216] is ~750 frames per
// minute per pod for as long as the outage lasts - amplifying log volume without adding a fact, since every record
// describes the same unreachable database. error/ApiExceptionHandler already settled the module's posture for a
// characterized dependency failure on the request path: one bounded record naming the condition, with the frames
// one level down at DEBUG. This is that posture applied to the probe path.
//
// Extending AbstractHealthIndicator and catching inside doHealthCheck would NOT have worked: the parent logs
// whatever reaches Health.Builder#down(Throwable), so the same stack would simply reappear under this class's
// logger. HealthIndicator is implemented directly so the exception never leaves this class.
@Component("dbHealthIndicator")
public class DatastoreHealthIndicator implements HealthIndicator {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatastoreHealthIndicator.class);

    // Seconds, and deliberately not the framework indicator's isValid(0), which means "wait indefinitely": the
    // chart's readinessProbe declares no timeoutSeconds, so the kubelet applies its 1 s default
    // [.../templates/cash-account.yaml:L211-L216] and an answer that arrives later is a failed probe either way.
    // A pooled connection that is open but dead must therefore cost at most a second here, the same bound
    // spring.datasource.hikari.validation-timeout gives the aliveness check inside the borrow.
    private static final int VALIDATION_TIMEOUT_SECONDS = 1;

    // The detail keys and the validation-query literal Spring Boot's own DataSourceHealthIndicator publishes, kept
    // verbatim so this indicator is a drop-in for anything that reads /actuator/health with details enabled.
    private static final String DATABASE_DETAIL = "database";

    private static final String VALIDATION_QUERY_DETAIL = "validationQuery";

    private static final String VALIDATION_QUERY = "isValid()";

    private static final String UNKNOWN_PRODUCT = "unknown";

    // The DOWN body carries the failure's TYPE and never its message. management.endpoint.health.show-details is
    // `never`, so nothing here reaches the wire today, but the probe paths are permitAll in config/SecurityConfig -
    // a kubelet presents no credential - so a later show-details change must not turn this contributor into an
    // unauthenticated disclosure of the datastore host, port or account, all of which a driver's message names.
    // The reason an operator needs is in the log record instead.
    private static final String ERROR_DETAIL = "error";

    // The one DOWN without an exception behind it, named like the types the other DOWN reports so a consumer of
    // the detail reads one kind of value.
    private static final String CONNECTION_NOT_VALID = "ConnectionNotValid";

    private static final int MAX_CAUSE_DEPTH = 10;

    private final DataSource dataSource;

    /**
     * Container constructor.
     *
     * @param dataSource the ledger's pool, injected rather than built here so the probe borrows through the same
     *     bounded {@code spring.datasource.hikari.connection-timeout} every request does, and so a saturated pool
     *     is reported by readiness instead of being measured around it
     */
    public DatastoreHealthIndicator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Reports whether the cash ledger's database is reachable.
     *
     * @return {@code UP} with the database product and validation method when a connection is borrowed and
     *     validates, otherwise {@code DOWN} carrying the failure's type
     */
    @Override
    public Health health() {
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.isValid(VALIDATION_TIMEOUT_SECONDS)) {
                // A borrowed connection that fails its own validation is a DOWN with no exception to report, which
                // is precisely the case a throwable-shaped record cannot express.
                LOGGER.warn("Datastore readiness check failed - the borrowed connection did not validate");
                return Health.down().withDetail(ERROR_DETAIL, CONNECTION_NOT_VALID).build();
            }
            return Health.up()
                    .withDetail(DATABASE_DETAIL, productName(connection))
                    .withDetail(VALIDATION_QUERY_DETAIL, VALIDATION_QUERY)
                    .build();
        } catch (SQLException | RuntimeException failure) {
            Throwable rootCause = rootCauseOf(failure);
            // ONE record per probe, and the throwable deliberately NOT passed: an unreachable datastore drives this
            // path on every probe for the whole outage, so the frames would be re-rendered per probe while saying
            // the same thing each time. The two fields are the pair error/ApiExceptionHandler logs for the same
            // failure on the request path - the thrown exception's own text, which is where the pool states what it
            // could not do ("Connection is not available, request timed out after 2000ms"), and the root cause's
            // type, which is what separates refused from timed out from rejected credentials. The datastore's host,
            // port and database are deliberately not repeated here: config/DataSourceGuardConfig names them once at
            // start-up, and a probe record that carried them would restate them every fifteen seconds. LogSafeText
            // bounds the text to 200 code points and escapes anything that could forge a second record, because a
            // driver message quotes values this service did not choose.
            LOGGER.warn("Datastore readiness check failed - {} ({})", LogSafeText.ofMessage(failure.toString()),
                    rootCause.getClass().getSimpleName());
            LOGGER.debug("Datastore readiness check failed", failure);
            return Health.down().withDetail(ERROR_DETAIL, rootCause.getClass().getSimpleName()).build();
        }
    }

    // Metadata is read from the connection already in hand rather than through a second borrow, and a driver that
    // refuses to describe itself must not turn a reachable database into a failed probe.
    private static String productName(Connection connection) {
        try {
            String product = connection.getMetaData().getDatabaseProductName();
            return product == null || product.isBlank() ? UNKNOWN_PRODUCT : product;
        } catch (SQLException metadataFailure) {
            LOGGER.debug("Datastore product name unavailable", metadataFailure);
            return UNKNOWN_PRODUCT;
        }
    }

    // The useful type is at the bottom of the chain: the pool raises SQLTransientConnectionException for a
    // refused connection, a saturated pool and rejected credentials alike, and only the driver's own cause
    // underneath separates them. Depth-bounded because a self-referencing chain must not loop, the same bound
    // error/ApiExceptionHandler applies for the same reason.
    private static Throwable rootCauseOf(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH; depth++) {
            Throwable next = cause.getCause();
            if (next == null || next == cause) {
                break;
            }
            cause = next;
        }
        return cause;
    }
}
