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

/** One side's synthetic Standing Settlement Instruction */
public class SettlementInstruction {
    /* These four names are published, not internal: PostTradeService compares the firm and
       counterparty instructions field by field in declaration order and writes the name of
       the differing field into MismatchField.field, so renaming or re-casing one here
       silently changes the exception body an analyst works from. Values are stored exactly
       as seeded, with no trimming or upper-casing, because a mismatch is a difference
       between two literal instructions and normalizing could hide one. */
    private final String custodianBic;
    private final String safekeepingAccount;
    private final String cashAccount;
    private final String placeOfSettlement;
    private final boolean synthetic;
    private final boolean simulated;
    private final String disclaimer;

    public SettlementInstruction(String custodianBic, String safekeepingAccount,
            String cashAccount, String placeOfSettlement) {
        this.custodianBic = custodianBic;
        this.safekeepingAccount = safekeepingAccount;
        this.cashAccount = cashAccount;
        this.placeOfSettlement = placeOfSettlement;
        this.synthetic = true;
        this.simulated = true;
        this.disclaimer = SimulationLabels.DISCLAIMER;
    }

    public String getCustodianBic() {
        return custodianBic;
    }

    public String getSafekeepingAccount() {
        return safekeepingAccount;
    }

    public String getCashAccount() {
        return cashAccount;
    }

    public String getPlaceOfSettlement() {
        return placeOfSettlement;
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
