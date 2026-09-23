package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount;

import java.util.Set;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point of the modernized cash ledger service, with scheduling enabled so that
 * institutional/ReservationService's expiry sweep fires and no overdue hold strands funds.
 */
@SpringBootApplication
@EnableScheduling
public class CashAccountApplication {

    // WHAT /actuator/startup IS ALLOWED TO PUBLISH. The endpoint answers a kubelet, which presents no credential,
    // so config/SecurityConfig must leave it permitAll and everything the recorder buffers is readable by any peer
    // that can reach port 8080 - and a GET is the endpoint's snapshot operation, not its drain, so the same payload
    // is re-served on every probe (period 30s). Unfiltered, the recorder buffers every container step and that
    // payload measured 170,993 bytes carrying 447 beanName tags and 221 fully-qualified class names, which is an
    // inventory of this service's internals rather than the liveness signal the probe asks for (CWE-200).
    //
    // These six are the whole application lifecycle - each one's duration is what diagnoses a slow start - and they
    // are the steps that carry NO tag at all, so the filtered timeline is timings and step names only.
    // spring.boot.application.starting is deliberately absent: its sole tag is mainApplicationClass, and a 24ms
    // marker is not worth publishing a class name for. Anything narrower than this set (an empty timeline) would
    // leave the endpoint green while telling an operator nothing, which is why the set is named rather than dropped.
    private static final Set<String> PUBLISHED_STARTUP_STEPS = Set.of(
            "spring.boot.application.environment-prepared",
            "spring.boot.application.context-prepared",
            "spring.boot.application.context-loaded",
            "spring.context.refresh",
            "spring.boot.application.started",
            "spring.boot.application.ready");

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(CashAccountApplication.class);

        // The chart's startupProbe httpGets /actuator/startup
        // [infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L204-L210], which exists
        // only while the context's ApplicationStartup is a buffering one - so the recorder is installed here rather
        // than through the static SpringApplication.run, which offers no seam for it.
        BufferingApplicationStartup startupRecorder = new BufferingApplicationStartup(2048);
        // The capacity is left as it was and is not the control: it caps how many steps are held, while the filter
        // decides which are held at all, and only the filter bounds what the endpoint discloses. actuator/
        // ActuatorProbesIT asserts both halves - a 200 carrying spring.boot.application.ready, and no beanName or
        // spring.beans.instantiate anywhere in the body - so neither a lost filter nor a Boot release that renames
        // a step passes silently.
        startupRecorder.addFilter(step -> PUBLISHED_STARTUP_STEPS.contains(step.getName()));
        application.setApplicationStartup(startupRecorder);

        application.run(args);
    }
}
