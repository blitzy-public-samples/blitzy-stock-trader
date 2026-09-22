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

// Every value below carries a default, so an absent configuration branch still yields a fully populated object.
// That matters because only six of these keys are fed by the deployment: AUTH_TYPE (auth.type), JDBC_KIND
// (database.kind), CURRENCY_API_URL (cashAccount.exchangeRateUrl), JWT_AUDIENCE, JWT_ISSUER and the optional
// OIDC_JWKS_URL [infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L73-L77, L84-L88,
// L156-L176]. The rest - the FX timeout and base currency, the accepted-currency set, the public-key location, the
// all-authenticated grant and both reservation durations - have no environment binding anywhere in that template,
// so their only override path is Spring's relaxed binding (CASHACCOUNT_FX_TIMEOUT, CASHACCOUNT_RESERVATION_DEFAULT_TTL
// and so on). Keeping them here rather than asking for new template entries is what leaves them tunable in a
// deployment while the chart template stays untouched, which this refactor requires absolutely.
//
// Registered by @Component under the exact bean name Spring's own configuration-properties registrars derive
// ("<prefix>-<fully qualified class name>"). CashAccountApplication carries no @ConfigurationPropertiesScan, so
// component scanning is what binds this type; naming the bean this way additionally makes a later
// @EnableConfigurationProperties(CashAccountProperties.class) on any sibling @Configuration a no-op instead of a
// second bean definition that would leave every injection point in this package ambiguous.
/** Typed, validated binding of the whole {@code cashaccount.*} configuration namespace. */
@Component(CashAccountProperties.BEAN_NAME)
@ConfigurationProperties(prefix = "cashaccount")
@Validated
public class CashAccountProperties {

    static final String BEAN_NAME =
            "cashaccount-com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.config.CashAccountProperties";

    private static final Set<String> DEFAULT_ACCEPTED_CURRENCIES = normalizedCurrencies(List.of(
            "AUD", "BGN", "BRL", "CAD", "CHF", "CNY", "CZK", "DKK", "EUR", "GBP", "HKD", "HUF", "IDR", "ILS",
            "INR", "ISK", "JPY", "KRW", "MXN", "MYR", "NOK", "NZD", "PHP", "PLN", "RON", "SEK", "SGD", "THB",
            "TRY", "USD", "ZAR"));

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

        // Adopted, not invented: this is the allowed_currencies CHECK the estate's own PostgreSQL initialization
        // already enforces on its never-built cashaccount table
        // [infra/stocktrader-setup/azure/modules/postgres_init/init_schema.sql.tmpl:L7]. It is the ESTATE
        // allowlist and NOT the set the exchange-rate provider serves: that provider publishes a 30-code subset,
        // omitting BGN as of 2026-09-22, so an accepted code is not by itself a convertible one and a code the
        // provider does not publish surfaces as 503 EXCHANGE_RATE_UNAVAILABLE on the credit/debit path. The
        // reasoning for keeping BGN, and the per-deployment way to narrow the list, are recorded once in
        // application.yml beside the property, which is this policy's single authority - this default exists so
        // that a context binding no property source still yields a fully populated, validated object (above all
        // the ApplicationContextRunner in DataSourceGuardConfigTest), never as a second source of truth.
        // Insertion order is preserved so the value reads back in the documented ISO order rather than a hash
        // order. Trimming and upper-casing on the way in is a convenience local to this package; every consumer
        // still normalizes its own input, so no correctness claim rests on it.
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
        // [backend/broker/src/main/liberty/config/server.xml:L56-L60]: with it, any authenticated caller may write
        // exactly as it may through broker today, so the GET-versus-write role split is latent rather than absent
        // and cutover changes no caller's effective permissions. False is the supported, documented strict mode in
        // which only the token's groups claim decides; it is a genuine bound value precisely so a deployment - and
        // RoleEnforcementIT's second Spring context - can select it without a code change.
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
