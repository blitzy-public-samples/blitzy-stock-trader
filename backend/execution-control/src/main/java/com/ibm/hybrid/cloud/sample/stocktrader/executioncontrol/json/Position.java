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

/** A client's synthetic holding in one symbol */
public class Position {
    private final String clientId;
    private final String symbol;
    private final long quantity;
    private final BigDecimal lastPrice;
    private final BigDecimal positionNotional;
    private final boolean synthetic;
    private final boolean simulated;
    private final String disclaimer;

    public Position(String clientId, String symbol, long quantity, BigDecimal lastPrice) {
        this.clientId = clientId;
        this.symbol = symbol;
        this.quantity = quantity;
        //Scale is pinned at 2 on both amounts because BigDecimal.equals is scale-sensitive:
        //without it, a notional of 5000000.0 and one of 5000000.00 would be different objects
        //to any caller that compares with equals rather than compareTo.
        this.lastPrice = lastPrice.setScale(2, RoundingMode.HALF_UP);
        //Derived here, never accepted as a parameter, so no caller can store a position whose
        //notional contradicts its quantity and price. Math.abs keeps a short position's
        //notional positive - the control compares magnitudes, not direction.
        this.positionNotional = this.lastPrice
                .multiply(BigDecimal.valueOf(Math.abs(quantity)))
                .setScale(2, RoundingMode.HALF_UP);
        this.synthetic = true;
        this.simulated = true;
        this.disclaimer = SimulationLabels.DISCLAIMER;
    }

    public String getClientId() {
        return clientId;
    }

    public String getSymbol() {
        return symbol;
    }

    public long getQuantity() {
        return quantity;
    }

    public BigDecimal getLastPrice() {
        return lastPrice;
    }

    public BigDecimal getPositionNotional() {
        return positionNotional;
    }

    public boolean isSynthetic() {
        return synthetic;
    }

    public boolean isSimulated() {
        return simulated;
    }

    public String getDisclaimer() {
        return disclaimer;
    }
}
