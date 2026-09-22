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

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.PostgresTestSupport;

/** Pins the deployed FX wiring: one ExchangeRateSource bean, the live client, with the staged legacy source absent. */
@SpringBootTest
class ExchangeRateSourceWiringTest extends PostgresTestSupport {

    // The container itself is the subject, so it is injected instead of any single bean: the claim being made is a
    // fact about the bean population, and a population can only be read from the registry. The default MOCK web
    // environment is deliberate - nothing here issues a request, so binding a port would prove nothing extra.
    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Test
    void defaultProfileExposesOnlyTheLiveExchangeRateSource() {
        // "Default profile" in AAP 0.6.1/0.6.5 means the deployed, non-tool wiring, not a context with zero profiles
        // active: the test profile PostgresTestSupport activates carries configuration values only - an offline
        // fx.url, a container-backed datasource - and contributes no ExchangeRateSource bean of its own. Stating
        // tool's absence makes that premise explicit rather than incidental, and fails here, on the cheapest
        // assertion in the file, if an @ActiveProfiles or spring.profiles.active override ever slips it back in.
        assertThat(environment.getActiveProfiles()).doesNotContain("tool");

        Map<String, ExchangeRateSource> sources = context.getBeansOfType(ExchangeRateSource.class);

        // Exactly one, so RetailCashAccountService's injection of the bare interface can resolve to nothing but the
        // live client: no qualifier, no @Primary and no bean ordering gets a say. Asserting the count before the
        // type is what turns a second implementation appearing on the deployed classpath into a failure here
        // instead of a NoUniqueBeanDefinitionException at a caller's first conversion.
        assertThat(sources).hasSize(1);

        // Asserted by type rather than by bean name: the @Component-derived name is incidental to the design and
        // renaming the class must not be the thing that breaks this test.
        assertThat(sources.values()).singleElement().isInstanceOf(FrankfurterExchangeRateClient.class);

        // Absence is the guard, and this is the half of the test that matters most. LegacyRateTableSource replays
        // the legacy in-database join, which selected RATES keyed on the account currency's first five characters
        // [backend/cash-account-cobol/COBOL/CASH00.cbl:L213-L219] out of a DECIMAL(3, 2) column
        // [backend/cash-account-cobol/COBOL/DCLFRANK.cpy:L12] - a two-decimal rate frozen at some past LOADDT.
        // That table is a migration-source artifact and never a target-state dependency (AAP 0.6.5, 0.12.5), so a
        // bean of it on the request path would price live retail credits and debits from a stale, coarse rate and
        // return 200 while doing it. @Profile("tool") is the containment boundary; this assertion holds it.
        assertThat(context.getBeansOfType(LegacyRateTableSource.class)).isEmpty();

        // The same claim for the tool-profile delegate, which is @Primary in that profile: present here it would
        // outrank the live client at every injection point, so its absence is what makes "exactly one" conclusive
        // rather than merely arithmetically true.
        assertThat(context.getBeansOfType(ToolExchangeRateSource.class)).isEmpty();
    }
}
