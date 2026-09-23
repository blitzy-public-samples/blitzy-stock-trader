package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;

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

    // The driver bounds application.yml declares, appended to every URL the guard resolves: the pool's
    // connection-timeout governs obtaining a connection, and only these two bound dialling and reading with one.
    private static final String DRIVER_BOUNDS = "connectTimeout=2&socketTimeout=30";

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
        assertThat(url).endsWith("&" + DRIVER_BOUNDS);

        String marker = "&sslrootcert=";
        String certificateAndBounds = url.substring(url.indexOf(marker) + marker.length());
        Path staged = Path.of(certificateAndBounds.substring(0, certificateAndBounds.indexOf('&')));
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
                + "?ssl=true&sslmode=verify-ca&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory&"
                + DRIVER_BOUNDS);
        assertThat(url).doesNotContain("sslrootcert=");
    }

    // Both bounds exist because a database outage otherwise reaches a caller as a wait rather than as an answer:
    // the pool's connection-timeout caps the wait for a connection, connectTimeout caps dialling for one, and
    // socketTimeout is the only bound on a read that has already begun - pgJDBC leaves that one infinite.
    @Test
    void boundsDiallingAndReadingOnEveryAssembledUrlAndRefusesATimeoutTheDriverWouldReadAsUnbounded() {
        assertThat(new DataSourceGuardConfig(new CashAccountProperties(), chartVariables()).resolveJdbcUrl())
                .isEqualTo(URL_BASE + "?" + DRIVER_BOUNDS);

        // A deployment retunes either bound with no chart change, through relaxed binding of the same key.
        assertThat(new DataSourceGuardConfig(new CashAccountProperties(), chartVariables()
                .withProperty("cashaccount.jdbc.connect-timeout", "PT5S")
                .withProperty("cashaccount.jdbc.socket-timeout", "PT60S")).resolveJdbcUrl())
                .isEqualTo(URL_BASE + "?connectTimeout=5&socketTimeout=60");

        // A parameter a supplied URL names is the operator's and is neither overridden nor duplicated: pgJDBC
        // keeps the last occurrence of a repeated parameter, so appending ours beside it would leave the URL
        // saying one thing and the connection doing another. The bound the URL leaves out is still added.
        assertThat(resolve("jdbc:postgresql://" + HOST + "/" + DATABASE + "?socketTimeout=90"))
                .isEqualTo(URL_BASE + "?socketTimeout=90&connectTimeout=2");

        // A fraction of a second is refused rather than truncated, because the driver takes whole seconds and
        // reads 0 as no timeout at all - the silent downgrade back to the 30 s stall these bounds remove.
        assertThatThrownBy(() -> new DataSourceGuardConfig(new CashAccountProperties(), chartVariables()
                .withProperty("cashaccount.jdbc.socket-timeout", "PT0.5S")).resolveJdbcUrl())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cashaccount.jdbc.socket-timeout")
                .hasMessageContaining("whole seconds");
        assertThatThrownBy(() -> new DataSourceGuardConfig(new CashAccountProperties(), chartVariables()
                .withProperty("cashaccount.jdbc.connect-timeout", "PT0S")).resolveJdbcUrl())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cashaccount.jdbc.connect-timeout");
    }

    // The third bound, and the one a value alone cannot show to be wrong: a lock_timeout at or above
    // socketTimeout is well-formed, and simply lets the read bound expire first - which kills the connection
    // mid-statement and surfaces a lock conflict as 500 INTERNAL, the case AAP 0.6.3 forbids ("never a generic
    // 500"). Measured before the bound existed: an external transaction holding one cash_account row parked a
    // retail credit for 30.07 s and then answered 500 with no Retry-After.
    @Test
    void boundsARowLockWaitBelowTheReadBoundAndRefusesEveryValueThatWouldNotExpireFirst() {
        Duration readBound = Duration.ofSeconds(30);

        // Unset takes application.yml's documented default rather than leaving the wait open-ended.
        assertThat(DataSourceGuardConfig.requireLockWaitBound(null, readBound)).isEqualTo(2000L);
        assertThat(DataSourceGuardConfig.requireLockWaitBound("   ", readBound)).isEqualTo(2000L);

        // Retuned with no chart change through relaxed binding of the same key, and whitespace tolerated because
        // a configMap block scalar is what delivers it.
        assertThat(DataSourceGuardConfig.requireLockWaitBound("500", readBound)).isEqualTo(500L);
        assertThat(DataSourceGuardConfig.requireLockWaitBound(" 250 ", readBound)).isEqualTo(250L);

        // 0 is PostgreSQL's own spelling of "no lock timeout at all", so it reads as a configured bound while
        // restoring the unbounded wait - refused by name rather than accepted as a number.
        assertThatThrownBy(() -> DataSourceGuardConfig.requireLockWaitBound("0", readBound))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cashaccount.jdbc.lock-wait-timeout-ms")
                .hasMessageContaining("no lock timeout at all");
        assertThatThrownBy(() -> DataSourceGuardConfig.requireLockWaitBound("-1", readBound))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("positive");

        // An ISO-8601 duration is the shape of both neighbouring keys, so it is the wrong value an operator is
        // most likely to write here - and one that would otherwise reach the pool as SET lock_timeout = PT2S and
        // fail every connection at creation instead of at configuration.
        assertThatThrownBy(() -> DataSourceGuardConfig.requireLockWaitBound("PT2S", readBound))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PT2S")
                .hasMessageContaining("whole number of milliseconds");

        // Equal is refused as well as greater: the bound has to expire strictly before the read bound, or the
        // conflict it exists to convert into a 409 still arrives as a 500.
        assertThatThrownBy(() -> DataSourceGuardConfig.requireLockWaitBound("30000", readBound))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cashaccount.jdbc.socket-timeout")
                .hasMessageContaining("500 INTERNAL");
        assertThatThrownBy(() -> DataSourceGuardConfig.requireLockWaitBound("45000", readBound))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("409 CONCURRENT_MODIFICATION");

        // The guard runs on every context, including one where a Testcontainers @ServiceConnection supplies the
        // JdbcConnectionDetails and no URL is assembled here at all - so a misordered pair cannot start a pod
        // just because this class's own bean stood down.
        new ApplicationContextRunner()
                .withUserConfiguration(CashAccountPropertiesBinding.class, DataSourceGuardConfig.class)
                .withPropertyValues("JDBC_HOST=" + HOST, "JDBC_PORT=" + PORT, "JDBC_DB=" + DATABASE,
                        "cashaccount.jdbc.socket-timeout=PT1S", "cashaccount.jdbc.lock-wait-timeout-ms=2000")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("cashaccount.jdbc.lock-wait-timeout-ms");
                });
    }

    // A supplied spring.datasource.url is the one input able to carry a whole connection - dialect, host,
    // credentials and TLS mode - which is why the guard never returns one as written. Each case below is a way that
    // single property could otherwise defeat the verify-ca connection the assembled URL guarantees: an unsafe
    // sslmode, a non-validating factory, credentials folded into the URL, another dialect, a second host, a swapped
    // transport, or simply a different server while the chart's own variables say where the ledger lives.
    @Test
    void normalizesASuppliedJdbcUrlAndRefusesEveryTlsCredentialOrDialectBypass() {
        // The shape audit/LedgerImmutabilityIT's second application context starts in: no chart variables at all and
        // a Testcontainers URL, whose own text survives unchanged because loggerLevel decides nothing about
        // credentials, TLS, the transport or which server is reached. The driver bounds are appended to it as to
        // every other resolved URL, since a hand-started run has the same outage to fail fast on.
        assertThat(resolve("jdbc:postgresql://localhost:32771/test?loggerLevel=OFF"))
                .isEqualTo("jdbc:postgresql://localhost:32771/test?loggerLevel=OFF&" + DRIVER_BOUNDS);

        // An omitted port normalizes to PostgreSQL's own, and a parameter that decides neither credentials, TLS nor
        // the server is kept as it stands.
        assertThat(resolve("jdbc:postgresql://" + HOST + "/" + DATABASE + "?currentSchema=cash_account_rehearsal"))
                .isEqualTo(URL_BASE + "?currentSchema=cash_account_rehearsal&" + DRIVER_BOUNDS);

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

    // What a chart deployment supplies and nothing else: host, port and database, with no URL and no TLS.
    private static MockEnvironment chartVariables() {
        return new MockEnvironment()
                .withProperty("JDBC_HOST", HOST)
                .withProperty("JDBC_PORT", PORT)
                .withProperty("JDBC_DB", DATABASE);
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
