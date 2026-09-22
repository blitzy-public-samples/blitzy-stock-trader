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
import java.math.RoundingMode;

//Collections
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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
        //has to carry the whole picture rather than the first breach found. Under the default
        //limits an order of notional 2500000.00 therefore reports FAT_FINGER passed in the same
        //list as a failed MAX_ORDER_NOTIONAL.
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
                : "Order notional " + amount(observed) + " exceeds " + CONTROL_MAX_ORDER_NOTIONAL
                        + " " + amount(limit);

        return new ControlResult(CONTROL_MAX_ORDER_NOTIONAL, amount(limit), amount(observed),
                passed, reason);
    }

    private ControlResult evaluateMaxPositionNotional(Order order, long resultingQuantity) {
        BigDecimal limit = limits.getMaxPositionNotional();
        long absoluteQuantity = Math.abs(resultingQuantity);
        //The whole resulting quantity is marked at the order's limitPrice, which is exactly the
        //lastPrice the position will carry once the order fills, so the value evaluated here equals
        //the positionNotional stored afterwards. Marking only the incoming shares at the order
        //price and the rest at the old lastPrice would test a figure that exists nowhere.
        BigDecimal observed = BigDecimal.valueOf(absoluteQuantity).multiply(order.getLimitPrice());
        boolean passed = !exceeds(observed, limit);

        //The separator is U+00D7 MULTIPLICATION SIGN, not the letter x, and is escaped so this source
        //stays ASCII and the asserted text survives any source-encoding setting the build runs with.
        String reason = passed ? WITHIN_LIMIT
                : "Resulting position notional " + amount(observed) + " (" + absoluteQuantity
                        + " \u00D7 " + amount(order.getLimitPrice()) + ") exceeds "
                        + CONTROL_MAX_POSITION_NOTIONAL + " " + amount(limit);

        return new ControlResult(CONTROL_MAX_POSITION_NOTIONAL, amount(limit), amount(observed),
                passed, reason);
    }

    private ControlResult evaluateRestrictedSymbol(Order order) {
        Set<String> restricted = limits.getRestrictedSymbols();
        //ControlLimits stores the restricted list trimmed and upper-cased, and OrderLifecycleService
        //canonicalizes a submitted symbol the same way, so this is a no-op for every live order. It
        //is repeated because the restriction must also hold for an order handed straight to this
        //service, and because the canonical form is what the result reports: the reason has to name
        //the ticker as the restricted list holds it, not as the caller happened to spell it.
        String symbol = canonical(order.getSymbol());
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

    private long resultingQuantity(Order order, Position existing) {
        long existingQuantity = (existing == null) ? 0L : existing.getQuantity();

        //An unrecognised side is treated as a buy rather than rejected here, because validating the
        //side belongs to OrderLifecycleService; adding is the direction that grows the exposure this
        //control measures, so an unexpected value can only make the check stricter, never blinder.
        return SELL.equalsIgnoreCase(order.getSide())
                ? existingQuantity - order.getQuantity()
                : existingQuantity + order.getQuantity();
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
        return value.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    //Locale.ROOT, not the default locale, because a Turkish locale upper-cases "i" to a dotted
    //capital and a ticker would then miss its own entry in the restricted list.
    private static String canonical(String symbol) {
        return (symbol == null) ? null : symbol.trim().toUpperCase(Locale.ROOT);
    }
}
