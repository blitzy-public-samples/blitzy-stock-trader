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

//CDI 4.0
import jakarta.enterprise.inject.Vetoed;


//Vetoed because beans.xml discovers every class: without this, the no-arg constructor below would
//also make CapacityLimits a managed bean, and Weld would reject every injection point as ambiguous
//between that bean and the producer (WELD-001409). CapacityLimitsProducer is the only legitimate
//source of ceilings - a managed-bean instance would carry the defaults, not configuration.
/** Immutable holder of the effective in-memory admission ceilings of the four bounded structures */
@Vetoed
public class CapacityLimits {

    public static final int DEFAULT_MAX_ORDERS = 10_000;
    public static final int DEFAULT_MAX_SETTLEMENT_EXCEPTIONS = 10_000;
    public static final int DEFAULT_MAX_POSITIONS = 5_000;
    public static final int DEFAULT_MAX_AUDIT_EVENTS = 150_000;

    /* Each minimum is exactly what the seed set consumes - SeedDataLoader writes three orders,
       five positions, one exception and thirteen audit events - because a ceiling below it would
       fail the startup load rather than a request, leaving the service permanently un-ready with
       no caller to report the refusal to. A configured ceiling is therefore refused at start-up,
       where the message names the key, instead of at the first seeded submission. */
    public static final int MIN_MAX_ORDERS = 3;
    public static final int MIN_MAX_SETTLEMENT_EXCEPTIONS = 1;
    public static final int MIN_MAX_POSITIONS = 5;
    public static final int MIN_MAX_AUDIT_EVENTS = 13;

    //An order whose settlement instructions mismatch and which is then worked to the end consumes
    //ten events: six for its submission including the exception's OPEN, then one assign, one
    //resolve and two for settlement-ready. See the coupling rule in the constructor.
    public static final int EVENTS_PER_FULLY_WORKED_ORDER = 10;

    private final int maxOrders;
    private final int maxSettlementExceptions;
    private final int maxPositions;
    private final int maxAuditEvents;


    public CapacityLimits(int maxOrders, int maxSettlementExceptions, int maxPositions,
            int maxAuditEvents) {
        requireAtLeast("ORDER_CAPACITY", maxOrders, MIN_MAX_ORDERS);
        requireAtLeast("SETTLEMENT_EXCEPTION_CAPACITY", maxSettlementExceptions,
                MIN_MAX_SETTLEMENT_EXCEPTIONS);
        requireAtLeast("POSITION_CAPACITY", maxPositions, MIN_MAX_POSITIONS);
        requireAtLeast("AUDIT_EVENT_CAPACITY", maxAuditEvents, MIN_MAX_AUDIT_EVENTS);

        /* The timeline must never be the thing that refuses a state change the stores would have
           accepted, so its ceiling has to stay above the worst case the order ceiling implies -
           every admitted order worked to the end. Without this rule, raising ORDER_CAPACITY alone
           would silently make the record the binding constraint and reintroduce the very
           exhaustion an operator raised the ceiling to escape. Computed in long because the
           product of two configured ints overflows an int well inside the range an operator may
           legitimately supply. */
        long requiredAuditEvents = (long) EVENTS_PER_FULLY_WORKED_ORDER * maxOrders;
        if (maxAuditEvents < requiredAuditEvents) {
            throw new IllegalArgumentException("AUDIT_EVENT_CAPACITY " + maxAuditEvents
                    + " is below " + requiredAuditEvents + ", which is "
                    + EVENTS_PER_FULLY_WORKED_ORDER + " events for each of the ORDER_CAPACITY "
                    + maxOrders + " orders; raise AUDIT_EVENT_CAPACITY to at least "
                    + requiredAuditEvents + " or lower ORDER_CAPACITY");
        }

        this.maxOrders = maxOrders;
        this.maxSettlementExceptions = maxSettlementExceptions;
        this.maxPositions = maxPositions;
        this.maxAuditEvents = maxAuditEvents;
    }

    public static CapacityLimits defaults() {
        return new CapacityLimits(DEFAULT_MAX_ORDERS, DEFAULT_MAX_SETTLEMENT_EXCEPTIONS,
                DEFAULT_MAX_POSITIONS, DEFAULT_MAX_AUDIT_EVENTS);
    }

    //Retained because CapacityLimitsProducer declares the bean it returns @ApplicationScoped, and
    //CDI generates that bean's client proxy only from a non-private no-arg constructor; nothing
    //calls it. It delegates to the defaults rather than to zeroes, which the validation above
    //would refuse.
    protected CapacityLimits() {
        this(DEFAULT_MAX_ORDERS, DEFAULT_MAX_SETTLEMENT_EXCEPTIONS, DEFAULT_MAX_POSITIONS,
                DEFAULT_MAX_AUDIT_EVENTS);
    }

    private static void requireAtLeast(String key, int supplied, int minimum) {
        if (supplied < minimum) {
            throw new IllegalArgumentException(key + " " + supplied + " is below the minimum "
                    + minimum + ", which is what the startup seed data consumes");
        }
    }

    public int getMaxOrders() {
        return maxOrders;
    }

    public int getMaxSettlementExceptions() {
        return maxSettlementExceptions;
    }

    public int getMaxPositions() {
        return maxPositions;
    }

    public int getMaxAuditEvents() {
        return maxAuditEvents;
    }
}
