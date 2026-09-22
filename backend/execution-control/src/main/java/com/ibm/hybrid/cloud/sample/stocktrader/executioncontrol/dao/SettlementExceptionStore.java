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


/** In-memory store of every simulated settlement exception, keyed by exception id */
@ApplicationScoped
public class SettlementExceptionStore {

    private final ConcurrentHashMap<String, SettlementException> exceptions = new ConcurrentHashMap<>();
    private final AtomicLong exceptionIdSequence = new AtomicLong();

    public String nextExceptionId() {
        return String.format("EXC-%06d", exceptionIdSequence.incrementAndGet());
    }

    public void insert(SettlementException exception) {
        exceptions.put(exception.getExceptionId(), exception);
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

    //Scanned in id order so the answer is the exception opened first for the order, rather than
    //whichever one the map's arbitrary iteration order happened to reach first.
    public SettlementException findByOrderId(String orderId) {
        if (orderId == null) {
            return null;
        }

        for (SettlementException exception : list()) {
            if (orderId.equals(exception.getOrderId())) {
                return exception;
            }
        }

        return null;
    }

    public List<SettlementException> list() {
        List<SettlementException> snapshot = new ArrayList<>(exceptions.values());
        snapshot.sort(Comparator.comparing(SettlementException::getExceptionId));
        return Collections.unmodifiableList(snapshot);
    }

    public int count() {
        return exceptions.size();
    }
}
