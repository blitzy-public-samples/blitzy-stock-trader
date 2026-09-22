package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount;

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

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(CashAccountApplication.class);

        // The chart's startupProbe httpGets /actuator/startup
        // [infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L204-L210], which exists
        // only while the context's ApplicationStartup is a buffering one - so the recorder is installed here rather
        // than through the static SpringApplication.run, which offers no seam for it.
        application.setApplicationStartup(new BufferingApplicationStartup(2048));

        application.run(args);
    }
}
