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

import java.lang.reflect.Field;

import java.math.BigDecimal;

import java.time.Instant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.eclipse.microprofile.config.ConfigValue;

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

    private static final String RESTRICTED_LIST = "RSTRA,RSTRB";

    //evaluate reads no clock, so this instant only fills the Order constructor and is never asserted.
    private static final Instant SUBMITTED_AT = Instant.parse("2025-01-02T03:04:05Z");

    //Comfortably above the longest refusal this service composes and far below the megabyte a
    //rendered out-of-range amount would run to, so the assertion tells the two apart.
    private static final int MAX_REFUSAL_MESSAGE_LENGTH = 300;


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
    void testResultingQuantityBeyondLongRangeIsMeasuredRatherThanWrapped() {
        /* The resulting share count here is one past Long.MAX_VALUE. In long arithmetic it wraps to
           Long.MIN_VALUE, whose Math.abs is still negative, and a control observing a negative
           notional finds it under a positive ceiling and passes the largest position the service
           could be asked to take. The true magnitude is 9223372036854775808 shares, an exposure of
           92233720368547758.08 at 0.01, so a breach is the only correct verdict. */
        List<ControlResult> overflowingBuy = defaultService().evaluate(
                order("INST-001", "BUY", "SYNA", 1, "0.01"),
                position("INST-001", "SYNA", Long.MAX_VALUE, "0.01"));

        ControlResult positionNotional = control(overflowingBuy, MAX_POSITION_NOTIONAL);
        assertFalse(positionNotional.isPassed(),
                MAX_POSITION_NOTIONAL + " must breach rather than wrap to a negative notional");
        assertEquals("92233720368547758.08", positionNotional.getObservedValue(),
                MAX_POSITION_NOTIONAL + " observed the exact resulting magnitude");
        assertEquals("Resulting position notional 92233720368547758.08 "
                + "(9223372036854775808 \u00D7 0.01) exceeds MAX_POSITION_NOTIONAL 5000000.00",
                positionNotional.getReason(),
                MAX_POSITION_NOTIONAL + " rejection reason names the exact share count");

        //A short taken past Long.MIN_VALUE is the same hazard in the other direction: the magnitude
        //is what the control measures, so the sign of the resulting quantity cannot hide it.
        List<ControlResult> overflowingSell = defaultService().evaluate(
                order("INST-001", "SELL", "SYNA", 2, "0.01"),
                position("INST-001", "SYNA", Long.MIN_VALUE + 1, "0.01"));

        ControlResult shortNotional = control(overflowingSell, MAX_POSITION_NOTIONAL);
        assertFalse(shortNotional.isPassed(),
                MAX_POSITION_NOTIONAL + " must breach on a short beyond the long range");
        //(Long.MIN_VALUE + 1) - 2 is -9223372036854775809, one share past what a long holds.
        assertEquals("92233720368547758.09", shortNotional.getObservedValue(),
                MAX_POSITION_NOTIONAL + " observed the exact resulting short magnitude");
    }

    @Test
    void testUnexpectedSideFailsFastRatherThanBeingMeasuredAsABuy() {
        /* OrderLifecycleService.submit refuses anything but BUY or SELL, so this order cannot arise
           from a request. The control matches the side exactly against BUY and then SELL and
           refuses anything else: measuring an unknown side as a buy moves the resulting quantity
           towards zero against the short below and reports 4000000.00 - less exposure than the
           trade carries - and guessing the side is the one thing this control must not do. */
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> defaultService().evaluate(order("INST-002", "SHORT", "SYND", 5000, "100.00"),
                        position("INST-002", "SYND", -45000, "100.00")),
                "an unrecognised side must fail rather than be measured as a buy");
        assertTrue(refused.getMessage().contains("SHORT"),
                "the message must name the rejected side: " + refused.getMessage());

        assertNotNull(defaultService().evaluate(order("INST-002", "BUY", "SYND", 1, "100.00"), null),
                "BUY must still be measured");
        assertNotNull(defaultService().evaluate(order("INST-002", "SELL", "SYND", 1, "100.00"), null),
                "SELL must still be measured");
    }

    @Test
    void testRestrictedSymbolConsumesTheCanonicalSymbolWithoutRenormalizingIt() {
        /* Canonicalization happens once, in OrderLifecycleService.submit, before any lookup,
           control or position key sees the symbol. This control therefore reports the symbol the
           order carries and matches it as-is: a raw ticker reaching it directly is evidence that
           the submit path was bypassed, not something to quietly repair here. */
        List<ControlResult> raw = defaultService().evaluate(
                orderWithRawSymbol("INST-001", "BUY", "rstra", 10, "10.00"), null);

        ControlResult restricted = control(raw, RESTRICTED_SYMBOL);
        assertEquals("rstra", restricted.getObservedValue(),
                RESTRICTED_SYMBOL + " reports the symbol exactly as the order carries it");
        assertTrue(restricted.isPassed(),
                "no second canonicalization happens here, so a raw ticker matches nothing");

        ControlResult canonical = control(defaultService().evaluate(
                orderWithRawSymbol("INST-001", "BUY", "RSTRA", 10, "10.00"), null),
                RESTRICTED_SYMBOL);
        assertFalse(canonical.isPassed(), "the canonical RSTRA is on the restricted list");
    }

    @Test
    void testNullExistingPositionTreatsExistingQuantityAsZero() {
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

        //The restricted list as configuration delivers it, rather than as a caller spells it: the
        //raw text, the expanded value and the declaring source are the three facts MicroProfile
        //Config reports, and the rule the control evaluates has to come out the same for every
        //well-formed spelling, drop a trailing comma, treat a declared-but-empty setting as
        //"nothing is restricted", and refuse a key no source declares at all.
        assertEquals(Set.of("RSTRA", "RSTRB"),
                ControlLimits.restrictedSymbolsFrom("RSTRA,RSTRB,", null, "properties"),
                "a trailing comma must not add a symbol");
        assertEquals(Set.of("SYNA"), ControlLimits.restrictedSymbolsFrom(null, " syna ", "env"),
                "the expanded value is read when the source reports no raw text");
        assertTrue(ControlLimits.restrictedSymbolsFrom("", "", "properties").isEmpty(),
                "a declared but empty setting must restrict nothing");
        assertTrue(ControlLimits.restrictedSymbolsFrom(null, null, "properties").isEmpty(),
                "a source that declares the key with no text must restrict nothing");
        IllegalStateException undeclared = assertThrows(IllegalStateException.class,
                () -> ControlLimits.restrictedSymbolsFrom(null, null, null),
                "a key no configuration source declares must stop start-up");
        assertTrue(undeclared.getMessage().startsWith("RESTRICTED_SYMBOLS is not declared"),
                "the undeclared key must be named");
    }

    @Test
    void testAmountsBeyondTheSupportedRangeAreRefusedBeforeBeingRendered() {
        /* Both reported values and the reason of each notional control are produced with setScale
           and toPlainString, and that is where a BigDecimal's exponent stops being a few characters
           and becomes digits. An order's own amounts are bounded when Order is constructed, so the
           value that can still arrive unbounded is a configured ceiling: 1E+40 normalizes to
           forty-three digits at two decimals, past the supported forty. */
        ControlLimits beyondRange = new ControlLimits(new BigDecimal("1E+40"),
                DEFAULT_MAX_POSITION_NOTIONAL, DEFAULT_FAT_FINGER_THRESHOLD,
                DEFAULT_RESTRICTED_SYMBOLS, DEFAULT_EXCEPTION_SLA_HOURS);

        IllegalArgumentException refusedLimit = assertThrows(IllegalArgumentException.class,
                () -> new PreTradeControlService(beyondRange).evaluate(
                        order("INST-001", "BUY", "SYNA", 100, "100.00"), null),
                "a configured limit outside the supported amount range must be refused");
        assertTrue(refusedLimit.getMessage().contains("significant digits"),
                "the refusal must report the representation: " + refusedLimit.getMessage());
        assertTrue(refusedLimit.getMessage().length() < MAX_REFUSAL_MESSAGE_LENGTH,
                "the refusal must describe the amount rather than render it, but its message ran to "
                        + refusedLimit.getMessage().length() + " characters");

        //Headroom rather than a hazard: 1E+30 is thirty-three digits at two decimals and is still
        //reported in full, so the bound refuses what cannot be rendered without clipping a ceiling
        //an operator might genuinely configure.
        List<ControlResult> permissive = new PreTradeControlService(new ControlLimits(
                new BigDecimal("1E+30"), new BigDecimal("1E+30"), new BigDecimal("1E+30"),
                DEFAULT_RESTRICTED_SYMBOLS, DEFAULT_EXCEPTION_SLA_HOURS)).evaluate(
                        order("INST-001", "BUY", "SYNA", 100, "100.00"), null);

        assertEquals("1000000000000000000000000000000.00",
                control(permissive, MAX_ORDER_NOTIONAL).getConfiguredLimit(),
                MAX_ORDER_NOTIONAL + " configured limit reported in full");
        assertPassedWithinLimit(permissive, MAX_ORDER_NOTIONAL);

        /* The other direction is closed one layer earlier: evaluate reads order.getNotional() and
           order.getLimitPrice(), so an order that cannot be constructed is an order whose amounts
           never reach the renderer. */
        IllegalArgumentException refusedOrder = assertThrows(IllegalArgumentException.class,
                () -> order("INST-001", "BUY", "SYNA", 100, "1E+1000000"),
                "an order carrying a compact exponent price must be refused at construction");
        assertTrue(refusedOrder.getMessage().contains("limitPrice"),
                "the refusal must name limitPrice: " + refusedOrder.getMessage());

        assertTrue(Order.isAmountWithinBounds(new BigDecimal("100.00")),
                "an ordinary two-decimal amount is within bounds");
        //A scale this large expands inside toPlainString rather than inside setScale, which is why
        //the bound is two-sided instead of a ceiling on magnitude alone.
        assertFalse(Order.isAmountWithinBounds(new BigDecimal("1E-1000000")),
                "a scale large enough to expand while being rendered is out of bounds");
        assertFalse(Order.isAmountWithinBounds(null), "a missing amount is not a bounded amount");
    }

    @Test
    void testNonPositiveMaxOrderNotionalIsRefusedAtConstruction() {
        /* A ceiling of zero or less is not a strict limit but an unusable one: no order with a
           positive quantity and a positive limit price can sit under it, so the service would
           reject every order ever submitted - including the three seeded ones - while readiness,
           which reads no limit, kept reporting UP. Refusing it at construction is what turns that
           into the deployment failure a non-convertible value already produces, and the message
           names the environment variable and the configured value because the failed-start log line
           is the only thing an operator has to act on. */
        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
                () -> new ControlLimits(new BigDecimal("-1.00"), DEFAULT_MAX_POSITION_NOTIONAL,
                        DEFAULT_FAT_FINGER_THRESHOLD, DEFAULT_RESTRICTED_SYMBOLS,
                        DEFAULT_EXCEPTION_SLA_HOURS),
                "a negative " + MAX_ORDER_NOTIONAL + " must be refused");
        assertEquals("MAX_ORDER_NOTIONAL must be greater than zero, not -1.00",
                negative.getMessage(), MAX_ORDER_NOTIONAL + " refusal message");

        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> new ControlLimits(new BigDecimal("0"), DEFAULT_MAX_POSITION_NOTIONAL,
                        DEFAULT_FAT_FINGER_THRESHOLD, DEFAULT_RESTRICTED_SYMBOLS,
                        DEFAULT_EXCEPTION_SLA_HOURS),
                "a zero " + MAX_ORDER_NOTIONAL + " must be refused");
        assertEquals("MAX_ORDER_NOTIONAL must be greater than zero, not 0", zero.getMessage(),
                MAX_ORDER_NOTIONAL + " refusal message at zero");

        //The value check sits in front of the existing null check and leaves its message alone.
        NullPointerException missing = assertThrows(NullPointerException.class,
                () -> new ControlLimits(null, DEFAULT_MAX_POSITION_NOTIONAL,
                        DEFAULT_FAT_FINGER_THRESHOLD, DEFAULT_RESTRICTED_SYMBOLS,
                        DEFAULT_EXCEPTION_SLA_HOURS),
                "a missing " + MAX_ORDER_NOTIONAL + " must still be refused as null");
        assertEquals("maxOrderNotional is required", missing.getMessage(),
                MAX_ORDER_NOTIONAL + " null message unchanged");

        //A refused ceiling still has to be described rather than rendered: -1E+1000000 is negative,
        //and toPlainString would put a million digits in the start-up log line.
        IllegalArgumentException outOfRange = assertThrows(IllegalArgumentException.class,
                () -> new ControlLimits(new BigDecimal("-1E+1000000"), DEFAULT_MAX_POSITION_NOTIONAL,
                        DEFAULT_FAT_FINGER_THRESHOLD, DEFAULT_RESTRICTED_SYMBOLS,
                        DEFAULT_EXCEPTION_SLA_HOURS),
                "a negative ceiling beyond the supported range must be refused");
        assertTrue(outOfRange.getMessage().startsWith(
                "MAX_ORDER_NOTIONAL must be greater than zero, not an amount of "),
                MAX_ORDER_NOTIONAL + " refusal must describe the amount: "
                        + outOfRange.getMessage().length() + " characters");
        assertTrue(outOfRange.getMessage().length() < MAX_REFUSAL_MESSAGE_LENGTH,
                MAX_ORDER_NOTIONAL + " refusal must not render the amount, but its message ran to "
                        + outOfRange.getMessage().length() + " characters");
    }

    @Test
    void testNonPositiveMaxPositionNotionalIsRefusedAtConstruction() {
        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
                () -> new ControlLimits(DEFAULT_MAX_ORDER_NOTIONAL, new BigDecimal("-5.00"),
                        DEFAULT_FAT_FINGER_THRESHOLD, DEFAULT_RESTRICTED_SYMBOLS,
                        DEFAULT_EXCEPTION_SLA_HOURS),
                "a negative " + MAX_POSITION_NOTIONAL + " must be refused");
        assertEquals("MAX_POSITION_NOTIONAL must be greater than zero, not -5.00",
                negative.getMessage(), MAX_POSITION_NOTIONAL + " refusal message");

        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> new ControlLimits(DEFAULT_MAX_ORDER_NOTIONAL, BigDecimal.ZERO,
                        DEFAULT_FAT_FINGER_THRESHOLD, DEFAULT_RESTRICTED_SYMBOLS,
                        DEFAULT_EXCEPTION_SLA_HOURS),
                "a zero " + MAX_POSITION_NOTIONAL + " must be refused");
        assertEquals("MAX_POSITION_NOTIONAL must be greater than zero, not 0", zero.getMessage(),
                MAX_POSITION_NOTIONAL + " refusal message at zero");

        NullPointerException missing = assertThrows(NullPointerException.class,
                () -> new ControlLimits(DEFAULT_MAX_ORDER_NOTIONAL, null,
                        DEFAULT_FAT_FINGER_THRESHOLD, DEFAULT_RESTRICTED_SYMBOLS,
                        DEFAULT_EXCEPTION_SLA_HOURS),
                "a missing " + MAX_POSITION_NOTIONAL + " must still be refused as null");
        assertEquals("maxPositionNotional is required", missing.getMessage(),
                MAX_POSITION_NOTIONAL + " null message unchanged");
    }

    @Test
    void testNonPositiveFatFingerThresholdIsRefusedAtConstruction() {
        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> new ControlLimits(DEFAULT_MAX_ORDER_NOTIONAL, DEFAULT_MAX_POSITION_NOTIONAL,
                        new BigDecimal("0"), DEFAULT_RESTRICTED_SYMBOLS,
                        DEFAULT_EXCEPTION_SLA_HOURS),
                "a zero " + FAT_FINGER + " threshold must be refused");
        assertEquals("FAT_FINGER_NOTIONAL_THRESHOLD must be greater than zero, not 0",
                zero.getMessage(), FAT_FINGER + " refusal message at zero");

        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
                () -> new ControlLimits(DEFAULT_MAX_ORDER_NOTIONAL, DEFAULT_MAX_POSITION_NOTIONAL,
                        new BigDecimal("-0.01"), DEFAULT_RESTRICTED_SYMBOLS,
                        DEFAULT_EXCEPTION_SLA_HOURS),
                "a negative " + FAT_FINGER + " threshold must be refused");
        assertEquals("FAT_FINGER_NOTIONAL_THRESHOLD must be greater than zero, not -0.01",
                negative.getMessage(), FAT_FINGER + " refusal message");

        NullPointerException missing = assertThrows(NullPointerException.class,
                () -> new ControlLimits(DEFAULT_MAX_ORDER_NOTIONAL, DEFAULT_MAX_POSITION_NOTIONAL,
                        null, DEFAULT_RESTRICTED_SYMBOLS, DEFAULT_EXCEPTION_SLA_HOURS),
                "a missing " + FAT_FINGER + " threshold must still be refused as null");
        assertEquals("fatFingerNotionalThreshold is required", missing.getMessage(),
                FAT_FINGER + " null message unchanged");
    }

    @Test
    void testNegativeExceptionSlaHoursIsRefusedAtConstruction() {
        //A negative SLA puts slaDeadline before openedAt, so every settlement exception is breached
        //the instant it is opened and no owner can resolve one in time.
        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
                () -> new ControlLimits(DEFAULT_MAX_ORDER_NOTIONAL, DEFAULT_MAX_POSITION_NOTIONAL,
                        DEFAULT_FAT_FINGER_THRESHOLD, DEFAULT_RESTRICTED_SYMBOLS, -5),
                "a negative EXCEPTION_SLA_HOURS must be refused");
        assertEquals("EXCEPTION_SLA_HOURS must not be negative, not -5", negative.getMessage(),
                "EXCEPTION_SLA_HOURS refusal message");

        IllegalArgumentException minusOne = assertThrows(IllegalArgumentException.class,
                () -> new ControlLimits(DEFAULT_MAX_ORDER_NOTIONAL, DEFAULT_MAX_POSITION_NOTIONAL,
                        DEFAULT_FAT_FINGER_THRESHOLD, DEFAULT_RESTRICTED_SYMBOLS, -1),
                "one hour below zero must be refused");
        assertEquals("EXCEPTION_SLA_HOURS must not be negative, not -1", minusOne.getMessage(),
                "EXCEPTION_SLA_HOURS refusal message one hour below zero");
    }

    @Test
    void testConfigurationBoundariesThatMustRemainLegal() {
        /* The two edges the validation above must not swallow. An SLA of zero hours is a real
           policy - the exception is due the moment it opens, slaDeadline equals openedAt - and the
           smallest amount the two-decimal scale can hold is a real, if severe, ceiling: an order of
           exactly that notional sits on it and passes, because only a strict breach rejects. */
        ControlLimits immediateSla = new ControlLimits(DEFAULT_MAX_ORDER_NOTIONAL,
                DEFAULT_MAX_POSITION_NOTIONAL, DEFAULT_FAT_FINGER_THRESHOLD,
                DEFAULT_RESTRICTED_SYMBOLS, 0);
        assertEquals(0, immediateSla.getExceptionSlaHours(),
                "an SLA of zero hours must remain legal");

        BigDecimal smallestAmount = new BigDecimal("0.01");
        List<ControlResult> atSmallestCeilings = new PreTradeControlService(new ControlLimits(
                smallestAmount, smallestAmount, smallestAmount, DEFAULT_RESTRICTED_SYMBOLS,
                DEFAULT_EXCEPTION_SLA_HOURS)).evaluate(
                        order("INST-001", "BUY", "SYNA", 1, "0.01"), null);

        assertControlNamesInFixedOrder(atSmallestCeilings);
        assertEquals("0.01", control(atSmallestCeilings, MAX_ORDER_NOTIONAL).getConfiguredLimit(),
                MAX_ORDER_NOTIONAL + " configured limit at the smallest positive amount");
        assertPassedWithinLimit(atSmallestCeilings, MAX_ORDER_NOTIONAL);
        assertPassedWithinLimit(atSmallestCeilings, MAX_POSITION_NOTIONAL);
        assertNotRestricted(atSmallestCeilings, "SYNA");
        assertPassedWithinLimit(atSmallestCeilings, FAT_FINGER);
    }

    @Test
    void testCeilingThatNormalizesToZeroIsRefusedLikeAZeroCeiling() {
        /* A sub-cent ceiling is positive and therefore passes a sign check, but the effective
           ceiling is the normalized one: 0.001 becomes 0.00, which is the unusable limit the
           positivity guard exists to refuse, reached by a value that guard admits. Left standing it
           reproduces the original symptom exactly - readiness UP, every order rejected against a
           ceiling of zero - so the effective value is what has to be positive, and the refusal
           names the minimum a two-decimal amount can hold alongside what was configured. */
        for (String subCent : new String[] {"0.001", "0.004", "0.0000001"}) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> new ControlLimits(new BigDecimal(subCent), DEFAULT_MAX_POSITION_NOTIONAL,
                            DEFAULT_FAT_FINGER_THRESHOLD, DEFAULT_RESTRICTED_SYMBOLS,
                            DEFAULT_EXCEPTION_SLA_HOURS),
                    "a " + MAX_ORDER_NOTIONAL + " of " + subCent + " normalizes to zero and must"
                            + " be refused");
            assertEquals("MAX_ORDER_NOTIONAL must be at least 0.01, not " + subCent,
                    refused.getMessage(),
                    MAX_ORDER_NOTIONAL + " refusal message for " + subCent);
        }

        //The same rule on the other two ceilings, since all three are normalized by one helper.
        assertEquals("MAX_POSITION_NOTIONAL must be at least 0.01, not 0.001",
                assertThrows(IllegalArgumentException.class,
                        () -> new ControlLimits(DEFAULT_MAX_ORDER_NOTIONAL, new BigDecimal("0.001"),
                                DEFAULT_FAT_FINGER_THRESHOLD, DEFAULT_RESTRICTED_SYMBOLS,
                                DEFAULT_EXCEPTION_SLA_HOURS)).getMessage(),
                MAX_POSITION_NOTIONAL + " refusal message below a cent");
        assertEquals("FAT_FINGER_NOTIONAL_THRESHOLD must be at least 0.01, not 0.001",
                assertThrows(IllegalArgumentException.class,
                        () -> new ControlLimits(DEFAULT_MAX_ORDER_NOTIONAL,
                                DEFAULT_MAX_POSITION_NOTIONAL, new BigDecimal("0.001"),
                                DEFAULT_RESTRICTED_SYMBOLS, DEFAULT_EXCEPTION_SLA_HOURS))
                        .getMessage(),
                FAT_FINGER + " refusal message below a cent");

        /* Half a cent is the edge: HALF_UP carries it to 0.01, which is a usable ceiling, so it is
           admitted and reported as the cent it became rather than refused for the form it arrived
           in. That is the existing normalization contract, and this fix does not narrow it. */
        assertEquals("0.01", new ControlLimits(new BigDecimal("0.005"),
                DEFAULT_MAX_POSITION_NOTIONAL, DEFAULT_FAT_FINGER_THRESHOLD,
                DEFAULT_RESTRICTED_SYMBOLS, DEFAULT_EXCEPTION_SLA_HOURS)
                        .getMaxOrderNotional().toPlainString(),
                "a ceiling that rounds up to a cent must remain legal");
    }

    @Test
    void testProxyConstructorBypassesTheValueValidation() {
        /* The @ApplicationScoped bean ControlLimitsProducer returns gets its client proxy from this
           non-private no-arg constructor, and the proxy needs the final fields set to something.
           Those placeholders are precisely the values the all-args constructor now refuses, so the
           constructor assigns them directly instead of delegating: were it to delegate, every
           deployment would fail at proxy creation rather than only a misconfigured one. Nothing
           reads the placeholders - the proxy forwards each call to the produced instance - but a
           null set would break the restricted-symbol control if anything ever did. */
        ControlLimits proxyPlaceholders = new ControlLimits();

        assertEquals(0, proxyPlaceholders.getMaxOrderNotional().signum(),
                "the proxy constructor must not be refused by the positivity check");
        assertEquals(0, proxyPlaceholders.getMaxPositionNotional().signum(),
                "the proxy constructor must not be refused by the positivity check");
        assertEquals(0, proxyPlaceholders.getFatFingerNotionalThreshold().signum(),
                "the proxy constructor must not be refused by the positivity check");
        assertTrue(proxyPlaceholders.getRestrictedSymbols().isEmpty(),
                "the placeholder restricted list must be empty rather than null");
        assertEquals(0, proxyPlaceholders.getExceptionSlaHours(), "placeholder SLA hours");
    }

    @Test
    void testProducerLogsARefusedConfigurationAtSevereAndRethrowsItUnchanged() {
        /* The producer's start-up line runs only after construction succeeds, so without a record
           written from the failure path an operator would see the container's deployment failure and
           nothing about which values were in force. The exception leaves the producer unchanged
           because that is what CDI turns into the failed deployment: an application installed with a
           ceiling no order can satisfy is the outcome the refusal exists to prevent. */
        ControlLimitsProducer producer = producerWith(new BigDecimal("-1.00"),
                DEFAULT_MAX_POSITION_NOTIONAL, DEFAULT_FAT_FINGER_THRESHOLD, RESTRICTED_LIST,
                DEFAULT_EXCEPTION_SLA_HOURS);

        List<IllegalArgumentException> refusals = new ArrayList<>();
        List<LogRecord> records = logsFromProducer(
                () -> refusals.add(assertThrows(IllegalArgumentException.class,
                        producer::controlLimits,
                        "a non-positive ceiling must not produce a ControlLimits bean")));

        assertEquals("MAX_ORDER_NOTIONAL must be greater than zero, not -1.00",
                refusals.get(0).getMessage(), "the producer must rethrow the refusal unchanged");

        assertEquals(1, records.size(), "the refusal must be logged exactly once");
        LogRecord refusal = records.get(0);
        assertEquals(Level.SEVERE, refusal.getLevel(), "an unusable rule set is a SEVERE condition");
        assertTrue(refusal.getMessage().startsWith("Refusing the configured pre-trade controls: "
                + "MAX_ORDER_NOTIONAL must be greater than zero, not -1.00"),
                "the record must open with the refusal: " + refusal.getMessage());
        //Every submitted value, so the log line alone tells an operator what the container had.
        assertTrue(refusal.getMessage().contains("submitted MAX_ORDER_NOTIONAL=-1.00")
                && refusal.getMessage().contains("MAX_POSITION_NOTIONAL=5000000.00")
                && refusal.getMessage().contains("FAT_FINGER_NOTIONAL_THRESHOLD=2500000.00")
                && refusal.getMessage().contains("RESTRICTED_SYMBOLS=" + RESTRICTED_LIST)
                && refusal.getMessage().contains("EXCEPTION_SLA_HOURS=24"),
                "the record must name every submitted value: " + refusal.getMessage());
    }

    @Test
    void testProducerReportsTheEffectiveControlsWhenTheConfigurationIsUsable() {
        //The start-up line and the produced values must be exactly what they were before the
        //refusal path was added around the construction.
        ControlLimitsProducer producer = producerWith(DEFAULT_MAX_ORDER_NOTIONAL,
                DEFAULT_MAX_POSITION_NOTIONAL, DEFAULT_FAT_FINGER_THRESHOLD, " rstra ,RstrB",
                DEFAULT_EXCEPTION_SLA_HOURS);

        List<ControlLimits> produced = new ArrayList<>();
        List<LogRecord> records = logsFromProducer(() -> produced.add(producer.controlLimits()));

        ControlLimits limits = produced.get(0);
        assertEquals("1000000.00", limits.getMaxOrderNotional().toPlainString(),
                MAX_ORDER_NOTIONAL + " as produced");
        assertEquals("5000000.00", limits.getMaxPositionNotional().toPlainString(),
                MAX_POSITION_NOTIONAL + " as produced");
        assertEquals("2500000.00", limits.getFatFingerNotionalThreshold().toPlainString(),
                FAT_FINGER + " threshold as produced");
        assertEquals(Set.of("RSTRA", "RSTRB"), limits.getRestrictedSymbols(),
                "the configured list must arrive canonicalized");
        assertEquals(DEFAULT_EXCEPTION_SLA_HOURS, limits.getExceptionSlaHours(),
                "EXCEPTION_SLA_HOURS as produced");

        assertEquals(1, records.size(), "the effective set must be reported exactly once");
        LogRecord effective = records.get(0);
        assertEquals(Level.INFO, effective.getLevel(), "a usable rule set is an INFO condition");
        assertEquals("Effective pre-trade controls: MAX_ORDER_NOTIONAL=1000000.00, "
                + "MAX_POSITION_NOTIONAL=5000000.00, FAT_FINGER_NOTIONAL_THRESHOLD=2500000.00, "
                + "RESTRICTED_SYMBOLS=[RSTRA, RSTRB], EXCEPTION_SLA_HOURS=24",
                effective.getMessage(), "start-up report of the effective controls");
    }


    private static PreTradeControlService defaultService() {
        return new PreTradeControlService(new ControlLimits(DEFAULT_MAX_ORDER_NOTIONAL,
                DEFAULT_MAX_POSITION_NOTIONAL, DEFAULT_FAT_FINGER_THRESHOLD,
                DEFAULT_RESTRICTED_SYMBOLS, DEFAULT_EXCEPTION_SLA_HOURS));
    }


    private static Order order(String clientId, String side, String symbol, long quantity,
            String limitPrice) {
        /* The symbol argument is spelled as a caller would post it and is canonicalized here,
           exactly as OrderLifecycleService.submit canonicalizes it before any control runs. That
           keeps the case- and padding-insensitivity these tests assert a property of the pipeline
           while leaving the single canonicalization where the service contract puts it: the
           evaluator consumes the canonical symbol and normalizes nothing itself. */
        return orderWithRawSymbol(clientId, side, symbol.trim().toUpperCase(Locale.ROOT), quantity,
                limitPrice);
    }

    //Bypasses the canonicalization above, for the one test that has to hand the evaluator a symbol
    //the submit path would never produce.
    private static Order orderWithRawSymbol(String clientId, String side, String symbol,
            long quantity, String limitPrice) {
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

    /* ControlLimitsProducer takes its five values through private @ConfigProperty fields, which is
       the bean shape the module fixes for it, so a unit test running without a configuration
       container populates them reflectively rather than having the producer carry a constructor
       that exists only to be called from here. */
    private static ControlLimitsProducer producerWith(BigDecimal maxOrderNotional,
            BigDecimal maxPositionNotional, BigDecimal fatFingerNotionalThreshold,
            String restrictedSymbols, int exceptionSlaHours) {
        ControlLimitsProducer producer = new ControlLimitsProducer();

        inject(producer, "maxOrderNotional", maxOrderNotional);
        inject(producer, "maxPositionNotional", maxPositionNotional);
        inject(producer, "fatFingerNotionalThreshold", fatFingerNotionalThreshold);
        inject(producer, "restrictedSymbols", new DeclaredConfigValue(restrictedSymbols));
        inject(producer, "exceptionSlaHours", exceptionSlaHours);

        return producer;
    }

    private static void inject(ControlLimitsProducer producer, String field, Object value) {
        try {
            Field injectionPoint = ControlLimitsProducer.class.getDeclaredField(field);
            injectionPoint.setAccessible(true);
            injectionPoint.set(producer, value);
        } catch (ReflectiveOperationException unavailable) {
            //A renamed or retyped injection point is a change of the producer's contract, not a
            //test that happens to fail: report it as such rather than as a null value later.
            fail("ControlLimitsProducer." + field + " is no longer injectable: " + unavailable);
        }
    }

    //Both the refusal and the start-up report are part of the producer's contract, and a JUL record
    //captured here is the only way a unit test can observe either.
    private static List<LogRecord> logsFromProducer(Runnable work) {
        Logger producerLogger = Logger.getLogger(ControlLimitsProducer.class.getName());
        List<LogRecord> records = new ArrayList<>();

        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            //Nothing is buffered and no resource is held, so there is nothing to flush or release.
            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };

        producerLogger.addHandler(capture);
        try {
            work.run();
        } finally {
            producerLogger.removeHandler(capture);
        }

        return records;
    }

    //The three facts ControlLimits.restrictedSymbolsFrom reads, as a source that declares the key
    //reports them: the raw text, the expanded value and the source's own name.
    private static final class DeclaredConfigValue implements ConfigValue {
        private static final int SOURCE_ORDINAL = 100;

        private final String value;

        private DeclaredConfigValue(String value) {
            this.value = value;
        }

        @Override
        public String getName() {
            return "RESTRICTED_SYMBOLS";
        }

        @Override
        public String getValue() {
            return value;
        }

        @Override
        public String getRawValue() {
            return value;
        }

        @Override
        public String getSourceName() {
            return PreTradeControlServiceTest.class.getSimpleName();
        }

        @Override
        public int getSourceOrdinal() {
            return SOURCE_ORDINAL;
        }
    }
}
