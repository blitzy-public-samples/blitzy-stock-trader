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
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.ErrorResponse;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.ExceptionStatus;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.ResolveRequest;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.SettlementException;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.lifecycle.PostTradeService;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.lifecycle.ValidationException;

//Collections
import java.util.List;
import java.util.Locale;

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
//StockViewer and StockTrader and every mutating verb to StockTrader alone, so the analyst
//working an exception is already authorized by the time any method below is entered.
/** Working surface for the simulated settlement-exception workflow: list, read, assign, resolve and mark settlement-ready. */
@Path("/exceptions")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class SettlementExceptionResource {

	private static final String STATUS_VALUES = "OPEN, ASSIGNED, RESOLVED, SETTLEMENT_READY";

	@Inject private PostTradeService postTradeService;

	@GET
	@APIResponse(responseCode = "200", description = "One page of settlement exceptions, in exception id order, narrowed by any status and owner supplied",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(type = SchemaType.ARRAY, implementation = SettlementException.class)),
			headers = {
				@Header(name = PageBounds.TOTAL_COUNT_HEADER, description = "Exceptions matching the filter, across the whole collection", schema = @Schema(type = SchemaType.INTEGER)),
				@Header(name = PageBounds.PAGE_OFFSET_HEADER, description = "Offset this page starts at", schema = @Schema(type = SchemaType.INTEGER)),
				@Header(name = PageBounds.PAGE_LIMIT_HEADER, description = "Page size actually applied, after clamping", schema = @Schema(type = SchemaType.INTEGER)),
				@Header(name = PageBounds.LINK_HEADER, description = "RFC 8288 first, prev, next and last page links, each carrying the same filter", schema = @Schema(type = SchemaType.STRING))
			})
	@APIResponse(responseCode = "400", description = "The status filter is not one of the four workflow states",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
	public Response getExceptions(
			@Parameter(description = "Workflow state to narrow to. Stripped and upper-cased before matching; blank means no filter; any other value is refused with 400",
					schema = @Schema(type = SchemaType.STRING, enumeration = {"OPEN", "ASSIGNED", "RESOLVED", "SETTLEMENT_READY"}))
			@QueryParam("status") String status,
			@Parameter(description = "Assigned owner to narrow to. Blank means no filter", schema = @Schema(type = SchemaType.STRING))
			@QueryParam("owner") String owner,
			@Parameter(description = "Records to skip. Clamped: a negative, malformed or absent value reads as 0, and a value past the end returns an empty page",
					schema = @Schema(type = SchemaType.INTEGER, defaultValue = "0"))
			@QueryParam("offset") String offset,
			@Parameter(description = "Page size. Clamped: absent, zero, negative, malformed or above 500 reads as 500",
					schema = @Schema(type = SchemaType.INTEGER, defaultValue = "500"))
			@QueryParam("limit") String limit,
			@Context UriInfo uriInfo) {
		ExceptionStatus statusFilter = statusFilter(status);
		//The page is cut from the filtered set, so a status or owner query answers with a page of its
		//own matches rather than with whatever survived a page of the whole store, and the total
		//reported alongside it counts that same filtered set.
		PageBounds bounds = PageBounds.clamp(offset, limit);

		return bounds.pagedResponse(
				postTradeService.list(statusFilter, owner, bounds.getOffset(), bounds.getLimit()),
				postTradeService.count(statusFilter, owner), uriInfo);
	}

	@GET
	@Path("/{exceptionId}")
	@APIResponse(responseCode = "200", description = "The settlement exception, with its SLA projection applied",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = SettlementException.class)))
	@APIResponse(responseCode = "404", description = "No settlement exception exists with that id",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
	public SettlementException getException(@PathParam("exceptionId") String exceptionId) {
		return postTradeService.get(exceptionId);
	}

	@GET
	@Path("/{exceptionId}/events")
	@APIResponse(responseCode = "200", description = "One page of that exception's EXCEPTION audit events, in sequence order",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(type = SchemaType.ARRAY, implementation = AuditEvent.class)),
			headers = {
				@Header(name = PageBounds.TOTAL_COUNT_HEADER, description = "Events in that exception's whole history", schema = @Schema(type = SchemaType.INTEGER)),
				@Header(name = PageBounds.PAGE_OFFSET_HEADER, description = "Offset this page starts at", schema = @Schema(type = SchemaType.INTEGER)),
				@Header(name = PageBounds.PAGE_LIMIT_HEADER, description = "Page size actually applied, after clamping", schema = @Schema(type = SchemaType.INTEGER)),
				@Header(name = PageBounds.LINK_HEADER, description = "RFC 8288 first, prev, next and last page links", schema = @Schema(type = SchemaType.STRING))
			})
	@APIResponse(responseCode = "404", description = "No settlement exception exists with that id",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
	public Response getExceptionEvents(@PathParam("exceptionId") String exceptionId,
			@Parameter(description = "Records to skip. Clamped: a negative, malformed or absent value reads as 0, and a value past the end returns an empty page",
					schema = @Schema(type = SchemaType.INTEGER, defaultValue = "0"))
			@QueryParam("offset") String offset,
			@Parameter(description = "Page size. Clamped: absent, zero, negative, malformed or above 500 reads as 500",
					schema = @Schema(type = SchemaType.INTEGER, defaultValue = "500"))
			@QueryParam("limit") String limit,
			@Context UriInfo uriInfo) {
		//Paged, unlike an order's history: ASSIGNED -> ASSIGNED is a legal edge, so one exception's
		//timeline grows with every re-assignment and has no bound of its own - which is exactly
		//why the response reports how long that history is.
		PageBounds bounds = PageBounds.clamp(offset, limit);

		return bounds.pagedResponse(
				postTradeService.events(exceptionId, bounds.getOffset(), bounds.getLimit()),
				postTradeService.eventCount(exceptionId), uriInfo);
	}

	@PUT
	@Path("/{exceptionId}/assign")
	@Consumes(MediaType.APPLICATION_JSON)
	@APIResponse(responseCode = "200", description = "The exception, now ASSIGNED to the owner in the body",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = SettlementException.class)))
	@APIResponse(responseCode = "400", description = "The owner is missing or blank, or the body could not be read as JSON",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
	@APIResponse(responseCode = "404", description = "No settlement exception exists with that id",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
	@APIResponse(responseCode = "409", description = "The exception is past assignment, so the transition is not legal",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
	@APIResponse(responseCode = "415", description = "The request declared a Content-Type other than application/json",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
	@APIResponse(responseCode = "503", description = "The audit timeline is at capacity, so the transition cannot be recorded",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
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
	@APIResponse(responseCode = "200", description = "The exception, now RESOLVED with the resolution note",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = SettlementException.class)))
	@APIResponse(responseCode = "400", description = "The resolutionNote is missing, the exception is unassigned and no owner was supplied, or the body could not be read as JSON",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
	@APIResponse(responseCode = "404", description = "No settlement exception exists with that id",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
	@APIResponse(responseCode = "409", description = "The exception is already resolved or past resolution, so the transition is not legal",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
	@APIResponse(responseCode = "415", description = "The request declared a Content-Type other than application/json",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
	@APIResponse(responseCode = "503", description = "The audit timeline is at capacity, so the transition cannot be recorded",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
	public SettlementException resolveException(@PathParam("exceptionId") String exceptionId, ResolveRequest resolveRequest, @Context SecurityContext securityContext) {
		return postTradeService.resolve(exceptionId, resolveRequest, securityContext.getUserPrincipal().getName());
	}

	@PUT
	@Path("/{exceptionId}/settlement-ready")
	@APIResponse(responseCode = "200", description = "The exception, now SETTLEMENT_READY; the parent order follows",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = SettlementException.class)))
	@APIResponse(responseCode = "404", description = "No settlement exception exists with that id",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
	@APIResponse(responseCode = "409", description = "The exception is not RESOLVED, so the transition is not legal",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
	@APIResponse(responseCode = "503", description = "The audit timeline is at capacity, so the transition cannot be recorded",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
	public SettlementException markSettlementReady(@PathParam("exceptionId") String exceptionId, @Context SecurityContext securityContext) {
		return postTradeService.markSettlementReady(exceptionId, securityContext.getUserPrincipal().getName());
	}

	/* Bound as a String and converted here rather than bound as the enum: the JAX-RS runtime
	   answers a failed enum conversion with a bodyless 404, which tells a caller the collection
	   does not exist and carries none of this service's {status, error, message, path} envelope.
	   Blank means "no filter", which is what the owner filter has always meant, so the two agree
	   on an empty value. The token is stripped and upper-cased first, the same canonicalization
	   the lifecycle applies to side and symbol, so status=open reads as OPEN rather than being
	   refused for its case. An unreadable token is refused instead of clamped - unlike a page
	   bound, a filter has no nearest honest reading, and silently ignoring it would answer a
	   narrowed query with the whole collection. */
	private static ExceptionStatus statusFilter(String status) {
		if (status == null || status.isBlank()) {
			return null;
		}

		try {
			return ExceptionStatus.valueOf(status.strip().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException unknownStatus) {
			throw new ValidationException("status must be one of " + STATUS_VALUES);
		}
	}
}
