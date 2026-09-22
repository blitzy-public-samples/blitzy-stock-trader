package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.config;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.ApiErrorAccessDeniedHandler;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.ApiErrorAuthenticationEntryPoint;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** The service's single security filter chain and method policy: broker's role split, fail-closed, as ApiError. */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    public static final String AUTH_TYPE_PROPERTY = "cashaccount.security.auth-type";

    /**
     * The authentication modes this service supports, in the order the estate documents them.
     *
     * <p>They are not an invention: the siblings select their security configuration by including
     * {@code includes/${AUTH_TYPE}.xml} [backend/broker/src/main/liberty/config/server.xml:L44], and the
     * include files that exist are exactly these four. There is no fifth mode anywhere in the estate.
     */
    public static final List<String> SUPPORTED_AUTH_TYPES = List.of("basic", "ldap", "oidc", "none");

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
     * @throws IllegalStateException when the configured auth type is unsupported, or when an authenticating
     *         mode has no decoder to verify with
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

                // Spring Security's default header writers are kept exactly as they are - no writer is
                // enumerated, disabled or replaced - and only WHEN they run is changed, which is the workaround
                // CVE-2026-22732 documents. In 6.3.0 through 6.3.14, the version range this module resolves
                // inside, OnCommittedResponseWrapper tracks Content-Length through addHeader() alone and not
                // through setHeader(), setIntHeader() or addIntHeader(), so a response committed through one of
                // those paths is sent with NONE of the lazily written headers - no Cache-Control: no-store on a
                // balance or ledger body, no nosniff, no DENY. Upgrading is not available here: Maven Central's
                // 6.3 line ends at 6.3.10, the 6.3.15 fix ships only through commercial support, and the OSS
                // fixes 6.5.9/7.0.4 require Spring Framework 6.2 while the mandated Boot 3.3 line ships 6.1
                // (see the parent comment above; AAP 0.11.2 keeps 3.3.13 an open item rather than a bump).
                // Writing eagerly has one documented behavioural consequence: an application-written header then
                // overrides that single header instead of suppressing Spring Security's whole cache-header set.
                // This service writes no cache headers of its own anywhere, so the caveat costs it nothing.
                //
                // An anonymous class, not a lambda: ObjectPostProcessor declares the generic method
                // <O extends T> O postProcess(O) and is therefore not a functional interface, and even were it
                // one, SecurityConfigurerAdapter's composite resolves each processor's type argument with
                // GenericTypeResolver - an erased lambda type resolves to null and the processor would be applied
                // to every object in the chain instead of the one filter meant here.
                .headers(headers -> headers.addObjectPostProcessor(new ObjectPostProcessor<HeaderWriterFilter>() {
                    @Override
                    public <O extends HeaderWriterFilter> O postProcess(O headerWriterFilter) {
                        headerWriterFilter.setShouldWriteHeadersEagerly(true);
                        return headerWriterFilter;
                    }
                }))

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

    /**
     * Registers the method-policy guard over this service's own path space.
     *
     * <p>Declared {@code static} so registering an interceptor never forces this configuration - and with it the
     * decoder, the converter and the whole chain's dependencies - to be instantiated while Spring MVC's own
     * infrastructure is still being built.
     *
     * @return the configurer that adds the guard to {@code /cash-account/**}
     */
    @Bean
    public static WebMvcConfigurer cashAccountMethodPolicyConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new UnsupportedMethodGuard()).addPathPatterns(SERVICE_SPACE);
            }
        };
    }

    /**
     * Fails closed the OPTIONS that Spring MVC would otherwise answer 200 on any mapped path, since a verb this
     * contract does not carry must arrive as 405 UNSUPPORTED_METHOD rather than as an apparent success.
     */
    private static final class UnsupportedMethodGuard implements HandlerInterceptor {

        // postHandle, not preHandle: by now MVC's metadata handler has computed the accurate per-path Allow on a
        // response that is not yet committed, so the 405 can name exactly the verbs that path implements without a
        // second copy of the routing table here. HEAD is deliberately not rejected - MVC answers it from the retail
        // read handler, so it is contract surface and is authorized exactly as GET is.
        @Override
        public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                ModelAndView modelAndView) throws HttpRequestMethodNotSupportedException {

            if (!HttpMethod.OPTIONS.matches(request.getMethod())) {
                return;
            }

            // A service that rejects OPTIONS must not advertise it, so it is dropped from the list MVC just
            // computed and the remainder - the verbs the path really implements - is what the 405 hands back.
            String advertised = response.getHeader(HttpHeaders.ALLOW);
            if (advertised != null) {
                String implemented = Arrays.stream(advertised.split(","))
                        .map(String::trim)
                        .filter(method -> !method.isEmpty())
                        .filter(method -> !HttpMethod.OPTIONS.name().equalsIgnoreCase(method))
                        .collect(Collectors.joining(","));
                if (!implemented.isEmpty()) {
                    response.setHeader(HttpHeaders.ALLOW, implemented);
                }
            }

            // Thrown with no supported-method list on purpose: the accurate Allow is already on the response, and
            // a list here would have error/ApiExceptionHandler add a second one beside it. DispatcherServlet
            // routes an exception from postHandle through the resolvers, so the 405 arrives as an ApiError.
            throw new HttpRequestMethodNotSupportedException(request.getMethod());
        }
    }

    // Broker's own split, re-expressed by verb over /cash-account/{owner} because broker takes its subject as a
    // path parameter too [backend/broker/src/main/webapp/WEB-INF/web.xml:L19-L32, L34-L51]. Rule order is the
    // behaviour: Spring Security applies the FIRST matching rule, so a broader pattern above a narrower one
    // silently becomes the only one that ever decides.
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
            // These write rules stay latent while cashaccount.security.all-authenticated-hold-stocktrader is true
            // (the deployed default): config/JwtDecoderConfig then grants StockTrader to every authenticated
            // caller, mirroring the siblings' ALL_AUTHENTICATED_USERS binding
            // [backend/broker/src/main/liberty/config/server.xml:L56-L60], so cutover changes no caller's
            // effective permissions. The grant belongs to the authority conversion alone - duplicating it as a
            // second matcher set here would give one behaviour two switches.
            registry
                    .requestMatchers(HttpMethod.GET, RETAIL_ACCOUNT_PATH)
                            .hasAnyRole(ROLE_STOCK_VIEWER, ROLE_STOCK_TRADER)
                    // HEAD carries the same roles as GET because Spring MVC answers a HEAD from the @GetMapping
                    // handler - it is the retail read, returning only the response metadata - so any weaker rule
                    // authorizes that read: under rule (5) authenticated() alone, which is where a GET-only
                    // matcher leaves it, a strict-mode principal holding neither role reaches the read handler.
                    // requestMatchers takes one method per call, so the parity is a second chained matcher.
                    .requestMatchers(HttpMethod.HEAD, RETAIL_ACCOUNT_PATH)
                            .hasAnyRole(ROLE_STOCK_VIEWER, ROLE_STOCK_TRADER)
                    .requestMatchers(HttpMethod.POST, RETAIL_ACCOUNT_PATH).hasRole(ROLE_STOCK_TRADER)
                    .requestMatchers(HttpMethod.PUT, RETAIL_ACCOUNT_PATH, RETAIL_DEBIT_PATH, RETAIL_CREDIT_PATH)
                            .hasRole(ROLE_STOCK_TRADER)
                    .requestMatchers(HttpMethod.DELETE, RETAIL_ACCOUNT_PATH).hasRole(ROLE_STOCK_TRADER)
                    // Claimed after the retail matchers, the order AAP 0.7.5 fixes: every genuine institutional
                    // route carries four or more segments, so none is absorbed above, while the only paths the
                    // retail matchers take from this wildcard are /cash-account/institutional and its
                    // debit|credit - which MVC dispatches to the retail controller as the owner INSTITUTIONAL, so
                    // claiming the wildcard first answers a strict-mode StockViewer's valid read of it 403.
                    .requestMatchers(INSTITUTIONAL_SPACE).hasRole(ROLE_STOCK_TRADER)
                    // authenticated(), never denyAll(): an authenticated caller asking for a path or verb this
                    // service does not implement must reach Spring MVC, which is what produces the 404
                    // UNSUPPORTED_PATH or 405 UNSUPPORTED_METHOD ApiError. Only an unauthenticated one is
                    // refused here. The legacy dispatcher's EVALUATE WS-REQ carried no WHEN OTHER, so an
                    // unrecognized code left SQLCODE untouched and echoed the caller's own amount back as the
                    // balance [backend/cash-account-cobol/COBOL/CASH00.cbl:L89-L108].
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

        // Closes everything outside this service's path space in every mode, none included - the analogue of
        // broker's <deny-uncovered-http-methods /> [backend/broker/src/main/webapp/WEB-INF/web.xml:L33]: whatever
        // is added to this application later is denied until a rule above admits it.
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
