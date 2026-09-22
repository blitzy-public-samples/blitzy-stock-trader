/*
       Copyright 2019-2021 IBM Corp, All Rights Reserved
       Copyright 2023-2024 Kyndryl, All Rights Reserved

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

package com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.health;

import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.dao.OrderStore;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.dao.ReferenceDataStore;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.dao.SettlementExceptionStore;

//Standard I/O classes
import java.io.PrintWriter;
import java.io.StringWriter;

//Logging (JSR 47)
import java.util.logging.Level;
import java.util.logging.Logger;

//CDI 2.0
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

//mpHealth 1.0
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Liveness;


@Liveness
@ApplicationScoped
/** Use mpHealth for liveness probe */
public class LivenessProbe implements HealthCheck {
    private static Logger logger = Logger.getLogger(LivenessProbe.class.getName());

    private static final String LIVE_MESSAGE = "Live";

    private @Inject OrderStore orderStore;
    private @Inject SettlementExceptionStore settlementExceptionStore;
    private @Inject ReferenceDataStore referenceDataStore;


    //mpHealth probe
    @Override
    public HealthCheckResponse call() {
        HealthCheckResponse response = null;
        try {
            HealthCheckResponseBuilder builder = HealthCheckResponse.named("ExecutionControl");

            int orders = orderStore.count();
            int exceptions = settlementExceptionStore.count();
            int clients = referenceDataStore.clientCount();
            int positions = referenceDataStore.positionCount();

            //Liveness asks only whether this process and its injected stores still answer: empty stores
            //are an idle or seeding service for startup and readiness to gate, never a restart signal.
            builder = builder.up();
            logger.fine("Returning healthy!");

            builder = builder.withData("orders", orders);
            builder = builder.withData("exceptions", exceptions);
            builder = builder.withData("clients", clients);
            builder = builder.withData("positions", positions);
            builder = builder.withData("message", LIVE_MESSAGE);

            response = builder.build();
        } catch (Throwable t) {
            logger.warning("Exception occurred during health check: "+t.getMessage());
            logException(t);
            throw t;
        }

        return response;
    }

    private static void logException(Throwable t) {
        logger.warning(t.getClass().getName()+": "+t.getMessage());

        //only log the stack trace if the level has been set to at least INFO
        if (logger.isLoggable(Level.INFO)) {
            StringWriter writer = new StringWriter();
            t.printStackTrace(new PrintWriter(writer));
            logger.info(writer.toString());
        }
    }
}
