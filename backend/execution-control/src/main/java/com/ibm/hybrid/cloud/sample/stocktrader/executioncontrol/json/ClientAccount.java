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

package com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json;

/** A synthetic institutional client and its two settlement instructions */
public class ClientAccount {
    private final String clientId;
    private final String clientName;
    /* Both sides of the settlement instruction hang off the client because comparing them is what
       produces a settlement exception: on every execution PostTradeService compares all four fields
       of the two instructions and, when one or more of them differ, opens a single SSI_MISMATCH
       carrying every field that did. Seeded INST-003 therefore carries a counterparty
       safekeepingAccount deliberately unequal to the firm's, which makes the flow reproducible. */
    private final SettlementInstruction firmSettlementInstruction;
    private final SettlementInstruction counterpartySettlementInstruction;
    private final boolean synthetic;
    private final boolean simulated;
    private final String disclaimer;

    public ClientAccount(String clientId, String clientName,
            SettlementInstruction firmSettlementInstruction,
            SettlementInstruction counterpartySettlementInstruction) {
        this.clientId = clientId;
        this.clientName = clientName;
        this.firmSettlementInstruction = firmSettlementInstruction;
        this.counterpartySettlementInstruction = counterpartySettlementInstruction;
        this.synthetic = true;
        this.simulated = true;
        this.disclaimer = SimulationLabels.DISCLAIMER;
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientName() {
        return clientName;
    }

    public SettlementInstruction getFirmSettlementInstruction() {
        return firmSettlementInstruction;
    }

    public SettlementInstruction getCounterpartySettlementInstruction() {
        return counterpartySettlementInstruction;
    }

    public boolean isSynthetic() {
        return synthetic;
    }

    public boolean isSimulated() {
        return simulated;
    }

    public String getDisclaimer() {
        return disclaimer;
    }
}
