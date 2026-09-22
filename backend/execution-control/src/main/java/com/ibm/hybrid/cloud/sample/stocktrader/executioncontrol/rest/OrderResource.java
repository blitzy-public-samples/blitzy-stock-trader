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

import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.AuditEvent;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.Order;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.OrderRequest;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.lifecycle.OrderLifecycleService;

//Collections
import java.util.List;

//CDI 4.0
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

//Jakarta REST 3.1
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;


//No role annotation here: web.xml is the estate's enforcement point, granting GET to both
//StockViewer and StockTrader and POST to StockTrader alone, so the submitting trader is already
//authorized on entry. Authorization and dispatch are two stages: PUT and DELETE are covered for
//StockTrader on every path, so they clear security and are then answered 405 here, while HEAD,
//OPTIONS, PATCH and TRACE are covered nowhere and get 403 from deny-uncovered-http-methods.
/** Submit surface for simulated institutional orders, plus the read views over one order and its audit timeline. */
@Path("/orders")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class OrderResource {
	@Inject private OrderLifecycleService orderLifecycleService;

	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	public Response submitOrder(OrderRequest orderRequest, @Context SecurityContext securityContext) {
		Order order = orderLifecycleService.submit(orderRequest, securityContext.getUserPrincipal().getName());

		//201 answers a REJECTED order exactly as it answers an EXECUTED one: the pre-trade verdict
		//is recorded on an order that now exists, is retrievable at its own URL and carries its own
		//audit timeline, so the creation succeeded. Reporting a control rejection as a 4xx would
		//tell the caller its request was at fault and would disown the auditable record it made.
		return Response.status(Response.Status.CREATED).entity(order).build();
	}

	@GET
	public List<Order> getOrders(@QueryParam("offset") @DefaultValue("0") int offset, @QueryParam("limit") @DefaultValue("0") int limit) {
		//The order estate grows for the life of the process, so this response is one page of it and
		//never the whole of it: PageBounds turns an absent, zero, negative or oversized parameter
		//into the nearest page that exists, which is what keeps this bound off the caller's contract.
		PageBounds bounds = PageBounds.clamp(offset, limit);

		return orderLifecycleService.list(bounds.getOffset(), bounds.getLimit());
	}

	@GET
	@Path("/{orderId}")
	public Order getOrder(@PathParam("orderId") String orderId) {
		return orderLifecycleService.get(orderId);
	}

	//Unpaged deliberately: an order can never carry more than six events, because neither the
	//ORDER nor the POST_TRADE transition table has a self-edge, so this response is bounded by the
	//state machines themselves rather than by a page size.
	@GET
	@Path("/{orderId}/events")
	public List<AuditEvent> getOrderEvents(@PathParam("orderId") String orderId) {
		return orderLifecycleService.events(orderId);
	}
}
