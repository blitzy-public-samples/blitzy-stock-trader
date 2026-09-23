package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/** Fail-closed datasource configuration: PostgreSQL or no start-up, plus the JDBC URL the chart's variables imply. */
@Configuration
public class DataSourceGuardConfig {

    // PostgreSQL only, deliberately: the chart's database.kind is one release-global value shared with
    // backend/portfolio [infra/stocktrader-operator/helm-charts/stocktrader/values.yaml:L74-L81] and is still db2,
    // while schema/cash-account-schema.sql, its ledger_entry_immutable trigger and every *IT are written for a
    // single dialect. Moving the release to PostgreSQL is a values change signed off as Step 0 of
    // docs/operational-runbook.md, never a chart template edit and never something this code adapts to.
    public static final String SUPPORTED_JDBC_KIND = "postgres";

    /** pgJDBC factory that leaves server-certificate trust to the JVM's own {@code cacerts}. */
    public static final String DEFAULT_JAVA_SSL_FACTORY = "org.postgresql.ssl.DefaultJavaSSLFactory";

    private static final String JDBC_URL_SCHEME = "jdbc:postgresql://";
    private static final String TLS_PARAMETERS = "ssl=true&sslmode=verify-ca";
    private static final String URL_BREAKING_CHARACTERS = "/?&# \t\r\n";
    private static final String TRUST_STORE_VARIABLE = "cert_defaultTrustStore";
    private static final String DEFAULT_POSTGRES_PORT = "5432";

    // The driver's own two bounds on the hop, which the pool cannot set: connectTimeout caps one TCP-and-TLS
    // handshake and socketTimeout caps a read on an established connection. Left unset, pgJDBC gives the first
    // 10 s and the second none at all - so a statement in flight when the peer vanishes waits for ever, which no
    // Hikari setting reaches, because the pool's connection-timeout governs OBTAINING a connection rather than
    // using one. Defaults here match application.yml, which stays the declared source of truth for both values.
    private static final String CONNECT_TIMEOUT_PARAMETER = "connectTimeout";
    private static final String SOCKET_TIMEOUT_PARAMETER = "socketTimeout";
    private static final String CONNECT_TIMEOUT_PROPERTY = "cashaccount.jdbc.connect-timeout";
    private static final String SOCKET_TIMEOUT_PROPERTY = "cashaccount.jdbc.socket-timeout";
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration DEFAULT_SOCKET_TIMEOUT = Duration.ofSeconds(30);

    // pgJDBC's own spellings, matched case-insensitively and listed in the rejection message; see keepableParameters
    // for why this is an allowlist rather than a list of parameters to strip.
    private static final Set<String> KEEPABLE_URL_PARAMETERS = caseInsensitiveSet("ApplicationName",
            "assumeMinServerVersion", "connectTimeout", "currentSchema", "defaultRowFetchSize", "loggerFile",
            "loggerLevel", "loginTimeout", "reWriteBatchedInserts", "socketTimeout", "tcpKeepAlive");
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

    // The condition is load-bearing: under @SpringBootTest the Testcontainers @ServiceConnection of
    // support/PostgresTestSupport contributes its own JdbcConnectionDetails, and without it ours would win and
    // every *IT would dial the chart's JDBC_HOST instead of its container. Contributing connection details rather
    // than a DataSource also leaves spring.datasource.hikari.* binding intact, which is what lets the runbook's
    // rehearsal pass --spring.datasource.hikari.schema=cash_account_rehearsal.
    @Bean
    @ConditionalOnMissingBean(JdbcConnectionDetails.class)
    JdbcConnectionDetails cashAccountJdbcConnectionDetails() {
        // Credentials are relayed from spring.datasource.username/password, which application.yml binds from the
        // chart secret's database.id/database.password
        // [infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L110-L119]; they are
        // never folded into the URL, where they would reach every log line and stack trace that quotes it.
        return new AssembledJdbcConnectionDetails(resolveJdbcUrl(),
                emptyToNull(environment.getProperty("spring.datasource.username")),
                emptyToNull(environment.getProperty("spring.datasource.password")));
    }

    /**
     * Resolves the JDBC URL of the cash ledger's own database.
     *
     * <p>The chart's {@code JDBC_HOST}, {@code JDBC_PORT} and {@code JDBC_DB} are the source of the target, and
     * {@code JDBC_SSL} with {@code cert_defaultTrustStore} decide the TLS parameters in every case. A
     * {@code spring.datasource.url} arriving from anywhere else is not honoured as a connection: it is refused while
     * those chart variables are present and whenever TLS is demanded, and otherwise reduced to a host, a port, a
     * database and a set of accepted parameters by {@link #normalizedConfiguredTarget}.
     *
     * <p>Every resolved URL also carries the driver's {@code connectTimeout} and {@code socketTimeout}, from
     * {@code cashaccount.jdbc.connect-timeout} and {@code cashaccount.jdbc.socket-timeout}, unless a supplied URL
     * names that parameter itself.</p>
     *
     * @return the URL {@link #buildJdbcUrl} produces for the resolved host, port, database and TLS mode, with the
     *         driver's connect and read bounds appended
     * @throws IllegalStateException when a required connection variable is missing, blank or malformed, when a
     *         configured {@code spring.datasource.url} would change the dialect, the credentials or the TLS mode,
     *         and when either timeout is not a positive duration of whole seconds
     */
    public String resolveJdbcUrl() {
        boolean ssl = sslEnabled(configuredValue("cashaccount.jdbc.ssl", "JDBC_SSL"));
        String configured = environment.getProperty("spring.datasource.url");
        ConnectionTarget target = hasText(configured)
                ? normalizedConfiguredTarget(configured.trim(), ssl)
                : chartTarget();
        // Staged after the target is settled, so a refused URL never leaves a certificate file behind.
        String certificatePath = ssl ? stagedTrustStorePath() : null;
        List<String> driverBounds = driverTimeoutParameters(target.parameters());
        String url = target.toUrl(ssl, certificatePath, driverBounds);
        // The bounds are logged with the target because they are the difference between a database outage that
        // surfaces as 503 DATASTORE_UNAVAILABLE in seconds and one that parks a request thread, and the effective
        // pair is otherwise invisible: either value may have come from a deployment override or from the URL.
        LOGGER.info("Cash ledger datasource: {} at {}:{}/{} from {}, TLS {}, driver bounds set here: {}",
                SUPPORTED_JDBC_KIND, target.host(), target.port(), target.database(), target.origin(),
                ssl ? (certificatePath == null ? "verify-ca against the JVM trust store"
                        : "verify-ca against the injected CA certificate") : "disabled",
                driverBounds.isEmpty() ? "none, both were supplied on the URL" : String.join(", ", driverBounds));
        return url;
    }

    // Skipped per parameter rather than wholesale: a supplied URL that names one of the two has an operator behind
    // it, and appending ours as well would hand pgJDBC the same parameter twice - the driver keeps the last
    // occurrence, so the value an operator can read in the URL would not be the value in force.
    private List<String> driverTimeoutParameters(List<String> suppliedParameters) {
        List<String> bounds = new ArrayList<>(2);
        addTimeoutUnlessSupplied(bounds, suppliedParameters, CONNECT_TIMEOUT_PARAMETER, CONNECT_TIMEOUT_PROPERTY,
                DEFAULT_CONNECT_TIMEOUT);
        addTimeoutUnlessSupplied(bounds, suppliedParameters, SOCKET_TIMEOUT_PARAMETER, SOCKET_TIMEOUT_PROPERTY,
                DEFAULT_SOCKET_TIMEOUT);
        return List.copyOf(bounds);
    }

    private void addTimeoutUnlessSupplied(List<String> bounds, List<String> suppliedParameters, String parameter,
            String property, Duration fallback) {
        for (String supplied : suppliedParameters) {
            int assignment = supplied.indexOf('=');
            String name = (assignment < 0) ? supplied : supplied.substring(0, assignment);
            if (parameter.equalsIgnoreCase(name)) {
                return;
            }
        }
        bounds.add(parameter + "=" + wholeSeconds(property, configuredDuration(property, fallback)));
    }

    // Binder, never a @Value placeholder: a placeholder's resolved text is handed on to Spring's expression
    // resolver, so a timeout written as #{...} would execute while this configuration was being created. Binder
    // resolves ${...} and converts, evaluating nothing. A non-configurable Environment exposes no property
    // sources, so it yields the documented default exactly as an unset key does.
    private Duration configuredDuration(String property, Duration fallback) {
        if (!(environment instanceof ConfigurableEnvironment)) {
            return fallback;
        }
        return Binder.get(environment).bind(property, Bindable.of(Duration.class)).orElse(fallback);
    }

    // Refused rather than rounded. pgJDBC takes both parameters as an int of SECONDS and reads 0 as "no timeout at
    // all", so PT0.5S would arrive as the unbounded default these two settings exist to remove - the same silent
    // downgrade JDBC_SSL=ture would be if sslEnabled read it as false.
    private static long wholeSeconds(String property, Duration value) {
        if (value == null || value.isZero() || value.isNegative() || value.toMillis() % 1000 != 0) {
            throw new IllegalStateException("Cannot assemble the cash ledger JDBC URL: " + property + " is "
                    + (value == null ? "not set" : value.toString()) + ", and it must be a positive ISO-8601"
                    + " duration of whole seconds (for example PT2S). The driver takes this bound as an integer"
                    + " number of seconds and treats 0 as no timeout at all, so a fraction of a second would be"
                    + " truncated into exactly the unbounded wait this setting exists to remove.");
        }
        return value.toSeconds();
    }

    // Assembled here rather than by a placeholder in application.yml because no static one expresses this
    // branch: JDBC_SSL=true appends either an sslrootcert file or an sslfactory class and never both, and the
    // certificate it points at has to be staged before the first connection. The inputs are the variables the
    // chart already injects [.../templates/cash-account.yaml:L84-L119], so cutover needs no template edit.
    private ConnectionTarget chartTarget() {
        return new ConnectionTarget(configuredValue("cashaccount.jdbc.host", "JDBC_HOST"),
                configuredValue("cashaccount.jdbc.port", "JDBC_PORT"),
                configuredValue("cashaccount.jdbc.database", "JDBC_DB"),
                List.of(), "the chart's JDBC_HOST/JDBC_PORT/JDBC_DB");
    }

    private String stagedTrustStorePath() {
        String pem = resolveTrustStorePem();
        return (pem == null) ? null : stageTrustStore(pem).toString();
    }

    // Why a supplied spring.datasource.url is never honoured as written: it is the one input that carries a whole
    // connection - dialect, host, credentials and TLS mode - so returning it verbatim would let SPRING_DATASOURCE_URL
    // in the pod's environment, or a stray property in any source Spring reads, replace ssl=true&sslmode=verify-ca
    // with sslmode=disable, swap LibPQFactory for NonValidatingFactory, fold user and password into the URL where
    // every log line and stack trace that quotes it would carry them, or point this ledger at another server
    // entirely. The JDBC_KIND guard above cannot see any of that: it inspects the dialect and nothing else.
    //
    // The chart injects JDBC_HOST and JDBC_DB from non-optional configMap keys
    // [infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L89-L103], so in a deployment
    // the first refusal below is the branch that fires and the URL cannot repoint anything; the second refuses it
    // wherever TLS is demanded at all. What remains is the runs that carry neither - a hand-started process, and the
    // second application context of audit/LedgerImmutabilityIT - which legitimately name their own database and
    // there decide nothing else: host, port and database are taken, and every other part of the URL has to be
    // absent or explicitly accepted rather than merely harmless.
    //
    // No message below quotes the URL or a parameter value, because either may be the password it was refused for.
    private ConnectionTarget normalizedConfiguredTarget(String configured, boolean ssl) {
        String chartHost = configuredValue("cashaccount.jdbc.host", "JDBC_HOST");
        String chartDatabase = configuredValue("cashaccount.jdbc.database", "JDBC_DB");
        if (hasText(chartHost) || hasText(chartDatabase)) {
            throw new IllegalStateException("Refusing to start: spring.datasource.url is set while this deployment's"
                    + " own connection variables are present (JDBC_HOST / cashaccount.jdbc.host and JDBC_DB /"
                    + " cashaccount.jdbc.database). Two sources cannot both say where the cash ledger lives, and the"
                    + " chart's variables are the only accepted one - they are what makes the URL PostgreSQL, and"
                    + " what keeps ssl=true&sslmode=verify-ca on it. Remove spring.datasource.url, and change"
                    + " database.host/port/db in the release values instead.");
        }
        // sslmode=verify-ca is the estate's parity setting (0.6.1), and it validates the server's certificate CHAIN
        // without checking that the certificate belongs to the host being dialled - that is what verify-full adds.
        // So under TLS the host is itself a trust decision: a URL naming its own host could reach a different server
        // presenting any certificate the same CA ever signed, and the connection would still verify. Where TLS is
        // demanded, the host therefore comes from the chart's variables alone.
        if (ssl) {
            throw new IllegalStateException("Refusing to start: spring.datasource.url is set while JDBC_SSL (property"
                    + " cashaccount.jdbc.ssl) asks for TLS. sslmode=verify-ca verifies the server's certificate chain"
                    + " but not its hostname, so a URL that names its own host could reach a different server holding"
                    + " any certificate the same CA signed. Remove spring.datasource.url and supply JDBC_HOST,"
                    + " JDBC_PORT and JDBC_DB, which is what the chart injects.");
        }
        return parseConfiguredUrl(configured);
    }

    private static ConnectionTarget parseConfiguredUrl(String configured) {
        for (int index = 0; index < configured.length(); index++) {
            char character = configured.charAt(index);
            if (character <= ' ' || character == '#') {
                throw rejectConfiguredUrl("it contains a space, a control character or '#'");
            }
        }
        if (!configured.regionMatches(true, 0, JDBC_URL_SCHEME, 0, JDBC_URL_SCHEME.length())) {
            throw rejectConfiguredUrl("it does not begin with '" + JDBC_URL_SCHEME + "'");
        }
        String remainder = configured.substring(JDBC_URL_SCHEME.length());
        int queryStart = remainder.indexOf('?');
        String addressed = (queryStart < 0) ? remainder : remainder.substring(0, queryStart);
        int databaseStart = addressed.indexOf('/');
        if (databaseStart <= 0 || databaseStart == addressed.length() - 1
                || addressed.indexOf('/', databaseStart + 1) >= 0) {
            throw rejectConfiguredUrl("it does not have the shape '" + JDBC_URL_SCHEME + "<host>[:<port>]/<database>'");
        }
        String authority = addressed.substring(0, databaseStart);
        String database = addressed.substring(databaseStart + 1);
        if (authority.indexOf('@') >= 0) {
            throw rejectConfiguredUrl("its host carries user information, so the URL embeds credentials; supply them"
                    + " as JDBC_ID and JDBC_PASSWORD, which reach spring.datasource.username/password and no URL");
        }
        if (authority.indexOf(',') >= 0) {
            throw rejectConfiguredUrl("it names more than one host, and this service connects to exactly the one"
                    + " database its schema and ledger live in");
        }
        return new ConnectionTarget(hostOf(authority), portOf(authority), database,
                keepableParameters(queryStart < 0 ? "" : remainder.substring(queryStart + 1)),
                "the configured spring.datasource.url, normalized");
    }

    private static String hostOf(String authority) {
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) {
                throw rejectConfiguredUrl("its bracketed host address is not closed");
            }
            return authority.substring(0, close + 1);
        }
        int colon = authority.indexOf(':');
        return (colon < 0) ? authority : authority.substring(0, colon);
    }

    // An absent port resolves to PostgreSQL's own listening port, which is both pgJDBC's default for a URL that omits
    // it and application.yml's default for JDBC_PORT, so normalizing it away changes nothing about where a run
    // connects.
    private static String portOf(String authority) {
        String remainder = authority.startsWith("[")
                ? authority.substring(authority.indexOf(']') + 1)
                : (authority.indexOf(':') < 0 ? "" : authority.substring(authority.indexOf(':')));
        if (remainder.isEmpty()) {
            return DEFAULT_POSTGRES_PORT;
        }
        if (remainder.charAt(0) != ':') {
            throw rejectConfiguredUrl("its host and port are not separated by a single ':'");
        }
        String port = remainder.substring(1);
        if (port.isEmpty()) {
            throw rejectConfiguredUrl("it carries a ':' with no port after the host");
        }
        return port;
    }

    // An allowlist rather than a list of parameters to strip, because pgJDBC's parameter set already contains
    // sslmode, sslfactory, sslrootcert, socketFactory, gssEncMode, targetServerType, loadBalanceHosts and options,
    // and grows: a denylist is a check that silently stops being complete, while an unrecognized name refused by
    // name costs one documented entry to admit. The parameters kept here decide logging, timeouts, fetch batching
    // and the search path - never the credentials, the transport, the certificate trust or which server is reached.
    private static List<String> keepableParameters(String query) {
        if (query.isEmpty()) {
            return List.of();
        }
        List<String> kept = new ArrayList<>();
        for (String parameter : query.split("&")) {
            if (parameter.isEmpty()) {
                continue;
            }
            int assignment = parameter.indexOf('=');
            String name = (assignment < 0) ? parameter : parameter.substring(0, assignment);
            if ("user".equalsIgnoreCase(name) || "password".equalsIgnoreCase(name)) {
                throw rejectConfiguredUrl("it carries the connection parameter '" + name + "', so the URL embeds"
                        + " credentials; supply them as JDBC_ID and JDBC_PASSWORD, which reach"
                        + " spring.datasource.username/password and no URL");
            }
            if (name.regionMatches(true, 0, "ssl", 0, 3)) {
                throw rejectConfiguredUrl("it sets the TLS parameter '" + name + "', which only JDBC_SSL and"
                        + " cert_defaultTrustStore may decide: this service always connects with"
                        + " ssl=true&sslmode=verify-ca once JDBC_SSL is true, against the injected CA certificate"
                        + " when one is supplied and against the JVM trust store otherwise");
            }
            if (!KEEPABLE_URL_PARAMETERS.contains(name)) {
                throw rejectConfiguredUrl("it carries the connection parameter '" + name + "', which is not one of"
                        + " the parameters this service accepts on a supplied URL ("
                        + String.join(", ", KEEPABLE_URL_PARAMETERS) + ")");
            }
            kept.add(parameter);
        }
        return List.copyOf(kept);
    }

    private static Set<String> caseInsensitiveSet(String... values) {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(Arrays.asList(values));
        return Collections.unmodifiableSet(names);
    }

    private static IllegalStateException rejectConfiguredUrl(String reason) {
        return new IllegalStateException("Refusing the configured spring.datasource.url: " + reason + ". This service"
                + " assembles its own URL - jdbc:postgresql://<host>:<port>/<database> from JDBC_HOST, JDBC_PORT and"
                + " JDBC_DB, with the TLS parameters JDBC_SSL and cert_defaultTrustStore imply - so a supplied URL is"
                + " read as a host, a port, a database and a set of accepted parameters only, never as a way to"
                + " change the dialect, the credentials or the TLS mode.");
    }

    // Where a run connects and with which non-security parameters; the TLS parameters are never part of it, so they
    // cannot arrive with the target.
    private record ConnectionTarget(String host, String port, String database, List<String> parameters,
            String origin) {

        // The driver bounds trail the accepted parameters, so a URL an operator supplied still reads as written
        // and what this service added to it is visible at the end.
        String toUrl(boolean ssl, String sslRootCertPath, List<String> driverBounds) {
            String url = buildJdbcUrl(host, port, database, ssl, sslRootCertPath);
            List<String> appended = new ArrayList<>(parameters.size() + driverBounds.size());
            appended.addAll(parameters);
            appended.addAll(driverBounds);
            if (appended.isEmpty()) {
                return url;
            }
            return url + (url.indexOf('?') < 0 ? '?' : '&') + String.join("&", appended);
        }
    }

    private String configuredValue(String property, String variable) {
        String value = environment.getProperty(property);
        return hasText(value) ? value : environment.getProperty(variable);
    }

    private static String emptyToNull(String value) {
        return hasText(value) ? value : null;
    }

    // The TLS parameter set mirrors the datasource portfolio already runs against this estate
    // [backend/portfolio/src/main/liberty/config/includes/postgres.xml:L9-L11], with Liberty's cert_* truststore
    // import replaced by an sslrootcert file because the chart delivers cert_defaultTrustStore as PEM TEXT
    // [infra/stocktrader-operator/helm-charts/stocktrader/templates/config.yaml:L92-L93] while pgJDBC's parameter
    // takes a path. The two branches are mutually exclusive by construction - DefaultJavaSSLFactory bypasses
    // LibPQFactory, so an sslrootcert beside it would trust the JVM cacerts while appearing to pin the operator's
    // certificate. Liberty's sslMode attribute and pgJDBC's sslmode parameter differ only in case; neither is a typo.
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

    // Absence means off, reproducing portfolio's <variable name="JDBC_SSL" defaultValue="false"/>
    // [backend/portfolio/src/main/liberty/config/includes/postgres.xml:L2] and the chart's database.ssl default
    // [infra/stocktrader-operator/helm-charts/stocktrader/values.yaml:L81]. A present value that is neither true
    // nor false is refused instead: reading JDBC_SSL=ture as false would drop ssl=true&sslmode=verify-ca and lose
    // server-certificate verification silently, which is the legacy dispatcher's fall-through failure mode
    // [backend/cash-account-cobol/COBOL/CASH00.cbl:L89-L102] that this module is fail-closed against (AAP 0.6.5).
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

    // One small file, written once per application start: the pod requests 32Mi of ephemeral storage against a
    // 256Mi limit [.../templates/cash-account.yaml:L224-L232], so staging per connection would accumulate inside
    // that budget until the kubelet evicted the pod. It is the trust anchor of the ledger's own database
    // connection, hence owner-only, and neither it nor its content is ever logged.
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
