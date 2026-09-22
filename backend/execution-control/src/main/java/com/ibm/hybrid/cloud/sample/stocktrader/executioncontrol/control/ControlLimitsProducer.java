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

//Collections
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

//Logging (JSR 47)
import java.util.logging.Logger;

//CDI 4.0
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

//mpConfig 3.1
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
    private @Inject @ConfigProperty(name = "RESTRICTED_SYMBOLS") List<String> restrictedSymbols;
    private @Inject @ConfigProperty(name = "EXCEPTION_SLA_HOURS") int exceptionSlaHours;


    //@Singleton rather than @ApplicationScoped: ControlLimits is a final value object with no no-arg
    //constructor, so CDI cannot client-proxy it and a normal scope here would turn every injection
    //point into a deployment error (Weld WELD-001410). @Singleton is a pseudo-scope - no proxy - and
    //the method still runs exactly once per application, which is the property that matters: the
    //limits are read from configuration once and every consumer shares that one immutable instance.
    @Produces
    @Singleton
    public ControlLimits controlLimits() {
        ControlLimits limits = new ControlLimits(maxOrderNotional, maxPositionNotional,
                fatFingerNotionalThreshold, canonicalize(restrictedSymbols), exceptionSlaHours);

        //These values decide whether every order is accepted or rejected, so the operator
        //diagnosing a rejection gets them once at start-up without turning on trace.
        logger.info("Effective pre-trade controls: MAX_ORDER_NOTIONAL=" + limits.getMaxOrderNotional()
                + ", MAX_POSITION_NOTIONAL=" + limits.getMaxPositionNotional()
                + ", FAT_FINGER_NOTIONAL_THRESHOLD=" + limits.getFatFingerNotionalThreshold()
                + ", RESTRICTED_SYMBOLS=" + limits.getRestrictedSymbols()
                + ", EXCEPTION_SLA_HOURS=" + limits.getExceptionSlaHours());

        return limits;
    }

    //Trimmed and upper-cased here so the pre-trade evaluation compares against the already-canonical
    //order symbol with no further work, and order-preserving so GET /controls reports the configured
    //list deterministically. A present-but-blank override means "nothing is restricted", so blank
    //tokens are dropped rather than becoming a symbol no order could ever match; a missing key never
    //reaches this method, because the injection point above has no default to fall back on.
    private static Set<String> canonicalize(List<String> symbols) {
        Set<String> canonical = new LinkedHashSet<>();
        if (symbols == null) {
            return canonical;
        }

        for (String symbol : symbols) {
            if (symbol == null || symbol.isBlank()) {
                continue;
            }
            canonical.add(symbol.trim().toUpperCase(Locale.ROOT));
        }

        return canonical;
    }
}
