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

import java.util.List;
import java.util.Locale;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.ApiErrorAccessDeniedHandler;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.ApiErrorAuthenticationEntryPoint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/*
 * The authorization rules below are broker's own, re-expressed: a GET is allowed to StockViewer and
 * StockTrader, a POST, PUT or DELETE to StockTrader alone
 * [backend/broker/src/main/webapp/WEB-INF/web.xml:L19-L32, L34-L51]. Broker states them as two
 * <security-constraint> blocks over url-pattern /* because it takes its subject as a path parameter, which
 * is exactly this service's shape too - so the split here is by verb over /cash-account/{owner}, not by
 * resource, and no path-by-path table is needed or wanted.
 *
 * The role NAMES carried in a token are not enough on their own to reproduce broker's effective behaviour,
 * because the siblings bind the StockTrader role to the ALL_AUTHENTICATED_USERS special subject
 * <security-role id="StockTrader"><special-subject type="ALL_AUTHENTICATED_USERS" id="IBMid"/></security-role>
 * [backend/broker/src/main/liberty/config/server.xml:L56-L60]: through broker today, ANY authenticated
 * caller may write. config/JwtDecoderConfig therefore grants ROLE_StockTrader to every authenticated
 * principal while cashaccount.security.all-authenticated-hold-stocktrader is true - the deployed default -
 * so cutover changes no caller's effective permissions and the GET-versus-write split written here is
 * latent rather than absent. Setting the property false is the supported, documented strict mode in which
 * only the token's groups claim decides. That is the whole reason the rules below appear once, as the real
 * role split, and never as a second conditional matcher set keyed on the grant: the grant belongs to the
 * authority conversion, and duplicating it as authorization rules would give one behaviour two switches.
 */
/** The service's single security filter chain: broker's role split, fail-closed, rendered as ApiError. */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** The configuration key, named in every failure message so the fix is unambiguous. */
    public static final String AUTH_TYPE_PROPERTY = "cashaccount.security.auth-type";

    /**
     * The authentication modes this service supports, in the order the estate documents them.
     *
     * <p>They are not an invention: the siblings select their security configuration by including
     * {@code includes/${AUTH_TYPE}.xml} [backend/broker/src/main/liberty/config/server.xml:L44], and the
     * include files that exist are exactly these four. There is no fifth mode anywhere in the estate.
     */
    public static final List<String> SUPPORTED_AUTH_TYPES = List.of("basic", "ldap", "oidc", "none");

    /** The mode in which this service authenticates nothing at all. */
    public static final String MODE_NONE = "none";

    private static final String ROLE_STOCK_TRADER = "StockTrader";
    private static final String ROLE_STOCK_VIEWER = "StockViewer";

    private static final String ACTUATOR_SPACE = "/actuator/**";
    private static final String METRICS_PATH = "/metrics";

    private static final String INSTITUTIONAL_SPACE = "/cash-account/institutional/**";

    // These four are the /cash-account/{owner} rules, written with a single-segment wildcard instead of the URI
    // template. Spring Security resolves a pattern string to an MVC pattern matcher only when it can see a
    // DispatcherServlet registration in the servlet context and falls back to an Ant matcher otherwise - a
    // condition that differs between a deployed pod, an embedded-server test and a mock-servlet test. An Ant
    // matcher reads "{owner}" as five literal characters, so the template would quietly stop covering
    // /cash-account/JOHN in the fallback case and the role split would degrade to rule (5) authenticated().
    // A single '*' means exactly one path segment to both implementations, so these rules decide the same way
    // wherever the chain is built. The owner value itself is never read here; only the shape of the path is.
    private static final String RETAIL_ACCOUNT_PATH = "/cash-account/*";
    private static final String RETAIL_DEBIT_PATH = "/cash-account/*/debit";
    private static final String RETAIL_CREDIT_PATH = "/cash-account/*/credit";
    private static final String SERVICE_SPACE = "/cash-account/**";

    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * Builds the one filter chain that covers every request this service can receive.
     *
     * @param http the chain under construction
     * @param properties source of {@code cashaccount.security.auth-type}
     * @param authenticationEntryPoint renderer for a 401, from the {@code error} package
     * @param accessDeniedHandler renderer for a 403, from the {@code error} package
     * @param jwtDecoderProvider the decoder config/JwtDecoderConfig publishes, absent in {@code none} mode
     * @param jwtAuthenticationConverterProvider the {@code groups}-to-authorities converter
     * @return the built chain
     * @throws Exception as {@link HttpSecurity#build()} declares
     * @throws IllegalStateException when the configured auth type is not one of
     *         {@link #SUPPORTED_AUTH_TYPES}, or when an authenticating mode has no decoder to verify with
     */
    @Bean
    public SecurityFilterChain cashAccountSecurityFilterChain(
            HttpSecurity http,
            CashAccountProperties properties,
            ApiErrorAuthenticationEntryPoint authenticationEntryPoint,
            ApiErrorAccessDeniedHandler accessDeniedHandler,
            ObjectProvider<JwtDecoder> jwtDecoderProvider,
            ObjectProvider<JwtAuthenticationConverter> jwtAuthenticationConverterProvider) throws Exception {

        // Read from the bound properties on every chain build, never cached in a static: security/RoleEnforcementIT
        // boots the default-grant and strict-grant matrices as two Spring contexts in one JVM, and a class-level
        // cache would leak the first context's configuration into the second.
        String authType = properties.getSecurity().getAuthType();

        // Validated before the decoder provider is touched, so an unsupported value fails with the message below -
        // naming this property, its chart variable and every accepted value - rather than with whatever the first
        // bean that happened to be instantiated said about it.
        boolean authenticating = authenticating(authType);

        http
                // Stateless and CSRF-free because this is a bearer-token JSON API: broker presents the caller's
                // propagated Authorization header on every request
                // [backend/broker/src/main/resources/META-INF/microprofile-config.properties:L1], so a session
                // would be state no caller uses and a synchronizer token would be a value no caller can send -
                // CSRF protection left on would reject every POST, PUT and DELETE that CashAccountClient issues
                // while defending against a cookie-borne attack this service has no cookie to suffer.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // No second credential path may exist beside the propagated JWT. Basic authentication in
                // particular is what the siblings fall back to when they disable mpJwt
                // [backend/broker/src/main/liberty/config/includes/none.xml:L61-L63]; here it is off in every mode.
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)

                // Spring Security decides both of these inside the filter chain, before any controller is
                // entered, so error/ApiExceptionHandler's @RestControllerAdvice never sees a 401 or a 403.
                // Registering the error package's two renderers here is therefore the only way those statuses
                // arrive in the same ApiError shape as every other error this service returns; without them a
                // caller would parse one structure for an authentication failure and another for everything else.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))

                .authorizeHttpRequests(registry -> authorize(registry, authenticating));

        if (authenticating) {
            JwtDecoder jwtDecoder = requiredDecoder(jwtDecoderProvider, authType);
            JwtAuthenticationConverter jwtAuthenticationConverter =
                    requiredConverter(jwtAuthenticationConverterProvider, authType);

            http.oauth2ResourceServer(resourceServer -> resourceServer
                    // Both renderers again, and deliberately: the exceptionHandling wiring above covers a request
                    // that carried NO credential, but a request carrying a malformed, expired or wrongly signed
                    // token is failed by BearerTokenAuthenticationFilter, which answers through the resource
                    // server's own entry point - by default a bodiless Bearer challenge. Pointing it at the same
                    // two beans keeps one payload shape across all three rejection routes.
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler)
                    .jwt(jwt -> jwt
                            .decoder(jwtDecoder)
                            .jwtAuthenticationConverter(jwtAuthenticationConverter)));
        }

        // Mode only. No issuer, audience, key location, JWKS URL or any part of a credential is logged here or
        // anywhere in this file; the line exists so an operator reading pod start-up can tell at a glance whether
        // the pod is authenticating, which the alternative - inferring it from the absence of 401s - cannot.
        LOGGER.info("Cash-account security filter chain built for {} '{}' ({}).", AUTH_TYPE_PROPERTY, authType,
                authenticating ? "tokens are validated" : "authentication disabled - unfit for a shared environment");

        return http.build();
    }

    /*
     * Rule order is the behaviour, not a formatting choice: Spring Security applies the FIRST matching rule, so a
     * broader pattern placed above a narrower one silently becomes the only one that ever decides.
     *
     * Two places in this sequence are load-bearing beyond the obvious top-to-bottom reading.
     *
     * The institutional space is claimed before the retail matchers. The retail rules cover exactly one path
     * segment after /cash-account, so /cash-account/institutional would otherwise be read as an owner named
     * "institutional" and a GET there would be decided by the read-only rule instead of the institutional one.
     * Claiming it first makes the reading of a path independent of how many segments a caller appended.
     *
     * The /cash-account/** rule that follows is authenticated(), never denyAll(). An authenticated caller asking
     * for a path or a verb this service does not implement MUST reach Spring MVC, because that is what produces
     * the 404 UNSUPPORTED_PATH or 405 UNSUPPORTED_METHOD ApiError; only an unauthenticated one is refused here,
     * with a 401. That pairing is the deliberate improvement on the legacy dispatcher, whose EVALUATE WS-REQ
     * carried no WHEN OTHER: an unrecognized request code executed no SQL, left SQLCODE untouched so the return
     * field looked like success, and echoed the caller's own amount back as the balance
     * [backend/cash-account-cobol/COBOL/CASH00.cbl:L89-L108]. Here an unsupported operation is named as such.
     *
     * anyRequest().denyAll() closes everything outside this service's path space, in every mode including none.
     * It is the analogue of broker's <deny-uncovered-http-methods /> [.../WEB-INF/web.xml:L33]: whatever is added
     * to this application later is denied until a rule above admits it, rather than inheriting whatever the
     * framework's default happens to be.
     */
    private static void authorize(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry,
            boolean authenticating) {

        // Kubernetes presents no credential. The chart's startupProbe, readinessProbe and livenessProbe GET
        // /actuator/startup, /actuator/health/readiness and /actuator/health/liveness on port 8080
        // [infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L203-L222], so an
        // authenticated probe path means the pod never passes its startup probe and the deployment never rolls.
        // /metrics is open for the same reason: the scrape annotation carries prometheus.io/scrape and
        // prometheus.io/port but no path [.../templates/cash-account.yaml:L51-L54], which is why
        // config/MetricsScrapeController serves the registry at the root path a path-less scraper requests.
        // Nothing sensitive is exposed by that decision - application.yml sets health show-details to never and
        // exposes only health, startup and prometheus.
        registry.requestMatchers(ACTUATOR_SPACE, METRICS_PATH).permitAll();

        if (authenticating) {
            registry
                    .requestMatchers(INSTITUTIONAL_SPACE).hasRole(ROLE_STOCK_TRADER)
                    .requestMatchers(HttpMethod.GET, RETAIL_ACCOUNT_PATH)
                            .hasAnyRole(ROLE_STOCK_VIEWER, ROLE_STOCK_TRADER)
                    .requestMatchers(HttpMethod.POST, RETAIL_ACCOUNT_PATH).hasRole(ROLE_STOCK_TRADER)
                    .requestMatchers(HttpMethod.PUT, RETAIL_ACCOUNT_PATH, RETAIL_DEBIT_PATH, RETAIL_CREDIT_PATH)
                            .hasRole(ROLE_STOCK_TRADER)
                    .requestMatchers(HttpMethod.DELETE, RETAIL_ACCOUNT_PATH).hasRole(ROLE_STOCK_TRADER)
                    .requestMatchers(SERVICE_SPACE).authenticated();
        } else {
            // auth-type=none disables authentication entirely, and the service's own path space has to open with
            // it: leaving these rules authenticated would answer 401 to every request while offering no mechanism
            // to authenticate with, which is a service that cannot be called rather than a service that is
            // guarded. The siblings' none.xml keeps a dummy basicRegistry of five hard-coded users and adds
            // overrideHttpAuthMethod="BASIC" [backend/broker/src/main/liberty/config/includes/none.xml:L32-L47,
            // L61-L63], but only because Liberty raises a NullPointerException without a user registry once the
            // application's auth method is unset - a constraint Spring Security does not have. Reproducing it
            // would put credentials in an image for no functional reason, so this service does not. The mode
            // remains an early-development convenience and is unfit for any shared environment; the module
            // README says so, and the start-up log line above repeats it on every boot.
            registry.requestMatchers(SERVICE_SPACE).permitAll();
        }

        registry.anyRequest().denyAll();
    }

    /**
     * Resolves the configured auth type to whether tokens are validated, rejecting anything unsupported.
     *
     * @param authType the configured {@code cashaccount.security.auth-type} (the chart's {@code AUTH_TYPE})
     * @return {@code true} for {@code basic}, {@code ldap} and {@code oidc}; {@code false} for {@code none}
     * @throws IllegalStateException for every other value, including blank and {@code null}
     */
    public static boolean authenticating(String authType) {
        String mode = (authType == null) ? "" : authType.trim().toLowerCase(Locale.ROOT);

        if (MODE_NONE.equals(mode)) {
            return false;
        }
        if (SUPPORTED_AUTH_TYPES.contains(mode)) {
            // basic, ldap and oidc are one branch here on purpose: they differ only in the registry Liberty
            // consults when ISSUING a token, and the token this service verifies is identical in all three.
            // Which key source verifies it - the signer certificate or a JWKS endpoint - is config/JwtDecoderConfig's
            // decision, made from the same property, so this file never learns that distinction.
            return true;
        }

        // Fail-closed, exactly as config/DataSourceGuardConfig refuses a JDBC_KIND it cannot serve. An
        // unrecognized mode has no safe reading: treating it as none would silently open the service, and
        // treating it as basic would authenticate against a mechanism the operator did not ask for. Start-up
        // stops instead, while the pod is still failing its probes rather than serving traffic.
        throw new IllegalStateException("Unsupported authentication mode: " + AUTH_TYPE_PROPERTY
                + " (chart variable AUTH_TYPE) is " + describe(authType) + ", and this service supports only "
                + quoted(SUPPORTED_AUTH_TYPES) + " - the include files the estate selects between"
                + " [backend/broker/src/main/liberty/config/server.xml:L44]. Set the chart value global.auth to one"
                + " of them; start-up fails rather than guess, because a mode this service cannot verify must never"
                + " resolve to accepting unverified requests.");
    }

    // An authenticating mode without a decoder cannot verify a signature, and a chain built anyway would
    // authenticate nobody while still answering 401 to everybody - a failure that looks like a caller problem.
    // Reaching this state means config/JwtDecoderConfig's condition and this file's mode reading have diverged,
    // which is a defect to be read in a start-up message, not at three in the morning in a 401 report.
    private static JwtDecoder requiredDecoder(ObjectProvider<JwtDecoder> provider, String authType) {
        JwtDecoder decoder = provider.getIfAvailable();
        if (decoder == null) {
            throw new IllegalStateException("No " + JwtDecoder.class.getSimpleName() + " bean is available, but "
                    + AUTH_TYPE_PROPERTY + " is " + describe(authType) + ", which validates tokens."
                    + " config/JwtDecoderConfig publishes the decoder for every mode except '" + MODE_NONE + "'.");
        }
        return decoder;
    }

    // Without the converter a token's groups claim would never become an authority, so every rule above would
    // deny an otherwise valid caller. Required rather than defaulted for that reason: Spring's default converter
    // reads scopes, not groups, and would make the role split unenforceable while appearing to work.
    private static JwtAuthenticationConverter requiredConverter(
            ObjectProvider<JwtAuthenticationConverter> provider, String authType) {

        JwtAuthenticationConverter converter = provider.getIfAvailable();
        if (converter == null) {
            throw new IllegalStateException("No " + JwtAuthenticationConverter.class.getSimpleName()
                    + " bean is available, but " + AUTH_TYPE_PROPERTY + " is " + describe(authType)
                    + ", which maps the token's groups claim to roles. config/JwtDecoderConfig publishes it"
                    + " unconditionally.");
        }
        return converter;
    }

    private static String describe(String value) {
        if (value == null) {
            return "not set";
        }
        return value.isBlank() ? "blank" : "'" + value + "'";
    }

    private static String quoted(List<String> values) {
        return values.stream().map(value -> "'" + value + "'").reduce((left, right) -> left + ", " + right)
                .orElse("");
    }
}
