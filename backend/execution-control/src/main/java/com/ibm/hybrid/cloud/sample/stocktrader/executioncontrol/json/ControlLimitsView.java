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

package com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json;

import java.math.BigDecimal;
import java.math.RoundingMode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;


/** The effective pre-trade control limits reported by GET /controls */
public class ControlLimitsView {
    /* The limits are resolved from configuration once, at startup, and never stored as a
       record, so the only provenance there is to publish is the configuration itself - not
       one of the SEED/API origins that say how a stored record came to exist. */
    private static final String CONFIG_SOURCE = "CONFIG";

    private static final int AMOUNT_SCALE = 2;

    private final BigDecimal maxOrderNotional;
    private final BigDecimal maxPositionNotional;
    private final BigDecimal fatFingerNotionalThreshold;
    private final List<String> restrictedSymbols;
    private final int exceptionSlaHours;
    private final String source;
    private final boolean simulated;
    private final String disclaimer;


    public ControlLimitsView(BigDecimal maxOrderNotional, BigDecimal maxPositionNotional,
            BigDecimal fatFingerNotionalThreshold, Collection<String> restrictedSymbols,
            int exceptionSlaHours) {
        this.maxOrderNotional = maxOrderNotional.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        this.maxPositionNotional = maxPositionNotional.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        this.fatFingerNotionalThreshold =
                fatFingerNotionalThreshold.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        //Copied then wrapped: the copy detaches the published view from a caller that keeps
        //mutating its own collection afterwards, and the wrapper stops a reader of this
        //response mutating what the service reports as its effective configuration. A List
        //rather than the producer's Set so the JSON array order is the configured order.
        this.restrictedSymbols = restrictedSymbols == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(restrictedSymbols));
        this.exceptionSlaHours = exceptionSlaHours;
        this.source = CONFIG_SOURCE;
        this.simulated = true;
        this.disclaimer = SimulationLabels.DISCLAIMER;
    }

    public BigDecimal getMaxOrderNotional() {
        return maxOrderNotional;
    }

    public BigDecimal getMaxPositionNotional() {
        return maxPositionNotional;
    }

    public BigDecimal getFatFingerNotionalThreshold() {
        return fatFingerNotionalThreshold;
    }

    public List<String> getRestrictedSymbols() {
        return restrictedSymbols;
    }

    public int getExceptionSlaHours() {
        return exceptionSlaHours;
    }

    public String getSource() {
        return source;
    }

    public boolean isSimulated() {
        return simulated;
    }

    public String getDisclaimer() {
        return disclaimer;
    }
}
