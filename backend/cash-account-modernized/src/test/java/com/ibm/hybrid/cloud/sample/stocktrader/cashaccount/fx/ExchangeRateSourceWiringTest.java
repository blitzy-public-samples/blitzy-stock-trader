package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.fx;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.config.CashAccountProperties;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.config.FxClientConfig;

/** Pins the deployed FX wiring: one ExchangeRateSource bean, the live client, with the staged legacy source absent. */
class ExchangeRateSourceWiringTest {

    // A bare runner loads no config data, so application.yml's ${CURRENCY_API_URL:...} default does not apply and
    // cashaccount.fx.url has to be supplied for FrankfurterExchangeRateClient to bind it. Port 1 refuses
    // instantly, as in application-test.yml, so the context stays offline by construction (AAP 0.3.2); https
    // because the client refuses a plaintext endpoint at construction, which plaintextEndpointFailsContextStartup
    // holds from the other side.
    private static final String OFFLINE_FX_URL = "cashaccount.fx.url=https://127.0.0.1:1/latest";

    private static final String PLAINTEXT_FX_URL = "cashaccount.fx.url=http://127.0.0.1:1/latest";

    // The system property the payload below would set if the endpoint were ever evaluated as an expression. A
    // system property is the observation of choice because it survives the context that would have set it,
    // whatever becomes of that context.
    private static final String EVALUATION_MARKER = "cashaccount.fx.url.expression-evaluated";

    // A well-formed endpoint carrying a SpEL expression, exactly as a configMap edit to cashAccount.exchangeRateUrl
    // could deliver one through CURRENCY_API_URL.
    private static final String EXPRESSION_FX_URL = "cashaccount.fx.url=https://127.0.0.1:1/latest"
            + "#{T(java.lang.System).setProperty('" + EVALUATION_MARKER + "','yes')}";

    // An ApplicationContextRunner needs no Docker, so the module's proof that the staged legacy source cannot
    // reach the request path (AAP 0.6.5) runs on every checkout instead of being skipped on a Docker-less one.
    private final ApplicationContextRunner deployedWiring = new ApplicationContextRunner()
            .withUserConfiguration(DeployedExchangeRateWiring.class)
            .withPropertyValues(OFFLINE_FX_URL);

    @Test
    void defaultProfileExposesOnlyTheLiveExchangeRateSource() {
        deployedWiring.run(context -> {
            // Asserted first: every claim below is about a context that started, and a refresh failure would
            // otherwise be reported as a missing bean and read as if it proved absence.
            assertThat(context).hasNotFailed();

            // "Default profile" in AAP 0.6.1/0.6.5 means the deployed, non-tool wiring, so an active-profiles
            // override slipping tool back in fails here, on the cheapest assertion in the file.
            assertThat(context.getEnvironment().getActiveProfiles()).doesNotContain("tool");

            // Exactly one, so RetailCashAccountService's injection of the bare interface can resolve to nothing
            // but the live client. Counting before typing is what turns a second implementation on the deployed
            // classpath into a failure here instead of a NoUniqueBeanDefinitionException at a first conversion.
            assertThat(context).hasSingleBean(ExchangeRateSource.class);

            // By type rather than by bean name: the @Component-derived name is incidental, and renaming the
            // class must not be what breaks this test.
            assertThat(context.getBean(ExchangeRateSource.class)).isInstanceOf(FrankfurterExchangeRateClient.class);

            // Absence is the guard, and the half of the test that matters most: LegacyRateTableSource replays the
            // legacy in-database join, which selected RATES keyed on the account currency's first five characters
            // [backend/cash-account-cobol/COBOL/CASH00.cbl:L213-L219] out of a DECIMAL(3, 2) column
            // [backend/cash-account-cobol/COBOL/DCLFRANK.cpy:L12], frozen at some past LOADDT. That table is a
            // migration-source artifact and never a target-state dependency (AAP 0.6.5, 0.12.5), so a bean of it
            // on the request path would price live credits and debits from a stale, coarse rate and answer 200
            // while doing it. @Profile("tool") is the containment boundary; this holds it.
            assertThat(context).doesNotHaveBean(LegacyRateTableSource.class);

            // The tool-profile delegate is @Primary in that profile, so present here it would outrank the live
            // client at every injection point and make "exactly one" arithmetically true but not conclusive.
            assertThat(context).doesNotHaveBean(ToolExchangeRateSource.class);
        });
    }

    @Test
    void deploymentSuppliedEndpointIsBoundAsInertDataRatherThanEvaluated() {
        // The endpoint is the one value in this module that arrives from a configMap key an operator edits
        // (CURRENCY_API_URL <- cashAccount.exchangeRateUrl, .../templates/cash-account.yaml:L156-L160), and Spring
        // hands a resolved @Value placeholder to its expression resolver, so reading it with @Value would make
        // configMap edit access equivalent to code execution in the pod. This asserts the mechanism used instead:
        // Binder resolves ${...} and converts, and evaluates nothing. Only the marker is asserted, deliberately -
        // whether the context then starts depends on what the endpoint validation makes of a URL carrying braces
        // and a fragment, which is free to tighten; the test above proves this same wiring starts from an ordinary
        // endpoint, so the only thing this payload can change is whether its text is data or code.
        System.clearProperty(EVALUATION_MARKER);
        try {
            new ApplicationContextRunner()
                    .withUserConfiguration(DeployedExchangeRateWiring.class)
                    .withPropertyValues(EXPRESSION_FX_URL)
                    .run(context -> assertThat(System.getProperty(EVALUATION_MARKER))
                            .as("cashaccount.fx.url was evaluated as an expression during bean creation")
                            .isNull());
        } finally {
            System.clearProperty(EVALUATION_MARKER);
        }
    }

    @Test
    void plaintextEndpointFailsContextStartup() {
        // The refusal is asserted at the boundary it has to hold at: a context, not a constructor. A rate is
        // multiplied into every cross-currency credit and debit and written to the ledger, so an endpoint an
        // on-path attacker can rewrite is an endpoint that can move money - which makes a plaintext CURRENCY_API_URL
        // a configuration the pod must refuse to start on rather than one it serves wrong balances under until
        // somebody reads a log. Failing at refresh is what turns it into a readiness failure the operator sees.
        deployedWiring.withPropertyValues(PLAINTEXT_FX_URL).run(context -> {
            assertThat(context).hasFailed();

            // The message is asserted, not just the failure, because a context can fail for a hundred reasons and
            // an assertion that accepts any of them stops proving this one.
            assertThat(context).getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cashaccount.fx.url")
                    .hasMessageContaining("https");
        });
    }

    /** The deployed exchange-rate wiring, and nothing else that would need a datasource or a servlet. */
    @Configuration(proxyBeanMethods = false)
    // Imported, not reproduced, so the real @Qualifier("fxRestClient") wiring, redirect policy and timeout
    // budget are the ones exercised, over CashAccountProperties bound from deployed defaults.
    @Import(FxClientConfig.class)
    @EnableConfigurationProperties(CashAccountProperties.class)
    // Scanned rather than hand-registered because component scanning with @Profile evaluated is the mechanism
    // under test, the same one CashAccountApplication performs; useDefaultFilters = false with the assignable-type
    // filter admits no repository, controller or entity, hence no database. The anchor package holds every
    // src/main ExchangeRateSource and no test-tree one, so an implementation added outside it escapes this scan -
    // and then fails every *IT, since RetailCashAccountService injects the bare interface.
    @ComponentScan(
            basePackageClasses = ExchangeRateSource.class,
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = ExchangeRateSource.class))
    static class DeployedExchangeRateWiring {
    }
}
