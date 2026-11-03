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

import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.SettlementException;
//The module's one capacity type, which is what lets one mapper answer 503 wherever a ceiling is
//reached; the class it names is a leaf that imports nothing, so no dependency on lifecycle
//behaviour comes with it.
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.lifecycle.CapacityExceededException;

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
import jakarta.inject.Inject;


/** In-memory store of every simulated settlement exception, keyed by exception id */
@ApplicationScoped
public class SettlementExceptionStore {

    //The label and the configuration key the admission counter reports this structure under.
    private static final String STRUCTURE = "settlement exception";
    private static final String CAPACITY_KEY = "SETTLEMENT_EXCEPTION_CAPACITY";

    private final ConcurrentHashMap<String, SettlementException> exceptions = new ConcurrentHashMap<>();
    private final AtomicLong exceptionIdSequence = new AtomicLong();

    /* The ceiling on how many settlement exceptions this process will hold, sized per instance
       from configuration. Its default matches the order ceiling's because a break is opened by an
       execution: in the worst case every admitted order mismatches and opens one. */
    private final AdmissionCounter admission;


    @Inject
    public SettlementExceptionStore(CapacityLimits limits) {
        this.admission = new AdmissionCounter(STRUCTURE, CAPACITY_KEY,
                limits.getMaxSettlementExceptions());
    }

    //Public rather than protected, and for two reasons: CDI generates the @ApplicationScoped
    //client proxy only from a non-private no-arg constructor, and the lifecycle unit tests build
    //the whole collaborator graph with new in a different package. A store built this way carries
    //the shipped defaults, which are the values microprofile-config.properties holds.
    public SettlementExceptionStore() {
        this(CapacityLimits.defaults());
    }

    //Formatted under Locale.ROOT rather than the JVM's default: %d follows the default formatting
    //locale, so under a non-Latin numbering system this id would come out in localized digits.
    //EXC-000001 is an ASCII contract - the seeded exception is addressed by that literal over REST,
    //quoted in the README and asserted by the tests - and must not vary by deployment.
    public String nextExceptionId() {
        return String.format(Locale.ROOT, "EXC-%06d", exceptionIdSequence.incrementAndGet());
    }

    //Read before admission rather than at the point of insertion: an exception is opened part-way
    //through a post-trade flow that has already moved the order, so the headroom has to be
    //established while the submission can still be refused whole. This is the gate that decides
    //which refusal a caller gets; the claim inside insert is the authority.
    public boolean hasCapacity() {
        return exceptions.size() < admission.ceiling();
    }

    //Creation only, never replacement, for the same reason as in OrderStore: an exception id is
    //minted once by nextExceptionId, so a key that is already taken means this call would swap out
    //a stored exception without passing through transition, the one path that asserts the edge is
    //legal and appends the audit event.
    //This call is also the moment an exception becomes reachable - the next caller may assign,
    //resolve and clear it - which is why PostTradeService reaches it only under the parent order's
    //OrderStore.inOrderLock and only once that order is already in EXCEPTION: an exception cleared
    //from under a parent that had not reached that state would move the order out of a state it
    //never occupied and file an audit event naming an origin that never happened.
    public void insert(SettlementException exception) {
        /* Claimed exactly, although the default relationship between the ceilings already implies
           it: exceptions are opened only by executions of admitted orders, at most one per order,
           so while SETTLEMENT_EXCEPTION_CAPACITY equals ORDER_CAPACITY the exact order claim alone
           keeps this map inside its ceiling. The check exists so that relationship is enforced
           rather than assumed - an operator may size the two independently, and a second path may
           one day open a break, and this map still cannot pass its own ceiling. */
        if (!tryAdmitException()) {
            throw new CapacityExceededException("the settlement-exception store is at capacity, so "
                    + "no further settlement exception can be opened; raise "
                    + "SETTLEMENT_EXCEPTION_CAPACITY or restart to clear");
        }

        if (exceptions.putIfAbsent(exception.getExceptionId(), exception) != null) {
            //The claim is handed back before the refusal, so a slot is never held by an exception
            //that was not stored. Reaching this is a defect in the caller rather than something a
            //client can provoke - the id comes from nextExceptionId - and the claim is balanced
            //here so that defect cannot also shrink the usable ceiling.
            releaseExceptionAdmission();
            throw new IllegalStateException(
                    "A settlement exception already exists with id " + exception.getExceptionId());
        }
    }

    //The exact atomic claim inside insert, and the one authority on the ceiling: hasCapacity above
    //is the gate that decides which refusal a caller gets.
    private boolean tryAdmitException() {
        return admission.tryAdmit();
    }

    private void releaseExceptionAdmission() {
        admission.release();
    }

    //The operator's steps - assert the transition is legal, build the replacement exception,
    //append the audit event - all run inside this one compute, so concurrent transitions on the
    //same exception are serialized by the map and an operator that refuses the transition throws
    //out of the remapping function, leaving the stored exception and the timeline as they were.
    //One call may carry more than one edge: resolving an OPEN exception with an owner both assigns
    //and resolves it, because the workflow has no direct OPEN -> RESOLVED edge, and both edges sit
    //in this one compute so no concurrent caller can interleave a different owner or note between
    //them.
    //An absent key yields null rather than a manufactured exception: only the caller knows what an
    //unknown id means to the request it is serving.
    public SettlementException transition(String exceptionId,
            UnaryOperator<SettlementException> operator) {
        return exceptions.compute(exceptionId,
                (key, current) -> (current == null) ? null : operator.apply(current));
    }

    public SettlementException find(String exceptionId) {
        return (exceptionId == null) ? null : exceptions.get(exceptionId);
    }

    //This store owns the order of a full read, and it owns it alone: the copy is taken and sorted
    //once here - the zero-padded id sorts naturally - and handed out unmodifiable, so a reader
    //filtering it keeps that order instead of sorting the survivors again.
    public List<SettlementException> list() {
        List<SettlementException> snapshot = new ArrayList<>(exceptions.values());
        snapshot.sort(Comparator.comparing(SettlementException::getExceptionId));
        return Collections.unmodifiableList(snapshot);
    }

    //Sorted before the page is cut, as in OrderStore: the order is the store's and not the page's,
    //so consecutive pages cannot overlap or skip an exception because the map iterated differently.
    public List<SettlementException> list(int offset, int limit) {
        List<SettlementException> snapshot = new ArrayList<>(exceptions.values());
        snapshot.sort(Comparator.comparing(SettlementException::getExceptionId));
        return page(snapshot, offset, limit);
    }

    public int count() {
        return exceptions.size();
    }

    //Read off the store rather than off configuration, which is what lets the health probes report
    //the ceiling and the headroom without reading a configuration source of their own.
    public int maxExceptions() {
        return admission.ceiling();
    }

    //Headroom in claims rather than in stored exceptions, for the same reason as in OrderStore: a
    //claim taken by an exception still being inserted is capacity this store will not grant twice.
    public int exceptionHeadroom() {
        return admission.headroom();
    }

    /* Both arguments are taken defensively rather than asserted: a page read is a GET, and a
       mis-typed query parameter must answer with the nearest page that exists instead of a 500.
       A non-positive limit means "do not cut the page" rather than "return nothing", which lets an
       in-process caller page without restating the REST layer's maximum page size. */
    private static List<SettlementException> page(List<SettlementException> ordered, int offset,
            int limit) {
        int from = Math.min(Math.max(offset, 0), ordered.size());
        int to = (limit <= 0) ? ordered.size()
                : (int) Math.min((long) from + limit, ordered.size());

        return Collections.unmodifiableList(new ArrayList<>(ordered.subList(from, to)));
    }
}
