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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.core.HttpHeaders;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigBuilder;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

import com.ibm.hybrid.cloud.sample.stocktrader.broker.client.CashAccountClient;

// The call shape follows the estate's only REST Client precedent,
// backend/trade-history/src/test/java/com/kyndryl/cjot/sample/stocktrader/tradehistory/test/RestClientProducers.java
// - RestClientBuilder.newBuilder().baseUri(...).build(Interface.class) - with two of its decorations deliberately
// dropped. Its class-level provider registration naming Yasson's JSON-B provider class is not carried over because
// Yasson is not a dependency here: this module supplies johnzon-jsonb plus RESTEasy's JSON-B binding provider, which
// RESTEasy discovers on its own, and naming the Yasson class would not even compile. Its CDI plumbing - an
// application-scoped bean exposing a producer method qualified for REST-client injection - is not carried over
// either, because there is no CDI container in a Spring Boot test: trade-history pulls weld-junit5 precisely because
// it runs one, and this module intentionally does not.
//
// The MicroProfile Config fallback below exists for a specific, verified reason. broker's interface carries a
// valueless @RegisterClientHeaders, which makes the implementation install DefaultClientHeadersFactoryImpl; that
// factory reads org.eclipse.microprofile.rest.client.propagateHeaders (the property broker sets at
// backend/broker/src/main/resources/META-INF/microprofile-config.properties:L1) through
// ConfigProvider.getConfig(), which throws "No ConfigProviderResolver implementation found" when the classpath
// carries the MP Config API without an implementation. org.jboss.resteasy.microprofile:microprofile-rest-client
// 3.0.1.Final declares io.smallrye.config:smallrye-config <optional>true</optional>, so the implementation does not
// arrive transitively: Liberty supplies one to trade-history, and trade-history's own pom.xml:L224-L230 additionally
// declares it at test scope - the precedent this module's pom.xml follows with smallrye-config 3.9.1, the version
// resteasy-microprofile-parent 3.0.1.Final manages. The fallback is the belt to that braces: it activates only when
// no implementation is present, so the pom stays the real fix and this class never masks its absence silently.
/** Builds broker's real {@code CashAccountClient} against a running instance of this service. */
public final class BrokerClientFactory {

    private static final Logger LOGGER = Logger.getLogger(BrokerClientFactory.class.getName());

    /**
     * Retail path prefix of the service, matching what the chart publishes to broker as
     * {@code cashAccount.url = http://{{ .Release.Name }}-cash-account-service:8080/cash-account}.
     */
    public static final String RETAIL_BASE_PATH = "/cash-account";

    // Runs exactly once, on class initialization, which is necessarily before the first client is built. The value is
    // reported in the build-failure message so a broken classpath names its own cause instead of surfacing as an
    // ExceptionInInitializerError from inside RESTEasy.
    private static final boolean FALLBACK_CONFIG_INSTALLED = installFallbackConfigIfMissing();

    private BrokerClientFactory() {
    }

    /**
     * Client for a service listening on {@code port} of this host, carrying {@code token} as its bearer credential.
     *
     * @param token compact JWS from {@link JwtTestTokens}, or {@code null} to send no {@code Authorization} header
     */
    public static CashAccountClient client(int port, String token) {
        if (port <= 0 || port > 65535) {
            // A zero port is the signature of an *IT that forgot @LocalServerPort; saying so beats a connect refusal.
            throw new IllegalArgumentException("port must be a bound TCP port in 1..65535 but was " + port
                    + "; an *IT supplies it with @LocalServerPort");
        }
        return client(URI.create("http://localhost:" + port + RETAIL_BASE_PATH), token, null);
    }

    /**
     * Client for a service reachable at {@code baseUri}, which must already carry {@link #RETAIL_BASE_PATH}: the
     * interface maps {@code @Path("/{owner}")} against the root of whatever base URI it is given.
     */
    public static CashAccountClient client(URI baseUri, String token) {
        return client(baseUri, token, null);
    }

    /**
     * Client that additionally hands every response body, as UTF-8 text, to {@code rawBodySink} before it is
     * deserialized - the hook the one raw-wire assertion needs to see {@code "balance":1234.56} as a plain decimal.
     *
     * @param rawBodySink receives each response body, or {@code null} to capture nothing
     */
    public static CashAccountClient client(URI baseUri, String token, Consumer<String> rawBodySink) {
        Objects.requireNonNull(baseUri, "baseUri");
        RestClientBuilder builder = RestClientBuilder.newBuilder().baseUri(baseUri);
        if (token != null) {
            // Integer.MAX_VALUE, not the default priority: JAX-RS runs ClientRequestFilters in ascending priority
            // order, and the MP REST Client implementation registers its own client-headers filter in the same chain.
            // Running last is what guarantees this token is the Authorization value that reaches the wire.
            builder.register(new AuthorizationHeaderFilter(token), Integer.MAX_VALUE);
        }
        if (rawBodySink != null) {
            // No priority needed: every ClientResponseFilter runs before the entity is read by the JSON-B provider.
            builder.register(new RawBodyCaptureFilter(rawBodySink));
        }
        try {
            return builder.build(CashAccountClient.class);
        } catch (RuntimeException | Error failure) {
            throw new IllegalStateException("Could not build " + CashAccountClient.class.getName() + " for " + baseUri
                    + ". The usual cause is a MicroProfile Config implementation missing from the test classpath:"
                    + " broker's interface carries a valueless @RegisterClientHeaders, and the default client-headers"
                    + " factory reads org.eclipse.microprofile.rest.client.propagateHeaders through"
                    + " ConfigProvider.getConfig(). Fix it by declaring io.smallrye.config:smallrye-config at test"
                    + " scope in backend/cash-account-modernized/pom.xml, as backend/trade-history/pom.xml does."
                    + " (in-class fallback config installed: " + FALLBACK_CONFIG_INSTALLED + ")", failure);
        }
    }

    // Guarded so the fallback is entirely inert whenever a real implementation is present: instance() succeeding means
    // smallrye-config (or any other provider) is on the classpath and must stay in charge of every lookup.
    private static boolean installFallbackConfigIfMissing() {
        try {
            ConfigProviderResolver.instance();
            return false;
        } catch (RuntimeException | Error missing) {
            ConfigProviderResolver.setInstance(new FallbackConfigProviderResolver());
            LOGGER.log(Level.WARNING, "No MicroProfile Config implementation on the test classpath; installed the"
                    + " BrokerClientFactory fallback so the REST Client's default header factory can resolve"
                    + " properties. Declare io.smallrye.config:smallrye-config at test scope to remove this"
                    + " fallback.", missing);
            return true;
        }
    }

    /** Puts the caller's bearer token on every outbound request, the way broker forwards the inbound header. */
    private static final class AuthorizationHeaderFilter implements ClientRequestFilter {

        private final String headerValue;

        private AuthorizationHeaderFilter(String token) {
            // "Bearer " + compact serialization, the exact shape
            // frontend/trader/.../Utilities.java:L110-L150 sends and broker propagates unchanged. The token arrives
            // already minted; nothing here re-signs or re-wraps it.
            this.headerValue = "Bearer " + token;
        }

        @Override
        public void filter(ClientRequestContext requestContext) {
            // putSingle rather than add: the value must replace anything an earlier filter left behind, never join it.
            requestContext.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, headerValue);
        }
    }

    /** Hands each response body to a sink as text and puts the bytes back for the entity provider to read. */
    private static final class RawBodyCaptureFilter implements ClientResponseFilter {

        private final Consumer<String> sink;

        private RawBodyCaptureFilter(Consumer<String> sink) {
            this.sink = sink;
        }

        @Override
        public void filter(ClientRequestContext requestContext, ClientResponseContext responseContext)
                throws IOException {
            if (!responseContext.hasEntity()) {
                return;
            }
            InputStream entityStream = responseContext.getEntityStream();
            if (entityStream == null) {
                return;
            }
            byte[] body = entityStream.readAllBytes();
            // Republishing is mandatory, and it happens before the sink runs: reading the stream consumed it, the
            // JSON-B provider deserializes from the same stream afterwards, and a sink that throws must not be able to
            // leave the response entity empty.
            responseContext.setEntityStream(new ByteArrayInputStream(body));
            try {
                sink.accept(new String(body, StandardCharsets.UTF_8));
            } catch (RuntimeException sinkFailure) {
                // A failing sink is the caller's defect, not a transport error: the entity is already republished, so
                // the call completes and the assertion that owns the sink reports the real problem.
                LOGGER.log(Level.WARNING, "Raw-body sink threw; the response entity was republished regardless",
                        sinkFailure);
            }
        }
    }

    /** Last-resort MP Config provider, installed only when the classpath carries the API without an implementation. */
    private static final class FallbackConfigProviderResolver extends ConfigProviderResolver {

        private final Config config = new FallbackConfig();

        @Override
        public Config getConfig() {
            return config;
        }

        @Override
        public Config getConfig(ClassLoader classLoader) {
            // One immutable process-wide view: this resolver reads system properties and the environment, neither of
            // which is classloader-scoped, so per-classloader configs would differ in nothing but identity.
            return config;
        }

        @Override
        public ConfigBuilder getBuilder() {
            throw new UnsupportedOperationException("The BrokerClientFactory fallback config is not programmatically"
                    + " buildable; declare io.smallrye.config:smallrye-config at test scope for a real builder");
        }

        @Override
        public void registerConfig(Config config, ClassLoader classLoader) {
            // Nothing to register against: getConfig ignores the classloader and always answers the single instance
            // above, so accepting the call and keeping that contract is the whole behaviour.
        }

        @Override
        public void releaseConfig(Config config) {
            // Symmetric with registerConfig: nothing was ever bound to a classloader, so nothing can be released.
        }
    }

    /** MP Config view of system properties overlaid on the process environment. */
    private static final class FallbackConfig implements Config {

        private static final ConfigSource SOURCE = new FallbackConfigSource();

        @Override
        public <T> T getValue(String propertyName, Class<T> propertyType) {
            return getOptionalValue(propertyName, propertyType).orElseThrow(() -> new NoSuchElementException(
                    "Property " + propertyName + " is set in neither the system properties nor the environment"));
        }

        @Override
        public ConfigValue getConfigValue(String propertyName) {
            // A ConfigValue with a null value is how the specification represents "absent"; returning null here would
            // break callers that dereference the result unconditionally.
            return new FallbackConfigValue(propertyName, rawValue(propertyName));
        }

        @Override
        public <T> Optional<T> getOptionalValue(String propertyName, Class<T> propertyType) {
            Objects.requireNonNull(propertyName, "propertyName");
            Objects.requireNonNull(propertyType, "propertyType");
            String raw = rawValue(propertyName);
            // An empty value counts as absent, which is what makes an unset
            // org.eclipse.microprofile.rest.client.propagateHeaders propagate nothing - the correct outcome here,
            // since a Spring Boot test has no inbound JAX-RS request whose headers could be forwarded.
            return raw == null || raw.isEmpty() ? Optional.empty() : Optional.of(convert(raw, propertyType));
        }

        @Override
        public Iterable<String> getPropertyNames() {
            return propertyNames();
        }

        @Override
        public Iterable<ConfigSource> getConfigSources() {
            return List.of(SOURCE);
        }

        @Override
        public <T> Optional<Converter<T>> getConverter(Class<T> forType) {
            if (!isConvertible(forType)) {
                return Optional.empty();
            }
            Converter<T> converter = raw -> convert(raw, forType);
            return Optional.of(converter);
        }

        @Override
        public <T> T unwrap(Class<T> type) {
            Objects.requireNonNull(type, "type");
            if (type.isInstance(this)) {
                return type.cast(this);
            }
            throw new IllegalArgumentException("The BrokerClientFactory fallback config cannot be unwrapped as "
                    + type.getName());
        }
    }

    /** Single resolved property, as the MP Config {@code ConfigValue} contract describes it. */
    private record FallbackConfigValue(String name, String value) implements ConfigValue {

        private static final String SOURCE_NAME = "BrokerClientFactory fallback (system properties, then environment)";

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getValue() {
            return value;
        }

        @Override
        public String getRawValue() {
            // Identical to getValue: this source performs no expression expansion, so there is no earlier form.
            return value;
        }

        @Override
        public String getSourceName() {
            return SOURCE_NAME;
        }

        @Override
        public int getSourceOrdinal() {
            return ConfigSource.DEFAULT_ORDINAL;
        }
    }

    /** The one source behind {@link FallbackConfig}, so {@code getConfigSources()} describes where values came from. */
    private static final class FallbackConfigSource implements ConfigSource {

        @Override
        public Set<String> getPropertyNames() {
            return propertyNames();
        }

        @Override
        public String getValue(String propertyName) {
            return rawValue(propertyName);
        }

        @Override
        public String getName() {
            return FallbackConfigValue.SOURCE_NAME;
        }
    }

    private static String rawValue(String propertyName) {
        String property = System.getProperty(propertyName);
        if (property != null) {
            return property;
        }
        String environment = System.getenv(propertyName);
        if (environment != null) {
            return environment;
        }
        // The specification's environment-variable rule: a property named foo.bar must also be found as FOO_BAR,
        // because most environments cannot express a dot in a variable name.
        return System.getenv(propertyName.replaceAll("[^A-Za-z0-9_]", "_").toUpperCase(Locale.ROOT));
    }

    private static Set<String> propertyNames() {
        Set<String> names = new LinkedHashSet<>(System.getProperties().stringPropertyNames());
        names.addAll(System.getenv().keySet());
        return Collections.unmodifiableSet(names);
    }

    private static boolean isConvertible(Class<?> propertyType) {
        return propertyType == String.class
                || propertyType == Boolean.class || propertyType == boolean.class
                || propertyType == Integer.class || propertyType == int.class
                || propertyType == Long.class || propertyType == long.class;
    }

    @SuppressWarnings("unchecked")
    private static <T> T convert(String raw, Class<T> propertyType) {
        Object converted;
        if (propertyType == String.class) {
            converted = raw;
        } else if (propertyType == Boolean.class || propertyType == boolean.class) {
            // The specification's truth set, not Boolean.parseBoolean: "yes", "y", "on" and "1" are true as well.
            String candidate = raw.trim();
            converted = "true".equalsIgnoreCase(candidate) || "yes".equalsIgnoreCase(candidate)
                    || "y".equalsIgnoreCase(candidate) || "on".equalsIgnoreCase(candidate)
                    || "1".equals(candidate);
        } else if (propertyType == Integer.class || propertyType == int.class) {
            converted = Integer.valueOf(raw.trim());
        } else if (propertyType == Long.class || propertyType == long.class) {
            converted = Long.valueOf(raw.trim());
        } else {
            throw new IllegalArgumentException("The BrokerClientFactory fallback config converts String, Boolean,"
                    + " Integer and Long only, so " + propertyType.getName() + " cannot be resolved; declare"
                    + " io.smallrye.config:smallrye-config at test scope for the full converter set");
        }
        // Unchecked by construction: every branch above produced an instance of propertyType's wrapper.
        return (T) converted;
    }
}
