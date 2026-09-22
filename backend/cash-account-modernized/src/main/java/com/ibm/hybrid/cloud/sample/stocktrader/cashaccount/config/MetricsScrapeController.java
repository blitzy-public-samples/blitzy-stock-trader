package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.config;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Publishes the meter registry at GET /metrics, the path the chart's path-less scrape annotation implies. */
@RestController
public class MetricsScrapeController {

    // Declared once and used for both the body and the Content-Type so the two cannot disagree, and set on the
    // ResponseEntity rather than through @GetMapping(produces = ...) because a concrete Content-Type is used
    // verbatim - a scraper whose Accept header prefers OpenMetrics still receives this format instead of a 406.
    private static final String TEXT_EXPOSITION_FORMAT = "text/plain;version=0.0.4;charset=utf-8";

    private static final MediaType TEXT_EXPOSITION_MEDIA_TYPE = MediaType.parseMediaType(TEXT_EXPOSITION_FORMAT);

    private final PrometheusMeterRegistry registry;

    /**
     * Container constructor.
     *
     * @param registry the auto-configured registry, injected rather than built here so that this endpoint and
     *     {@code /actuator/prometheus} always report the same meters
     */
    public MetricsScrapeController(PrometheusMeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Serves the path an annotation-based scraper requests when the pod annotations carry
     * {@code prometheus.io/scrape} and {@code prometheus.io/port} but no {@code prometheus.io/path}
     * [infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L51-L54]; without it every
     * scrape would 404, since Actuator publishes this same registry at {@code /actuator/prometheus} instead.
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
