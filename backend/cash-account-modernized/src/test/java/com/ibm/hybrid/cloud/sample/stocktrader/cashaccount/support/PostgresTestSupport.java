package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support;

import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Base class giving every {@code *IT} in this module one disposable PostgreSQL instance on the test profile. */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
// Inherited by every subclass context so no *IT declares it: the test slice sets
// management.defaults.metrics.export.enabled=false, which would leave the component-scanned
// config/MetricsScrapeController without the PrometheusMeterRegistry its constructor demands.
@AutoConfigureObservability
public abstract class PostgresTestSupport {

    // Pinned to the major version the estate provisions [infra/stocktrader-setup/azure/modules/postgres/main.tf:L23],
    // at its final patch: running the suite on the compatibility floor proves rather than assumes that
    // schema/cash-account-schema.sql stays inside PostgreSQL 12, where a newer tag would accept the 14+
    // CREATE OR REPLACE TRIGGER form and hide the break.
    //
    // @ServiceConnection is the interlock config/DataSourceGuardConfig stands down for - it contributes its
    // chart-assembled JdbcConnectionDetails only @ConditionalOnMissingBean - and it derives database name, user and
    // password, which is why application-test.yml sets no spring.datasource.*.
    //
    // max_connections is raised from PostgreSQL's default 100 because this one instance serves every *IT's Spring
    // context at once: the test-context cache holds each context's Hikari pool open until the JVM exits.
    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:12.22-alpine"))
                    .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=200");

    // The Testcontainers singleton, deliberately without @Container: that annotation binds a container to one
    // class's lifecycle, so each *IT would be handed a freshly mapped host port while the test-context cache went
    // on serving a Hikari pool bound to the previous, dead one. Nothing stops it - Ryuk reclaims it at JVM exit.
    static {
        POSTGRES.start();
    }

    protected static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }
}
