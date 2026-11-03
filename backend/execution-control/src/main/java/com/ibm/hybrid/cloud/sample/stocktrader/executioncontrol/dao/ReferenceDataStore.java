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

import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.ClientAccount;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.ControlResult;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.Position;
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
import java.util.Objects;

//Concurrency
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;

//CDI 4.0
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;


/** In-memory store of the synthetic institutional clients and their positions */
@ApplicationScoped
public class ReferenceDataStore {
    private static final String KEY_SEPARATOR = "|";

    //The label and the configuration key the admission counter reports this structure under.
    private static final String STRUCTURE = "position";
    private static final String CAPACITY_KEY = "POSITION_CAPACITY";

    //Ordered on the way out because ConcurrentHashMap iteration order is arbitrary, and
    //GET /positions is read entry by entry by its consumers.
    private static final Comparator<Position> BY_CLIENT_THEN_SYMBOL =
            Comparator.comparing(Position::getClientId).thenComparing(Position::getSymbol);

    //A fixed stripe count, not one lock per holding: the table then cannot grow with the number of
    //positions, and two holdings that happen to share a stripe contend only for the few
    //microseconds one admission and fill hold it. Matches OrderStore's stripe count.
    private static final int POSITION_LOCK_STRIPES = 64;

    private final ConcurrentHashMap<String, ClientAccount> clients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Position> positions = new ConcurrentHashMap<>();

    private final ReentrantLock[] positionLocks = createPositionLocks();

    /* The ceiling on distinct client-and-symbol holdings, sized per instance from configuration.
       Its default is lower than the order ceiling's because a position is a key, not a record per
       order: only a client's first fill in a symbol adds one, and every later fill in that symbol
       rewrites it in place. */
    private final AdmissionCounter admission;


    @Inject
    public ReferenceDataStore(CapacityLimits limits) {
        this.admission = new AdmissionCounter(STRUCTURE, CAPACITY_KEY, limits.getMaxPositions());
    }

    //Public rather than protected, and for two reasons: CDI generates the @ApplicationScoped
    //client proxy only from a non-private no-arg constructor, and the lifecycle unit tests build
    //the whole collaborator graph with new in a different package. A store built this way carries
    //the shipped defaults, which are the values microprofile-config.properties holds.
    public ReferenceDataStore() {
        this(CapacityLimits.defaults());
    }

    public void putClient(ClientAccount client) {
        clients.put(client.getClientId(), client);
    }

    /* Written inside a compute so the claim decision and the write are one step: a plain put
       could not tell a creation from a replacement without a preceding read, and between that
       read and the put another thread could create the very key this call then counted twice.
       Only a creation claims - a replacement rewrites an entry that already holds its slot. */
    public void putPosition(Position position) {
        String clientId = position.getClientId();
        String symbol = position.getSymbol();

        positions.compute(positionKey(clientId, symbol), (key, current) -> {
            if (current == null && !tryAdmitPosition()) {
                throw new CapacityExceededException("the position store is at capacity, so no new "
                        + symbol + " position can be opened for clientId " + clientId
                        + "; raise POSITION_CAPACITY or restart to clear");
            }
            return position;
        });
    }

    /* Evaluating the controls and applying the fill are deliberately one atomic step per client
       and symbol: a second order has to see the first order's fill, or two orders that each pass
       the resulting-position limit on their own could settle into a position that breaches it.
       Answering with the current value is how a rejected order leaves the position exactly as it
       was - and leaves an absent one absent, so a rejection never creates a position either. */
    public FillDecision evaluateAndFill(String clientId, String symbol,
            Function<Position, FillDecision> operator) {
        FillDecision[] decided = new FillDecision[1];

        //The current position reaches the operator as it is, null included: an absent position is
        //this client's first order in the symbol, which the controls value at quantity zero rather
        //than refuse, and which the operator may turn into a brand-new position.
        positions.compute(positionKey(clientId, symbol), (key, current) -> {
            /* An absent key is about to be created, so the slot is claimed before the operator is
               given the chance to create it, and the refusal is thrown from inside this remapping
               function: compute then leaves the map exactly as it was, so a submission refused at
               the ceiling creates nothing at all. */
            boolean claimed = false;
            if (current == null) {
                if (!tryAdmitPosition()) {
                    //Named as in every other refusal: the message is the 503 body a caller reads,
                    //and the ceiling is operator-sized, so it carries the remedy too.
                    throw new CapacityExceededException("the position store is at capacity, so no "
                            + "new " + symbol + " position can be opened for clientId " + clientId
                            + "; raise POSITION_CAPACITY or restart to clear");
                }
                claimed = true;
            }

            boolean created = false;
            try {
                FillDecision decision = operator.apply(current);
                decided[0] = decision;
                created = decision.isFilled();
                return created ? decision.getReplacement() : current;
            } finally {
                //Handed back whenever no position came of the claim - the controls refused the
                //order, or the operator itself refused it - because a slot held by a position that
                //does not exist would retire one of the ceiling for the life of the process.
                if (claimed && !created) {
                    releasePositionAdmission();
                }
            }
        });

        return decided[0];
    }

    /* evaluateAndFill is atomic for one holding, which is all the fill itself needs. A submission
       needs more than that: it has to decide, before it stores anything, whether the share count
       this order would leave behind can be represented at all, and that decision is only sound if
       no other fill of the same holding can land between the decision and the fill it admits.
       This is the primitive that makes the pair indivisible per holding - a caller runs its whole
       sequence in here - and, like OrderStore.inOrderLock, it is taken before any compute and
       never from inside one, so the single lock order (this lock, then a map's own) admits no
       cycle. Striped on the canonical key, so two holdings contend only when they share a stripe. */
    public <T> T inPositionLock(String clientId, String symbol, Supplier<T> work) {
        //floorMod rather than %: a negative hash gives a negative remainder and so an index
        //outside the array, while floorMod always lands inside the stripe range.
        ReentrantLock lock = positionLocks[Math.floorMod(
                Objects.hashCode(positionKey(clientId, symbol)), POSITION_LOCK_STRIPES)];
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    private static ReentrantLock[] createPositionLocks() {
        ReentrantLock[] locks = new ReentrantLock[POSITION_LOCK_STRIPES];
        for (int stripe = 0; stripe < locks.length; stripe++) {
            locks[stripe] = new ReentrantLock();
        }
        return locks;
    }

    //The exact atomic claim taken inside the compute that would create the key, and the one
    //authority on the ceiling: hasPositionCapacityFor below is the gate that decides which refusal
    //a caller gets.
    private boolean tryAdmitPosition() {
        return admission.tryAdmit();
    }

    private void releasePositionAdmission() {
        admission.release();
    }

    /* Answered per key rather than on the map's size alone: a fill into a holding this client
       already has rewrites that entry and adds no key, so an order in an existing position must
       stay admissible at the ceiling - otherwise a full position map would freeze trading in the
       very symbols it already holds. Only a first fill in a new client-and-symbol pair needs
       headroom. This is the gate, asked before the submission has moved anything so the whole
       flow can be declined at once; the claim inside evaluateAndFill is the authority, and it is
       what stays exact when two first fills in distinct symbols pass this gate together. */
    public boolean hasPositionCapacityFor(String clientId, String symbol) {
        if (clientId == null || symbol == null) {
            return positions.size() < admission.ceiling();
        }

        return positions.containsKey(positionKey(clientId, symbol))
                || positions.size() < admission.ceiling();
    }

    public ClientAccount findClient(String clientId) {
        return clients.get(clientId);
    }

    public List<ClientAccount> listClients() {
        List<ClientAccount> snapshot = new ArrayList<>(clients.values());
        snapshot.sort(Comparator.comparing(ClientAccount::getClientId));
        return Collections.unmodifiableList(snapshot);
    }

    public Position findPosition(String clientId, String symbol) {
        return positions.get(positionKey(clientId, symbol));
    }

    public List<Position> listPositions() {
        List<Position> snapshot = new ArrayList<>(positions.values());
        snapshot.sort(BY_CLIENT_THEN_SYMBOL);
        return Collections.unmodifiableList(snapshot);
    }

    //Sorted before the page is cut: ConcurrentHashMap iteration order is arbitrary, so a page
    //taken from an unsorted copy could repeat or skip a holding between consecutive reads.
    public List<Position> listPositions(int offset, int limit) {
        List<Position> snapshot = new ArrayList<>(positions.values());
        snapshot.sort(BY_CLIENT_THEN_SYMBOL);
        return page(snapshot, offset, limit);
    }

    public int clientCount() {
        return clients.size();
    }

    public int positionCount() {
        return positions.size();
    }

    //Read off the store rather than off configuration, which is what lets the health probes report
    //the ceiling and the headroom without reading a configuration source of their own.
    public int maxPositions() {
        return admission.ceiling();
    }

    /* Headroom in claims rather than in stored keys, as in the other two stores: a claim taken
       inside a fill still in flight is capacity this store will not grant twice. It bounds only
       first fills in new holdings - an order in a holding the client already has needs none. */
    public int positionHeadroom() {
        return admission.headroom();
    }

    //strip rather than trim, matching the canonicalization the lifecycle applies before it gets
    //here: trim stops at U+0020, so the two would disagree on a symbol padded with Unicode
    //whitespace and one holding could end up reachable under two different keys.
    private static String positionKey(String clientId, String symbol) {
        return clientId + KEY_SEPARATOR + symbol.strip().toUpperCase(Locale.ROOT);
    }

    /* Both arguments are taken defensively rather than asserted: a page read is a GET, and a
       mis-typed query parameter must answer with the nearest page that exists instead of a 500.
       A non-positive limit means "do not cut the page" rather than "return nothing", which lets an
       in-process caller page without restating the REST layer's maximum page size. */
    private static List<Position> page(List<Position> ordered, int offset, int limit) {
        int from = Math.min(Math.max(offset, 0), ordered.size());
        int to = (limit <= 0) ? ordered.size()
                : (int) Math.min((long) from + limit, ordered.size());

        return Collections.unmodifiableList(new ArrayList<>(ordered.subList(from, to)));
    }


    /** One evaluate-and-fill outcome: the control results, plus the replacement position when the fill applied */
    public static final class FillDecision {
        private final Position replacement;
        private final List<ControlResult> controlResults;

        private FillDecision(Position replacement, List<ControlResult> controlResults) {
            this.replacement = replacement;
            this.controlResults = Collections.unmodifiableList(new ArrayList<>(controlResults));
        }

        public static FillDecision filled(Position replacement, List<ControlResult> controlResults) {
            return new FillDecision(Objects.requireNonNull(replacement,
                    "a filled decision requires a replacement position"), controlResults);
        }

        public static FillDecision unchanged(List<ControlResult> controlResults) {
            return new FillDecision(null, controlResults);
        }

        public boolean isFilled() {
            return replacement != null;
        }

        public Position getReplacement() {
            return replacement;
        }

        public List<ControlResult> getControlResults() {
            return controlResults;
        }
    }
}
