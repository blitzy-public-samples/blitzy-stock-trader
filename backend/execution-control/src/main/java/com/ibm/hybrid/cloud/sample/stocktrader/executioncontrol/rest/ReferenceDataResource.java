/*
       Copyright 2020-2021 IBM Corp, All Rights Reserved
       Copyright 2022-2025 Kyndryl, All Rights Reserved

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

package com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.rest;

import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.control.ControlLimits;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.dao.ReferenceDataStore;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.ClientAccount;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.ControlLimitsView;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.Position;

//Collections
import java.util.ArrayList;
import java.util.List;

//CDI 4.0
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

//Jakarta REST 3.1
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;


//No role annotation here: GET is granted to both StockViewer and StockTrader declaratively in
//web.xml, which is the estate's enforcement point, and every other verb is refused there by
//deny-uncovered-http-methods - so a read is all this class can ever be reached for.
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
/** Read view over the effective pre-trade controls and the seeded synthetic clients and positions. */
public class ReferenceDataResource {
	@Inject private ControlLimits controlLimits;
	@Inject private ReferenceDataStore referenceDataStore;

	@GET
	@Path("controls")
	public ControlLimitsView getControls() {
		//The limits reported here are the ones being enforced: ControlLimitsProducer resolves them
		//from configuration once per start-up and no record of them is ever stored, which is why the
		//view labels itself CONFIG instead of carrying a SEED/API origin - and why reading them back
		//from the produced ControlLimits, rather than from a store, is what makes them effective.
		return new ControlLimitsView(controlLimits.getMaxOrderNotional(),
				controlLimits.getMaxPositionNotional(),
				controlLimits.getFatFingerNotionalThreshold(),
				new ArrayList<>(controlLimits.getRestrictedSymbols()),
				controlLimits.getExceptionSlaHours());
	}

	@GET
	@Path("clients")
	public List<ClientAccount> getClients() {
		return referenceDataStore.listClients();
	}

	@GET
	@Path("positions")
	public List<Position> getPositions() {
		return referenceDataStore.listPositions();
	}
}
