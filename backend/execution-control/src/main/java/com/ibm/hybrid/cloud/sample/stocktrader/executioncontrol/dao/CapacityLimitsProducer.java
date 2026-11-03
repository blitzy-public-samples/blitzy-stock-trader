/*
       Copyright 2020-2021 IBM Corp, All Rights Reserved
       Copyright 2022-2025 Kyndryl, All Rights Reserved

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

package com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.dao;

//Logging (JSR 47)
import java.util.logging.Logger;

//CDI 4.0
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

//mpConfig 3.1
import org.eclipse.microprofile.config.inject.ConfigProperty;


/** Reads the four admission-capacity properties from configuration and produces the module's CapacityLimits */
@ApplicationScoped
public class CapacityLimitsProducer {
    private static Logger logger = Logger.getLogger(CapacityLimitsProducer.class.getName());

    //No injection point below declares an in-code fallback, so every default lives in
    //META-INF/microprofile-config.properties alone and there is exactly one place to change one.
    //The consequence is deliberate: with nothing to fall back on, a misspelled or deleted key fails
    //start-up loudly instead of silently sizing a store to a ceiling nobody configured.
    private @Inject @ConfigProperty(name = "ORDER_CAPACITY") int orderCapacity;
    private @Inject @ConfigProperty(name = "SETTLEMENT_EXCEPTION_CAPACITY") int settlementExceptionCapacity;
    private @Inject @ConfigProperty(name = "POSITION_CAPACITY") int positionCapacity;
    private @Inject @ConfigProperty(name = "AUDIT_EVENT_CAPACITY") int auditEventCapacity;


    @Produces
    @ApplicationScoped
    public CapacityLimits capacityLimits() {
        CapacityLimits limits = new CapacityLimits(orderCapacity, settlementExceptionCapacity,
                positionCapacity, auditEventCapacity);

        //These four decide when a submission or a workflow step is refused with 503, so an operator
        //diagnosing a refusal - or sizing the heap for them - gets the effective set once at
        //start-up without turning on trace.
        logger.info("Effective admission capacity: ORDER_CAPACITY=" + limits.getMaxOrders()
                + ", SETTLEMENT_EXCEPTION_CAPACITY=" + limits.getMaxSettlementExceptions()
                + ", POSITION_CAPACITY=" + limits.getMaxPositions()
                + ", AUDIT_EVENT_CAPACITY=" + limits.getMaxAuditEvents());

        return limits;
    }
}
