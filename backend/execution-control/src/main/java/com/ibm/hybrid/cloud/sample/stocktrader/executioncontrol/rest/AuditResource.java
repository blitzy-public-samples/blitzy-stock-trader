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

import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.audit.AuditTimeline;
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.AuditEvent;

//CDI 4.0
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

//Jakarta REST 3.1
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

//mpOpenAPI 4.1
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;


//No role annotation here: GET is granted to both StockViewer and StockTrader declaratively in
//web.xml, which is the estate's enforcement point. Authorization and dispatch are two stages:
//POST, PUT and DELETE are covered there for StockTrader on every path, so an attempt at one clears
//security and is then answered 405 by this GET-only resource, while HEAD, OPTIONS, PATCH and TRACE
//are covered nowhere and get 403 from deny-uncovered-http-methods.
/** Read view over the whole append-only audit timeline, optionally narrowed to one entity. */
@Path("/audit")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class AuditResource {
	@Inject private AuditTimeline auditTimeline;

	@GET
	@APIResponse(responseCode = "200", description = "One page of the audit timeline in sequence order, narrowed by any entityType and entityId supplied",
			content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(type = SchemaType.ARRAY, implementation = AuditEvent.class)),
			headers = {
				@Header(name = PageBounds.TOTAL_COUNT_HEADER, description = "Events matching the filter, across the whole timeline - the number a full traversal will read", schema = @Schema(type = SchemaType.INTEGER)),
				@Header(name = PageBounds.PAGE_OFFSET_HEADER, description = "Offset this page starts at", schema = @Schema(type = SchemaType.INTEGER)),
				@Header(name = PageBounds.PAGE_LIMIT_HEADER, description = "Page size actually applied, after clamping", schema = @Schema(type = SchemaType.INTEGER)),
				@Header(name = PageBounds.LINK_HEADER, description = "RFC 8288 first, prev, next and last page links, each carrying the same filter", schema = @Schema(type = SchemaType.STRING))
			})
	public Response getAuditEvents(
			@Parameter(description = "ORDER or EXCEPTION. A value matching nothing returns an empty page", schema = @Schema(type = SchemaType.STRING))
			@QueryParam("entityType") String entityType,
			@Parameter(description = "Order or exception id. A value matching nothing returns an empty page", schema = @Schema(type = SchemaType.STRING))
			@QueryParam("entityId") String entityId,
			@Parameter(description = "Records to skip. Clamped: a negative, malformed or absent value reads as 0, and a value past the end returns an empty page",
					schema = @Schema(type = SchemaType.INTEGER, defaultValue = "0"))
			@QueryParam("offset") String offset,
			@Parameter(description = "Page size. Clamped: absent, zero, negative, malformed or above 500 reads as 500",
					schema = @Schema(type = SchemaType.INTEGER, defaultValue = "500"))
			@QueryParam("limit") String limit,
			@Context UriInfo uriInfo) {
		/* One call for every shape of this query: the timeline narrows on each key it was given and
		   ignores a key it was not, so the both-key, single-key and unfiltered reads all come back
		   from the same walk and all come back as one page. The timeline is this service's only
		   structure with no entity ceiling of its own, which is why nothing here projects from all().
		   A filter matching nothing returns an empty page rather than a 404: the audit collection
		   itself always exists, and reporting an unknown entity as missing is the job of
		   GET /orders/{orderId}/events and GET /exceptions/{exceptionId}/events, which resolve the
		   entity through their services first.
		   This is the endpoint the page total matters most on: the timeline holds up to 150,000
		   events and a page holds 500, so a caller reading it once sees a fraction of the record
		   and the total is what tells it so - reading the timeline in full is a traversal, and an
		   audit consumer has to be able to know when it has reached the end of one. */
		PageBounds bounds = PageBounds.clamp(offset, limit);

		return bounds.pagedResponse(
				auditTimeline.page(entityType, entityId, bounds.getOffset(), bounds.getLimit()),
				auditTimeline.count(entityType, entityId), uriInfo);
	}
}
