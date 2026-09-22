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

import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.dao.SeedDataLoader;

//Optional value
import java.util.Optional;

//Logging (JSR 47)
import java.util.logging.Level;
import java.util.logging.Logger;

//CDI 4.0
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

//mpConfig 3.1
import org.eclipse.microprofile.config.inject.ConfigProperty;

//mpHealth 4.0
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;


/** Reports the service ready once the seed data is loaded and both JWT settings resolve. */
@Readiness
@ApplicationScoped
public class ReadinessProbe implements HealthCheck {
    private static Logger logger = Logger.getLogger(ReadinessProbe.class.getName());

    private static final String NOT_SET = "(not set)";

    private @Inject SeedDataLoader seedDataLoader;

    //Both settings have server.xml <variable> defaults and resolve through MicroProfile Config,
    //which exposes those variables as a config source alongside any deployment environment
    //override. They are read optionally because a mandatory injection point would fail CDI
    //deployment validation and take the whole application down wherever neither source resolves.
    private @Inject @ConfigProperty(name = "JWT_AUDIENCE") Optional<String> jwtAudience;
    private @Inject @ConfigProperty(name = "JWT_ISSUER") Optional<String> jwtIssuer;


    @Override
    public HealthCheckResponse call() {
        HealthCheckResponse response = null;
        String message = "Ready";
        try {
            HealthCheckResponseBuilder builder = HealthCheckResponse.named("ExecutionControl");

            //No order can resolve a client or a position before the synthetic data exists.
            if (!seedDataLoader.isLoaded()) {
                builder = builder.down();
                message = "Seed data not yet loaded";
                logger.warning("Returning NOT ready!");
            } else if (!isResolved(jwtAudience) || !isResolved(jwtIssuer)) {
                builder = builder.down();
                message = "JWT configuration not resolved!";
                logger.warning("Returning NOT ready!");
            } else {
                builder = builder.up();
                logger.fine("Returning ready!");
            }

            builder = builder.withData("message", message);
            builder = builder.withData("jwtAudience", jwtAudience.orElse(NOT_SET));
            builder = builder.withData("jwtIssuer", jwtIssuer.orElse(NOT_SET));

            response = builder.build();
        } catch (Throwable t) {
            logger.warning("Exception occurred during health check: "+t.getMessage());
            logException(t);
            throw t;
        }

        return response;
    }

    //A blank value is as unusable as an absent one, so an explicitly emptied variable must not
    //report ready against a setting no JWT can ever be validated with.
    private static boolean isResolved(Optional<String> value) {
        return value.isPresent() && !value.get().isBlank();
    }

    private static void logException(Throwable t) {
        logger.warning(t.getClass().getName()+": "+t.getMessage());
        logger.log(Level.INFO, "Readiness health check failed", t);
    }
}
