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
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.ErrorResponse;
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
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

//mpOpenAPI 4.1
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;


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

	/* The declared statuses are the contract this service publishes at /openapi, and they are
	   declared because mpOpenAPI cannot infer them: a Response-returning method tells it nothing
	   about the entity, and the error bodies are produced by @Provider ExceptionMappers it never
	   sees. Undeclared, the generated contract offered a single 200 per operation and no error
	   model at all, so a client generated from it treated the real 201 as unexpected and had no
	   type for the {status, error, message, path} body every refusal carries. */
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@APIResponse(responseCode = "201", description = "The order was recorded in its terminal state, EXECUTED or REJECTED, with all four control results",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Order.class)),
			headers = @Header(name = "Location", description = "Absolute path of the created order - /execution-control/orders/{orderId}, carrying no scheme and no host, so a client resolves it against the URL it sent the request to", schema = @Schema(type = SchemaType.STRING)))
	@APIResponse(responseCode = "400", description = "A field is missing or outside its bounds, the clientId is unknown, or the body could not be read as JSON",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
	@APIResponse(responseCode = "409", description = "The clientOrderId has already been submitted",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
	@APIResponse(responseCode = "415", description = "The request declared a Content-Type other than application/json",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
	@APIResponse(responseCode = "503", description = "An in-memory ceiling is exhausted, so the order cannot be admitted",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
	public Response submitOrder(OrderRequest orderRequest, @Context SecurityContext securityContext,
			@Context UriInfo uriInfo) {
		Order order = orderLifecycleService.submit(orderRequest, securityContext.getUserPrincipal().getName());

		//201 answers a REJECTED order exactly as it answers an EXECUTED one: the pre-trade verdict
		//is recorded on an order that now exists, is retrievable at its own URL and carries its own
		//audit timeline, so the creation succeeded. Reporting a control rejection as a 4xx would
		//tell the caller its request was at fault and would disown the auditable record it made.
		//Location carries that resource's path, so a caller learns where the record lives from the
		//response itself rather than by reassembling the path around an id parsed out of the body.

		/* The path alone, never the scheme and authority the request arrived with: those are read
		   from the caller's own Host and X-Forwarded-Proto headers, so an absolute Location would
		   echo whatever address a caller chose back as this service's own and take a client that
		   follows it somewhere else entirely. The path still comes from the builder rather than
		   being assembled here, so it survives a change of context root, and the header is set
		   directly because Response.created and ResponseBuilder.location resolve a relative URI
		   against the application base URI - rebuilding the authority this removes. */
		String createdOrderPath = uriInfo.getAbsolutePathBuilder()
				.path(order.getOrderId()).build().getRawPath();

		return Response.status(Response.Status.CREATED)
				.header(HttpHeaders.LOCATION, createdOrderPath)
				.entity(order).build();
	}

	@GET
	@APIResponse(responseCode = "200", description = "One page of orders, seeded and live, in order id order",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(type = SchemaType.ARRAY, implementation = Order.class)),
			headers = {
				@Header(name = PageBounds.TOTAL_COUNT_HEADER, description = "Orders in the whole collection this page was cut from", schema = @Schema(type = SchemaType.INTEGER)),
				@Header(name = PageBounds.PAGE_OFFSET_HEADER, description = "Offset this page starts at", schema = @Schema(type = SchemaType.INTEGER)),
				@Header(name = PageBounds.PAGE_LIMIT_HEADER, description = "Page size actually applied, after clamping", schema = @Schema(type = SchemaType.INTEGER)),
				@Header(name = PageBounds.LINK_HEADER, description = "RFC 8288 first, prev, next and last page links; next is absent on the final page", schema = @Schema(type = SchemaType.STRING))
			})
	public Response getOrders(
			@Parameter(description = "Records to skip. Clamped: a negative, malformed or absent value reads as 0, and a value past the end returns an empty page",
					schema = @Schema(type = SchemaType.INTEGER, defaultValue = "0"))
			@QueryParam("offset") String offset,
			@Parameter(description = "Page size. Clamped: absent, zero, negative, malformed or above 500 reads as 500",
					schema = @Schema(type = SchemaType.INTEGER, defaultValue = "500"))
			@QueryParam("limit") String limit,
			@Context UriInfo uriInfo) {
		//The order estate grows for the life of the process, so this response is one page of it and
		//never the whole of it: PageBounds turns an absent, zero, negative, oversized or malformed
		//parameter into the nearest page that exists, which is what keeps this bound off the
		//caller's contract, and the page reports the total it was cut from so a caller can tell a
		//collection that ends here from one that was truncated.
		PageBounds bounds = PageBounds.clamp(offset, limit);

		return bounds.pagedResponse(orderLifecycleService.list(bounds.getOffset(), bounds.getLimit()),
				orderLifecycleService.count(), uriInfo);
	}

	@GET
	@Path("/{orderId}")
	@APIResponse(responseCode = "200", description = "The order",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Order.class)))
	@APIResponse(responseCode = "404", description = "No order exists with that id",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
	public Order getOrder(@PathParam("orderId") String orderId) {
		return orderLifecycleService.get(orderId);
	}

	//Unpaged deliberately: an order can never carry more than six events, because neither the
	//ORDER nor the POST_TRADE transition table has a self-edge, so this response is bounded by the
	//state machines themselves rather than by a page size.
	@GET
	@Path("/{orderId}/events")
	@APIResponse(responseCode = "200", description = "That order's ORDER and POST_TRADE audit events, in sequence order",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(type = SchemaType.ARRAY, implementation = AuditEvent.class)))
	@APIResponse(responseCode = "404", description = "No order exists with that id",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
	public List<AuditEvent> getOrderEvents(@PathParam("orderId") String orderId) {
		return orderLifecycleService.events(orderId);
	}
}
