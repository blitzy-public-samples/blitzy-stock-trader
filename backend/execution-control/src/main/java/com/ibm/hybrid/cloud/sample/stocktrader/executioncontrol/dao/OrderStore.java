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

import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.Order;

//Collections
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

//Concurrency
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

//Functional interfaces
import java.util.function.UnaryOperator;

//CDI 4.0
import jakarta.enterprise.context.ApplicationScoped;


/** In-memory store of every simulated order, keyed by order id */
@ApplicationScoped
public class OrderStore {

    private final ConcurrentHashMap<String, Order> orders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> orderIdsByClientOrderId = new ConcurrentHashMap<>();
    private final AtomicLong orderIdSequence = new AtomicLong();
    private final AtomicLong executionIdSequence = new AtomicLong();

    public String nextOrderId() {
        return String.format("ORD-%06d", orderIdSequence.incrementAndGet());
    }

    public String nextExecutionId() {
        return String.format("EXE-%06d", executionIdSequence.incrementAndGet());
    }

    //Duplicate submission is settled here, in one atomic step, before any order object exists, so
    //exactly one of any number of concurrent submissions carrying the same client order id is told
    //to proceed. A read followed by a write would leave a window in which two callers each
    //believed they were first.
    public boolean reserveClientOrderId(String clientOrderId, String orderId) {
        return orderIdsByClientOrderId.putIfAbsent(clientOrderId, orderId) == null;
    }

    public void insert(Order order) {
        orders.put(order.getOrderId(), order);
    }

    //The operator's three steps - assert the transition is legal, build the replacement order,
    //append the audit event - all run inside this one compute, so concurrent transitions on the
    //same order are serialized by the map and an operator that refuses the transition throws out
    //of the remapping function, leaving the stored order and the timeline exactly as they were.
    //An absent key yields null rather than a manufactured order: only the caller knows what an
    //unknown id means to the request it is serving.
    public Order transition(String orderId, UnaryOperator<Order> operator) {
        return orders.compute(orderId, (key, current) -> (current == null) ? null : operator.apply(current));
    }

    public Order find(String orderId) {
        return (orderId == null) ? null : orders.get(orderId);
    }

    public Order findByClientOrderId(String clientOrderId) {
        String orderId = (clientOrderId == null) ? null : orderIdsByClientOrderId.get(clientOrderId);
        return (orderId == null) ? null : orders.get(orderId);
    }

    public List<Order> list() {
        List<Order> snapshot = new ArrayList<>(orders.values());
        snapshot.sort(Comparator.comparing(Order::getOrderId));
        return Collections.unmodifiableList(snapshot);
    }

    public int count() {
        return orders.size();
    }
}
