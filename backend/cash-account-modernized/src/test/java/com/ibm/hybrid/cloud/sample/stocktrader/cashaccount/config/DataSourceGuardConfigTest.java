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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

/** Unit tests for the fail-closed datastore guard and for both TLS shapes of the assembled JDBC URL. */
class DataSourceGuardConfigTest {

    private static final String HOST = "stocktrader-pg.postgres.database.azure.com";
    private static final String PORT = "5432";
    private static final String DATABASE = "trader";
    private static final String URL_BASE = "jdbc:postgresql://" + HOST + ":" + PORT + "/" + DATABASE;

    // The chart delivers cert_defaultTrustStore from the configMap's "ssl.certs" key, which is a YAML block scalar
    // [infra/stocktrader-operator/helm-charts/stocktrader/templates/config.yaml:L92-L93], so the value that reaches
    // the service genuinely carries embedded newlines and "|-" leaves no trailing one. A single-line fixture would
    // pass while hiding the newline handling that decides whether pgJDBC can parse the trust anchor at all.
    private static final String TRUST_STORE_PEM = String.join("\n",
            "-----BEGIN CERTIFICATE-----",
            "MIIBFAKECERTIFICATEBODYFORUNITTESTINGONLYNOTAREALTRUSTANCHORAAAA",
            "RUNITTESTINGONLYRUNITTESTINGONLYRUNITTESTINGONLYRUNITTESTINGONLY",
            "Tk9UQVJFQUxDRVJUSUZJQ0FURQ==",
            "-----END CERTIFICATE-----");

    @Test
    void rejectsStartupUnlessJdbcKindIsPostgres() {
        // No JdbcConnectionDetails bean is contributed here on purpose: the guard's own bean stands down whenever one
        // is already present, so a context that supplied one would deactivate half of what is under test and still
        // report green. Host, port and database are supplied because the guard's bean assembles the URL eagerly, and
        // without them the accept direction would fail for a reason that has nothing to do with the kind.
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(CashAccountPropertiesBinding.class, DataSourceGuardConfig.class)
                .withPropertyValues("JDBC_HOST=" + HOST, "JDBC_PORT=" + PORT, "JDBC_DB=" + DATABASE);

        // db2 is the chart's live database.kind default [.../helm-charts/stocktrader/values.yaml:L74-L81], so this is
        // the value that actually arrives when the release has not been moved to PostgreSQL first - not a synthetic
        // bad input.
        runner.withPropertyValues("cashaccount.jdbc.kind=db2").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cashaccount.jdbc.kind")
                    .hasMessageContaining("JDBC_KIND")
                    .hasMessageContaining("db2")
                    .hasMessageContaining("postgres");
        });

        runner.withPropertyValues("cashaccount.jdbc.kind=postgres")
                .run(context -> assertThat(context).hasNotFailed());

        assertThatThrownBy(() -> DataSourceGuardConfig.requirePostgres("   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("postgres");
    }

    @Test
    void appendsSslRootCertWhenTrustStorePemIsSupplied() throws IOException {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("JDBC_HOST", HOST)
                .withProperty("JDBC_PORT", PORT)
                .withProperty("JDBC_DB", DATABASE)
                .withProperty("JDBC_SSL", "true")
                .withProperty("cert_defaultTrustStore", TRUST_STORE_PEM);

        String url = new DataSourceGuardConfig(new CashAccountProperties(), environment).resolveJdbcUrl();

        assertThat(url).startsWith(URL_BASE + "?ssl=true&sslmode=verify-ca&sslrootcert=");
        assertThat(url).doesNotContain("sslfactory=");

        String marker = "&sslrootcert=";
        Path staged = Path.of(url.substring(url.indexOf(marker) + marker.length()));
        try {
            assertThat(staged).exists().isRegularFile();
            assertThat(Files.readString(staged, StandardCharsets.UTF_8)).isEqualTo(TRUST_STORE_PEM);

            // Asserted only where the filesystem can express POSIX modes, so a store that cannot neither weakens the
            // assertion where it is meaningful nor fails the build over an attribute it does not have.
            if (Files.getFileStore(staged).supportsFileAttributeView(PosixFileAttributeView.class)) {
                assertThat(Files.getPosixFilePermissions(staged))
                        .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    @Test
    void fallsBackToDefaultJavaSslFactoryWhenNoTrustStorePemIsSupplied() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("JDBC_HOST", HOST)
                .withProperty("JDBC_PORT", PORT)
                .withProperty("JDBC_DB", DATABASE)
                .withProperty("JDBC_SSL", "true");

        String url = new DataSourceGuardConfig(new CashAccountProperties(), environment).resolveJdbcUrl();

        assertThat(url).isEqualTo(URL_BASE
                + "?ssl=true&sslmode=verify-ca&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory");
        assertThat(url).doesNotContain("sslrootcert=");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CashAccountProperties.class)
    private static class CashAccountPropertiesBinding {
    }
}
