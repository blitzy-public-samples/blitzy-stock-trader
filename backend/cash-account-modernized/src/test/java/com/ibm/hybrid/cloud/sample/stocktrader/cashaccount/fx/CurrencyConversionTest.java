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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
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

    // A host in the reserved .invalid TLD, which by definition can never resolve. The recorder intercepts every
    // call, so this value is never dialled; naming an unresolvable host means that even a future mistake in the
    // harness fails locally instead of reaching the public rate service, which this module is never permitted to
    // call - its tooling and tests are verified against fixtures only.
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

    private MockRestServiceServer server;

    private FrankfurterExchangeRateClient client;

    @BeforeEach
    void bindRecordedTransportToTheProductionClient() {
        CashAccountProperties properties = new CashAccountProperties();
        properties.getFx().setUrl(FX_URL);

        // The client under test is handed the bean config/FxClientConfig actually publishes rather than a lookalike
        // assembled here: mutate() yields a builder that carries that bean's configuration, so what the assertions
        // observe - above all the absence of any default header - is the deployed client's own shape, and a header
        // added there in future fails this test instead of slipping through.
        //
        // bindTo replaces the request factory, which is why no timeout is asserted anywhere in this file: the
        // connect and read budget FxClientConfig applies is no longer in play once the transport is a recorder, and
        // provoking a real timeout would need a real socket.
        RestClient.Builder builder = new FxClientConfig().fxRestClient(properties).mutate();
        server = MockRestServiceServer.bindTo(builder).bufferContent().build();
        client = new FrankfurterExchangeRateClient(builder.build(), FX_URL,
                properties.getFx().getAcceptedCurrencies());
    }

    @Test
    void shippedAcceptedCurrencySetMatchesTheConfiguredAuthority() {
        // cashaccount.fx.accepted-currencies in application.yml is the single authority every consumer binds;
        // config/CashAccountProperties keeps a compiled-in copy only so that a context binding no property source
        // still yields a fully populated, validated object. A copy nobody compares is a copy that drifts, and the
        // drift would be invisible: the deployment would enforce the file while every such context enforced the
        // literal. Binder is used exactly as the consumers use it, so this also pins the property's SHAPE - a
        // YAML sequence that only Binder can aggregate, which is why no consumer may bind it with @Value.
        Set<String> configured = new Binder(ConfigurationPropertySources.from(applicationYamlPropertySource()))
                .bind("cashaccount.fx.accepted-currencies", Bindable.setOf(String.class))
                .orElseThrow(() -> new AssertionError(
                        "application.yml declares no cashaccount.fx.accepted-currencies"));

        assertThat(new CashAccountProperties().getFx().getAcceptedCurrencies())
                .as("the compiled-in default and application.yml's list are one policy or they are a defect")
                .containsExactlyInAnyOrderElementsOf(configured);

        // The estate allowlist of AAP 0.7.2, whose provenance is the allowed_currencies CHECK at
        // infra/stocktrader-setup/azure/modules/postgres_init/init_schema.sql.tmpl:L7 - and NOT the set the rate
        // provider serves, which omits BGN. BGN is named here so that removing it from the shipped policy has to
        // be a deliberate act with this test's reasoning in front of whoever does it.
        assertThat(configured).hasSize(31).contains("BGN", "USD", "EUR", "GBP");
    }

    @Test
    void sameCurrencyShortCircuitsWithoutHttpCall() {
        // No expectation is declared, and that is the assertion: the recorder fails on any request it was not told
        // to expect, so an un-stubbed recorder surviving the call proves nothing left the client.
        //
        // Parity rather than optimization. An account already in the base currency was multiplied by a RATES of 1
        // in the program being replaced [backend/cash-account-cobol/COBOL/CASH00.cbl:L221-L222], so producing that
        // value locally reconciles exactly, and it refuses to let a third-party outage reach an operation that
        // needs no conversion at all.
        BigDecimal rate = client.rate("USD", "USD");

        // isEqualTo rather than isEqualByComparingTo: equality compares scale too, so this pins the returned value
        // to the constant BigDecimal.ONE, whose scale of zero says no scaling step was applied on the way out.
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

        // What this asserts is the composition, not the arithmetic: domain/MoneyTest already pins applyRate on
        // hand-written operands, so the claim here is that a rate arriving over HTTP is still raw enough to feed
        // it and produce the legacy answer.
        //
        // The program being replaced evaluated the whole expression in one COMPUTE carrying no ROUNDED and no ON
        // SIZE ERROR [CASH00.cbl:L221-L222 for credit, L255-L256 for debit], truncating exactly once as the result
        // was stored into the two-decimal WS-CALC. So: 0.92 x 0.30 = 0.2760, then 1234567.89 - 0.2760 =
        // 1234567.6140, truncated once to 1234567.61. A client that pre-scaled the rate, or that truncated the
        // product before the subtraction, would answer 1234567.62 and every reconciliation of this owner would
        // break by a penny with nothing failing to announce it. The triple is the plan's ERIC D 0.30 shadow-fixture
        // row, so the expected value is a documented one.
        assertThat(Money.applyRate(new BigDecimal("1234567.89"), Money.SIGN_DEBIT, rate, new BigDecimal("0.30")))
                .isEqualTo(new BigDecimal("1234567.61"));
        server.verify();
    }

    @Test
    void ratePreservesFullBigDecimalPrecision() {
        server.expect(ExpectedCount.once(), requestToFxEndpointIgnoringQuery())
                .andRespond(recordedFrankfurterResponse());

        // Equality rather than comparison, deliberately: it compares scale as well as value, so this pins the
        // recorded 0.92 at scale 2 - untouched by setScale, round or stripTrailingZeros on the way through.
        assertThat(client.rate("USD", "EUR")).isEqualTo(new BigDecimal("0.92"));
        server.verify();

        server.reset();
        server.expect(ExpectedCount.once(), requestToFxEndpointIgnoringQuery())
                .andRespond(MockRestResponseCreators.withSuccess(OVER_PRECISE_BODY, MediaType.APPLICATION_JSON));

        // The value surviving intact is what rules out a binary intermediate - BigDecimal.valueOf over a binary
        // operand, or a parse that went through one - and any pre-scaling of the rate, because either would round
        // these digits away. The contrast with the legacy is the point: its rate column was RATES DECIMAL(3, 2) /
        // PIC S9(1)V9(2) COMP-3 [backend/cash-account-cobol/COBOL/DCLFRANK.cpy:L12, L22], two decimals ceilinged
        // at 9.99, which could not represent a currency worth less than a tenth of the base unit. Live rates are
        // wider, and the reconciliation tooling reports that width as a RATE_SOURCE variance rather than having
        // this seam absorb it.
        assertThat(client.rate("USD", "EUR")).isEqualTo(new BigDecimal(OVER_PRECISE_RATE));
        server.verify();
    }

    @Test
    void unavailableRateRaisesExchangeRateUnavailableException() {
        server.expect(ExpectedCount.once(), requestToFxEndpointIgnoringQuery())
                .andRespond(MockRestResponseCreators.withServerError());

        // Deliberate deviation, and the reason the concrete type is asserted. The legacy failed open: CASH-ACCT-
        // CREDIT and CASH-ACCT-DEBIT ran their COMPUTE and UPDATE even when the rate SELECT found no row
        // [CASH00.cbl:L214-L219 then L221-L222], so the miss's SQLCODE 100 was overwritten by the UPDATE's
        // SQLCODE 0 and a balance derived from the uninitialized RATES host variable [DCLFRANK.cpy:L22] was
        // committed under a success code. Being unchecked and distinct from CashAccountException is what makes the
        // caller's transaction roll back instead, leaving the balance unchanged and writing no ledger row. The 503
        // EXCHANGE_RATE_UNAVAILABLE and its Retry-After are asserted where they are produced, in the retail
        // service, not at this seam.
        //
        // once() also records that a refused status is not retried: only a transport failure can differ on a
        // second attempt, so retrying a settled outcome would just spend the caller's patience.
        assertThatExceptionOfType(ExchangeRateUnavailableException.class)
                .isThrownBy(() -> client.rate("USD", "EUR"))
                .withMessageContaining("500")
                // No wrapped throwable by construction, not by loss: a well-formed refusal is diagnosed from the
                // status line itself, so no lower-level exception exists to carry.
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

        // A 200 whose rates omit the requested code is the failure most easily mistaken for a rate of zero, which
        // is exactly the silently-wrong answer this service exists to eliminate - a zero would turn every credit
        // and debit into a no-op that reports success. The pair travels on the exception so an operator can name
        // the unpublished currency without correlating logs.
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

        // The third drive carries the half of the contract the first two cannot: both of those are diagnosed from
        // a well-formed answer and so have no underlying throwable to keep, while an unreadable body does. Keeping
        // it is what leaves an operator able to tell a provider contract change from an outage, since the type of
        // the cause is the whole diagnosis once the message deliberately omits the endpoint and the body.
        assertThatExceptionOfType(ExchangeRateUnavailableException.class)
                .isThrownBy(() -> client.rate("USD", "EUR"))
                .withCauseInstanceOf(RestClientException.class);
        server.verify();
    }

    @Test
    void fxRequestCarriesNoAuthorizationHeader() {
        server.expect(ExpectedCount.once(), requestToFxEndpointIgnoringQuery())
                .andExpect(MockRestRequestMatchers.headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andExpect(MockRestRequestMatchers.headerDoesNotExist(HttpHeaders.PROXY_AUTHORIZATION))
                .andExpect(MockRestRequestMatchers.headerDoesNotExist(HttpHeaders.COOKIE))
                .andRespond(recordedFrankfurterResponse());

        // A structural security proof rather than a style check. Broker propagates the caller's credentials INTO
        // this service, which validates them; this service's only outbound hop is to a public rate API that
        // requires no credential whatsoever, so a token arriving there would hand a third party the caller's
        // identity for nothing in return. All three header shapes are checked because a credential can travel as
        // any of them. The harness mutates the bean config/FxClientConfig publishes, so the absence asserted is
        // that bean's own: a default header set there, or a customizer reaching its builder, fails here.
        assertThat(client.rate("USD", "EUR")).isEqualTo(new BigDecimal("0.92"));
        server.verify();
    }

    // The endpoint is matched with its query stripped while the parameters are matched by name, so neither claim
    // depends on the order the client happens to append them in.
    private static RequestMatcher requestToFxEndpointIgnoringQuery() {
        return request -> {
            URI uri = request.getURI();
            assertThat(uri.getScheme() + "://" + uri.getAuthority() + uri.getPath()).isEqualTo(FX_URL);
        };
    }

    // The shipped application.yml itself, loaded with Spring Boot's own YAML loader rather than re-typed here:
    // a hand-written copy of the list would be a third declaration of the very policy this test exists to keep
    // single. It FAILS and never skips when the file or the key is absent, because either would mean the
    // authority the running service binds does not exist.
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

    // Served straight from the classpath resource: the recorded body is replayed byte for byte, so no string
    // surgery here can quietly change the contract under test.
    private static ResponseCreator recordedFrankfurterResponse() {
        return MockRestResponseCreators.withSuccess(new ClassPathResource(RECORDED_RESPONSE),
                MediaType.APPLICATION_JSON);
    }
}
