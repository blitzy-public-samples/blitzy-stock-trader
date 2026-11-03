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

package com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.dao;

//Logging (JSR 47)
import java.util.logging.Logger;

//Concurrency
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


/** Exact atomic claim counter for one bounded structure, with a one-shot warning before and at saturation */
public final class AdmissionCounter {

    private static Logger logger = Logger.getLogger(AdmissionCounter.class.getName());

    //Remaining claims at which the warning fires. A tenth of the ceiling, floored at one, so the
    //warning always arrives at least one claim before the first refusal however small an operator
    //sizes the structure: a signal that only appeared at saturation would tell an operator nothing
    //they could still act on.
    private static final int LOW_WATERMARK_DIVISOR = 10;

    //A human label for log text only - "order", "settlement exception", "position" - so a warning
    //names the structure that is running out without its reader mapping a class name to it, and
    //the key beside it is the one an operator raises to give that structure more room.
    private final String structure;
    private final String configurationKey;
    private final int ceiling;
    private final int lowWatermark;

    /* Claims, not stored records: a claim is taken before the record exists and released if the
       operation is refused after it, so this counter - never the map's size - is what makes the
       ceiling exact under concurrency. Comparing a map's size would let any number of simultaneous
       claimants each read the same under-ceiling size and all proceed. */
    private final AtomicInteger admitted = new AtomicInteger();

    //One shot each, so sustained load at the ceiling cannot turn either warning into a log flood:
    //the operator needs to be told once that a ceiling is about to bind and once that it has.
    private final AtomicBoolean lowHeadroomWarned = new AtomicBoolean();
    private final AtomicBoolean saturationWarned = new AtomicBoolean();


    public AdmissionCounter(String structure, String configurationKey, int ceiling) {
        this.structure = structure;
        this.configurationKey = configurationKey;
        this.ceiling = ceiling;
        this.lowWatermark = Math.max(1, ceiling / LOW_WATERMARK_DIVISOR);
    }

    //A compare-and-set loop rather than incrementAndGet followed by a test: incrementing first
    //would let concurrent claimants push the counter past the ceiling and then hand some of them a
    //refusal, so the ceiling would be exact only after the fact. Here a claim is never recorded
    //unless it was granted, and the caller that is refused has changed nothing.
    public boolean tryAdmit() {
        int claimed = admitted.get();
        while (claimed < ceiling) {
            if (admitted.compareAndSet(claimed, claimed + 1)) {
                warnIfHeadroomIsLow(claimed + 1);
                return true;
            }
            claimed = admitted.get();
        }

        warnOnSaturation();
        return false;
    }

    //Returns a claim that never became a stored record - a duplicate client order id refused after
    //admission, or controls that rejected an order before its position was created - because a
    //slot consumed by a record that does not exist would shrink the usable ceiling for the life of
    //the process. Floored at zero so an unbalanced release cannot mint capacity never claimed.
    public void release() {
        admitted.updateAndGet(claimed -> (claimed > 0) ? claimed - 1 : 0);
    }

    public int admitted() {
        return admitted.get();
    }

    public int ceiling() {
        return ceiling;
    }

    //Floored defensively rather than out of necessity: the claim loop never lets the counter pass
    //the ceiling and release floors at zero, so this subtraction cannot go negative as the class
    //stands - and this figure is published in the health data, which must never carry a negative
    //headroom if a later claim path ever gets that wrong.
    public int headroom() {
        return Math.max(0, ceiling - admitted.get());
    }

    private void warnIfHeadroomIsLow(int claimed) {
        if ((ceiling - claimed) <= lowWatermark && lowHeadroomWarned.compareAndSet(false, true)) {
            logger.warning("The " + structure + " store is close to its admission ceiling: "
                    + claimed + " of " + ceiling + " claimed. Raise " + configurationKey
                    + " and restart before it saturates, or restart to clear the state.");
        }
    }

    private void warnOnSaturation() {
        if (saturationWarned.compareAndSet(false, true)) {
            logger.warning("The " + structure + " store is saturated at its admission ceiling of "
                    + ceiling + ", so further admissions are refused with 503. Raise "
                    + configurationKey + " and restart, or restart to clear the state.");
        }
    }
}
