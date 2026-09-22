package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.config.CashAccountProperties;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.config.FxClientConfig;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.Money;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;

/** Unit tests for the live rate lookup, with spring-test's MockRestServiceServer standing in at the HTTP boundary. */
class CurrencyConversionTest {

    // A host in the reserved .invalid TLD, which can never resolve: the recorder intercepts every call, so
    // naming an unresolvable host means a mistake in the harness fails locally instead of reaching the public
    // rate service, which this module's tests are never permitted to call (AAP 0.3.2).
    private static final String FX_URL = "https://fx.test.invalid/latest";

    private static final String RECORDED_RESPONSE = "fixtures/fx/frankfurter-latest-usd.json";

    // Twenty-five decimal places, which no binary numeric type can represent. Carrying it end to end is what
    // distinguishes a text-to-BigDecimal parse from a binary approximation.
    private static final String OVER_PRECISE_RATE = "0.9200000000000000123456789";

    private static final String OVER_PRECISE_BODY =
            "{\"base\":\"USD\",\"date\":\"2024-01-15\",\"rates\":{\"EUR\":" + OVER_PRECISE_RATE + "}}";

    // A well-formed answer that simply does not carry the requested code - the provider's response to a pair it
    // does not publish, which is a different failure from a refusal and must not be read as a rate of zero.
    private static final String RATES_WITHOUT_EUR_BODY =
            "{\"base\":\"USD\",\"date\":\"2024-01-15\",\"rates\":{\"GBP\":0.79}}";

    // Twelve characters of provider JSON that parse into a BigDecimal for nothing - the exponent is an int scale
    // field - and then expand into hundreds of megabytes of digits at the first multiply inside domain/Money.
    private static final String EXTREME_EXPONENT_RATE_BODY =
            "{\"base\":\"USD\",\"date\":\"2024-01-15\",\"rates\":{\"EUR\":1e1000000000}}";

    // The contract's minimal well-formed answer, used where the body's content is beside the point because the
    // claim under test is about its size.
    private static final String MINIMAL_ANSWER_BODY =
            "{\"base\":\"USD\",\"date\":\"2024-01-15\",\"rates\":{\"EUR\":0.92}}";

    // Stand-ins for the two things a rejected endpoint value must never carry into a start-up log: a credential
    // somebody put in the URL, and a line break that would let the rest of the value pose as its own log record.
    private static final String SMUGGLED_SECRET = "s3cr3t-fx-token-sentinel";

    private static final String FORGED_LOG_LINE = "INFO forged-log-record-sentinel";

    private MockRestServiceServer server;

    private FrankfurterExchangeRateClient client;

    @BeforeEach
    void bindRecordedTransportToTheProductionClient() {
        CashAccountProperties properties = new CashAccountProperties();
        properties.getFx().setUrl(FX_URL);

        // mutate() carries the configuration of the bean config/FxClientConfig actually publishes, so what the
        // assertions observe - above all the absence of any default header - is the deployed client's own shape
        // rather than a lookalike assembled here. bindTo replaces the request factory, which is why no timeout is
        // asserted in this file: FxClientConfig's connect and read budget is out of play once the transport is a
        // recorder, and provoking a real timeout would need a real socket.
        RestClient.Builder builder = new FxClientConfig().fxRestClient(properties).mutate();
        server = MockRestServiceServer.bindTo(builder).bufferContent().build();
        client = new FrankfurterExchangeRateClient(builder.build(), FX_URL,
                properties.getFx().getAcceptedCurrencies());
    }

    @Test
    void shippedAcceptedCurrencySetMatchesTheConfiguredAuthority() {
        // cashaccount.fx.accepted-currencies in application.yml is the single authority every consumer binds;
        // config/CashAccountProperties keeps a compiled-in copy only so a context binding no property source still
        // yields a populated, validated object. A copy nobody compares drifts invisibly, the deployment enforcing
        // the file while such a context enforced the literal. Binder is used as the consumers use it, and Binder is
        // what every consumer uses for every cashaccount.* and tool.* value: only Binder aggregates a YAML sequence
        // into a Set, and a @Value placeholder's resolved text is handed on to Spring's expression resolver, which
        // would make a deployment-supplied #{...} executable at bean creation.
        Set<String> configured = new Binder(ConfigurationPropertySources.from(applicationYamlPropertySource()))
                .bind("cashaccount.fx.accepted-currencies", Bindable.setOf(String.class))
                .orElseThrow(() -> new AssertionError(
                        "application.yml declares no cashaccount.fx.accepted-currencies"));

        assertThat(new CashAccountProperties().getFx().getAcceptedCurrencies())
                .as("the compiled-in default and application.yml's list are one policy or they are a defect")
                .containsExactlyInAnyOrderElementsOf(configured);

        // The estate allowlist of AAP 0.7.2, whose provenance is the allowed_currencies CHECK at
        // infra/stocktrader-setup/azure/modules/postgres_init/init_schema.sql.tmpl:L7 - and not the set the rate
        // provider serves, which omits BGN. BGN is named so that dropping it from the shipped policy has to be a
        // deliberate act with this reasoning in front of whoever does it.
        assertThat(configured).hasSize(31).contains("BGN", "USD", "EUR", "GBP");
    }

    @Test
    void sameCurrencyShortCircuitsWithoutHttpCall() {
        // No expectation is declared, and that is the assertion: the recorder fails on any request it was not
        // told to expect, so an un-stubbed recorder surviving the call proves nothing left the client. Parity
        // rather than optimization: an account already in the base currency was multiplied by a RATES of 1 in the
        // program being replaced [backend/cash-account-cobol/COBOL/CASH00.cbl:L221-L222], so producing that value
        // locally reconciles exactly and no third-party outage can reach an operation that needs no conversion.
        BigDecimal rate = client.rate("USD", "USD");

        // Equality compares scale too, so this pins the returned value to the constant BigDecimal.ONE, whose
        // scale of zero says no scaling step was applied on the way out.
        assertThat(rate).isEqualTo(BigDecimal.ONE);
        server.verify();
    }

    @Test
    void crossCurrencyRequestShapeAndSingleFinalTruncation() {
        server.expect(ExpectedCount.once(), requestToFxEndpointIgnoringQuery())
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andExpect(MockRestRequestMatchers.queryParam("from", "USD"))
                .andExpect(MockRestRequestMatchers.queryParam("to", "EUR"))
                .andRespond(recordedFrankfurterResponse());

        BigDecimal rate = client.rate("USD", "EUR");

        // The composition rather than the arithmetic, which domain/MoneyTest already pins: the claim here is that
        // a rate arriving over HTTP is still raw enough to feed applyRate and produce the legacy answer. The
        // program being replaced evaluated the whole expression in one COMPUTE carrying no ROUNDED and no ON SIZE
        // ERROR [CASH00.cbl:L221-L222 credit, L255-L256 debit], truncating once as the result was stored into the
        // two-decimal WS-CALC, so a client that pre-scaled the rate or truncated the product first would answer
        // 1234567.62 and break every reconciliation of this owner by a penny. The triple is the ERIC D 0.30
        // shadow-fixture row, so the expected value is a documented one.
        assertThat(Money.applyRate(new BigDecimal("1234567.89"), Money.SIGN_DEBIT, rate, new BigDecimal("0.30")))
                .isEqualTo(new BigDecimal("1234567.61"));
        server.verify();
    }

    @Test
    void ratePreservesFullBigDecimalPrecision() {
        server.expect(ExpectedCount.once(), requestToFxEndpointIgnoringQuery())
                .andRespond(recordedFrankfurterResponse());

        // Equality compares scale as well as value, pinning the recorded rate at scale 2 - untouched by
        // setScale, round or stripTrailingZeros on the way through.
        assertThat(client.rate("USD", "EUR")).isEqualTo(new BigDecimal("0.92"));
        server.verify();

        server.reset();
        server.expect(ExpectedCount.once(), requestToFxEndpointIgnoringQuery())
                .andRespond(MockRestResponseCreators.withSuccess(OVER_PRECISE_BODY, MediaType.APPLICATION_JSON));

        // The value surviving intact rules out a binary intermediate and any pre-scaling of the rate, either of
        // which would round these digits away. The legacy rate column was RATES DECIMAL(3, 2) / PIC S9(1)V9(2)
        // COMP-3 [backend/cash-account-cobol/COBOL/DCLFRANK.cpy:L12, L22], two decimals ceilinged at 9.99; live
        // rates are wider, and the reconciliation tooling reports that width as a RATE_SOURCE variance rather
        // than having this seam absorb it.
        assertThat(client.rate("USD", "EUR")).isEqualTo(new BigDecimal(OVER_PRECISE_RATE));
        server.verify();
    }

    @Test
    void unavailableRateRaisesExchangeRateUnavailableException() {
        server.expect(ExpectedCount.once(), requestToFxEndpointIgnoringQuery())
                .andRespond(MockRestResponseCreators.withServerError());

        // Deliberate deviation, and the reason the concrete type is asserted: the legacy failed open, running its
        // COMPUTE and UPDATE even when the rate SELECT found no row [CASH00.cbl:L214-L219 then L221-L222], so the
        // miss's SQLCODE 100 was overwritten by the UPDATE's SQLCODE 0 and a balance derived from the
        // uninitialized RATES host variable [DCLFRANK.cpy:L22] was committed under a success code. Unchecked and
        // distinct from CashAccountException, it rolls the caller's transaction back instead, leaving the balance
        // unchanged and writing no ledger row; the 503 EXCHANGE_RATE_UNAVAILABLE is asserted where it is produced,
        // in the retail service. once() also records that a refused status is not retried, since only a transport
        // failure can differ on a second attempt.
        assertThatExceptionOfType(ExchangeRateUnavailableException.class)
                .isThrownBy(() -> client.rate("USD", "EUR"))
                .withMessageContaining("500")
                // No wrapped throwable by construction, not by loss: a well-formed refusal is diagnosed from
                // the status line itself, so no lower-level exception exists to carry.
                .withNoCause()
                .satisfies(refused -> {
                    assertThat(refused).isNotInstanceOf(CashAccountException.class);
                    assertThat(refused.base()).isEqualTo("USD");
                    assertThat(refused.quote()).isEqualTo("EUR");
                });
        server.verify();

        server.reset();
        server.expect(ExpectedCount.once(), requestToFxEndpointIgnoringQuery())
                .andRespond(MockRestResponseCreators.withSuccess(RATES_WITHOUT_EUR_BODY,
                        MediaType.APPLICATION_JSON));

        // A 200 whose rates omit the requested code is the failure most easily mistaken for a rate of zero, the
        // silently-wrong answer this service exists to eliminate: a zero would turn every credit and debit into a
        // no-op reporting success. The pair travels on the exception so an operator can name the unpublished
        // currency without correlating logs.
        assertThatExceptionOfType(ExchangeRateUnavailableException.class)
                .isThrownBy(() -> client.rate("USD", "EUR"))
                .satisfies(omitted -> {
                    assertThat(omitted.base()).isEqualTo("USD");
                    assertThat(omitted.quote()).isEqualTo("EUR");
                });
        server.verify();

        server.reset();
        server.expect(ExpectedCount.once(), requestToFxEndpointIgnoringQuery())
                .andRespond(MockRestResponseCreators.withSuccess("not-json", MediaType.APPLICATION_JSON));

        // An unreadable body, unlike the two well-formed answers above, does have an underlying throwable, and
        // keeping it is what lets an operator tell a provider contract change from an outage - the type of the
        // cause is the whole diagnosis, since the message deliberately omits the endpoint and the body.
        assertThatExceptionOfType(ExchangeRateUnavailableException.class)
                .isThrownBy(() -> client.rate("USD", "EUR"))
                .withCauseInstanceOf(RestClientException.class);
        server.verify();
    }

    @Test
    void extremeExponentRateIsRefusedAsUnavailable() {
        server.expect(ExpectedCount.once(), requestToFxEndpointIgnoringQuery())
                .andRespond(MockRestResponseCreators.withSuccess(EXTREME_EXPONENT_RATE_BODY,
                        MediaType.APPLICATION_JSON));

        // The third party is the least trusted input this service has, and a number's SIZE is a separate question
        // from its value: the preceding test pins that a 25-decimal rate survives untouched, while this one pins
        // that a rate whose exponent would cost seconds of CPU and hundreds of megabytes at the first setScale or
        // multiply never reaches the arithmetic at all. Refused as EXCHANGE_RATE_UNAVAILABLE and therefore as the
        // 503 the service already answers a provider outage with - the caller's balance is untouched and no ledger
        // row is written - rather than as the 500 an ArithmeticException from BigDecimal would have produced.
        //
        // No limit is named here on purpose: domain/Money owns them, exactly as it owns the scale and the rounding
        // mode, so a second copy in the fx package cannot drift away from the one the arithmetic enforces.
        assertThatExceptionOfType(ExchangeRateUnavailableException.class)
                .isThrownBy(() -> client.rate("USD", "EUR"))
                .satisfies(refused -> {
                    assertThat(refused).isNotInstanceOf(CashAccountException.class);
                    assertThat(refused.base()).isEqualTo("USD");
                    assertThat(refused.quote()).isEqualTo("EUR");
                });
        server.verify();
    }

    @Test
    void fxRequestCarriesNoAuthorizationHeader() {
        server.expect(ExpectedCount.once(), requestToFxEndpointIgnoringQuery())
                .andExpect(MockRestRequestMatchers.headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andExpect(MockRestRequestMatchers.headerDoesNotExist(HttpHeaders.PROXY_AUTHORIZATION))
                .andExpect(MockRestRequestMatchers.headerDoesNotExist(HttpHeaders.COOKIE))
                .andRespond(recordedFrankfurterResponse());

        // A structural security proof rather than a style check: broker propagates the caller's credentials into
        // this service, which validates them, while this service's only outbound hop is to a public rate API that
        // requires no credential at all - so a token arriving there would hand a third party the caller's identity
        // for nothing in return. All three header shapes are checked because a credential can travel as any of
        // them, and the absence asserted is that of the bean config/FxClientConfig publishes.
        assertThat(client.rate("USD", "EUR")).isEqualTo(new BigDecimal("0.92"));
        server.verify();
    }

    @Test
    void endpointMustBeHttpsWithAHostAndNoCredentials() {
        RestClient transport = new FxClientConfig().fxRestClient(new CashAccountProperties());

        // Refused at construction, which for the @Component is refused at start-up: a pod told to price balances
        // over plaintext never reaches readiness instead of serving rates an on-path attacker chose. That matters
        // here more than at most seams, because the rate is multiplied into every cross-currency credit and debit
        // and the result is written to the immutable ledger - a forged rate is a moved balance, not a bad read.
        // The entries past http are the ways a URL can be https and still be unusable: no host leaves the request
        // target undetermined, a credential in user-info parks a secret in a configMap value that reaches logs and
        // metrics tags, and a fragment is never put on the wire so it cannot mean what it reads as.
        List<String> refused = List.of(
                "http://fx.test.invalid/latest",
                "HTTP://fx.test.invalid/latest",
                "ftp://fx.test.invalid/latest",
                "//fx.test.invalid/latest",
                "/latest",
                "https:///latest",
                "https:latest",
                "https://operator:secret@fx.test.invalid/latest",
                "https://fx.test.invalid/latest#fragment",
                "ht tp://fx.test.invalid/latest",
                "   ");
        for (String endpoint : refused) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("endpoint %s", endpoint)
                    .isThrownBy(() -> new FrankfurterExchangeRateClient(transport, endpoint))
                    .withMessageContaining("cashaccount.fx.url");
        }
        // Cast because the container constructor also takes two arguments; the absent endpoint is the case here.
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new FrankfurterExchangeRateClient(transport, (String) null))
                .withMessageContaining("cashaccount.fx.url");

        // The rejected scheme is named, and only the scheme: its grammar is letters, digits and "+-." so it cannot
        // carry a forged log record, while the rest of an operator-controlled value could. Naming it is what makes
        // the start-up failure self-diagnosing rather than a generic refusal.
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new FrankfurterExchangeRateClient(transport, "http://fx.test.invalid/latest"))
                .withMessageContaining("https")
                .withMessageContaining("http");

        // A malformed value is refused without its parser exception travelling with it. URISyntaxException renders
        // the entire rejected input in its message, and a context that fails to refresh has every nested cause
        // printed, so chaining it would put whatever was configured - a token, or a CR/LF-forged log record -
        // into the start-up log. The whole rendered failure is searched, not just the message, because the
        // disclosure would be in the cause chain rather than at the top of it.
        String smuggled = "https://fx.test.invalid/lat est?token=" + SMUGGLED_SECRET + "\n" + FORGED_LOG_LINE;
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new FrankfurterExchangeRateClient(transport, smuggled))
                .withMessageContaining("cashaccount.fx.url")
                .satisfies(rejection -> {
                    assertThat(rejection).hasNoCause();
                    assertThat(renderedFailure(rejection))
                            .doesNotContain(SMUGGLED_SECRET)
                            .doesNotContain(FORGED_LOG_LINE);
                });

        // Accepted, padding and all: the guard refuses schemes, not whitespace, so a chart value that arrived with
        // a stray newline still starts. Nothing is dialled - construction never opens a socket.
        assertThatNoException()
                .isThrownBy(() -> new FrankfurterExchangeRateClient(transport, "  " + FX_URL + "\n"));
    }

    @Test
    void oversizeOrOverCardinalityAnswerIsRejectedAsUnavailable() {
        // A timeout bounds how long a third party may hold a request thread and says nothing about how much it may
        // make this process allocate. The four drives below are the four ways that gap gets closed, and each is the
        // whole claim: an answer that breaches a bound is never bound, so no rate reaches domain.Money and the
        // caller's 503 leaves the balance and the ledger untouched.
        //
        // 1. A declared length above the ceiling, refused before a body byte is read.
        server.expect(ExpectedCount.once(), requestToFxEndpointIgnoringQuery())
                .andRespond(MockRestResponseCreators.withSuccess(MINIMAL_ANSWER_BODY, MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.CONTENT_LENGTH,
                                Long.toString(FxClientConfig.MAX_RESPONSE_BYTES + 1L)));

        assertThatExceptionOfType(ExchangeRateUnavailableException.class)
                .isThrownBy(() -> client.rate("USD", "EUR"))
                // The cause is asserted as a RestClientException and not an I/O failure on purpose: that type is
                // what tells the client the outcome is settled, so an oversize answer is refused once instead of
                // being read a second time as though a retry could change it.
                .withCauseInstanceOf(RestClientException.class)
                .satisfies(refused -> assertThat(refused.getCause())
                        .hasMessageContaining(String.valueOf(FxClientConfig.MAX_RESPONSE_BYTES))
                        .hasMessageContaining(HttpHeaders.CONTENT_LENGTH));
        server.verify();

        // 2. The same excess with no declared length at all - chunked, or delimited by the connection closing -
        //    which is the framing a hostile endpoint would choose precisely because a header check cannot see it.
        //    The padding sits inside the object so it has to be consumed before the rate is reached.
        server.reset();
        server.expect(ExpectedCount.once(), requestToFxEndpointIgnoringQuery())
                .andRespond(MockRestResponseCreators.withSuccess(
                        answerOfExactly(FxClientConfig.MAX_RESPONSE_BYTES + 1), MediaType.APPLICATION_JSON));

        assertThatExceptionOfType(ExchangeRateUnavailableException.class)
                .isThrownBy(() -> client.rate("USD", "EUR"))
                .withCauseInstanceOf(RestClientException.class)
                .satisfies(refused -> assertThat(refused.getCause())
                        .hasMessageContaining(String.valueOf(FxClientConfig.MAX_RESPONSE_BYTES)));
        server.verify();

        // 3. One byte less, by the same construction, still parses. Without this the bound could be off by any
        //    margin - or reject everything - and the two drives above would look identical.
        server.reset();
        server.expect(ExpectedCount.once(), requestToFxEndpointIgnoringQuery())
                .andRespond(MockRestResponseCreators.withSuccess(
                        answerOfExactly(FxClientConfig.MAX_RESPONSE_BYTES), MediaType.APPLICATION_JSON));

        assertThat(client.rate("USD", "EUR")).isEqualTo(new BigDecimal("0.92"));
        server.verify();

        // 4. Cardinality, the half the byte ceiling cannot express: an answer well under the ceiling that still
        //    carries more rates than the contract has currencies. At the limit it is accepted, one past it is not,
        //    and both bodies stay under the byte ceiling so this drive can only be proving the entry bound.
        server.reset();
        server.expect(ExpectedCount.once(), requestToFxEndpointIgnoringQuery())
                .andRespond(MockRestResponseCreators.withSuccess(
                        ratesAnswerCarrying(FrankfurterExchangeRateClient.MAX_RATE_ENTRIES),
                        MediaType.APPLICATION_JSON));

        assertThat(client.rate("USD", "EUR")).isEqualTo(new BigDecimal("0.92"));
        server.verify();

        server.reset();
        server.expect(ExpectedCount.once(), requestToFxEndpointIgnoringQuery())
                .andRespond(MockRestResponseCreators.withSuccess(
                        ratesAnswerCarrying(FrankfurterExchangeRateClient.MAX_RATE_ENTRIES + 1),
                        MediaType.APPLICATION_JSON));

        assertThatExceptionOfType(ExchangeRateUnavailableException.class)
                .isThrownBy(() -> client.rate("USD", "EUR"))
                .withMessageContaining(String.valueOf(FrankfurterExchangeRateClient.MAX_RATE_ENTRIES))
                // No cause, because nothing lower down failed: the answer arrived whole and was refused on its
                // own shape, which is what distinguishes this from the transport bound above.
                .withNoCause();
        server.verify();
    }

    // Message and cause chain together, exactly as a start-up failure is printed, so a value hidden one level down
    // is still in scope for the assertion.
    private static String renderedFailure(Throwable thrown) {
        StringWriter rendered = new StringWriter();
        thrown.printStackTrace(new PrintWriter(rendered));
        return rendered.toString();
    }

    // Padded inside the object rather than after it: a parser stops at the closing brace, so trailing bytes might
    // never be read and a size bound asserted on them would pass while bounding nothing.
    private static String answerOfExactly(int totalBytes) {
        String head = "{\"padding\":\"";
        String tail = "\",\"base\":\"USD\",\"rates\":{\"EUR\":0.92}}";
        int padding = totalBytes - head.length() - tail.length();
        assertThat(padding).as("padding width for a %d byte answer", totalBytes).isPositive();
        // ASCII throughout, so the character count the caller asked for is the byte count on the wire.
        return head + "x".repeat(padding) + tail;
    }

    // EUR is always present, so an unbounded client would have answered 0.92 from any of these bodies and the
    // rejection can only be the entry bound rather than a missing rate.
    private static String ratesAnswerCarrying(int entries) {
        StringBuilder body = new StringBuilder("{\"base\":\"USD\",\"date\":\"2024-01-15\",\"rates\":{\"EUR\":0.92");
        for (int entry = 1; entry < entries; entry++) {
            body.append(",\"K").append(entry).append("\":1.01");
        }
        String answer = body.append("}}").toString();
        assertThat(answer.length())
                .as("a cardinality fixture must stay under the byte ceiling to prove the entry bound alone")
                .isLessThan(FxClientConfig.MAX_RESPONSE_BYTES);
        return answer;
    }

    // The query is stripped here and the parameters matched by name, so no claim depends on the order the
    // client happens to append them in.
    private static RequestMatcher requestToFxEndpointIgnoringQuery() {
        return request -> {
            URI uri = request.getURI();
            assertThat(uri.getScheme() + "://" + uri.getAuthority() + uri.getPath()).isEqualTo(FX_URL);
        };
    }

    // The shipped application.yml itself, loaded with Spring Boot's own YAML loader: a hand-written copy of the
    // list would be a third declaration of the policy this test exists to keep single. It fails and never skips
    // when the file or the key is absent, because either means the authority the running service binds is gone.
    private static PropertySource<?> applicationYamlPropertySource() {
        ClassPathResource applicationYaml = new ClassPathResource("application.yml");
        assertThat(applicationYaml.exists()).as("src/main/resources/application.yml on the test classpath").isTrue();
        try {
            List<PropertySource<?>> sources =
                    new YamlPropertySourceLoader().load("application.yml", applicationYaml);
            assertThat(sources).as("YAML documents in application.yml").hasSize(1);
            return sources.get(0);
        } catch (IOException unreadable) {
            throw new AssertionError("application.yml could not be read", unreadable);
        }
    }

    private static ResponseCreator recordedFrankfurterResponse() {
        return MockRestResponseCreators.withSuccess(new ClassPathResource(RECORDED_RESPONSE),
                MediaType.APPLICATION_JSON);
    }
}
