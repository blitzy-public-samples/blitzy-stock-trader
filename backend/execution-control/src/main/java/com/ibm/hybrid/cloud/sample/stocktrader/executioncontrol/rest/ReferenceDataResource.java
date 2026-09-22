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
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;


//No role annotation here: GET is granted to both StockViewer and StockTrader declaratively in
//web.xml, which is the estate's enforcement point. Authorization and dispatch are two stages:
//POST, PUT and DELETE are covered there for StockTrader on every path, so an attempt at one clears
//security and is then answered 405 by this GET-only resource, while HEAD, OPTIONS, PATCH and TRACE
//are covered nowhere and get 403 from deny-uncovered-http-methods.
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

	//Unpaged deliberately: the client records are the three written by the startup seed and no
	//code path adds a fourth, so this response cannot grow.
	@GET
	@Path("clients")
	public List<ClientAccount> getClients() {
		return referenceDataStore.listClients();
	}

	@GET
	@Path("positions")
	public List<Position> getPositions(@QueryParam("offset") @DefaultValue("0") int offset, @QueryParam("limit") @DefaultValue("0") int limit) {
		//Positions grow with every first fill in a new client-and-symbol pair, so this response is
		//one page of the holdings rather than all of them.
		PageBounds bounds = PageBounds.clamp(offset, limit);

		return referenceDataStore.listPositions(bounds.getOffset(), bounds.getLimit());
	}
}
