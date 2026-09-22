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

//Collections
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

//Concurrency
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

//CDI 4.0
import jakarta.enterprise.context.ApplicationScoped;


/** In-memory store of the synthetic institutional clients and their positions */
@ApplicationScoped
public class ReferenceDataStore {
    private static final String KEY_SEPARATOR = "|";

    //Ordered on the way out because ConcurrentHashMap iteration order is arbitrary, and
    //GET /positions is read entry by entry by its consumers.
    private static final Comparator<Position> BY_CLIENT_THEN_SYMBOL =
            Comparator.comparing(Position::getClientId).thenComparing(Position::getSymbol);

    private final ConcurrentHashMap<String, ClientAccount> clients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Position> positions = new ConcurrentHashMap<>();


    public void putClient(ClientAccount client) {
        clients.put(client.getClientId(), client);
    }

    public void putPosition(Position position) {
        positions.put(positionKey(position.getClientId(), position.getSymbol()), position);
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
            FillDecision decision = operator.apply(current);
            decided[0] = decision;
            return decision.isFilled() ? decision.getReplacement() : current;
        });

        return decided[0];
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

    public int clientCount() {
        return clients.size();
    }

    public int positionCount() {
        return positions.size();
    }

    private static String positionKey(String clientId, String symbol) {
        return clientId + KEY_SEPARATOR + symbol.trim().toUpperCase(Locale.ROOT);
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
