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

//Arbitrary-precision arithmetic
import java.math.BigDecimal;
import java.math.RoundingMode;

//Collections
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

//CDI 4.0
import jakarta.enterprise.inject.Vetoed;


//Vetoed because beans.xml discovers every class: without this, the no-arg constructor below would
//also make ControlLimits a managed bean, and Weld would reject every injection point as ambiguous
//between that bean and the producer (WELD-001409). ControlLimitsProducer is the only legitimate
//source of limits - a managed-bean instance would carry that constructor's zeroes, not configuration.
/** Immutable holder of the effective pre-trade control limits and the settlement-exception SLA */
@Vetoed
public class ControlLimits {
    private static final int AMOUNT_SCALE = 2;

    private final BigDecimal maxOrderNotional;
    private final BigDecimal maxPositionNotional;
    private final BigDecimal fatFingerNotionalThreshold;
    private final Set<String> restrictedSymbols;
    private final int exceptionSlaHours;


    public ControlLimits(BigDecimal maxOrderNotional, BigDecimal maxPositionNotional,
            BigDecimal fatFingerNotionalThreshold, Collection<String> restrictedSymbols,
            int exceptionSlaHours) {
        //Every consumer renders these amounts with two decimals - the pre-trade control reason
        //strings and the GET /controls body - so the scale is normalized once here and no
        //consumer re-derives it.
        this.maxOrderNotional = Objects.requireNonNull(maxOrderNotional,
                "maxOrderNotional is required").setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        this.maxPositionNotional = Objects.requireNonNull(maxPositionNotional,
                "maxPositionNotional is required").setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        this.fatFingerNotionalThreshold = Objects.requireNonNull(fatFingerNotionalThreshold,
                "fatFingerNotionalThreshold is required").setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        this.restrictedSymbols = canonicalize(restrictedSymbols);
        this.exceptionSlaHours = exceptionSlaHours;
    }

    //Retained because ControlLimitsProducer declares the bean it returns @ApplicationScoped, and CDI
    //generates that bean's client proxy only from a non-private no-arg constructor; nothing calls it.
    protected ControlLimits() {
        this(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Collections.emptySet(), 0);
    }

    //Canonicalized here rather than at the configuration boundary because unit tests construct this object
    //directly, and the invariant "the stored set is stripped and upper-cased" has to hold for every
    //caller: the control evaluation matches an already-canonical order symbol against this set with
    //no further normalization. LinkedHashSet preserves the configured order, which is what lets
    //GET /controls report the restricted list deterministically.
    private static Set<String> canonicalize(Collection<String> symbols) {
        if (symbols == null) {
            return Collections.emptySet();
        }

        Set<String> canonical = new LinkedHashSet<>();
        for (String symbol : symbols) {
            if (symbol == null || symbol.isBlank()) {
                continue;
            }
            //strip rather than trim, to match the isBlank above and the form a submitted symbol
            //reaches the evaluation in: trim stops at U+0020, so an entry padded with Unicode
            //whitespace would be kept unstripped and would then match no order at all - a
            //restricted symbol nobody could see was unenforced.
            canonical.add(symbol.strip().toUpperCase(Locale.ROOT));
        }

        return Collections.unmodifiableSet(canonical);
    }

    //MicroProfile Config reports three facts about a configured key: the raw text the winning source
    //holds, the expanded value, and the name of that source. A source that declares the key names
    //itself even when the text is empty, which is an operator saying "nothing is restricted"; a key
    //no source declares reports none of the three, and that alone is a misconfiguration - it stops
    //start-up rather than let the service trade against a restricted list nobody set. The decision
    //lives beside the canonicalization it feeds so the two cannot drift apart, and so the pre-trade
    //rule it produces is testable without standing up a configuration container.
    static Set<String> restrictedSymbolsFrom(String rawValue, String expandedValue, String sourceName) {
        String declared = rawValue != null ? rawValue : expandedValue;
        if (declared == null) {
            if (sourceName == null) {
                throw new IllegalStateException("RESTRICTED_SYMBOLS is not declared by any"
                        + " configuration source; it must be declared in"
                        + " META-INF/microprofile-config.properties");
            }
            declared = "";
        }

        return canonicalize(Arrays.asList(declared.split(",")));
    }

    public BigDecimal getMaxOrderNotional() {
        return maxOrderNotional;
    }

    public BigDecimal getMaxPositionNotional() {
        return maxPositionNotional;
    }

    public BigDecimal getFatFingerNotionalThreshold() {
        return fatFingerNotionalThreshold;
    }

    public Set<String> getRestrictedSymbols() {
        return restrictedSymbols;
    }

    public int getExceptionSlaHours() {
        return exceptionSlaHours;
    }
}
