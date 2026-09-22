package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.config;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Typed, validated binding of the whole {@code cashaccount.*} configuration namespace. */
@Component(CashAccountProperties.BEAN_NAME)
// Bound by @ConfigurationProperties, and nothing in this module reads configuration any other way: Spring hands a
// resolved @Value placeholder to its expression resolver, so a deployment value of the form #{...} - and
// CURRENCY_API_URL arrives from a configMap key an operator edits - would execute during bean creation. The Binder
// resolves ${...} and converts, and evaluates nothing, so a configuration value stays inert data; the packages that
// may not import config (AAP 0.8.2) bind the same keys with Binder off the Environment for the identical reason.
@ConfigurationProperties(prefix = "cashaccount")
@Validated
public class CashAccountProperties {

    // CashAccountApplication carries no @ConfigurationPropertiesScan, so component scanning is what binds this
    // type; the bean name is the one Spring's own configuration-properties registrars derive
    // ("<prefix>-<fully qualified class name>"), which makes a later
    // @EnableConfigurationProperties(CashAccountProperties.class) on a sibling @Configuration a no-op rather than
    // a second definition that would leave every injection point in this package ambiguous.
    static final String BEAN_NAME =
            "cashaccount-com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.config.CashAccountProperties";

    private static final Set<String> DEFAULT_ACCEPTED_CURRENCIES = normalizedCurrencies(List.of(
            "AUD", "BGN", "BRL", "CAD", "CHF", "CNY", "CZK", "DKK", "EUR", "GBP", "HKD", "HUF", "IDR", "ILS",
            "INR", "ISK", "JPY", "KRW", "MXN", "MYR", "NOK", "NZD", "PHP", "PLN", "RON", "SEK", "SGD", "THB",
            "TRY", "USD", "ZAR"));

    // Every value in these four groups carries a default, so an absent configuration branch still binds a fully
    // populated object. The chart injects six of the keys as environment variables - AUTH_TYPE, JDBC_KIND,
    // CURRENCY_API_URL, JWT_ISSUER, JWT_AUDIENCE and the optional OIDC_JWKS_URL
    // [infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L73-L77, L84-L88, L156-L176];
    // the rest have no environment binding in that template, so a deployment retunes them through Spring's relaxed
    // binding (CASHACCOUNT_FX_TIMEOUT, CASHACCOUNT_RESERVATION_DEFAULT_TTL and so on) with no template change.
    @Valid
    @NotNull
    private Jdbc jdbc = new Jdbc();

    @Valid
    @NotNull
    private Fx fx = new Fx();

    @Valid
    @NotNull
    private Security security = new Security();

    @Valid
    @NotNull
    private Reservation reservation = new Reservation();

    public Jdbc getJdbc() {
        return jdbc;
    }

    public void setJdbc(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public Fx getFx() {
        return fx;
    }

    public void setFx(Fx fx) {
        this.fx = fx;
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    public Reservation getReservation() {
        return reservation;
    }

    public void setReservation(Reservation reservation) {
        this.reservation = reservation;
    }

    /** Which relational dialect the service was configured against. */
    public static class Jdbc {

        // Carried, never judged. The chart's database.kind is global and still db2 in the repository
        // [infra/stocktrader-operator/helm-charts/stocktrader/values.yaml:L74-L81], and rejecting anything other
        // than postgres is DataSourceGuardConfig's single responsibility. Validating it here as well would give two
        // files an opinion about one value and two places to change when the release moves to PostgreSQL.
        @NotBlank
        private String kind = "postgres";

        public String getKind() {
            return kind;
        }

        public void setKind(String kind) {
            this.kind = trimmed(kind);
        }
    }

    /** Exchange-rate lookup settings and the set of currency codes the service will accept. */
    public static class Fx {

        @NotBlank
        private String url = "https://api.frankfurter.app/latest";

        @NotBlank
        private String baseCurrency = "USD";

        @NotNull
        @DurationMin(nanos = 1)
        private Duration timeout = Duration.ofSeconds(2);

        // Adopted, not invented: the allowed_currencies CHECK the estate's PostgreSQL initialization already
        // enforces [infra/stocktrader-setup/azure/modules/postgres_init/init_schema.sql.tmpl:L7]. It is the ESTATE
        // allowlist and not the set the exchange-rate provider serves, so an accepted code is not by itself a
        // convertible one and a code the provider omits surfaces as 503 EXCHANGE_RATE_UNAVAILABLE. This default
        // exists so a context binding no property source still yields a validated object - application.yml holds
        // the policy beside the property, and insertion order is preserved so it reads back in ISO order.
        @NotEmpty
        private Set<String> acceptedCurrencies = DEFAULT_ACCEPTED_CURRENCIES;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = trimmed(url);
        }

        public String getBaseCurrency() {
            return baseCurrency;
        }

        public void setBaseCurrency(String baseCurrency) {
            this.baseCurrency = normalizedCurrency(baseCurrency);
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Set<String> getAcceptedCurrencies() {
            return acceptedCurrencies;
        }

        public void setAcceptedCurrencies(Set<String> acceptedCurrencies) {
            this.acceptedCurrencies = normalizedCurrencies(acceptedCurrencies);
        }
    }

    /** Authentication mode, the role-grant switch, and the token contract to validate against. */
    public static class Security {

        @NotBlank
        private String authType = "basic";

        // True is the deployed default because it reproduces the siblings' special-subject binding
        // <security-role id="StockTrader"><special-subject type="ALL_AUTHENTICATED_USERS"/></security-role>
        // [backend/broker/src/main/liberty/config/server.xml:L56-L60]: any authenticated caller may write exactly
        // as it may through broker today, so cutover changes no caller's effective permissions. False is the
        // supported strict mode in which only the token's groups claim decides, bound rather than compiled in so a
        // deployment - and RoleEnforcementIT's second context - selects it without a code change.
        private boolean allAuthenticatedHoldStocktrader = true;

        @Valid
        @NotNull
        private Jwt jwt = new Jwt();

        public String getAuthType() {
            return authType;
        }

        public void setAuthType(String authType) {
            this.authType = trimmed(authType);
        }

        public boolean isAllAuthenticatedHoldStocktrader() {
            return allAuthenticatedHoldStocktrader;
        }

        public void setAllAuthenticatedHoldStocktrader(boolean allAuthenticatedHoldStocktrader) {
            this.allAuthenticatedHoldStocktrader = allAuthenticatedHoldStocktrader;
        }

        public Jwt getJwt() {
            return jwt;
        }

        public void setJwt(Jwt jwt) {
            this.jwt = jwt;
        }
    }

    /** The RS256 token contract: issuer, audience and where the verification key comes from. */
    public static class Jwt {

        @NotBlank
        private String issuer = "http://stock-trader.ibm.com";

        @NotBlank
        private String audience = "stock-trader";

        @NotBlank
        private String publicKeyLocation = "classpath:security/jwtsigner.pem";

        // Deliberately unconstrained and never null. The chart marks OIDC_JWKS_URL optional
        // [infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L171-L176] because it is
        // meaningful only when auth-type is oidc, where the key source is a JWKS endpoint instead of the signer
        // certificate; requiring it would break every basic, ldap and none deployment. Absent binds to empty rather
        // than null so a mode check can ask whether it is blank without guarding for null first.
        private String jwksUrl = "";

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = trimmed(issuer);
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = trimmed(audience);
        }

        public String getPublicKeyLocation() {
            return publicKeyLocation;
        }

        public void setPublicKeyLocation(String publicKeyLocation) {
            this.publicKeyLocation = trimmed(publicKeyLocation);
        }

        public String getJwksUrl() {
            return jwksUrl;
        }

        public void setJwksUrl(String jwksUrl) {
            this.jwksUrl = (jwksUrl == null) ? "" : jwksUrl.trim();
        }
    }

    /** How long a hold survives unattended, and how often overdue holds are swept. */
    public static class Reservation {

        @NotNull
        @DurationMin(nanos = 1)
        private Duration defaultTtl = Duration.ofHours(24);

        @NotNull
        @DurationMin(nanos = 1)
        private Duration expirySweepInterval = Duration.ofSeconds(60);

        public Duration getDefaultTtl() {
            return defaultTtl;
        }

        public void setDefaultTtl(Duration defaultTtl) {
            this.defaultTtl = defaultTtl;
        }

        public Duration getExpirySweepInterval() {
            return expirySweepInterval;
        }

        public void setExpirySweepInterval(Duration expirySweepInterval) {
            this.expirySweepInterval = expirySweepInterval;
        }
    }

    private static String trimmed(String value) {
        return (value == null) ? null : value.trim();
    }

    private static String normalizedCurrency(String value) {
        return (value == null) ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static Set<String> normalizedCurrencies(Iterable<String> values) {
        if (values == null) {
            return Collections.emptySet();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String code = normalizedCurrency(value);
            if (code != null && !code.isEmpty()) {
                normalized.add(code);
            }
        }
        return Collections.unmodifiableSet(normalized);
    }
}
