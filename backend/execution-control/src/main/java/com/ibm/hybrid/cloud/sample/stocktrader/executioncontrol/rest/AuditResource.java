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
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;


//No role annotation here: GET is granted to both StockViewer and StockTrader declaratively in
//web.xml, which is the estate's enforcement point, and every other verb is refused there by
//deny-uncovered-http-methods - so a read is all this class can ever be reached for.
@Path("/audit")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
/** Read view over the whole append-only audit timeline, optionally narrowed to one entity. */
public class AuditResource {
	@Inject private AuditTimeline auditTimeline;

	@GET
	public List<AuditEvent> getAuditEvents(@QueryParam("entityType") String entityType, @QueryParam("entityId") String entityId) {
		boolean filterByType = (entityType != null) && !entityType.isBlank();
		boolean filterById = (entityId != null) && !entityId.isBlank();

		//AuditTimeline answers on both keys together or not at all, so a single-key query is
		//projected from all() here rather than growing the timeline a third accessor nothing
		//else needs. A filter matching nothing returns an empty timeline rather than a 404: the
		//audit collection itself always exists, and reporting an unknown entity as missing is
		//the job of GET /orders/{orderId}/events and GET /exceptions/{exceptionId}/events,
		//which resolve the entity through their services first.
		if (filterByType && filterById) {
			return auditTimeline.forEntity(entityType, entityId);
		}
		if (filterByType) {
			return auditTimeline.all().stream()
					.filter(event -> entityType.equals(event.getEntityType()))
					.toList();
		}
		if (filterById) {
			return auditTimeline.all().stream()
					.filter(event -> entityId.equals(event.getEntityId()))
					.toList();
		}

		return auditTimeline.all();
	}
}
