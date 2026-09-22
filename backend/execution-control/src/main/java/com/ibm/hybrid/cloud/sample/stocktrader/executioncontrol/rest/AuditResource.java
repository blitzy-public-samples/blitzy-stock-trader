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

//Collections
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
@Path("/audit")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
/** Read view over the whole append-only audit timeline, optionally narrowed to one entity. */
public class AuditResource {
	@Inject private AuditTimeline auditTimeline;

	@GET
	public List<AuditEvent> getAuditEvents(@QueryParam("entityType") String entityType, @QueryParam("entityId") String entityId,
			@QueryParam("offset") @DefaultValue("0") int offset, @QueryParam("limit") @DefaultValue("0") int limit) {
		/* One call for every shape of this query: the timeline narrows on each key it was given and
		   ignores a key it was not, so the both-key, single-key and unfiltered reads all come back
		   from the same walk and all come back as one page. The timeline is this service's only
		   structure with no entity ceiling of its own, which is why nothing here projects from all().
		   A filter matching nothing returns an empty page rather than a 404: the audit collection
		   itself always exists, and reporting an unknown entity as missing is the job of
		   GET /orders/{orderId}/events and GET /exceptions/{exceptionId}/events, which resolve the
		   entity through their services first. */
		PageBounds bounds = PageBounds.clamp(offset, limit);

		return auditTimeline.page(entityType, entityId, bounds.getOffset(), bounds.getLimit());
	}
}
