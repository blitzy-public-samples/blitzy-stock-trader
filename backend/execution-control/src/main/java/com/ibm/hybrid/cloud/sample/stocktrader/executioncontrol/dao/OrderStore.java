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
import java.util.Objects;

//Concurrency
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

//Functional interfaces
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

//CDI 4.0
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;


/** In-memory store of every simulated order, keyed by order id */
@ApplicationScoped
public class OrderStore {

    //The label and the configuration key the admission counter reports this structure under.
    private static final String STRUCTURE = "order";
    private static final String CAPACITY_KEY = "ORDER_CAPACITY";

    //A fixed stripe count, not one lock per order: the table then cannot grow with the number of
    //orders, and two orders that happen to share a stripe contend only for the few microseconds a
    //post-trade publication holds it.
    private static final int ORDER_LOCK_STRIPES = 64;

    private final ConcurrentHashMap<String, Order> orders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> orderIdsByClientOrderId = new ConcurrentHashMap<>();
    private final AtomicLong orderIdSequence = new AtomicLong();
    private final AtomicLong executionIdSequence = new AtomicLong();
    private final ReentrantLock[] orderLocks = createOrderLocks();

    /* Submission is the estate's primary growth vector - one accepted order also writes an
       execution, a position mark and up to six audit events - so the whole in-memory footprint is
       governed from this ceiling, and it is sized per instance from configuration rather than held
       as a code constant: a deployment whose volume exceeds the default must be able to say so
       without a code change, and one that needs unbounded state still needs a datastore. */
    private final AdmissionCounter admission;


    @Inject
    public OrderStore(CapacityLimits limits) {
        this.admission = new AdmissionCounter(STRUCTURE, CAPACITY_KEY, limits.getMaxOrders());
    }

    //Public rather than protected, and for two reasons: CDI generates the @ApplicationScoped
    //client proxy only from a non-private no-arg constructor, and the lifecycle and audit unit
    //tests build the whole collaborator graph with new in a different package. A store built this
    //way carries the shipped defaults, which are the values microprofile-config.properties holds.
    public OrderStore() {
        this(CapacityLimits.defaults());
    }

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

    //The exact atomic claim every submission passes, and the one authority on the ceiling: the
    //size comparisons the lifecycle asks first are gates that decide which refusal a caller gets.
    public boolean tryAdmitOrder() {
        return admission.tryAdmit();
    }

    //Returns a claim that never became a stored order - a duplicate client order id refused after
    //admission - because a slot consumed by an order that does not exist would shrink the usable
    //ceiling for the life of the process.
    public void releaseOrderAdmission() {
        admission.release();
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

    //A transition is atomic for one key in one map, which is all an order's own state needs. The
    //post-trade flow needs more than that: opening a settlement exception against an order, and
    //releasing that order when the exception is cleared, each move two entities held in two
    //independent maps, and there is no compute that spans both. This is the one primitive that
    //makes such a pair indivisible per order - callers of both maps run their whole sequence in
    //here - and it is taken before any compute and never from inside one, so the single lock
    //order (this lock, then a map's own) admits no cycle.
    public <T> T inOrderLock(String orderId, Supplier<T> work) {
        //floorMod rather than %: a negative hash gives a negative remainder and so an index
        //outside the array, while floorMod always lands inside the stripe range.
        ReentrantLock lock =
                orderLocks[Math.floorMod(Objects.hashCode(orderId), ORDER_LOCK_STRIPES)];
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    private static ReentrantLock[] createOrderLocks() {
        ReentrantLock[] locks = new ReentrantLock[ORDER_LOCK_STRIPES];
        for (int stripe = 0; stripe < locks.length; stripe++) {
            locks[stripe] = new ReentrantLock();
        }
        return locks;
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

    //Ordering is a property of the whole store rather than of a page, so the snapshot is sorted
    //before the page is cut: without that, two calls could return overlapping or skipped orders
    //while the map iterated differently. The order ceiling is what keeps the intermediate copy
    //bounded.
    public List<Order> list(int offset, int limit) {
        List<Order> snapshot = new ArrayList<>(orders.values());
        snapshot.sort(Comparator.comparing(Order::getOrderId));
        return page(snapshot, offset, limit);
    }

    public int count() {
        return orders.size();
    }

    //Read off the store rather than off configuration, which is what lets the health probes report
    //the ceiling and the headroom without reading a configuration source of their own.
    public int maxOrders() {
        return admission.ceiling();
    }

    /* Headroom in claims rather than in stored orders, because a claim is what the next submission
       has to win: a claim taken by a submission still in flight is capacity this store will not
       grant twice, so reporting ceiling minus count would overstate what remains. */
    public int orderHeadroom() {
        return admission.headroom();
    }

    /* Both arguments are taken defensively rather than asserted: a page read is a GET, and a
       mis-typed query parameter must answer with the nearest page that exists instead of a 500.
       A non-positive limit means "do not cut the page" rather than "return nothing", which lets an
       in-process caller page without restating the REST layer's maximum page size. */
    private static List<Order> page(List<Order> ordered, int offset, int limit) {
        int from = Math.min(Math.max(offset, 0), ordered.size());
        int to = (limit <= 0) ? ordered.size()
                : (int) Math.min((long) from + limit, ordered.size());

        return Collections.unmodifiableList(new ArrayList<>(ordered.subList(from, to)));
    }
}
