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
// Inherited by every subclass context, like @ActiveProfiles above, so no *IT declares it: the test slice sets
// management.defaults.metrics.export.enabled=false, which would leave the component-scanned
// config/MetricsScrapeController without the PrometheusMeterRegistry its constructor demands. Measured: without
// this, the contexts survive only on application.yml naming management.prometheus.metrics.export.enabled, a
// deployment property the test tree does not own. A @SpringBootTest not extending this class needs its own.
@AutoConfigureObservability
public abstract class PostgresTestSupport {

    // Pinned to the major version the estate provisions [infra/stocktrader-setup/azure/modules/postgres/main.tf:L23],
    // at 12.22 because that is its final patch. Running the suite on the compatibility floor is what proves, rather
    // than assumes, that schema/cash-account-schema.sql stays inside PostgreSQL 12 - identity columns and DO blocks,
    // never CREATE OR REPLACE TRIGGER, which needs 14+. A newer tag would accept the 14+ form and hide the break.
    //
    // @ServiceConnection is also the interlock the main code is written against: config/DataSourceGuardConfig
    // contributes its chart-assembled JdbcConnectionDetails only @ConditionalOnMissingBean(JdbcConnectionDetails.class),
    // so the bean this field registers is what makes the guard stand down and every *IT reach this container instead
    // of dialling the deployment's JDBC_HOST. Database name, user and password are left at the container's defaults
    // because @ServiceConnection derives all three, which is why application-test.yml sets no spring.datasource.*.
    //
    // max_connections is raised from PostgreSQL's default 100 because this single instance serves every *IT's
    // Spring context at once (Spring's test-context cache keeps each one's Hikari pool open until the JVM exits),
    // each pool stays at Hikari's default size, and this headroom is what keeps a future *IT from rediscovering
    // "FATAL: sorry, too many clients already". fsync=off is the container class's own default, kept for speed.
    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:12.22-alpine"))
                    .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=200");

    // Started once per JVM as the documented Testcontainers singleton, deliberately without @Container: that
    // annotation binds a container to one class's lifecycle, so each *IT would be handed a freshly mapped host port
    // while Spring's test-context cache went on serving a Hikari pool bound to the previous, dead one - and
    // audit/LedgerImmutabilityIT, which restarts the context against this same instance to prove the ;;-separated
    // schema script re-applies without dropping the ledger_entry_immutable trigger, could not be written at all.
    // Nothing stops it: the Ryuk sidecar reclaims it when the JVM exits.
    static {
        POSTGRES.start();
    }

    protected static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }
}
