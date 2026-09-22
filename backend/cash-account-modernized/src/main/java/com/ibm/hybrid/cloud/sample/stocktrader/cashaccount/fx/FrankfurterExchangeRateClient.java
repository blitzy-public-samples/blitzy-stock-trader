package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.fx;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
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
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.Money;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;

/** Live exchange-rate lookup at the chart-injected CURRENCY_API_URL; the target-state replacement for the legacy rate-table join. */
@Component
public class FrankfurterExchangeRateClient implements ExchangeRateSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(FrankfurterExchangeRateClient.class);

    private static final String FX_URL_PROPERTY = "cashaccount.fx.url";

    private static final String ACCEPTED_CURRENCIES_PROPERTY = "cashaccount.fx.accepted-currencies";

    private static final Pattern ISO_4217_CODE = Pattern.compile("^[A-Z]{3}$");

    // The accepted set as shipped, held here as well as in configuration so a context that binds no property list
    // still rejects an unaccepted code instead of forwarding it. It enforces ESTATE acceptance only - the same 31
    // codes as the allowed_currencies CHECK of
    // infra/stocktrader-setup/azure/modules/postgres_init/init_schema.sql.tmpl:L7 - and is not a promise of
    // convertibility: the provider serves a subset of it, BGN among the codes it may omit, and a code it omits
    // comes back missing from the response, which extractRate raises as ExchangeRateUnavailableException for the
    // service to render as 503 EXCHANGE_RATE_UNAVAILABLE - never as 400 INVALID_CURRENCY.
    private static final Set<String> DEFAULT_ACCEPTED_CURRENCIES = Set.of(
            "AUD", "BGN", "BRL", "CAD", "CHF", "CNY", "CZK", "DKK", "EUR", "GBP", "HKD", "HUF", "IDR", "ILS", "INR",
            "ISK", "JPY", "KRW", "MXN", "MYR", "NOK", "NZD", "PHP", "PLN", "RON", "SEK", "SGD", "THB", "TRY", "USD",
            "ZAR");

    // Two attempts, no backoff. Both happen before the account row is locked - the caller resolves the rate on an
    // unlocked read and only then opens the write transaction that takes the row under PESSIMISTIC_WRITE
    // (retail/RetailCashAccountService.applyRateChange) - so a retry spends the caller's own request budget and
    // nobody else's. Two rather than more because the connect and read timeouts already bound the worst case
    // (cashaccount.fx.timeout in config/FxClientConfig), and no backoff because sleeping before an immediate
    // transport retry adds latency without improving its odds.
    private static final int MAX_ATTEMPTS = 2;

    // The only scheme this client will dial. See requireEndpoint for why plaintext is refused outright.
    private static final String REQUIRED_SCHEME = "https";

    // Cardinality bound on the answer, the second half of the size bound config/FxClientConfig applies to the
    // bytes. The byte ceiling already makes an enormous map impossible, but the two limits fail differently and
    // both are worth having: the transport one is about how much this process may be made to allocate, this one is
    // about the answer still being the contract. A pair query publishes one rate and the whole set is about thirty;
    // ISO 4217 assigns fewer than two hundred active codes, so 512 is beyond anything a correct answer can carry
    // and rejects only a response that has stopped being one. Package-private so fx/CurrencyConversionTest drives
    // the boundary against the limit itself rather than a duplicate of it.
    static final int MAX_RATE_ENTRIES = 512;

    private static final String QUERY_PARAM_FROM = "from";

    private static final String QUERY_PARAM_TO = "to";

    private final RestClient restClient;
    private final String fxUrl;
    private final Set<String> acceptedCurrencies;

    /**
     * Container constructor.
     *
     * @param restClient  the bounded, header-free client published by {@code config.FxClientConfig}, which owns
     *                    its connect and read budget
     * @param environment source of both configured values, each read through {@link Binder}: the endpoint
     *                    {@code cashaccount.fx.url} - a deployment value, never a literal here: the chart injects
     *                    {@code CURRENCY_API_URL} from configMap key {@code cashAccount.exchangeRateUrl}
     *                    (infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L156-L160,
     *                    value at .../values.yaml:L147) and {@code cashaccount.fx.url} in application.yml supplies
     *                    the fallback, so this class chooses no host of its own. A blank value is rejected by the
     *                    {@code @NotBlank} on {@code config.CashAccountProperties.Fx.url} before this constructor
     *                    runs, and by {@code requireEndpoint} below, which also covers the direct constructions
     *                    that bypass the container - and the accepted-currency list
     *                    {@code cashaccount.fx.accepted-currencies}, a YAML sequence no placeholder can bind
     */
    @Autowired
    public FrankfurterExchangeRateClient(@Qualifier("fxRestClient") RestClient restClient, Environment environment) {
        this(restClient, endpointFrom(environment), acceptedCurrenciesFrom(environment));
    }

    // Takes the accepted-currency set as shipped, for a caller that holds no Environment.
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

        // Defence in depth, and what keeps the two failure classes distinct: a rejected input stays
        // 400 INVALID_CURRENCY and only an undeterminable rate becomes 503, so a caller error cannot invite a retry.
        requireAcceptedCode(from);
        requireAcceptedCode(to);

        // Exact legacy parity, not an optimization: the program being replaced multiplied an account already in the
        // base currency by a rate of 1, and no third party can be down for an operation that needs no conversion.
        if (from.equals(to)) {
            return BigDecimal.ONE;
        }

        // Built from the configured value rather than concatenated onto it, so a query string already present in
        // that value survives instead of being clobbered.
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
                // The bytes are bounded before this binding happens, not after: the client published by
                // config/FxClientConfig refuses a Content-Length above its ceiling before reading anything and
                // meters a body that declares no length as it is consumed, so no answer a third party sends can
                // decide how much this process allocates. That failure arrives here as a RestClientException and
                // leaves through the catch below as 503 EXCHANGE_RATE_UNAVAILABLE, unretried.
                return requireBoundedAnswer(restClient.get()
                        .uri(uri)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (request, response) -> {
                            throw unavailable(from, to, "endpoint answered HTTP " + response.getStatusCode().value(),
                                    null);
                        })
                        .body(FrankfurterLatestResponse.class), from, to);
            } catch (ResourceAccessException transport) {
                // Only a transport failure can differ on a second attempt; a refused status and an unreadable body
                // are deterministic, so retrying either would add latency to a settled outcome.
                transportFailure = transport;
            } catch (RestClientException unreadable) {
                throw unavailable(from, to, "endpoint answer could not be read", unreadable);
            }
        }
        throw unavailable(from, to, "endpoint unreachable after " + MAX_ATTEMPTS + " attempts", transportFailure);
    }

    // Raised as an unavailable rate rather than an invalid input: the caller supplied an accepted pair, so nothing
    // about the request is wrong - the answer is. Checked here, between binding and extraction, so the rejection
    // is on the answer as a whole and not on the one entry that happens to be read out of it.
    private FrankfurterLatestResponse requireBoundedAnswer(FrankfurterLatestResponse answer, String from, String to) {
        Map<String, BigDecimal> rates = answer == null ? null : answer.rates();
        if (rates != null && rates.size() > MAX_RATE_ENTRIES) {
            throw unavailable(from, to,
                    "endpoint answer carried more than " + MAX_RATE_ENTRIES + " rates", null);
        }
        return answer;
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

        // A zero rate would turn every credit and debit into a silent no-op and a negative one would invert the
        // operation: the same class of quietly wrong answer as an absent rate.
        if (rate.signum() <= 0) {
            throw unavailable(from, to, "endpoint answer carried a non-positive rate", null);
        }

        // A rate of an extreme exponent - "EUR":1e1000000000 is twelve bytes of third-party JSON - parses for
        // nothing but expands into hundreds of megabytes of digits at the first multiply or setScale inside
        // domain.Money, so the size of the provider's number is judged here, before it reaches the arithmetic.
        // domain.Money owns the limits and this class holds no copy of them, as it holds no copy of the scale or
        // rounding mode. A bound on the number's size, never on a rate's precision or value - the legacy two-decimal
        // RATES column is the defect this service removes - answered as 503 EXCHANGE_RATE_UNAVAILABLE with the
        // balance untouched and no ledger row written, rather than as a 500 from an ArithmeticException deeper down.
        if (!Money.isWithinInputBounds(rate)) {
            throw unavailable(from, to, "endpoint answer carried a rate of unusable magnitude", null);
        }

        // Returned at its natural precision, untouched: the program being replaced truncated the whole expression
        // exactly once, after the signed addition [CASH00.cbl:L222 credit, L256 debit], and domain.Money owns that
        // single truncation, so trimming the rate here would break parity without failing anything.
        return rate;
    }

    // Deliberate deviation - fail closed. CASH-ACCT-CREDIT and CASH-ACCT-DEBIT ran their COMPUTE and UPDATE even
    // when the rate SELECT found no row [CASH00.cbl:L214-L231 credit, L248-L264 debit]: the missing row's
    // SQLCODE 100 was overwritten by the UPDATE's SQLCODE 0, so a balance computed from the uninitialized RATES
    // host variable [DCLFRANK.cpy:L22 declares it with no VALUE clause] was committed under a success code. Every
    // undeterminable rate raises instead, and the service renders it as 503 with the balance unchanged.
    //
    // DEBUG and not WARN: error/ApiExceptionHandler emits the single WARN when it renders the 503, holding the
    // owner and the request context, so a second line here would double the log volume of a provider outage that
    // repeats on every request. Never a stack trace, and the reason never names the endpoint or a response body.
    private ExchangeRateUnavailableException unavailable(String from, String to, String reason, Throwable cause) {
        LOGGER.debug("Exchange rate lookup for {}->{} failed: {}{}", from, to, reason, causeSuffix(cause));
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

    // HTTPS is required, and the refusal is deliberately a start-up failure rather than a per-request one: this
    // runs in the constructor, so a pod configured to fetch rates over plaintext never becomes ready instead of
    // serving wrong balances until someone notices. The rate is not advisory data - it is multiplied into every
    // cross-currency credit and debit and written to the ledger, so an on-path attacker who can rewrite an HTTP
    // answer can move money (CWE-319 cleartext transmission, CWE-345 insufficient verification of data
    // authenticity). HTTPS moves that from a network position to the server's certificate chain, which the JVM
    // trust store verifies, and config/FxClientConfig's Redirect.NORMAL keeps it there by refusing a redirect back
    // down to HTTP. The chart's own value is already https (values.yaml:L147), so nothing deployed changes.
    //
    // The three checks past the scheme close the ways a URL can be HTTPS and still be wrong: no host means the
    // request target is undetermined; user-info means a credential is sitting in a configMap value that reaches
    // logs and metrics tags; a fragment is never sent on the wire and so silently means something other than it
    // reads. Comparison is case-insensitive because a scheme is (RFC 3986 section 3.1) and the JDK client agrees.
    private static String requireEndpoint(String fxUrl) {
        if (fxUrl == null || fxUrl.isBlank()) {
            throw new IllegalStateException(
                    "cashaccount.fx.url is required; the chart supplies it as CURRENCY_API_URL");
        }

        String endpoint = fxUrl.trim();
        URI parsed;
        try {
            parsed = new URI(endpoint);
        } catch (URISyntaxException malformed) {
            // The rejected value is never echoed, and the parser's own exception is dropped rather than chained for
            // exactly that reason: URISyntaxException.getMessage() renders the whole rejected input, and Spring
            // prints every nested cause when a context fails to refresh, so keeping it would publish a mistyped or
            // hostile CURRENCY_API_URL - credentials, CR/LF-forged log lines and all - into the start-up log. What
            // survives is metadata that cannot carry the value: the parser's own reason category, itself filtered,
            // and the numeric index it failed at, which is what an operator actually needs to find the typo.
            throw new IllegalStateException("cashaccount.fx.url (CURRENCY_API_URL) is not a valid URI: "
                    + reasonForMessage(malformed.getReason()) + " at index " + malformed.getIndex());
        }

        if (!REQUIRED_SCHEME.equalsIgnoreCase(parsed.getScheme())) {
            // The scheme is the one part of the value safe to echo: URI syntax restricts it to a letter followed
            // by letters, digits and "+-.", so it cannot carry a forged log record.
            throw new IllegalStateException("cashaccount.fx.url (CURRENCY_API_URL) must use the "
                    + REQUIRED_SCHEME + " scheme, not " + schemeForMessage(parsed.getScheme()));
        }
        if (parsed.getHost() == null || parsed.getHost().isBlank()) {
            throw new IllegalStateException("cashaccount.fx.url (CURRENCY_API_URL) must name a host");
        }
        if (parsed.getUserInfo() != null) {
            throw new IllegalStateException(
                    "cashaccount.fx.url (CURRENCY_API_URL) must carry no user-info credentials");
        }
        if (parsed.getFragment() != null) {
            throw new IllegalStateException("cashaccount.fx.url (CURRENCY_API_URL) must carry no fragment");
        }
        return endpoint;
    }

    private static String schemeForMessage(String scheme) {
        return scheme == null || scheme.isBlank()
                ? "(none)"
                : sanitizedForMessage(scheme.toLowerCase(Locale.ROOT), 16);
    }

    private static String reasonForMessage(String reason) {
        return reason == null || reason.isBlank() ? "(unspecified)" : sanitizedForMessage(reason, 64);
    }

    // Printable ASCII only and length-capped. Both callers render a fragment derived from operator-supplied
    // configuration into a start-up log line, and a configMap is writable by anyone who can patch it, so the
    // filter is what makes "this diagnostic cannot carry a forged log record" true of the code rather than of the
    // value that happened to be configured.
    private static String sanitizedForMessage(String value, int maxLength) {
        StringBuilder safe = new StringBuilder(maxLength);
        for (int index = 0; index < value.length() && safe.length() < maxLength; index++) {
            char candidate = value.charAt(index);
            if (candidate >= ' ' && candidate < 127) {
                safe.append(candidate);
            }
        }
        return safe.isEmpty() ? "(filtered)" : safe.toString();
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

    // Binder, never @Value, and the reason is the endpoint: a @Value placeholder is resolved and its resolved text
    // is then handed to Spring's expression resolver, so a value of the form #{...} executes during bean creation.
    // This endpoint is the one configuration value that arrives from a configMap key an operator edits
    // (CURRENCY_API_URL <- cashAccount.exchangeRateUrl), which would make edit access to that map equivalent to
    // code execution in the pod. Binder resolves ${...} and converts, and evaluates nothing, so the same text stays
    // inert data and is rejected as a bad endpoint rather than run. A non-configurable Environment exposes no
    // property sources, so it yields the empty string and requireEndpoint stops start-up.
    private static String endpointFrom(Environment environment) {
        if (!(environment instanceof ConfigurableEnvironment)) {
            return "";
        }
        return Binder.get(environment).bind(FX_URL_PROPERTY, Bindable.of(String.class)).orElse("");
    }

    // Binder rather than @Value because the property is a YAML sequence, which @Value cannot bind; Binder also
    // accepts the comma-separated scalar form, so relaxed binding through an environment variable keeps working.
    // Reading the Environment rather than injecting the typed properties object keeps this package free of config,
    // which wires everything and is depended on by nothing. A non-configurable Environment exposes no property
    // sources at all, so that case takes the shipped set instead of failing start-up.
    private static Set<String> acceptedCurrenciesFrom(Environment environment) {
        if (!(environment instanceof ConfigurableEnvironment)) {
            return DEFAULT_ACCEPTED_CURRENCIES;
        }
        return Binder.get(environment)
                .bind(ACCEPTED_CURRENCIES_PROPERTY, Bindable.setOf(String.class))
                .orElse(DEFAULT_ACCEPTED_CURRENCIES);
    }

    /** Declares rates as BigDecimal so Jackson builds each rate from the response text, never through a double. */
    // A map is the contract's own shape and cannot be narrowed here, so its growth is bounded outside the type:
    // bytes by the ceiling in config/FxClientConfig before this is bound, entries by MAX_RATE_ENTRIES after.
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FrankfurterLatestResponse(Map<String, BigDecimal> rates) {
    }
}
