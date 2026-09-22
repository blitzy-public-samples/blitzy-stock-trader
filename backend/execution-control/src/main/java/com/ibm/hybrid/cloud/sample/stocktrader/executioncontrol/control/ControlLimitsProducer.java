/*
       Copyright 2020-2021 IBM Corp All Rights Reserved
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

package com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.control;

//Arbitrary-precision arithmetic
import java.math.BigDecimal;

//Logging (JSR 47)
import java.util.logging.Logger;

//CDI 4.0
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

//mpConfig 3.1
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.inject.ConfigProperty;


/** Reads the five business-rule properties from configuration and produces the module's ControlLimits */
@ApplicationScoped
public class ControlLimitsProducer {
    private static Logger logger = Logger.getLogger(ControlLimitsProducer.class.getName());

    //No injection point below declares an in-code fallback, so every default lives in
    //META-INF/microprofile-config.properties alone and there is exactly one place to change one.
    //The consequence is deliberate: with nothing to fall back on, a misspelled or deleted key fails
    //start-up loudly instead of silently trading against a limit nobody configured.
    private @Inject @ConfigProperty(name = "MAX_ORDER_NOTIONAL") BigDecimal maxOrderNotional;
    private @Inject @ConfigProperty(name = "MAX_POSITION_NOTIONAL") BigDecimal maxPositionNotional;
    private @Inject @ConfigProperty(name = "FAT_FINGER_NOTIONAL_THRESHOLD") BigDecimal fatFingerNotionalThreshold;
    //This one key arrives as its raw ConfigValue rather than a converted list, because a mandatory
    //injection cannot express a declared-but-empty setting: MicroProfile Config reports an empty
    //string as a missing property and would fail the deployment an operator meant to clear the list.
    private @Inject @ConfigProperty(name = "RESTRICTED_SYMBOLS") ConfigValue restrictedSymbols;
    private @Inject @ConfigProperty(name = "EXCEPTION_SLA_HOURS") int exceptionSlaHours;


    @Produces
    @ApplicationScoped
    public ControlLimits controlLimits() {
        ControlLimits limits = new ControlLimits(maxOrderNotional, maxPositionNotional,
                fatFingerNotionalThreshold,
                ControlLimits.restrictedSymbolsFrom(restrictedSymbols.getRawValue(),
                        restrictedSymbols.getValue(), restrictedSymbols.getSourceName()),
                exceptionSlaHours);

        //The four pre-trade controls decide whether an order is accepted or rejected and the last
        //value ages settlement exceptions, so an operator diagnosing a rejection or an SLA figure
        //gets the effective set once at start-up without turning on trace.
        logger.info("Effective pre-trade controls: MAX_ORDER_NOTIONAL=" + limits.getMaxOrderNotional()
                + ", MAX_POSITION_NOTIONAL=" + limits.getMaxPositionNotional()
                + ", FAT_FINGER_NOTIONAL_THRESHOLD=" + limits.getFatFingerNotionalThreshold()
                + ", RESTRICTED_SYMBOLS=" + limits.getRestrictedSymbols()
                + ", EXCEPTION_SLA_HOURS=" + limits.getExceptionSlaHours());

        return limits;
    }
}
