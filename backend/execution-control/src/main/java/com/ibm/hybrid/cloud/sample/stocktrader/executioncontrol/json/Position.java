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
    private static final int AMOUNT_SCALE = 2;

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
        this.lastPrice = cents(lastPrice, "lastPrice");
        /* Derived here, never accepted as a parameter, so no caller can store a position whose
           notional contradicts its quantity and price. The magnitude is taken with BigDecimal.abs
           rather than Math.abs because Math.abs(Long.MIN_VALUE) is itself negative: a wrapped
           quantity would then store a negative notional, which every MAX_POSITION_NOTIONAL ceiling
           would pass however large the real exposure was. */
        this.positionNotional = cents(
                this.lastPrice.multiply(BigDecimal.valueOf(quantity).abs()), "positionNotional");
        this.synthetic = true;
        this.simulated = true;
        this.disclaimer = SimulationLabels.DISCLAIMER;
    }

    /* Pinning the scale must never change the amount. A sub-cent price rounded away here would
       report a holding the market never priced - 0.001 becomes 0.00, and a position worth nothing
       clears every notional ceiling - so a value that cannot be held in cents is refused instead
       of rounded. Amounts derived from an already-normalized price are exact at this scale, so the
       guard only ever fires on a caller's own input. */
    private static BigDecimal cents(BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }

        BigDecimal atCents = value.setScale(AMOUNT_SCALE, RoundingMode.DOWN);
        if (atCents.compareTo(value) != 0) {
            throw new IllegalArgumentException(
                    field + " must be a whole number of cents, not " + value.toPlainString());
        }

        return atCents;
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
