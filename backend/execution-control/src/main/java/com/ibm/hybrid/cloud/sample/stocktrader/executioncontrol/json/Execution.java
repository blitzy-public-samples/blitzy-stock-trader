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
import java.time.Instant;

/** An immutable simulated fill of an accepted order */
public class Execution {
    /* No exchange, FIX session or order routing exists in this service, so there is exactly one
       venue and it is a constant rather than a constructor parameter: no caller can claim a fill
       happened anywhere real. */
    private static final String SIMULATED_VENUE = "SIMULATED";

    private static final int AMOUNT_SCALE = 2;

    private final String executionId;
    private final BigDecimal fillPrice;
    private final long filledQuantity;
    private final Instant executedAt;
    private final String venue;
    private final boolean simulated;
    private final String disclaimer;

    public Execution(String executionId, BigDecimal fillPrice, long filledQuantity, Instant executedAt) {
        this.executionId = executionId;
        /* BigDecimal.equals is scale-sensitive - 100.0 does not equal 100.00 even though compareTo
           returns 0 - so the scale is fixed once, here, rather than wherever a price literal was
           written. That keeps both the serialized amount and every value assertion on it
           deterministic, including the copies PostTradeService denormalises onto an exception. */
        this.fillPrice = (fillPrice == null) ? null : cents(fillPrice, "fillPrice");
        this.filledQuantity = filledQuantity;
        this.executedAt = executedAt;
        this.venue = SIMULATED_VENUE;
        this.simulated = true;
        this.disclaimer = SimulationLabels.DISCLAIMER;
    }

    /* Pinning the scale must never change the price. A fill recorded at 0.001 would be rounded to
       0.00 and the trade would report as free, so a price that cannot be held in cents is refused
       here rather than rounded away where nobody can see it happen. */
    private static BigDecimal cents(BigDecimal value, String field) {
        BigDecimal atCents = value.setScale(AMOUNT_SCALE, RoundingMode.DOWN);
        if (atCents.compareTo(value) != 0) {
            throw new IllegalArgumentException(
                    field + " must be a whole number of cents, not " + value.toPlainString());
        }

        return atCents;
    }

    public String getExecutionId() {
        return executionId;
    }

    public BigDecimal getFillPrice() {
        return fillPrice;
    }

    public long getFilledQuantity() {
        return filledQuantity;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public String getVenue() {
        return venue;
    }

    public boolean isSimulated() {
        return simulated;
    }

    public String getDisclaimer() {
        return disclaimer;
    }
}
