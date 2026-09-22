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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

// Two responsibilities, both of which end in a refusal to start rather than in a service that runs half-working.
//
// PostgreSQL only. The chart's database.kind is one release-global value shared with backend/portfolio
// [infra/stocktrader-operator/helm-charts/stocktrader/values.yaml:L74-L81] and is still db2 in the repository, so a
// service that quietly accepted whatever arrived would be a service running against a store this module was never
// written for: schema/cash-account-schema.sql, its ledger_entry_immutable trigger and every integration test are
// written for a single dialect precisely so none of them has to be matrixed across two. Moving the release to
// PostgreSQL is a signed-off prerequisite in docs/operational-runbook.md - a values change applied by the platform
// operator, never a chart template edit and never something this code adapts to. Refusing an unrecognized value is
// the same fail-closed principle that replaces the legacy dispatcher's missing WHEN OTHER, where an unknown request
// code fell through to a success-looking return code [backend/cash-account-cobol/COBOL/CASH00.cbl:L89-L102].
//
// URL assembly lives here rather than in application.yml because no static placeholder can express it: JDBC_SSL=true
// has to add either an sslrootcert file or an sslfactory class and never both, and the certificate it points at has
// to be written before the first connection. The inputs are the very variables the chart already injects
// [infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L84-L119], which is what keeps
// cutover a values change with no template edit and no new mechanism beyond the one backend/portfolio already uses.
/** Fail-closed datasource configuration: PostgreSQL or no start-up, plus the JDBC URL the chart's variables imply. */
@Configuration
public class DataSourceGuardConfig {

    /** The only relational dialect this service is built for. */
    public static final String SUPPORTED_JDBC_KIND = "postgres";

    /** pgJDBC factory that leaves server-certificate trust to the JVM's own {@code cacerts}. */
    public static final String DEFAULT_JAVA_SSL_FACTORY = "org.postgresql.ssl.DefaultJavaSSLFactory";

    private static final String JDBC_URL_SCHEME = "jdbc:postgresql://";
    private static final String TLS_PARAMETERS = "ssl=true&sslmode=verify-ca";
    private static final String URL_BREAKING_CHARACTERS = "/?&# \t\r\n";
    private static final String TRUST_STORE_VARIABLE = "cert_defaultTrustStore";
    private static final Logger LOGGER = LoggerFactory.getLogger(DataSourceGuardConfig.class);

    private final CashAccountProperties properties;
    private final Environment environment;

    // One reference, set at most once, so the certificate is staged exactly once per application start.
    private final AtomicReference<Path> trustStoreFile = new AtomicReference<>();

    public DataSourceGuardConfig(CashAccountProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    // Thrown from initialization rather than logged, because a warning that scrolled past would leave the pod
    // passing its probes while every ledger write went to a store whose schema was never applied.
    @PostConstruct
    void guardDatastoreKind() {
        requirePostgres(properties.getJdbc().getKind());
    }

    /**
     * Accepts only {@value #SUPPORTED_JDBC_KIND}, trimmed and case-insensitively, and returns it normalized.
     *
     * @param kind the configured {@code cashaccount.jdbc.kind} (the chart's {@code JDBC_KIND})
     * @return {@value #SUPPORTED_JDBC_KIND}
     * @throws IllegalStateException for every other value, including {@code db2}, blank and {@code null}
     */
    public static String requirePostgres(String kind) {
        String candidate = (kind == null) ? "" : kind.trim();
        if (!SUPPORTED_JDBC_KIND.equalsIgnoreCase(candidate)) {
            throw new IllegalStateException("Unsupported datastore: cashaccount.jdbc.kind (chart variable JDBC_KIND) is "
                    + describe(kind) + ", and this service supports only '" + SUPPORTED_JDBC_KIND + "'. Set the chart"
                    + " value database.kind to '" + SUPPORTED_JDBC_KIND + "' and point database.host/port/db at the"
                    + " PostgreSQL instance - a values change made by the platform operator per the Step 0"
                    + " prerequisite of docs/operational-runbook.md, not a chart template change.");
        }
        return SUPPORTED_JDBC_KIND;
    }

    // Do not remove this condition, and do not turn this into a DataSource bean. Spring Boot's own
    // PropertiesJdbcConnectionDetails carries the same @ConditionalOnMissingBean(JdbcConnectionDetails.class), so in
    // a deployed run this bean registers first and the properties-based fallback stands down; under @SpringBootTest
    // the Testcontainers @ServiceConnection of support/PostgresTestSupport contributes its own JdbcConnectionDetails
    // before the context refreshes, and without this condition ours would win and every *IT in the module would
    // dial the chart's JDBC_HOST instead of its own PostgreSQL container. Contributing connection details rather
    // than a DataSource also leaves spring.datasource.hikari.* binding intact, which is what lets the Operational
    // Runbook's rehearsal step pass --spring.datasource.hikari.schema=cash_account_rehearsal and keep the
    // production tables untouched - a schema pinned here would silently defeat that.
    @Bean
    @ConditionalOnMissingBean(JdbcConnectionDetails.class)
    JdbcConnectionDetails cashAccountJdbcConnectionDetails() {
        // Credentials are relayed from spring.datasource.username/password, which application.yml binds from the
        // chart secret's database.id/database.password [.../templates/cash-account.yaml:L110-L119]; they are never
        // folded into the URL, where they would reach every log line and stack trace that quotes it.
        return new AssembledJdbcConnectionDetails(resolveJdbcUrl(),
                emptyToNull(environment.getProperty("spring.datasource.username")),
                emptyToNull(environment.getProperty("spring.datasource.password")));
    }

    /**
     * Resolves the JDBC URL from the deployment's own variables.
     *
     * <p>An explicitly configured {@code spring.datasource.url} wins. Nothing in this module sets one -
     * {@code application.yml} carries credentials only - so a deployment always reaches the assembly below, while a
     * hand-started run or a test that points the datasource somewhere itself stays in control of where it points.
     *
     * @return the URL {@link #buildJdbcUrl} produces for the configured host, port, database and TLS mode
     * @throws IllegalStateException when a required connection variable is missing, blank or malformed
     */
    public String resolveJdbcUrl() {
        String configured = environment.getProperty("spring.datasource.url");
        if (hasText(configured)) {
            return configured.trim();
        }
        String host = configuredValue("cashaccount.jdbc.host", "JDBC_HOST");
        String port = configuredValue("cashaccount.jdbc.port", "JDBC_PORT");
        String database = configuredValue("cashaccount.jdbc.database", "JDBC_DB");
        boolean ssl = sslEnabled(configuredValue("cashaccount.jdbc.ssl", "JDBC_SSL"));
        String certificatePath = null;
        if (ssl) {
            String pem = resolveTrustStorePem();
            if (pem != null) {
                certificatePath = stageTrustStore(pem).toString();
            }
        }
        String url = buildJdbcUrl(host, port, database, ssl, certificatePath);
        LOGGER.info("Cash ledger datasource: {} at {}:{}/{}, TLS {}", SUPPORTED_JDBC_KIND, host, port, database,
                ssl ? (certificatePath == null ? "verify-ca against the JVM trust store"
                        : "verify-ca against the injected CA certificate") : "disabled");
        return url;
    }

    private String configuredValue(String property, String variable) {
        String value = environment.getProperty(property);
        return hasText(value) ? value : environment.getProperty(variable);
    }

    private static String emptyToNull(String value) {
        return hasText(value) ? value : null;
    }

    // The TLS parameter set mirrors the datasource portfolio already runs against the same estate - ssl,
    // sslMode=verify-ca and sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory
    // [backend/portfolio/src/main/liberty/config/includes/postgres.xml:L9-L11] - with Liberty's cert_* truststore
    // import convention replaced by an sslrootcert file, because the chart delivers cert_defaultTrustStore as the
    // PEM certificate TEXT of configMap key ssl.certs
    // [infra/stocktrader-operator/helm-charts/stocktrader/templates/config.yaml:L92-L93] while pgJDBC's parameter
    // takes a path. The two TLS branches are mutually exclusive by construction: DefaultJavaSSLFactory bypasses
    // LibPQFactory, so emitting sslrootcert beside it would be dead configuration that trusts the JVM's cacerts
    // while appearing to pin the certificate the operator supplied. Liberty's sslMode attribute and pgJDBC's
    // sslmode URL parameter differ only in case and both are correct where they stand - neither is a typo to fix.
    /**
     * Assembles the PostgreSQL JDBC URL. Pure: the inputs are the whole input, so both TLS shapes are reachable
     * without an {@code Environment}, a container or a Spring context.
     *
     * @param host             the chart's {@code JDBC_HOST}
     * @param port             the chart's {@code JDBC_PORT}
     * @param database         the chart's {@code JDBC_DB}
     * @param ssl              whether {@code JDBC_SSL} asked for TLS
     * @param sslRootCertPath  path of the staged CA certificate, or {@code null}/blank when none was injected
     * @return {@code jdbc:postgresql://host:port/db}, with {@code ?ssl=true&sslmode=verify-ca} and exactly one of
     *         {@code sslrootcert} or {@code sslfactory} appended when {@code ssl} is set
     * @throws IllegalStateException when a required input is missing, blank or cannot appear in a JDBC URL
     */
    public static String buildJdbcUrl(String host, String port, String database, boolean ssl,
            String sslRootCertPath) {
        StringBuilder url = new StringBuilder(JDBC_URL_SCHEME)
                .append(requireUrlSafe(host, "JDBC_HOST", "cashaccount.jdbc.host"))
                .append(':')
                .append(requirePort(port))
                .append('/')
                .append(requireUrlSafe(database, "JDBC_DB", "cashaccount.jdbc.database"));
        if (!ssl) {
            return url.toString();
        }
        url.append('?').append(TLS_PARAMETERS);
        if (hasText(sslRootCertPath)) {
            url.append("&sslrootcert=").append(sslRootCertPath.trim());
        } else {
            url.append("&sslfactory=").append(DEFAULT_JAVA_SSL_FACTORY);
        }
        return url.toString();
    }

    // Absence means off - that and only that reproduces <variable name="JDBC_SSL" defaultValue="false"/>
    // [backend/portfolio/src/main/liberty/config/includes/postgres.xml:L2] and the chart's own database.ssl default
    // [infra/stocktrader-operator/helm-charts/stocktrader/values.yaml:L81], which is what a local run relies on. A
    // value that IS present and is neither true nor false is refused instead, because reading JDBC_SSL=ture as
    // false would drop ssl=true&sslmode=verify-ca from the URL and the ledger's own database connection would lose
    // server-certificate verification with nothing in the log to say so. That is the legacy dispatcher's
    // silent-success failure mode - an unrecognized input falling through to a code that looks like it worked
    // [backend/cash-account-cobol/COBOL/CASH00.cbl:L89-L102] - and this module is fail-closed by design (0.6.5).
    /**
     * Parses the TLS flag strictly.
     *
     * @param rawValue the configured {@code cashaccount.jdbc.ssl} (the chart's {@code JDBC_SSL})
     * @return {@code true} for a case-insensitive {@code true} after trimming, {@code false} for a case-insensitive
     *         {@code false} and for an unset or blank value
     * @throws IllegalStateException for any other value, so a typo cannot silently disable TLS
     */
    public static boolean sslEnabled(String rawValue) {
        if (!hasText(rawValue)) {
            return false;
        }
        String candidate = rawValue.trim();
        if ("true".equalsIgnoreCase(candidate)) {
            return true;
        }
        if ("false".equalsIgnoreCase(candidate)) {
            return false;
        }
        throw new IllegalStateException("Cannot assemble the cash ledger JDBC URL: JDBC_SSL (property"
                + " cashaccount.jdbc.ssl) is '" + candidate + "', which is neither 'true' nor 'false'. Set the"
                + " chart value database.ssl to one of those two, or leave the variable unset for no TLS - this"
                + " service will not read an unrecognized value as 'no TLS' and connect without"
                + " server-certificate verification.");
    }

    // cert_defaultTrustStore is not a canonical relaxed-binding name and belongs to no prefix this module owns, so
    // it is read exactly as the chart names it [.../templates/cash-account.yaml:L179-L186]: through the
    // application.yml binding first, then by that exact property name, then straight from the process environment
    // for a context that carries no system-environment property source at all.
    private String resolveTrustStorePem() {
        String pem = environment.getProperty("cashaccount.jdbc.trust-store-pem");
        if (!hasText(pem)) {
            pem = environment.getProperty(TRUST_STORE_VARIABLE);
        }
        if (!hasText(pem)) {
            pem = System.getenv(TRUST_STORE_VARIABLE);
        }
        return hasText(pem) ? pem : null;
    }

    // One small file, written once per application start. The pod requests 32Mi of ephemeral storage against a
    // 256Mi limit [.../templates/cash-account.yaml:L224-L232], so staging the certificate per connection - or on
    // every call of this method - would accumulate inside that budget until the kubelet evicted the pod. The file
    // is the trust anchor of the ledger's own database connection, which is why it is owner-only and why neither it
    // nor its content is ever logged.
    private Path stageTrustStore(String pem) {
        Path staged = trustStoreFile.get();
        if (staged != null) {
            return staged;
        }
        Path created = writePrivatePemFile(pem);
        if (trustStoreFile.compareAndSet(null, created)) {
            return created;
        }
        // Lost a race with a concurrent first call: keep the winner's file and leave nothing of ours behind.
        deleteQuietly(created);
        return trustStoreFile.get();
    }

    private static Path writePrivatePemFile(String pem) {
        try {
            Path file = createPrivateFile();
            file.toFile().deleteOnExit();
            // Verbatim bytes: the configMap block scalar delivers real newlines and pgJDBC parses the certificate
            // as it stands, so re-wrapping, re-indenting or trimming the body would either change the trust anchor
            // or leave it unparsable.
            Files.write(file, pem.getBytes(StandardCharsets.UTF_8));
            return file;
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot stage the database CA certificate injected as "
                    + TRUST_STORE_VARIABLE + ": " + ex.getClass().getSimpleName()
                    + " while writing it to a private temporary file.", ex);
        }
    }

    private static Path createPrivateFile() throws IOException {
        try {
            return Files.createTempFile("cash-account-db-ca-", ".pem",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException ex) {
            // Non-POSIX filesystem: narrow the file to its owner with what java.io offers and carry on rather than
            // refuse to start, since the alternative is no TLS pinning at all.
            Path file = Files.createTempFile("cash-account-db-ca-", ".pem");
            File handle = file.toFile();
            handle.setReadable(true, true);
            handle.setWritable(true, true);
            return file;
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            LOGGER.debug("Could not remove a redundant staged database CA certificate", ex);
        }
    }

    // A blank or malformed connection variable fails here, naming itself, rather than reaching pgJDBC as a
    // malformed URL whose driver error names nothing an operator can act on.
    private static String requireUrlSafe(String value, String variable, String property) {
        String candidate = requireConfigured(value, variable, property);
        for (int index = 0; index < candidate.length(); index++) {
            if (URL_BREAKING_CHARACTERS.indexOf(candidate.charAt(index)) >= 0) {
                throw new IllegalStateException("Cannot assemble the cash ledger JDBC URL: " + variable
                        + " (property " + property + ") is '" + candidate + "', which contains a character that"
                        + " cannot appear in that position of a JDBC URL.");
            }
        }
        return candidate;
    }

    private static String requirePort(String value) {
        String candidate = requireConfigured(value, "JDBC_PORT", "cashaccount.jdbc.port");
        try {
            int port = Integer.parseInt(candidate);
            if (port < 1 || port > 65535) {
                throw new NumberFormatException(candidate);
            }
            return Integer.toString(port);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Cannot assemble the cash ledger JDBC URL: JDBC_PORT (property"
                    + " cashaccount.jdbc.port) is '" + candidate + "', which is not a TCP port between 1 and 65535.",
                    ex);
        }
    }

    private static String requireConfigured(String value, String variable, String property) {
        if (!hasText(value)) {
            throw new IllegalStateException("Cannot assemble the cash ledger JDBC URL: " + variable + " (property "
                    + property + ") is " + describe(value) + ". The chart injects it from the release configMap's"
                    + " database.host/port/db keys; a hand-started run must supply it. There is no fallback on"
                    + " purpose: a default would point this ledger at some other database instead of stopping"
                    + " here.");
        }
        return value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String describe(String value) {
        if (value == null) {
            return "not set";
        }
        return value.isBlank() ? "blank" : "'" + value + "'";
    }

    // getDriverClassName() is left to the interface default, which derives org.postgresql.Driver from the URL - one
    // fewer place for a second dialect to be named.
    private record AssembledJdbcConnectionDetails(String jdbcUrl, String username, String password)
            implements JdbcConnectionDetails {

        @Override
        public String getJdbcUrl() {
            return jdbcUrl;
        }

        @Override
        public String getUsername() {
            return username;
        }

        @Override
        public String getPassword() {
            return password;
        }
    }
}
