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

    // Supplied here because a bare runner loads no config data, so application.yml's own
    // url: ${CURRENCY_API_URL:https://api.frankfurter.app/latest} never applies and
    // FrankfurterExchangeRateClient's @Value("${cashaccount.fx.url}") would not resolve. Port 1 refuses instantly,
    // as in application-test.yml, so the context stays offline by construction (AAP 0.3.2) though nothing calls out.
    private static final String OFFLINE_FX_URL = "cashaccount.fx.url=http://127.0.0.1:1/latest";

    // A runner, not the container-backed @SpringBootTest this class used to be: Surefire collects it and AAP 0.7.6
    // budgets it as a unit test, yet extending support/PostgresTestSupport for datasource details it never uses made
    // it @Testcontainers(disabledWithoutDocker = true) - so on a Docker-less checkout the module's only proof that
    // the staged legacy source cannot reach the request path (AAP 0.6.5) was skipped while the suite stayed green.
    // That also makes the base class's inherited @AutoConfigureObservability unnecessary here: no component-scanned
    // config/MetricsScrapeController, and no TestContext-framework context for its customizer to act on.
    private final ApplicationContextRunner deployedWiring = new ApplicationContextRunner()
            .withUserConfiguration(DeployedExchangeRateWiring.class)
            .withPropertyValues(OFFLINE_FX_URL);

    @Test
    void defaultProfileExposesOnlyTheLiveExchangeRateSource() {
        deployedWiring.run(context -> {
            // Asserted first and explicitly: every claim below is a claim about a context that started, and a
            // refresh failure would otherwise be reported as a missing bean and read as if it proved absence.
            assertThat(context).hasNotFailed();

            // "Default profile" in AAP 0.6.1/0.6.5 means the deployed, non-tool wiring. Stating tool's absence
            // makes that premise explicit rather than incidental, and fails here, on the cheapest assertion in the
            // file, if a spring.profiles.active override ever slips it back in.
            assertThat(context.getEnvironment().getActiveProfiles()).doesNotContain("tool");

            // Exactly one, so RetailCashAccountService's injection of the bare interface can resolve to nothing but
            // the live client: no qualifier, no @Primary and no bean ordering gets a say. Asserting the count before
            // the type is what turns a second implementation appearing on the deployed classpath into a failure
            // here instead of a NoUniqueBeanDefinitionException at a caller's first conversion.
            assertThat(context).hasSingleBean(ExchangeRateSource.class);

            // Asserted by type rather than by bean name: the @Component-derived name is incidental to the design
            // and renaming the class must not be the thing that breaks this test.
            assertThat(context.getBean(ExchangeRateSource.class)).isInstanceOf(FrankfurterExchangeRateClient.class);

            // Absence is the guard, and this is the half of the test that matters most. LegacyRateTableSource
            // replays the legacy in-database join, which selected RATES keyed on the account currency's first five
            // characters [backend/cash-account-cobol/COBOL/CASH00.cbl:L213-L219] out of a DECIMAL(3, 2) column
            // [backend/cash-account-cobol/COBOL/DCLFRANK.cpy:L12] - a two-decimal rate frozen at some past LOADDT.
            // That table is a migration-source artifact and never a target-state dependency (AAP 0.6.5, 0.12.5),
            // so a bean of it on the request path would price live retail credits and debits from a stale, coarse
            // rate and return 200 while doing it. @Profile("tool") is the containment boundary; this holds it.
            assertThat(context).doesNotHaveBean(LegacyRateTableSource.class);

            // The same claim for the tool-profile delegate, which is @Primary in that profile: present here it
            // would outrank the live client at every injection point, so its absence is what makes "exactly one"
            // conclusive rather than merely arithmetically true.
            assertThat(context).doesNotHaveBean(ToolExchangeRateSource.class);
        });
    }

    /** The deployed exchange-rate wiring, and nothing else that would need a datasource or a servlet. */
    @Configuration(proxyBeanMethods = false)
    // Imported, not reproduced, so the real @Qualifier("fxRestClient") wiring, redirect policy and timeout budget
    // are the ones exercised; CashAccountProperties is what its @Bean method binds, from deployed defaults.
    @Import(FxClientConfig.class)
    @EnableConfigurationProperties(CashAccountProperties.class)
    // Scanned rather than hand-registered because component scanning with @Profile evaluated IS the mechanism under
    // test, the same one CashAccountApplication performs; useDefaultFilters = false with the assignable-type filter
    // keeps it to that and admits no repository, controller or entity, hence no database. The anchor package holds
    // every src/main ExchangeRateSource and no test-tree one. An implementation added OUTSIDE it would escape this
    // scan; RetailCashAccountService injects the bare interface, so a second unprofiled bean fails every *IT.
    @ComponentScan(
            basePackageClasses = ExchangeRateSource.class,
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = ExchangeRateSource.class))
    static class DeployedExchangeRateWiring {
    }
}
