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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Spring Boot entry point of the modernized cash ledger service. */
@SpringBootApplication
// Without this, institutional/ReservationService's @Scheduled sweep never fires and an overdue hold strands funds.
@EnableScheduling
public class CashAccountApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(CashAccountApplication.class);

        // The actuator "startup" endpoint exists only while the application's ApplicationStartup is a
        // BufferingApplicationStartup, and the chart's startupProbe does an httpGet on /actuator/startup at port
        // 8080 with initialDelaySeconds 60, periodSeconds 30 and failureThreshold 3
        // [infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L204-L210] - so without
        // the recorder that probe 404s, Kubernetes exhausts its three failures and the pod never starts. That is
        // also why this is the two-step form rather than the static SpringApplication.run(Class, String[])
        // convenience, which offers no seam to install a recorder.
        application.setApplicationStartup(new BufferingApplicationStartup(2048));

        application.run(args);
    }
}
