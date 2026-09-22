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
import java.util.Locale;

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

    //Formatted under Locale.ROOT rather than the JVM's default: %d follows the default formatting
    //locale, so under a non-Latin numbering system these ids would come out in localized digits.
    //ORD-000001 and EXE-000001 are an ASCII contract - they are read back through the REST paths,
    //quoted in the README and asserted literally by the tests - and must not vary by deployment.
    public String nextOrderId() {
        return String.format(Locale.ROOT, "ORD-%06d", orderIdSequence.incrementAndGet());
    }

    public String nextExecutionId() {
        return String.format(Locale.ROOT, "EXE-%06d", executionIdSequence.incrementAndGet());
    }

    //Duplicate submission is settled here, in one atomic step, before any order object exists, so
    //exactly one of any number of concurrent submissions carrying the same client order id is told
    //to proceed. A read followed by a write would leave a window in which two callers each
    //believed they were first.
    public boolean reserveClientOrderId(String clientOrderId, String orderId) {
        return orderIdsByClientOrderId.putIfAbsent(clientOrderId, orderId) == null;
    }

    //Creation only, never replacement. An order id is minted once by nextOrderId, so a key that is
    //already taken means this call would swap out a stored order without passing through
    //transition - the one path that asserts the edge is legal and appends the audit event. Refusing
    //it keeps "no status changes outside an audited transition" a property of the store itself
    //rather than a habit its callers have to keep.
    public void insert(Order order) {
        if (orders.putIfAbsent(order.getOrderId(), order) != null) {
            throw new IllegalStateException("An order already exists with id " + order.getOrderId());
        }
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

    //This store owns the order of a full read, and it owns it alone: the copy is taken and sorted
    //once here - the zero-padded id sorts naturally - and handed out unmodifiable, so a reader
    //returns this snapshot as it received it instead of copying and sorting it a second time.
    public List<Order> list() {
        List<Order> snapshot = new ArrayList<>(orders.values());
        snapshot.sort(Comparator.comparing(Order::getOrderId));
        return Collections.unmodifiableList(snapshot);
    }

    public int count() {
        return orders.size();
    }
}
