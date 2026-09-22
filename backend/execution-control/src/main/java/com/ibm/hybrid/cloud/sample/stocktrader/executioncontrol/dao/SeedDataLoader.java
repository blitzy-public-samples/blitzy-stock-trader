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
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.OrderRequest;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.Position;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.RecordSource;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.SettlementInstruction;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.lifecycle.OrderLifecycleService;

//Arbitrary-precision arithmetic
import java.math.BigDecimal;

//Logging (JSR 47)
import java.util.logging.Logger;

//CDI 4.0
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;


/** Loads the fixed set of synthetic clients, positions and historical orders once at application start */
@ApplicationScoped
public class SeedDataLoader {
    private static Logger logger = Logger.getLogger(SeedDataLoader.class.getName());

    private static final String SEED_ACTOR = "seed";
    private static final String BUY = "BUY";

    private static final String NORTHWIND_ID = "INST-001";
    private static final String NORTHWIND_NAME = "Northwind Asset Management";
    private static final String NORTHWIND_CUSTODIAN_BIC = "SYNTGB2LXXX";
    private static final String NORTHWIND_SAFEKEEPING_ACCOUNT = "SAFE-NW-0001";
    private static final String NORTHWIND_CASH_ACCOUNT = "CASH-NW-0001";
    private static final String NORTHWIND_PLACE_OF_SETTLEMENT = "XLON";

    private static final String CONTOSO_ID = "INST-002";
    private static final String CONTOSO_NAME = "Contoso Pension Trust";
    private static final String CONTOSO_CUSTODIAN_BIC = "SYNTUS33XXX";
    private static final String CONTOSO_SAFEKEEPING_ACCOUNT = "SAFE-CP-0002";
    private static final String CONTOSO_CASH_ACCOUNT = "CASH-CP-0002";
    private static final String CONTOSO_PLACE_OF_SETTLEMENT = "XNYS";

    private static final String FABRIKAM_ID = "INST-003";
    private static final String FABRIKAM_NAME = "Fabrikam Capital Partners";
    private static final String FABRIKAM_CUSTODIAN_BIC = "SYNTDEFFXXX";
    private static final String FABRIKAM_FIRM_SAFEKEEPING_ACCOUNT = "SAFE-FB-0003";
    private static final String FABRIKAM_COUNTERPARTY_SAFEKEEPING_ACCOUNT = "SAFE-FB-9903";
    private static final String FABRIKAM_CASH_ACCOUNT = "CASH-FB-0003";
    private static final String FABRIKAM_PLACE_OF_SETTLEMENT = "XETR";

    private ReferenceDataStore referenceData;
    private OrderLifecycleService orderLifecycle;
    private volatile boolean loaded;


    @Inject
    public SeedDataLoader(ReferenceDataStore referenceData, OrderLifecycleService orderLifecycle) {
        this.referenceData = referenceData;
        this.orderLifecycle = orderLifecycle;
    }

    //Exists only so CDI can generate the @ApplicationScoped client proxy, which requires a
    //non-private no-arg constructor; no application code calls it and the proxy reads no field.
    protected SeedDataLoader() {
    }

    void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
        load();
        logger.info("Seed data loaded: " + referenceData.clientCount() + " synthetic clients, "
                + referenceData.positionCount() + " positions");
    }

    public void load() {
        loadReferenceData();
        submitHistoricalOrders();
        loaded = true;
    }

    public boolean isLoaded() {
        return loaded;
    }

    private void loadReferenceData() {
        referenceData.putClient(new ClientAccount(NORTHWIND_ID, NORTHWIND_NAME,
                new SettlementInstruction(NORTHWIND_CUSTODIAN_BIC,
                        NORTHWIND_SAFEKEEPING_ACCOUNT, NORTHWIND_CASH_ACCOUNT,
                        NORTHWIND_PLACE_OF_SETTLEMENT),
                new SettlementInstruction(NORTHWIND_CUSTODIAN_BIC,
                        NORTHWIND_SAFEKEEPING_ACCOUNT, NORTHWIND_CASH_ACCOUNT,
                        NORTHWIND_PLACE_OF_SETTLEMENT)));

        referenceData.putClient(new ClientAccount(CONTOSO_ID, CONTOSO_NAME,
                new SettlementInstruction(CONTOSO_CUSTODIAN_BIC,
                        CONTOSO_SAFEKEEPING_ACCOUNT, CONTOSO_CASH_ACCOUNT,
                        CONTOSO_PLACE_OF_SETTLEMENT),
                new SettlementInstruction(CONTOSO_CUSTODIAN_BIC,
                        CONTOSO_SAFEKEEPING_ACCOUNT, CONTOSO_CASH_ACCOUNT,
                        CONTOSO_PLACE_OF_SETTLEMENT)));

        referenceData.putClient(new ClientAccount(FABRIKAM_ID, FABRIKAM_NAME,
                new SettlementInstruction(FABRIKAM_CUSTODIAN_BIC,
                        FABRIKAM_FIRM_SAFEKEEPING_ACCOUNT, FABRIKAM_CASH_ACCOUNT,
                        FABRIKAM_PLACE_OF_SETTLEMENT),
                new SettlementInstruction(FABRIKAM_CUSTODIAN_BIC,
                        FABRIKAM_COUNTERPARTY_SAFEKEEPING_ACCOUNT, FABRIKAM_CASH_ACCOUNT,
                        FABRIKAM_PLACE_OF_SETTLEMENT)));

        referenceData.putPosition(new Position(NORTHWIND_ID, "SYNA", 10000L,
                new BigDecimal("100.00")));
        referenceData.putPosition(new Position(NORTHWIND_ID, "SYNB", 5000L,
                new BigDecimal("50.00")));
        referenceData.putPosition(new Position(CONTOSO_ID, "SYNC", 20000L,
                new BigDecimal("40.00")));
        //Worth 4,500,000 of the 5,000,000 resulting-position ceiling the service defaults to, so a
        //buy of more than 5000 shares at 100.00 crosses that ceiling and anything up to 5000 stays
        //under it: this holding is what makes the boundary reachable, so its quantity and price
        //are not free to change.
        referenceData.putPosition(new Position(CONTOSO_ID, "SYND", 45000L,
                new BigDecimal("100.00")));
        referenceData.putPosition(new Position(FABRIKAM_ID, "SYNA", 2000L,
                new BigDecimal("100.00")));
    }

    /* The three historical orders are submitted through the same service call a REST request
       enters, rather than written into the order and exception stores directly, so the seeded
       executions, the one seeded open exception and every seeded audit event are produced by the
       code path that serves live requests and carry its timestamps. The reference data above is
       written into its store directly instead, because it has no lifecycle to run and therefore
       no state transition to record. */
    private void submitHistoricalOrders() {
        OrderRequest executedForNorthwind =
                seedRequest("SEED-001", NORTHWIND_ID, "SYNA", BUY, 100L, "100.00");
        OrderRequest restrictedForContoso =
                seedRequest("SEED-002", CONTOSO_ID, "RSTRA", BUY, 10L, "10.00");
        OrderRequest mismatchingForFabrikam =
                seedRequest("SEED-003", FABRIKAM_ID, "SYNA", BUY, 500L, "100.00");

        orderLifecycle.submit(executedForNorthwind, SEED_ACTOR, RecordSource.SEED);
        orderLifecycle.submit(restrictedForContoso, SEED_ACTOR, RecordSource.SEED);
        orderLifecycle.submit(mismatchingForFabrikam, SEED_ACTOR, RecordSource.SEED);
    }

    private static OrderRequest seedRequest(String clientOrderId, String clientId, String symbol,
            String side, long quantity, String limitPrice) {
        return new OrderRequest(clientOrderId, clientId, symbol, side, quantity,
                new BigDecimal(limitPrice));
    }
}
