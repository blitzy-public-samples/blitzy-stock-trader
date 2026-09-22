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
import java.util.Locale;

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

    //Formatted under Locale.ROOT rather than the JVM's default: %d follows the default formatting
    //locale, so under a non-Latin numbering system this id would come out in localized digits.
    //EXC-000001 is an ASCII contract - the seeded exception is addressed by that literal over REST,
    //quoted in the README and asserted by the tests - and must not vary by deployment.
    public String nextExceptionId() {
        return String.format(Locale.ROOT, "EXC-%06d", exceptionIdSequence.incrementAndGet());
    }

    //Creation only, never replacement, for the same reason as in OrderStore: an exception id is
    //minted once by nextExceptionId, so a key that is already taken means this call would swap out
    //a stored exception without passing through transition, the one path that asserts the edge is
    //legal and appends the audit event.
    public void insert(SettlementException exception) {
        if (exceptions.putIfAbsent(exception.getExceptionId(), exception) != null) {
            throw new IllegalStateException(
                    "A settlement exception already exists with id " + exception.getExceptionId());
        }
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

    public int count() {
        return exceptions.size();
    }
}
