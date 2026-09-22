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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.fx;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;

// Technology choice: the legacy rate lookup was an in-database join, selecting STOCKTRD.FRANKFURT1 on the account's
// own currency truncated to five characters [backend/cash-account-cobol/COBOL/CASH00.cbl:L213-L219]. That table is a
// migration-source artifact and not a target-state dependency, and its precision is the reason: RATES was
// DECIMAL(3, 2) / PIC S9(1)V9(2) COMP-3 [backend/cash-account-cobol/COBOL/DCLFRANK.cpy:L12, L22], two decimals with a
// ceiling of 9.99, so any currency worth less than a tenth of the base unit was unrepresentable. Live rates carry four
// to six significant digits; the reconciliation tooling classifies that divergence as a RATE_SOURCE variance rather
// than absorbing it, and the staged table survives only behind LegacyRateTableSource for replaying legacy arithmetic.
//
// Deliberate deviation - fail closed. CASH-ACCT-CREDIT and CASH-ACCT-DEBIT ran their COMPUTE and UPDATE even when the
// rate SELECT found no row [CASH00.cbl:L214-L231 for credit, L248-L264 for debit]: the missing row's SQLCODE 100 was
// overwritten by the UPDATE's SQLCODE 0, so a balance computed from the uninitialized RATES host variable
// [DCLFRANK.cpy:L22 declares it with no VALUE clause] was committed under a success code. Here every undeterminable
// rate raises ExchangeRateUnavailableException instead, which the service layer renders as
// 503 EXCHANGE_RATE_UNAVAILABLE with Retry-After: 5, leaving the balance unchanged and writing no ledger row.
//
// No @Profile, deliberately. ToolExchangeRateSource is @Profile("tool") @Primary and delegates to this client when
// tool.rate-source=live, so this bean has to exist in that profile too; in the deployed profile it is then the only
// ExchangeRateSource bean, which is what ExchangeRateSourceWiringTest asserts.
/** Live exchange-rate lookup at the chart-injected CURRENCY_API_URL; the target-state replacement for the legacy rate-table join. */
@Component
public class FrankfurterExchangeRateClient implements ExchangeRateSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(FrankfurterExchangeRateClient.class);

    private static final String ACCEPTED_CURRENCIES_PROPERTY = "cashaccount.fx.accepted-currencies";

    private static final Pattern ISO_4217_CODE = Pattern.compile("^[A-Z]{3}$");

    // The accepted set as shipped, held here as well as in configuration so a context that binds no property list
    // still rejects an unconvertible code instead of forwarding it to the rate service. Same 31 codes the estate
    // already enforces through its allowed_currencies CHECK
    // (infra/stocktrader-setup/azure/modules/postgres_init/init_schema.sql.tmpl:L7).
    private static final Set<String> DEFAULT_ACCEPTED_CURRENCIES = Set.of(
            "AUD", "BGN", "BRL", "CAD", "CHF", "CNY", "CZK", "DKK", "EUR", "GBP", "HKD", "HUF", "IDR", "ILS", "INR",
            "ISK", "JPY", "KRW", "MXN", "MYR", "NOK", "NZD", "PHP", "PLN", "RON", "SEK", "SGD", "THB", "TRY", "USD",
            "ZAR");

    // Two attempts, no backoff: the caller is a synchronous retail credit or debit holding a cash_account row lock,
    // so waiting between attempts spends someone else's request budget. Bounded by the client's own connect and read
    // timeouts, the worst case stays inside the caller's patience.
    private static final int MAX_ATTEMPTS = 2;

    private static final String QUERY_PARAM_FROM = "from";

    private static final String QUERY_PARAM_TO = "to";

    private final RestClient restClient;
    private final String fxUrl;
    private final Set<String> acceptedCurrencies;

    /**
     * Container constructor.
     *
     * @param restClient  the bounded, header-free client published by {@code config.FxClientConfig}; its connect and
     *                    read budget is configuration owned there and is never re-read or re-applied here
     * @param fxUrl       the endpoint, which is a deployment value and never a literal in this class: the chart
     *                    injects it as {@code CURRENCY_API_URL} from configMap key
     *                    {@code cashAccount.exchangeRateUrl}
     *                    (infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L156-L160,
     *                    value at .../values.yaml:L147). Declared with no fallback so a release that fails to supply
     *                    it fails at start-up rather than silently reaching a host this code chose
     * @param environment source of the accepted-currency list, read through {@link Binder} because the property is
     *                    written as a YAML sequence, which cannot be bound by {@code @Value}
     */
    @Autowired
    public FrankfurterExchangeRateClient(@Qualifier("fxRestClient") RestClient restClient,
            @Value("${cashaccount.fx.url}") String fxUrl, Environment environment) {
        this(restClient, fxUrl, acceptedCurrenciesFrom(environment));
    }

    /** Uses the accepted-currency set as shipped; for a caller that holds no {@link Environment}. */
    public FrankfurterExchangeRateClient(RestClient restClient, String fxUrl) {
        this(restClient, fxUrl, DEFAULT_ACCEPTED_CURRENCIES);
    }

    public FrankfurterExchangeRateClient(RestClient restClient, String fxUrl, Collection<String> acceptedCurrencies) {
        this.restClient = Objects.requireNonNull(restClient, "fx RestClient is required");
        this.fxUrl = requireEndpoint(fxUrl);
        this.acceptedCurrencies = normalizedCodes(acceptedCurrencies);
    }

    @Override
    public BigDecimal rate(String base, String quote) {
        String from = normalizeCode(base);
        String to = normalizeCode(quote);

        // Defence in depth rather than duplication: the authoritative validation is at the API boundary and the quote
        // usually arrives from a stored account currency that was validated on write. Checking again here is what
        // keeps the two failure classes distinct - a rejected input stays 400 INVALID_CURRENCY and only an
        // undeterminable rate becomes 503 EXCHANGE_RATE_UNAVAILABLE, so a caller error can never invite a retry.
        requireAcceptedCode(from);
        requireAcceptedCode(to);

        // Exact legacy parity, not an optimization: an account already in the base currency was multiplied by a rate
        // of 1 in the program being replaced, so reproducing that value locally makes its balances reconcile exactly
        // - and it refuses to make an operation that needs no conversion depend on a third party that could be down.
        if (from.equals(to)) {
            return BigDecimal.ONE;
        }

        // Built from the configured value rather than concatenated onto it, so a query string already present in that
        // value survives instead of being clobbered. There is no conversion endpoint to ask for a converted amount;
        // the rate comes back and the caller applies it.
        URI uri = UriComponentsBuilder.fromUriString(fxUrl)
                .queryParam(QUERY_PARAM_FROM, from)
                .queryParam(QUERY_PARAM_TO, to)
                .build()
                .toUri();

        return extractRate(fetch(uri, from, to), from, to);
    }

    // No header is attached to this request, and the absence is a security control rather than an omission: broker
    // forwards the caller's Authorization header into this service, and this outbound hop goes to a public rate API
    // that requires no credential, so propagating the caller's token to a third party would be a defect.
    private FrankfurterLatestResponse fetch(URI uri, String from, String to) {
        ResourceAccessException transportFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return restClient.get()
                        .uri(uri)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (request, response) -> {
                            throw unavailable(from, to, "endpoint answered HTTP " + response.getStatusCode().value(),
                                    null);
                        })
                        .body(FrankfurterLatestResponse.class);
            } catch (ResourceAccessException transport) {
                // Only a transport failure is retried, because only a transport failure can differ on a second
                // attempt. A refused status and an unreadable body are deterministic, so retrying either would add
                // latency to a request whose outcome is already settled.
                transportFailure = transport;
            } catch (RestClientException unreadable) {
                throw unavailable(from, to, "endpoint answer could not be read", unreadable);
            }
        }
        throw unavailable(from, to, "endpoint unreachable after " + MAX_ATTEMPTS + " attempts", transportFailure);
    }

    private BigDecimal extractRate(FrankfurterLatestResponse response, String from, String to) {
        Map<String, BigDecimal> rates = response == null ? null : response.rates();
        if (rates == null || rates.isEmpty()) {
            throw unavailable(from, to, "endpoint answer carried no rates", null);
        }

        BigDecimal rate = rates.get(to);
        if (rate == null) {
            throw unavailable(from, to, "endpoint answer omitted the requested currency", null);
        }

        // A non-positive rate is refused for the same reason this class never returns a sentinel: it would turn every
        // credit and debit into a silent no-op, which is exactly the class of quietly wrong answer being eliminated.
        if (rate.signum() <= 0) {
            throw unavailable(from, to, "endpoint answer carried a non-positive rate", null);
        }

        // Returned at its natural precision, untouched. The program being replaced truncated the whole expression
        // exactly once, after the signed addition [CASH00.cbl:L222 for credit, L256 for debit], and domain.Money
        // owns that single truncation; trimming the rate here would break parity without failing anything - with a
        // stored balance of 100.00, a rate of 0.03 and an amount of 0.30 the one final truncation yields 99.99 while
        // a pre-trimmed product yields 100.00.
        return rate;
    }

    // Logged here, once, at the single point every failure funnels through, so an outage cannot be reported twice or
    // not at all. The cause's type but not its stack trace: a rate outage repeats on every request, and the type
    // carries the diagnosis - refused, timed out - without multiplying the log volume of someone else's outage. The
    // reason stays a short phrase and never names the endpoint or carries a response body.
    private ExchangeRateUnavailableException unavailable(String from, String to, String reason, Throwable cause) {
        LOGGER.warn("Exchange rate lookup for {}->{} failed: {}{}", from, to, reason, causeSuffix(cause));
        return ExchangeRateUnavailableException.forPair(from, to, reason, cause);
    }

    private static String causeSuffix(Throwable cause) {
        return cause == null ? "" : " (" + cause.getClass().getSimpleName() + ")";
    }

    // Locale.ROOT, never the default locale: a Turkish-locale uppercase turns the "i" of ILS into a dotted capital and
    // silently corrupts the code.
    private static String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    private void requireAcceptedCode(String code) {
        if (!ISO_4217_CODE.matcher(code).matches() || !acceptedCurrencies.contains(code)) {
            throw CashAccountException.of(CashAccountErrorCode.INVALID_CURRENCY,
                    "Unsupported currency code: " + quoteForMessage(code));
        }
    }

    // The rejected value reaches an error payload and a log line, so only a bounded alphanumeric form of it is echoed:
    // enough to diagnose a typo, too little to carry an injected log record or an unbounded body.
    private static String quoteForMessage(String code) {
        StringBuilder safe = new StringBuilder(8);
        for (int index = 0; index < code.length() && safe.length() < 8; index++) {
            char candidate = code.charAt(index);
            if (Character.isLetterOrDigit(candidate) && candidate < 128) {
                safe.append(candidate);
            }
        }
        return safe.isEmpty() ? "(blank)" : safe.toString();
    }

    private static String requireEndpoint(String fxUrl) {
        if (fxUrl == null || fxUrl.isBlank()) {
            throw new IllegalStateException(
                    "cashaccount.fx.url is required; the chart supplies it as CURRENCY_API_URL");
        }
        return fxUrl.trim();
    }

    private static Set<String> normalizedCodes(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return DEFAULT_ACCEPTED_CURRENCIES;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String code : codes) {
            String candidate = normalizeCode(code);
            if (!candidate.isEmpty()) {
                normalized.add(candidate);
            }
        }
        return normalized.isEmpty() ? DEFAULT_ACCEPTED_CURRENCIES : Set.copyOf(normalized);
    }

    // Binder rather than @Value because the property is a YAML sequence, which @Value cannot bind; Binder also accepts
    // the comma-separated scalar form, so relaxed binding through an environment variable keeps working. Reading it
    // from the Environment rather than injecting the typed properties object is what keeps this package free of the
    // config package, which wires everything and is depended on by nothing. A non-configurable Environment cannot
    // expose property sources at all, so that case takes the shipped set instead of failing start-up.
    private static Set<String> acceptedCurrenciesFrom(Environment environment) {
        if (!(environment instanceof ConfigurableEnvironment)) {
            return DEFAULT_ACCEPTED_CURRENCIES;
        }
        return Binder.get(environment)
                .bind(ACCEPTED_CURRENCIES_PROPERTY, Bindable.setOf(String.class))
                .orElse(DEFAULT_ACCEPTED_CURRENCIES);
    }

    // Only rates is declared, and its value type is BigDecimal: that declared type is what makes Jackson build each
    // rate straight from the response text, so no binary approximation of a rate can exist even for an instant. The
    // amount, base and date members of the contract are deliberately unread, and unknown members are ignored so a
    // provider adding one cannot turn a working conversion into a parse failure.
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FrankfurterLatestResponse(Map<String, BigDecimal> rates) {
    }
}
