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

/** The outcome of one pre-trade control evaluation, recorded whether it passed or failed */
public class ControlResult {
    private final String control;
    //String, not BigDecimal: the two value fields carry a rendered amount for the three
    //notional controls but a symbol list and a symbol for RESTRICTED_SYMBOL, so one
    //numeric type cannot hold them. PreTradeControlService renders; this type only stores.
    private final String configuredLimit;
    private final String observedValue;
    private final boolean passed;
    private final String reason;

    public ControlResult(String initialControl, String initialConfiguredLimit,
            String initialObservedValue, boolean initialPassed, String initialReason) {
        control = initialControl;
        configuredLimit = initialConfiguredLimit;
        observedValue = initialObservedValue;
        passed = initialPassed;
        reason = initialReason;
    }

    public String getControl() {
        return control;
    }

    public String getConfiguredLimit() {
        return configuredLimit;
    }

    public String getObservedValue() {
        return observedValue;
    }

    //isPassed (not getPassed) so JSON-B derives the property name "passed"
    public boolean isPassed() {
        return passed;
    }

    public String getReason() {
        return reason;
    }

    //Diagnostic only, for assertion-failure messages; JSON-B owns the wire format, so this
    //deliberately emits no JSON - a second serializer could silently drift from the first
    public String toString() {
        return "ControlResult[" + control + " passed=" + passed + "]";
    }
}
