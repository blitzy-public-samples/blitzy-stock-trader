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

import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.AssignRequest;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.AuditEvent;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.ExceptionStatus;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.ResolveRequest;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.SettlementException;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.lifecycle.PostTradeService;

//Collections
import java.util.List;

//CDI 4.0
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

//Jakarta REST 3.1
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;


//No role annotation here: web.xml is the estate's enforcement point, granting GET to both
//StockViewer and StockTrader and every mutating verb to StockTrader alone, so the analyst
//working an exception is already authorized by the time any method below is entered.
@Path("/exceptions")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
/** Working surface for the simulated settlement-exception workflow: list, read, assign, resolve and mark settlement-ready. */
public class SettlementExceptionResource {
	@Inject private PostTradeService postTradeService;

	@GET
	public List<SettlementException> getExceptions(@QueryParam("status") ExceptionStatus status, @QueryParam("owner") String owner) {
		return postTradeService.list(status, owner);
	}

	@GET
	@Path("/{exceptionId}")
	public SettlementException getException(@PathParam("exceptionId") String exceptionId) {
		return postTradeService.get(exceptionId);
	}

	@GET
	@Path("/{exceptionId}/events")
	public List<AuditEvent> getExceptionEvents(@PathParam("exceptionId") String exceptionId) {
		return postTradeService.events(exceptionId);
	}

	@PUT
	@Path("/{exceptionId}/assign")
	@Consumes(MediaType.APPLICATION_JSON)
	public SettlementException assignException(@PathParam("exceptionId") String exceptionId, AssignRequest assignRequest, @Context SecurityContext securityContext) {
		//The service is declared over the owner rather than over the request, so the body has to be
		//dereferenced here; reading it null-safely is what lets a PUT sent with no entity at all
		//still answer with the service's 400 "owner is required" instead of failing as a 500.
		return postTradeService.assign(exceptionId, assignRequest == null ? null : assignRequest.getOwner(),
				securityContext.getUserPrincipal().getName());
	}

	@PUT
	@Path("/{exceptionId}/resolve")
	@Consumes(MediaType.APPLICATION_JSON)
	public SettlementException resolveException(@PathParam("exceptionId") String exceptionId, ResolveRequest resolveRequest, @Context SecurityContext securityContext) {
		return postTradeService.resolve(exceptionId, resolveRequest, securityContext.getUserPrincipal().getName());
	}

	@PUT
	@Path("/{exceptionId}/settlement-ready")
	public SettlementException markSettlementReady(@PathParam("exceptionId") String exceptionId, @Context SecurityContext securityContext) {
		return postTradeService.markSettlementReady(exceptionId, securityContext.getUserPrincipal().getName());
	}
}
