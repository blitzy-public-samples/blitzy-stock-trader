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

//Arbitrary-precision arithmetic
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

//Collections
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

//CDI 4.0
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

//mpTelemetry 2.1
import io.opentelemetry.instrumentation.annotations.WithSpan;


/** Evaluates the four configurable pre-trade controls against one order and records every outcome */
@ApplicationScoped
public class PreTradeControlService {
    private static final String CONTROL_MAX_ORDER_NOTIONAL = "MAX_ORDER_NOTIONAL";
    private static final String CONTROL_MAX_POSITION_NOTIONAL = "MAX_POSITION_NOTIONAL";
    private static final String CONTROL_RESTRICTED_SYMBOL = "RESTRICTED_SYMBOL";
    private static final String CONTROL_FAT_FINGER = "FAT_FINGER";
    private static final String WITHIN_LIMIT = "within limit";
    private static final String NOT_RESTRICTED = "not restricted";
    private static final String BUY = "BUY";
    private static final String SELL = "SELL";
    private static final int AMOUNT_SCALE = 2;
    private static final int CONTROL_COUNT = 4;

    private final ControlLimits limits;


    @Inject
    public PreTradeControlService(ControlLimits controlLimits) {
        limits = controlLimits;
    }

    //Exists only so CDI can generate the @ApplicationScoped client proxy, which requires a
    //non-private no-arg constructor; no application code calls it and the proxy reads no field.
    protected PreTradeControlService() {
        limits = null;
    }

    @WithSpan
    public List<ControlResult> evaluate(Order order, Position existing) {
        Objects.requireNonNull(order, "order is required");

        //Nothing short-circuits: Order.controlResults persists all four outcomes and
        //OrderLifecycleService joins every failing reason into one rejectionReason, so a rejection
        //has to carry the whole picture rather than the first breach found - one order can sit
        //within one notional ceiling and outside another, and both verdicts are evidence.
        List<ControlResult> results = new ArrayList<>(CONTROL_COUNT);
        results.add(evaluateMaxOrderNotional(order));
        results.add(evaluateMaxPositionNotional(order, resultingQuantity(order, existing)));
        results.add(evaluateRestrictedSymbol(order));
        results.add(evaluateFatFinger(order));

        return Collections.unmodifiableList(results);
    }

    private ControlResult evaluateMaxOrderNotional(Order order) {
        BigDecimal limit = limits.getMaxOrderNotional();
        BigDecimal observed = order.getNotional();
        boolean passed = !exceeds(observed, limit);

        String reason = passed ? WITHIN_LIMIT
                : "Order notional " + amount(observed) + " exceeds MAX_ORDER_NOTIONAL "
                        + amount(limit);

        return new ControlResult(CONTROL_MAX_ORDER_NOTIONAL, amount(limit), amount(observed),
                passed, reason);
    }

    private ControlResult evaluateMaxPositionNotional(Order order, BigInteger resultingQuantity) {
        BigDecimal limit = limits.getMaxPositionNotional();
        /* The magnitude is an exact BigInteger rather than Math.abs of a long: a share count that
           overflowed a long would wrap to Long.MIN_VALUE, whose Math.abs is still negative, and a
           negative observed notional clears any positive ceiling. Measuring the true magnitude is
           what keeps this control from passing the very exposure it exists to refuse. */
        BigInteger absoluteQuantity = resultingQuantity.abs();
        //The whole resulting quantity is marked at the order's limitPrice, which is exactly the
        //lastPrice the position will carry once the order fills, so the value evaluated here equals
        //the positionNotional stored afterwards. Marking only the incoming shares at the order
        //price and the rest at the old lastPrice would test a figure that exists nowhere.
        BigDecimal observed = new BigDecimal(absoluteQuantity).multiply(order.getLimitPrice());
        boolean passed = !exceeds(observed, limit);

        //The separator is U+00D7 MULTIPLICATION SIGN, not the letter x, and is escaped so this source
        //stays ASCII and the asserted text survives any source-encoding setting the build runs with.
        String reason = passed ? WITHIN_LIMIT
                : "Resulting position notional " + amount(observed) + " (" + absoluteQuantity
                        + " \u00D7 " + amount(order.getLimitPrice())
                        + ") exceeds MAX_POSITION_NOTIONAL " + amount(limit);

        return new ControlResult(CONTROL_MAX_POSITION_NOTIONAL, amount(limit), amount(observed),
                passed, reason);
    }

    private ControlResult evaluateRestrictedSymbol(Order order) {
        Set<String> restricted = limits.getRestrictedSymbols();
        /* The symbol is consumed exactly as the order carries it. OrderLifecycleService.submit
           canonicalizes it once, before any lookup, control or position key sees it, and
           ControlLimits stores the restricted list the same way, so the two forms already agree.
           Normalizing again here would put a second copy of that rule in the one place that must
           not own it: the day the two spellings diverged, this control would silently match its own
           private form of the ticker rather than the one the order was stored and filled under. */
        String symbol = order.getSymbol();
        boolean passed = !restricted.contains(symbol);

        String reason = passed ? NOT_RESTRICTED
                : "Symbol " + symbol + " is on the restricted list RESTRICTED_SYMBOLS";

        return new ControlResult(CONTROL_RESTRICTED_SYMBOL, String.join(",", restricted), symbol,
                passed, reason);
    }

    private ControlResult evaluateFatFinger(Order order) {
        //Deliberately a second, independent verdict on the same order notional: the fat-finger
        //ceiling is a firm-wide anomaly mandate with its own configured value, so it neither
        //defers to nor overrides MAX_ORDER_NOTIONAL.
        BigDecimal limit = limits.getFatFingerNotionalThreshold();
        BigDecimal observed = order.getNotional();
        boolean passed = !exceeds(observed, limit);

        String reason = passed ? WITHIN_LIMIT
                : "Order notional " + amount(observed) + " exceeds FAT_FINGER_NOTIONAL_THRESHOLD "
                        + amount(limit);

        return new ControlResult(CONTROL_FAT_FINGER, amount(limit), amount(observed), passed,
                reason);
    }

    /* BigInteger, not long: the resulting share count is the sum of a stored position and an order
       quantity, and a long sum that overflowed would wrap to a magnitude smaller than either input
       - the one arithmetic outcome that makes this control under-report exposure instead of
       over-reporting it. Exact arithmetic here, and a refusal at the fill in OrderLifecycleService,
       keeps a quantity no long can hold out of the store rather than out of the measurement. */
    private BigInteger resultingQuantity(Order order, Position existing) {
        BigInteger existingQuantity = BigInteger.valueOf((existing == null) ? 0L : existing.getQuantity());
        BigInteger orderQuantity = BigInteger.valueOf(order.getQuantity());
        String side = order.getSide();

        /* The side is matched exactly against the canonical BUY and SELL that
           OrderLifecycleService.submit validates and stores, and an unrecognised value fails fast
           rather than falling back to one of them. Defaulting to BUY looks conservative and is not:
           against an existing short, adding the order quantity moves the resulting position towards
           zero, so an unexpected side would report less exposure than the trade really carries. */
        if (BUY.equals(side)) {
            return existingQuantity.add(orderQuantity);
        }
        if (SELL.equals(side)) {
            return existingQuantity.subtract(orderQuantity);
        }

        throw new IllegalArgumentException(
                "side must be BUY or SELL to measure a resulting position, not " + side);
    }

    //Equality passes: a value sitting exactly on a configured ceiling is still inside the mandate,
    //so only a strict breach rejects. compareTo rather than equals because BigDecimal.equals is
    //scale-sensitive - 1000000.0 does not equal 1000000.00 - and that boundary is the asserted rule.
    private static boolean exceeds(BigDecimal observed, BigDecimal limit) {
        return observed.compareTo(limit) > 0;
    }

    //Locale-independent by construction, unlike a "%.2f" format, which would render 1000000,00 on a
    //JVM whose default locale uses a comma separator and break text compared character for character.
    private static String amount(BigDecimal value) {
        /* Refused on its representation before the conversion and the rendering below, because a
           BigDecimal keeps its exponent as a scale: setScale and toPlainString are where a compact
           exponent becomes millions of digits, and every amount the three notional controls report
           passes through here. The order's own amounts are already bounded when Order is
           constructed, so what this guard actually catches is a configured limit wide enough to
           exhaust the heap while being reported - and it reports the representation rather than the
           value, since rendering the value is exactly what it is refusing to do. */
        if (!Order.isAmountWithinBounds(value)) {
            throw new IllegalArgumentException("a control amount outside the supported range "
                    + "cannot be rendered (" + Order.describeAmount(value) + "): at most "
                    + Order.MAX_AMOUNT_DECIMAL_PLACES + " decimal places, "
                    + Order.MAX_AMOUNT_SIGNIFICANT_DIGITS
                    + " significant digits and a magnitude below 1E+"
                    + (Order.MAX_AMOUNT_ADJUSTED_EXPONENT + 1) + " are supported");
        }

        return value.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP).toPlainString();
    }
}
