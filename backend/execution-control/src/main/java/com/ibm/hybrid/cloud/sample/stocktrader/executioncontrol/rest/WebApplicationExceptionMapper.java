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

import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.ErrorResponse;

//Jakarta REST 3.1
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.List;
import java.util.Map;


/** Gives the ErrorResponse envelope to the statuses the Jakarta REST runtime decides for itself. */
@Provider
public class WebApplicationExceptionMapper implements ExceptionMapper<WebApplicationException> {

	@Context private UriInfo uriInfo;

	/* No application code throws WebApplicationException - every refusal this service decides is a
	   ValidationException, EntityNotFoundException, StateConflictException or
	   CapacityExceededException with a mapper of its own - so everything arriving here was raised by
	   the runtime before or after a resource method ran: no resource matched the path (404), none
	   accepts the method (405) or the request's media type (415), none produces a type the Accept
	   header allows (406), or a query parameter value could not be converted (404). The runtime
	   answers each of those with a status and no entity, which is a body a client cannot parse and
	   the one gap in this service's otherwise uniform {status, error, message, path} contract. */
	@Override
	public Response toResponse(WebApplicationException exception) {
		Response original = exception.getResponse();

		if (original == null) {
			return envelope(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), null);
		}

		//A response the runtime already gave a body to is left exactly as it is: it carries more
		//about the failure than a status-derived message could, and overwriting it would lose that.
		if (original.hasEntity()) {
			return original;
		}

		return envelope(original.getStatus(), original);
	}

	private Response envelope(int status, Response original) {
		String path = (uriInfo == null) ? "/" : uriInfo.getPath();
		ErrorResponse body = new ErrorResponse(status, ContainerErrorServlet.reasonPhraseOf(status),
				ContainerErrorServlet.messageFor(status, path), path);

		Response.ResponseBuilder builder = Response.status(status).entity(body)
				.type(MediaType.APPLICATION_JSON);

		//Allow on a 405 and Accept-Patch or Warning on the other statuses are part of the answer the
		//runtime computed; only the entity is being replaced, so its headers are carried across.
		if (original != null) {
			copyHeaders(original.getStringHeaders(), builder);
		}

		return builder.build();
	}

	private static void copyHeaders(MultivaluedMap<String, String> headers,
			Response.ResponseBuilder builder) {
		if (headers == null) {
			return;
		}

		for (Map.Entry<String, List<String>> header : headers.entrySet()) {
			String name = header.getKey();

			//The two headers that describe the entity are skipped: this response has a new one.
			if (HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name)
					|| HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)) {
				continue;
			}

			for (String value : header.getValue()) {
				builder.header(name, value);
			}
		}
	}
}
