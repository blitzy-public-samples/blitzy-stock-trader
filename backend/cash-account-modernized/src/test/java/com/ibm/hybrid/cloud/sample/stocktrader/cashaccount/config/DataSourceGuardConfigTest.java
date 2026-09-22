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

    // The chart delivers cert_defaultTrustStore from the configMap's "ssl.certs" block scalar
    // [infra/stocktrader-operator/helm-charts/stocktrader/templates/config.yaml:L92-L93], so the value reaching
    // the service carries embedded newlines with no trailing one, and a single-line fixture would hide the
    // newline handling that decides whether pgJDBC can parse the trust anchor at all.
    private static final String TRUST_STORE_PEM = String.join("\n",
            "-----BEGIN CERTIFICATE-----",
            "MIIBFAKECERTIFICATEBODYFORUNITTESTINGONLYNOTAREALTRUSTANCHORAAAA",
            "RUNITTESTINGONLYRUNITTESTINGONLYRUNITTESTINGONLYRUNITTESTINGONLY",
            "Tk9UQVJFQUxDRVJUSUZJQ0FURQ==",
            "-----END CERTIFICATE-----");

    @Test
    void rejectsStartupUnlessKindHostAndDatabaseAreConfiguredAndTheSslFlagIsParsable() {
        // No JdbcConnectionDetails bean is contributed on purpose: the guard's own bean stands down whenever one is
        // present, so supplying one would deactivate half of what is under test and still report green. Host, port
        // and database are supplied because the guard assembles the URL eagerly, and without them the accept
        // direction would fail for a reason unrelated to the kind.
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(CashAccountPropertiesBinding.class, DataSourceGuardConfig.class)
                .withPropertyValues("JDBC_HOST=" + HOST, "JDBC_PORT=" + PORT, "JDBC_DB=" + DATABASE);

        // db2 is the chart's live database.kind default [.../helm-charts/stocktrader/values.yaml:L74-L81], so it
        // is the value that actually arrives when the release has not been moved to PostgreSQL first, not a
        // synthetic bad input.
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

        // The empty string is what application.yml's ${JDBC_HOST:} and ${JDBC_DB:} resolve to when the release
        // configMap is absent, so a present-but-empty property is the case that fails if a localhost or trader
        // convenience default is ever re-added - which would let a misconfigured process write the ledger to a
        // local database with its probes still passing.
        assertThatThrownBy(() -> new DataSourceGuardConfig(new CashAccountProperties(), new MockEnvironment()
                .withProperty("cashaccount.jdbc.host", "")
                .withProperty("JDBC_PORT", PORT)
                .withProperty("JDBC_DB", DATABASE)).resolveJdbcUrl())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JDBC_HOST")
                .hasMessageContaining("cashaccount.jdbc.host");

        assertThatThrownBy(() -> new DataSourceGuardConfig(new CashAccountProperties(), new MockEnvironment()
                .withProperty("JDBC_HOST", HOST)
                .withProperty("JDBC_PORT", PORT)
                .withProperty("cashaccount.jdbc.database", "")).resolveJdbcUrl())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JDBC_DB")
                .hasMessageContaining("cashaccount.jdbc.database");

        // Only absence means "no TLS", matching portfolio's <variable name="JDBC_SSL" defaultValue="false"/>
        // [backend/portfolio/src/main/liberty/config/includes/postgres.xml:L2]. "yes" is refused rather than read
        // as false, because a typo read as false strips sslmode=verify-ca from the URL and the ledger connection
        // would run without server-certificate verification.
        assertThat(DataSourceGuardConfig.sslEnabled("true")).isTrue();
        assertThat(DataSourceGuardConfig.sslEnabled("TRUE")).isTrue();
        assertThat(DataSourceGuardConfig.sslEnabled(" true ")).isTrue();
        assertThat(DataSourceGuardConfig.sslEnabled("false")).isFalse();
        assertThat(DataSourceGuardConfig.sslEnabled("FALSE")).isFalse();
        assertThat(DataSourceGuardConfig.sslEnabled(null)).isFalse();
        assertThat(DataSourceGuardConfig.sslEnabled("   ")).isFalse();

        assertThatThrownBy(() -> DataSourceGuardConfig.sslEnabled("yes"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JDBC_SSL")
                .hasMessageContaining("cashaccount.jdbc.ssl")
                .hasMessageContaining("yes");
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

            // Guarded because a file store that cannot express POSIX modes must not fail the build over an
            // attribute it does not have.
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

    // A supplied spring.datasource.url is the one input able to carry a whole connection - dialect, host,
    // credentials and TLS mode - which is why the guard never returns one as written. Each case below is a way that
    // single property could otherwise defeat the verify-ca connection the assembled URL guarantees: an unsafe
    // sslmode, a non-validating factory, credentials folded into the URL, another dialect, a second host, a swapped
    // transport, or simply a different server while the chart's own variables say where the ledger lives.
    @Test
    void normalizesASuppliedJdbcUrlAndRefusesEveryTlsCredentialOrDialectBypass() {
        // The shape audit/LedgerImmutabilityIT's second application context starts in: no chart variables at all and
        // a Testcontainers URL, which survives byte-identically because loggerLevel decides nothing about
        // credentials, TLS, the transport or which server is reached.
        assertThat(resolve("jdbc:postgresql://localhost:32771/test?loggerLevel=OFF"))
                .isEqualTo("jdbc:postgresql://localhost:32771/test?loggerLevel=OFF");

        // An omitted port normalizes to PostgreSQL's own, and a parameter that decides neither credentials, TLS nor
        // the server is kept as it stands.
        assertThat(resolve("jdbc:postgresql://" + HOST + "/" + DATABASE + "?currentSchema=cash_account_rehearsal"))
                .isEqualTo(URL_BASE + "?currentSchema=cash_account_rehearsal");

        // Under TLS the host is itself a trust decision, because verify-ca checks the certificate chain and not the
        // hostname: a URL naming its own host could reach another server holding any certificate the same CA signed.
        assertThatThrownBy(() -> resolveWithSsl("jdbc:postgresql://" + HOST + ":" + PORT + "/" + DATABASE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JDBC_SSL")
                .hasMessageContaining("verify-ca")
                .hasMessageContaining("JDBC_HOST");

        // A URL that tries to decide TLS itself is refused, never quietly rewritten: silently upgrading
        // sslmode=disable would hide a caller who believes the connection is in the clear, and silently accepting it
        // is the bypass itself.
        assertThatThrownBy(() -> resolve("jdbc:postgresql://" + HOST + ":" + PORT + "/" + DATABASE
                + "?sslmode=disable"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sslmode")
                .hasMessageContaining("JDBC_SSL")
                .hasMessageContaining("verify-ca");
        assertThatThrownBy(() -> resolve("jdbc:postgresql://" + HOST + "/" + DATABASE
                + "?ssl=true&sslfactory=org.postgresql.ssl.NonValidatingFactory"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cert_defaultTrustStore");

        // Credentials in a URL reach every log line and stack trace that quotes it, so both shapes are refused - and
        // the refusal may not repeat the secret it was refused for.
        assertThatThrownBy(() -> resolve("jdbc:postgresql://" + DATABASE + ":s3cr3t@" + HOST + ":" + PORT + "/"
                + DATABASE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JDBC_PASSWORD")
                .hasMessageNotContaining("s3cr3t");
        assertThatThrownBy(() -> resolve("jdbc:postgresql://" + HOST + "/" + DATABASE
                + "?user=trader&password=s3cr3t"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JDBC_ID")
                .hasMessageNotContaining("s3cr3t");

        // Another dialect reaches a store this schema was never written for; a host list and an unrecognized
        // parameter reach a server or a transport nothing here verified.
        assertThatThrownBy(() -> resolve("jdbc:db2://" + HOST + ":50000/" + DATABASE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jdbc:postgresql://");
        assertThatThrownBy(() -> resolve("jdbc:postgresql://" + HOST + ":" + PORT + ",replica.example:" + PORT + "/"
                + DATABASE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("more than one host");
        assertThatThrownBy(() -> resolve("jdbc:postgresql://" + HOST + "/" + DATABASE
                + "?socketFactory=org.example.Tunnel"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("socketFactory");

        // In a deployment the URL is refused whatever it says, because the chart injects JDBC_HOST and JDBC_DB from
        // non-optional configMap keys: two sources cannot both say where the ledger lives. Asserted through a real
        // context refresh as well as directly, because the property is only guarded if the refusal actually stops
        // start-up - this bean is the one Boot's datasource auto-configuration consumes.
        assertThatThrownBy(() -> new DataSourceGuardConfig(new CashAccountProperties(), new MockEnvironment()
                .withProperty("JDBC_HOST", HOST)
                .withProperty("JDBC_PORT", PORT)
                .withProperty("JDBC_DB", DATABASE)
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/" + DATABASE))
                .resolveJdbcUrl())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.url")
                .hasMessageContaining("JDBC_HOST")
                .hasMessageContaining("JDBC_DB");

        new ApplicationContextRunner()
                .withUserConfiguration(CashAccountPropertiesBinding.class, DataSourceGuardConfig.class)
                .withPropertyValues("JDBC_HOST=" + HOST, "JDBC_PORT=" + PORT, "JDBC_DB=" + DATABASE,
                        "spring.datasource.url=jdbc:postgresql://localhost:5432/" + DATABASE)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("spring.datasource.url");
                });
    }

    private static String resolve(String configuredUrl) {
        return new DataSourceGuardConfig(new CashAccountProperties(),
                new MockEnvironment().withProperty("spring.datasource.url", configuredUrl)).resolveJdbcUrl();
    }

    private static String resolveWithSsl(String configuredUrl) {
        return new DataSourceGuardConfig(new CashAccountProperties(), new MockEnvironment()
                .withProperty("spring.datasource.url", configuredUrl)
                .withProperty("JDBC_SSL", "true")).resolveJdbcUrl();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CashAccountProperties.class)
    private static class CashAccountPropertiesBinding {
    }
}
