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

import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.ControlResult;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.Order;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.Position;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.RecordSource;

//Arbitrary-precision arithmetic
import java.math.BigDecimal;

//Time (java.time)
import java.time.Instant;

//Collections
import java.util.Arrays;
import java.util.List;
import java.util.Set;

//JUnit 5 Jupiter
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;


/** Unit tests for the four configurable pre-trade controls evaluated by PreTradeControlService */
class PreTradeControlServiceTest {

    private static final String MAX_ORDER_NOTIONAL = "MAX_ORDER_NOTIONAL";
    private static final String MAX_POSITION_NOTIONAL = "MAX_POSITION_NOTIONAL";
    private static final String RESTRICTED_SYMBOL = "RESTRICTED_SYMBOL";
    private static final String FAT_FINGER = "FAT_FINGER";
    private static final String WITHIN_LIMIT = "within limit";
    private static final String NOT_RESTRICTED = "not restricted";

    /* The configured defaults of META-INF/microprofile-config.properties, restated here rather than
       read from it: a test that loaded the same file it is checking would still pass if an operator
       lowered a default, and the boundary cases below are only meaningful against a known ceiling. */
    private static final BigDecimal DEFAULT_MAX_ORDER_NOTIONAL = new BigDecimal("1000000.00");
    private static final BigDecimal DEFAULT_MAX_POSITION_NOTIONAL = new BigDecimal("5000000.00");
    private static final BigDecimal DEFAULT_FAT_FINGER_THRESHOLD = new BigDecimal("2500000.00");
    private static final List<String> DEFAULT_RESTRICTED_SYMBOLS = Arrays.asList("RSTRA", "RSTRB");
    private static final int DEFAULT_EXCEPTION_SLA_HOURS = 24;

    //The rendering the restricted-symbol result reports as its configured limit.
    private static final String RESTRICTED_LIST = "RSTRA,RSTRB";

    //evaluate reads no clock, so this instant only fills the Order constructor and is never asserted.
    private static final Instant SUBMITTED_AT = Instant.parse("2025-01-02T03:04:05Z");


    @Test
    void testAllControlsPassForOrderUnderEveryLimit() {
        List<ControlResult> results = defaultService().evaluate(
                order("INST-001", "BUY", "SYNA", 100, "100.00"),
                position("INST-001", "SYNA", 10000, "100.00"));

        assertControlNamesInFixedOrder(results);

        ControlResult orderNotional = results.get(0);
        assertTrue(orderNotional.isPassed(), MAX_ORDER_NOTIONAL + " must pass at 10000.00");
        assertEquals(WITHIN_LIMIT, orderNotional.getReason(), MAX_ORDER_NOTIONAL + " reason");
        assertEquals("1000000.00", orderNotional.getConfiguredLimit(),
                MAX_ORDER_NOTIONAL + " configured limit");
        assertEquals("10000.00", orderNotional.getObservedValue(),
                MAX_ORDER_NOTIONAL + " observed order notional");

        ControlResult positionNotional = results.get(1);
        assertTrue(positionNotional.isPassed(), MAX_POSITION_NOTIONAL + " must pass at 1010000.00");
        assertEquals(WITHIN_LIMIT, positionNotional.getReason(), MAX_POSITION_NOTIONAL + " reason");
        assertEquals("5000000.00", positionNotional.getConfiguredLimit(),
                MAX_POSITION_NOTIONAL + " configured limit");
        assertEquals("1010000.00", positionNotional.getObservedValue(),
                MAX_POSITION_NOTIONAL + " observed resulting position notional");

        ControlResult restricted = results.get(2);
        assertTrue(restricted.isPassed(), RESTRICTED_SYMBOL + " must pass for SYNA");
        assertEquals(NOT_RESTRICTED, restricted.getReason(), RESTRICTED_SYMBOL + " reason");
        //The single place the restricted list's rendering is pinned, so a change of separator is
        //caught here instead of in whichever consumer happens to parse it.
        assertEquals(RESTRICTED_LIST, restricted.getConfiguredLimit(),
                RESTRICTED_SYMBOL + " configured list");
        assertEquals("SYNA", restricted.getObservedValue(),
                RESTRICTED_SYMBOL + " observed canonical symbol");

        ControlResult fatFinger = results.get(3);
        assertTrue(fatFinger.isPassed(), FAT_FINGER + " must pass at 10000.00");
        assertEquals(WITHIN_LIMIT, fatFinger.getReason(), FAT_FINGER + " reason");
        assertEquals("2500000.00", fatFinger.getConfiguredLimit(), FAT_FINGER + " configured limit");
        assertEquals("10000.00", fatFinger.getObservedValue(), FAT_FINGER + " observed order notional");
    }

    @Test
    void testEvaluateReturnsFourResultsInFixedOrderWhenEveryControlFails() {
        //6000000.00 breaches the order ceiling, the position ceiling and the fat-finger ceiling, and
        //RSTRA is restricted, so every control has something to say about this one order.
        List<ControlResult> results = defaultService().evaluate(
                order("INST-001", "BUY", "RSTRA", 60000, "100.00"), null);

        /* Asserting all four verdicts are present is the only way to tell a complete record from a
           short-circuited one: an evaluation that stopped at the first breach would reject this order
           just the same. The count and the four false flags are what prove Order.controlResults can
           carry the whole picture and OrderLifecycleService can join every failing reason. */
        assertControlNamesInFixedOrder(results);

        for (ControlResult result : results) {
            assertFalse(result.isPassed(), result.getControl() + " must fail for this order");
            assertNotNull(result.getReason(), result.getControl() + " must record a reason");
            assertFalse(result.getReason().isBlank(),
                    result.getControl() + " reason must not be blank");
        }
    }

    @Test
    void testRejectOrderExceedingMaxOrderNotional() {
        List<ControlResult> results = defaultService().evaluate(
                order("INST-001", "BUY", "SYNA", 15000, "100.00"), null);

        ControlResult orderNotional = control(results, MAX_ORDER_NOTIONAL);
        assertFalse(orderNotional.isPassed(), "1500000.00 breaches " + MAX_ORDER_NOTIONAL);
        assertEquals("1500000.00", orderNotional.getObservedValue(),
                MAX_ORDER_NOTIONAL + " observed order notional");
        assertEquals("Order notional 1500000.00 exceeds MAX_ORDER_NOTIONAL 1000000.00",
                orderNotional.getReason(), MAX_ORDER_NOTIONAL + " rejection reason");

        assertPassedWithinLimit(results, MAX_POSITION_NOTIONAL);
        assertNotRestricted(results, "SYNA");
        assertPassedWithinLimit(results, FAT_FINGER);
    }

    @Test
    void testRejectRestrictedSymbol() {
        PreTradeControlService service = defaultService();

        List<ControlResult> lowerCase = service.evaluate(
                order("INST-001", "BUY", "rstra", 10, "10.00"), null);

        ControlResult restricted = control(lowerCase, RESTRICTED_SYMBOL);
        assertFalse(restricted.isPassed(), "rstra resolves to the restricted RSTRA");
        assertEquals("RSTRA", restricted.getObservedValue(),
                RESTRICTED_SYMBOL + " observed canonical symbol");
        assertEquals("Symbol RSTRA is on the restricted list RESTRICTED_SYMBOLS",
                restricted.getReason(), RESTRICTED_SYMBOL + " rejection reason");

        assertPassedWithinLimit(lowerCase, MAX_ORDER_NOTIONAL);
        assertPassedWithinLimit(lowerCase, MAX_POSITION_NOTIONAL);
        assertPassedWithinLimit(lowerCase, FAT_FINGER);

        List<ControlResult> padded = service.evaluate(
                order("INST-001", "BUY", " rstrb ", 10, "10.00"), null);

        ControlResult paddedRestricted = control(padded, RESTRICTED_SYMBOL);
        assertFalse(paddedRestricted.isPassed(), "a padded rstrb resolves to the restricted RSTRB");
        assertEquals("RSTRB", paddedRestricted.getObservedValue(),
                RESTRICTED_SYMBOL + " observed canonical symbol for a padded ticker");
        assertEquals("Symbol RSTRB is on the restricted list RESTRICTED_SYMBOLS",
                paddedRestricted.getReason(),
                RESTRICTED_SYMBOL + " rejection reason for a padded ticker");
    }

    @Test
    void testRejectOrderExceedingMaxPositionNotional() {
        List<ControlResult> results = defaultService().evaluate(
                order("INST-002", "BUY", "SYND", 5001, "100.00"),
                position("INST-002", "SYND", 45000, "100.00"));

        ControlResult positionNotional = control(results, MAX_POSITION_NOTIONAL);
        assertFalse(positionNotional.isPassed(), "5000100.00 breaches " + MAX_POSITION_NOTIONAL);
        assertEquals("5000100.00", positionNotional.getObservedValue(),
                MAX_POSITION_NOTIONAL + " observed resulting position notional");
        assertEquals("Resulting position notional 5000100.00 (50001 \u00D7 100.00) exceeds "
                + "MAX_POSITION_NOTIONAL 5000000.00", positionNotional.getReason(),
                MAX_POSITION_NOTIONAL + " rejection reason");

        //The order itself is small: only the position it would leave behind breaches a ceiling.
        assertPassedWithinLimit(results, MAX_ORDER_NOTIONAL);
        assertNotRestricted(results, "SYND");
        assertPassedWithinLimit(results, FAT_FINGER);
    }

    @Test
    void testPositionNotionalMarksWholeResultingQuantityAtOrderLimitPrice() {
        List<ControlResult> results = defaultService().evaluate(
                order("INST-002", "BUY", "SYND", 1, "120.00"),
                position("INST-002", "SYND", 45000, "100.00"));

        /* One share at 120.00 is what rejects this order, and that is the point: a mixed-price basis
           would have valued the resulting position at 45000 x 100.00 + 1 x 120.00 = 4500120.00 and
           passed. Marking the whole resulting quantity at the order's own limitPrice values it at the
           price the stored positionNotional will actually carry once the order fills, so the figure
           the control rejects is the figure the position would report. */
        ControlResult positionNotional = control(results, MAX_POSITION_NOTIONAL);
        assertFalse(positionNotional.isPassed(), "5400120.00 breaches " + MAX_POSITION_NOTIONAL);
        assertEquals("5400120.00", positionNotional.getObservedValue(),
                MAX_POSITION_NOTIONAL + " observed resulting position notional");
        assertEquals("Resulting position notional 5400120.00 (45001 \u00D7 120.00) exceeds "
                + "MAX_POSITION_NOTIONAL 5000000.00", positionNotional.getReason(),
                MAX_POSITION_NOTIONAL + " rejection reason");

        assertPassedWithinLimit(results, MAX_ORDER_NOTIONAL);
        assertNotRestricted(results, "SYND");
        assertPassedWithinLimit(results, FAT_FINGER);
    }

    @Test
    void testSellSideSubtractsOrderQuantityFromResultingPosition() {
        List<ControlResult> sellDown = defaultService().evaluate(
                order("INST-002", "SELL", "SYND", 5000, "100.00"),
                position("INST-002", "SYND", 45000, "100.00"));

        ControlResult reducedPosition = control(sellDown, MAX_POSITION_NOTIONAL);
        assertTrue(reducedPosition.isPassed(), MAX_POSITION_NOTIONAL + " must pass at 4000000.00");
        //40000, not 50000: a sell subtracts its quantity from the holding it settles against.
        assertEquals("4000000.00", reducedPosition.getObservedValue(),
                MAX_POSITION_NOTIONAL + " observed resulting position notional");
        assertPassedWithinLimit(sellDown, MAX_ORDER_NOTIONAL);
        assertNotRestricted(sellDown, "SYND");
        assertPassedWithinLimit(sellDown, FAT_FINGER);

        //Raised order and fat-finger ceilings leave the position control as the only one that can
        //fail, so the short exposure is what this evaluation measures and nothing else.
        PreTradeControlService service = new PreTradeControlService(new ControlLimits(
                new BigDecimal("10000000.00"), DEFAULT_MAX_POSITION_NOTIONAL,
                new BigDecimal("10000000.00"), DEFAULT_RESTRICTED_SYMBOLS,
                DEFAULT_EXCEPTION_SLA_HOURS));

        List<ControlResult> sellShort = service.evaluate(
                order("INST-003", "SELL", "SYNA", 60000, "100.00"),
                position("INST-003", "SYNA", 2000, "100.00"));

        ControlResult shortPosition = control(sellShort, MAX_POSITION_NOTIONAL);
        assertFalse(shortPosition.isPassed(),
                "a resulting short of 58000 breaches " + MAX_POSITION_NOTIONAL);
        //A short position is exposure, so the control measures the magnitude of the resulting
        //quantity rather than its sign.
        assertEquals("5800000.00", shortPosition.getObservedValue(),
                MAX_POSITION_NOTIONAL + " observed resulting position notional");
        assertTrue(shortPosition.getReason().startsWith("Resulting position notional 5800000.00 ("),
                MAX_POSITION_NOTIONAL + " reason must state the resulting notional: "
                        + shortPosition.getReason());
        assertTrue(shortPosition.getReason().contains("exceeds MAX_POSITION_NOTIONAL 5000000.00"),
                MAX_POSITION_NOTIONAL + " reason must name the breached limit: "
                        + shortPosition.getReason());

        assertPassedWithinLimit(sellShort, MAX_ORDER_NOTIONAL);
        assertPassedWithinLimit(sellShort, FAT_FINGER);
    }

    @Test
    void testRuleBoundaryValuesExactlyAtThresholdPassAndOneCentOverFails() {
        /* Exceeding a limit means strictly greater, so a value sitting exactly on a configured
           ceiling is still inside the mandate and passes - an operator who sets MAX_ORDER_NOTIONAL
           to 1000000.00 has authorised an order of exactly that notional, not forbidden it. The
           implementation says so with compareTo(limit) > 0, and compareTo rather than equals is what
           makes an operator's 1000000 compare equal to the rendered 1000000.00 despite the differing
           scale; BigDecimal.equals would call those two values different and reject the order. */
        PreTradeControlService service = defaultService();

        List<ControlResult> atOrderCeiling = service.evaluate(
                order("INST-001", "BUY", "SYNA", 10000, "100.00"), null);
        ControlResult exactOrderNotional = control(atOrderCeiling, MAX_ORDER_NOTIONAL);
        assertTrue(exactOrderNotional.isPassed(),
                "a notional of exactly 1000000.00 is within " + MAX_ORDER_NOTIONAL);
        assertEquals(WITHIN_LIMIT, exactOrderNotional.getReason(),
                MAX_ORDER_NOTIONAL + " reason at the ceiling");
        assertEquals("1000000.00", exactOrderNotional.getObservedValue(),
                MAX_ORDER_NOTIONAL + " observed order notional at the ceiling");
        assertPassedWithinLimit(atOrderCeiling, MAX_POSITION_NOTIONAL);
        assertNotRestricted(atOrderCeiling, "SYNA");
        assertPassedWithinLimit(atOrderCeiling, FAT_FINGER);

        List<ControlResult> oneCentOverOrderCeiling = service.evaluate(
                order("INST-001", "BUY", "SYNA", 1, "1000000.01"), null);
        ControlResult breachedOrderNotional = control(oneCentOverOrderCeiling, MAX_ORDER_NOTIONAL);
        assertFalse(breachedOrderNotional.isPassed(),
                "one cent over 1000000.00 breaches " + MAX_ORDER_NOTIONAL);
        assertEquals("Order notional 1000000.01 exceeds MAX_ORDER_NOTIONAL 1000000.00",
                breachedOrderNotional.getReason(),
                MAX_ORDER_NOTIONAL + " rejection reason one cent over the ceiling");
        assertPassedWithinLimit(oneCentOverOrderCeiling, MAX_POSITION_NOTIONAL);
        assertNotRestricted(oneCentOverOrderCeiling, "SYNA");
        assertPassedWithinLimit(oneCentOverOrderCeiling, FAT_FINGER);

        List<ControlResult> atPositionCeiling = service.evaluate(
                order("INST-002", "BUY", "SYND", 5000, "100.00"),
                position("INST-002", "SYND", 45000, "100.00"));
        ControlResult exactPositionNotional = control(atPositionCeiling, MAX_POSITION_NOTIONAL);
        assertTrue(exactPositionNotional.isPassed(),
                "a resulting position of exactly 5000000.00 is within " + MAX_POSITION_NOTIONAL);
        assertEquals(WITHIN_LIMIT, exactPositionNotional.getReason(),
                MAX_POSITION_NOTIONAL + " reason at the ceiling");
        assertEquals("5000000.00", exactPositionNotional.getObservedValue(),
                MAX_POSITION_NOTIONAL + " observed resulting position notional at the ceiling");
        assertPassedWithinLimit(atPositionCeiling, MAX_ORDER_NOTIONAL);
        assertNotRestricted(atPositionCeiling, "SYND");
        assertPassedWithinLimit(atPositionCeiling, FAT_FINGER);

        //Raised order and position ceilings leave the fat-finger threshold as the only control that
        //can discriminate, so its own boundary is what these two evaluations measure.
        PreTradeControlService fatFingerOnly = new PreTradeControlService(new ControlLimits(
                new BigDecimal("10000000.00"), new BigDecimal("100000000.00"),
                DEFAULT_FAT_FINGER_THRESHOLD, DEFAULT_RESTRICTED_SYMBOLS,
                DEFAULT_EXCEPTION_SLA_HOURS));

        List<ControlResult> atFatFingerCeiling = fatFingerOnly.evaluate(
                order("INST-001", "BUY", "SYNA", 25000, "100.00"), null);
        ControlResult exactFatFinger = control(atFatFingerCeiling, FAT_FINGER);
        assertTrue(exactFatFinger.isPassed(),
                "a notional of exactly 2500000.00 is within " + FAT_FINGER);
        assertEquals(WITHIN_LIMIT, exactFatFinger.getReason(), FAT_FINGER + " reason at the ceiling");
        assertEquals("2500000.00", exactFatFinger.getObservedValue(),
                FAT_FINGER + " observed order notional at the ceiling");

        List<ControlResult> oneCentOverFatFingerCeiling = fatFingerOnly.evaluate(
                order("INST-001", "BUY", "SYNA", 1, "2500000.01"), null);
        ControlResult breachedFatFinger = control(oneCentOverFatFingerCeiling, FAT_FINGER);
        assertFalse(breachedFatFinger.isPassed(),
                "one cent over 2500000.00 breaches " + FAT_FINGER);
        assertEquals("Order notional 2500000.01 exceeds FAT_FINGER_NOTIONAL_THRESHOLD 2500000.00",
                breachedFatFinger.getReason(),
                FAT_FINGER + " rejection reason one cent over the ceiling");
        assertPassedWithinLimit(oneCentOverFatFingerCeiling, MAX_ORDER_NOTIONAL);
        assertPassedWithinLimit(oneCentOverFatFingerCeiling, MAX_POSITION_NOTIONAL);
    }

    @Test
    void testNullExistingPositionTreatsExistingQuantityAsZero() {
        //A client's first order in a symbol has no position to add to, which must read as a holding
        //of zero rather than fail the evaluation.
        List<ControlResult> results = defaultService().evaluate(
                order("INST-001", "BUY", "SYNA", 100, "100.00"), null);

        assertControlNamesInFixedOrder(results);

        ControlResult positionNotional = control(results, MAX_POSITION_NOTIONAL);
        assertTrue(positionNotional.isPassed(), MAX_POSITION_NOTIONAL + " must pass at 10000.00");
        assertEquals("10000.00", positionNotional.getObservedValue(),
                MAX_POSITION_NOTIONAL + " observed resulting position notional with no holding");
    }

    @Test
    void testControlLimitsNormalizationIsVisibleInResults() {
        //The unscaled forms an operator would type as environment-variable overrides.
        ControlLimits limits = new ControlLimits(new BigDecimal("1000000"),
                new BigDecimal("5000000"), new BigDecimal("2500000"),
                Arrays.asList(" rstra ", "RstrB", "   "), DEFAULT_EXCEPTION_SLA_HOURS);

        List<ControlResult> results = new PreTradeControlService(limits).evaluate(
                order("INST-001", "BUY", "SYNA", 100, "100.00"), null);

        assertEquals("1000000.00", control(results, MAX_ORDER_NOTIONAL).getConfiguredLimit(),
                MAX_ORDER_NOTIONAL + " configured limit reported at two decimals");
        assertEquals("5000000.00", control(results, MAX_POSITION_NOTIONAL).getConfiguredLimit(),
                MAX_POSITION_NOTIONAL + " configured limit reported at two decimals");
        assertEquals("2500000.00", control(results, FAT_FINGER).getConfiguredLimit(),
                FAT_FINGER + " configured limit reported at two decimals");

        Set<String> restrictedSymbols = limits.getRestrictedSymbols();
        assertTrue(restrictedSymbols.contains("RSTRA"),
                "the canonical RSTRA must be in the restricted list");
        assertTrue(restrictedSymbols.contains("RSTRB"),
                "the canonical RSTRB must be in the restricted list");
        assertFalse(restrictedSymbols.contains(" rstra "),
                "the configured spelling must not survive canonicalization");
        //A blank override means nothing is restricted, so a blank token is dropped rather than
        //becoming an entry no order could ever match.
        assertEquals(2, restrictedSymbols.size(), "restricted list size after canonicalization");
        assertEquals(RESTRICTED_LIST, control(results, RESTRICTED_SYMBOL).getConfiguredLimit(),
                RESTRICTED_SYMBOL + " reports the configured order");
        assertThrows(UnsupportedOperationException.class, () -> limits.getRestrictedSymbols().add("X"),
                "the restricted list must be unmodifiable");

        //Carried for the settlement-exception SLA and never read by a pre-trade control, which is
        //why exactly four results come back from a holder of five values.
        assertEquals(DEFAULT_EXCEPTION_SLA_HOURS, limits.getExceptionSlaHours(),
                "exceptionSlaHours must survive unchanged");
        assertEquals(4, results.size(), "the SLA value adds no fifth control");

        //An absent restricted list restricts nothing, rather than leaving a null set for the
        //restricted-symbol control to dereference on the next order.
        ControlLimits nothingRestricted = new ControlLimits(DEFAULT_MAX_ORDER_NOTIONAL,
                DEFAULT_MAX_POSITION_NOTIONAL, DEFAULT_FAT_FINGER_THRESHOLD, null,
                DEFAULT_EXCEPTION_SLA_HOURS);
        assertTrue(nothingRestricted.getRestrictedSymbols().isEmpty(),
                "a missing restricted list must yield an empty set");
        assertNotRestricted(new PreTradeControlService(nothingRestricted).evaluate(
                order("INST-001", "BUY", "RSTRA", 10, "10.00"), null), "RSTRA");

        /* A limit that was never read is the one value this service must not guess at: producing
           limits without configuration fails loudly here instead of handing every order a null
           ceiling to compare against. This is the producer's whole contract - it declares no in-code
           fallback precisely so a deleted or misspelled key cannot become a silent limit. */
        NullPointerException unconfigured = assertThrows(NullPointerException.class,
                () -> new ControlLimitsProducer().controlLimits(),
                "limits produced from unread configuration must fail rather than carry nulls");
        assertEquals("maxOrderNotional is required", unconfigured.getMessage(),
                "the unread property must be named");
    }


    private static PreTradeControlService defaultService() {
        return new PreTradeControlService(new ControlLimits(DEFAULT_MAX_ORDER_NOTIONAL,
                DEFAULT_MAX_POSITION_NOTIONAL, DEFAULT_FAT_FINGER_THRESHOLD,
                DEFAULT_RESTRICTED_SYMBOLS, DEFAULT_EXCEPTION_SLA_HOURS));
    }

    private static Order order(String clientId, String side, String symbol, long quantity,
            String limitPrice) {
        //The submitted state is all evaluate needs: it reads the request fields and the derived
        //notional, and never the status, the control results or the execution.
        return new Order("ORD-000001", "C1", clientId, symbol, side, quantity,
                new BigDecimal(limitPrice), "stock", SUBMITTED_AT, RecordSource.API);
    }

    private static Position position(String clientId, String symbol, long quantity,
            String lastPrice) {
        return new Position(clientId, symbol, quantity, new BigDecimal(lastPrice));
    }

    private static void assertControlNamesInFixedOrder(List<ControlResult> results) {
        assertEquals(4, results.size(), "evaluate must record one result per control");
        assertEquals(MAX_ORDER_NOTIONAL, results.get(0).getControl(), "first control evaluated");
        assertEquals(MAX_POSITION_NOTIONAL, results.get(1).getControl(), "second control evaluated");
        assertEquals(RESTRICTED_SYMBOL, results.get(2).getControl(), "third control evaluated");
        assertEquals(FAT_FINGER, results.get(3).getControl(), "fourth control evaluated");
    }

    private static ControlResult control(List<ControlResult> results, String control) {
        for (ControlResult result : results) {
            if (control.equals(result.getControl())) {
                return result;
            }
        }

        return fail("no result recorded for control " + control);
    }

    private static void assertPassedWithinLimit(List<ControlResult> results, String control) {
        ControlResult result = control(results, control);
        assertTrue(result.isPassed(), control + " must pass but reported: " + result.getReason());
        assertEquals(WITHIN_LIMIT, result.getReason(), control + " passing reason");
    }

    private static void assertNotRestricted(List<ControlResult> results, String expectedSymbol) {
        ControlResult result = control(results, RESTRICTED_SYMBOL);
        assertTrue(result.isPassed(),
                RESTRICTED_SYMBOL + " must pass but reported: " + result.getReason());
        assertEquals(NOT_RESTRICTED, result.getReason(), RESTRICTED_SYMBOL + " passing reason");
        assertEquals(expectedSymbol, result.getObservedValue(),
                RESTRICTED_SYMBOL + " observed canonical symbol");
    }
}

