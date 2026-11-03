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

package com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.lifecycle;

import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.ExceptionStatus;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.OrderStatus;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.PostTradeStatus;

//Collections
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;


/** Declares every legal order, post-trade and exception lifecycle transition, and refuses every other pair */
public final class LifecycleTransitions {
    public static final String NONE = "(none)";

    private static final Map<OrderStatus, Set<OrderStatus>> ORDER_TRANSITIONS;
    private static final Map<PostTradeStatus, Set<PostTradeStatus>> POST_TRADE_TRANSITIONS;
    private static final Map<ExceptionStatus, Set<ExceptionStatus>> EXCEPTION_TRANSITIONS;

    static {
        Map<OrderStatus, Set<OrderStatus>> order = new EnumMap<>(OrderStatus.class);
        order.put(OrderStatus.SUBMITTED, EnumSet.of(OrderStatus.REJECTED, OrderStatus.ACCEPTED));
        order.put(OrderStatus.ACCEPTED, EnumSet.of(OrderStatus.EXECUTED));
        ORDER_TRANSITIONS = Collections.unmodifiableMap(order);

        Map<PostTradeStatus, Set<PostTradeStatus>> postTrade = new EnumMap<>(PostTradeStatus.class);
        postTrade.put(PostTradeStatus.PENDING_AFFIRMATION,
                EnumSet.of(PostTradeStatus.EXCEPTION, PostTradeStatus.SETTLEMENT_READY));
        postTrade.put(PostTradeStatus.EXCEPTION, EnumSet.of(PostTradeStatus.SETTLEMENT_READY));
        POST_TRADE_TRANSITIONS = Collections.unmodifiableMap(postTrade);

        //No OPEN -> RESOLVED edge exists on purpose. Resolving an unassigned exception is one
        //composite step that assigns and then resolves, leaving both events on the timeline; a
        //direct edge would let an exception close with no recorded owner, which is exactly the
        //fact an operations reviewer needs. ASSIGNED -> ASSIGNED is legal so a re-assignment is
        //recorded rather than refused.
        Map<ExceptionStatus, Set<ExceptionStatus>> exception = new EnumMap<>(ExceptionStatus.class);
        exception.put(ExceptionStatus.OPEN, EnumSet.of(ExceptionStatus.ASSIGNED));
        exception.put(ExceptionStatus.ASSIGNED,
                EnumSet.of(ExceptionStatus.ASSIGNED, ExceptionStatus.RESOLVED));
        exception.put(ExceptionStatus.RESOLVED, EnumSet.of(ExceptionStatus.SETTLEMENT_READY));
        EXCEPTION_TRANSITIONS = Collections.unmodifiableMap(exception);
    }

    //The origin edges live in their own sets because an EnumMap admits no null key, and because
    //"(none)" is not a state any entity ever occupies - it is the absence of a prior state, which
    //the audit timeline records as NONE.
    private static final Set<OrderStatus> ORDER_ORIGINS =
            Collections.unmodifiableSet(EnumSet.of(OrderStatus.SUBMITTED));
    private static final Set<PostTradeStatus> POST_TRADE_ORIGINS =
            Collections.unmodifiableSet(EnumSet.of(PostTradeStatus.PENDING_AFFIRMATION));
    private static final Set<ExceptionStatus> EXCEPTION_ORIGINS =
            Collections.unmodifiableSet(EnumSet.of(ExceptionStatus.OPEN));

    private LifecycleTransitions() {
    }

    public static void assertLegal(OrderStatus from, OrderStatus to) {
        assertLegal(from, to, ORDER_TRANSITIONS, ORDER_ORIGINS);
    }

    public static void assertLegal(PostTradeStatus from, PostTradeStatus to) {
        assertLegal(from, to, POST_TRADE_TRANSITIONS, POST_TRADE_ORIGINS);
    }

    public static void assertLegal(ExceptionStatus from, ExceptionStatus to) {
        assertLegal(from, to, EXCEPTION_TRANSITIONS, EXCEPTION_ORIGINS);
    }

    private static <S extends Enum<S>> void assertLegal(S from, S to, Map<S, Set<S>> table, Set<S> origins) {
        Set<S> permitted = (from == null) ? origins : table.getOrDefault(from, Collections.emptySet());
        if (to == null || !permitted.contains(to)) {
            //Callers run this as the first statement of a store transition operator: the refusal
            //has to happen before the replacement object is built and before AuditTimeline.append,
            //so an illegal request leaves both the entity and the timeline untouched. The arrow is
            //written as an escape so the message is byte-identical whatever encoding compiles it.
            throw new StateConflictException(
                    render(from) + " \u2192 " + render(to) + " is not a legal transition");
        }
    }

    private static String render(Enum<?> state) {
        return (state == null) ? NONE : state.name();
    }
}
