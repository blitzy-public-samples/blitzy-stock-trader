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

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

// Transport only. The rate lookup itself lives in fx/FrankfurterExchangeRateClient, and no ExchangeRateSource bean
// is declared here on purpose: fx/ExchangeRateSourceWiringTest asserts that the deployed profile holds exactly one
// such bean, and the staged legacy rate table must never become reachable from the request path. That split also
// keeps the dependency direction the module is built on - fx receives a framework type it can consume without
// importing this package, so config still wires everything and is itself depended on by nothing.
//
// No base URL is configured. The endpoint is a deployment value, not a property of this code: the chart injects it
// as CURRENCY_API_URL from configMap key cashAccount.exchangeRateUrl
// [infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L156-L160], the client reads that
// value as cashaccount.fx.url and sends it as an absolute URI. A base URL here would be either dead weight or, if
// the two ever disagreed, a silent rewrite of the request target - so the host stays unknown to this file.
//
// No default headers of any kind are set, and that absence is the security control rather than an omission.
// Broker forwards the caller's credentials into this service - propagateHeaders=Authorization,Proxy-Authorization
// [backend/broker/src/main/resources/META-INF/microprofile-config.properties:L1] - and this service's only
// outbound call is to a public third-party exchange-rate API that requires no credential whatsoever. Forwarding a
// caller's token there would be a security defect, so no header is ever attached by default and nothing is
// registered on this client that could add one. The lookup is driven from the plain RestClient.builder() rather
// than the auto-configured builder bean for the same reason: a customizer contributed elsewhere in the context
// cannot reach a builder that was never exposed to one. fx/CurrencyConversionTest mutates this very bean and
// asserts the recorded request carries no credential header, so the guarantee is checked and not merely stated.
/** Publishes the single bounded, header-free HTTP client used for the outbound exchange-rate lookup. */
@Configuration
public class FxClientConfig {

    /**
     * The one {@link RestClient} bean in the application context, named so that injection by type, by name and by
     * {@code @Qualifier("fxRestClient")} all resolve to it.
     *
     * @param properties source of {@code cashaccount.fx.timeout}, the connect and read budget applied below
     * @return a client with bounded timeouts, no default headers and no base URL
     */
    @Bean
    public RestClient fxRestClient(CashAccountProperties properties) {
        // One budget, applied to both phases of the call, because either one can hang alone: a connect that never
        // completes and a response that never arrives are the same outage from the caller's seat. Read once so the
        // two can never drift apart. The value is configuration with an internal default and no environment
        // binding in the chart template, so retuning it needs relaxed binding (CASHACCOUNT_FX_TIMEOUT) and never a
        // template change; its validated lower bound makes it non-null and positive here.
        //
        // Bounding it is not tidiness. Readiness deliberately excludes the exchange-rate endpoint so that a
        // third-party outage never flaps pods, and an unbounded wait would defeat that from the other side by
        // parking request threads until the pool is gone - turning someone else's outage into ours. Bounded, the
        // failure surfaces as 503 EXCHANGE_RATE_UNAVAILABLE with Retry-After: 5, the balance untouched and no
        // ledger row written. That is a deliberate improvement on the program being replaced, which ran its
        // COMPUTE and UPDATE even after the rate SELECT found no row and let the UPDATE's SQLCODE 0 mask it,
        // committing a balance derived from an uninitialized RATES host variable under a success code
        // [backend/cash-account-cobol/COBOL/CASH00.cbl:L214-L231; backend/cash-account-cobol/COBOL/DCLFRANK.cpy:L22].
        Duration timeout = properties.getFx().getTimeout();

        // Redirects are followed because the endpoint the chart ships answers one: the configured default
        // api.frankfurter.app/latest returns 301 to its api.frankfurter.dev/v1 host, and the JDK client declines
        // redirects unless asked, which would turn every cross-currency conversion into a rate failure. NORMAL
        // rather than ALWAYS so a redirect from HTTPS down to HTTP is refused instead of quietly downgrading the
        // hop. Retuning the URL is a chart value change, so this client must cope with the value as shipped.
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(timeout)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);

        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
