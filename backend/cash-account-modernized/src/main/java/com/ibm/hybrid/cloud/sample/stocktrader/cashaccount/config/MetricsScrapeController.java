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

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// This endpoint exists because of what the deployment does NOT say. The pod annotations are
// prometheus.io/scrape: 'true' and prometheus.io/port: "8080" with no prometheus.io/path
// [infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L51-L54], emitted whenever
// global.monitoring is on, which it is by default [.../values.yaml:L21]. An annotation-based scraper given no path
// requests /metrics, while Actuator publishes the registry at /actuator/prometheus - so without this mapping every
// scrape of every pod would 404 and the service would look instrumented while reporting nothing. Adding
// prometheus.io/path to the chart would be the other way to close that gap, and it is not available: the service
// conforms to the deployment shape, never the reverse, so the path the annotation implies is published here
// instead. /actuator/prometheus stays exactly as it is (application.yml exposes prometheus), and this is an
// additional route onto the same registry rather than a relocation of it.
//
// It sits in config rather than in a surface package because it is deployment-shape conformance and not a business
// endpoint: it carries no domain type, reads nothing from the ledger and shares no path space with
// /cash-account/**. config/SecurityConfig already admits the bare /metrics unauthenticated, alongside
// /actuator/**, because a scraper presents no credential.
/** Publishes the meter registry at GET /metrics, the path the chart's path-less scrape annotation implies. */
@RestController
public class MetricsScrapeController {

    // The Prometheus text exposition format, declared once and used for both the body and the Content-Type header
    // so the two can never disagree. It is passed to scrape(String) rather than relying on the no-argument
    // overload's default, and set on the response rather than through @GetMapping(produces = ...): a concrete
    // Content-Type on the ResponseEntity is used verbatim, so a scraper whose Accept header prefers OpenMetrics
    // still receives this format instead of a 406 from content negotiation. Anything but a Prometheus text type
    // here - the JSON default above all - is a body no scraper parses.
    private static final String TEXT_EXPOSITION_FORMAT = "text/plain;version=0.0.4;charset=utf-8";

    private static final MediaType TEXT_EXPOSITION_MEDIA_TYPE = MediaType.parseMediaType(TEXT_EXPOSITION_FORMAT);

    private final PrometheusMeterRegistry registry;

    /**
     * @param registry the registry auto-configured by {@code spring-boot-starter-actuator} together with
     *     {@code micrometer-registry-prometheus}; this class publishes no bean of its own and constructs no
     *     registry, so the endpoint and {@code /actuator/prometheus} always report the same meters
     */
    public MetricsScrapeController(PrometheusMeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Read-only and free of side effects by design: no meter is registered, nothing is filtered and nothing is
     * cached, so the scrape interval alone decides how often this runs.
     *
     * @return {@code 200} carrying the registry's meters in the Prometheus text exposition format
     */
    @GetMapping("/metrics")
    public ResponseEntity<String> scrape() {
        return ResponseEntity.ok()
                .contentType(TEXT_EXPOSITION_MEDIA_TYPE)
                .body(registry.scrape(TEXT_EXPOSITION_FORMAT));
    }
}
