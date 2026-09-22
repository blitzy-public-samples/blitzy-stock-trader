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
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.lifecycle.CapacityExceededException;

//Jakarta REST 3.1
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;


/** Turns an exhausted in-memory admission capacity into an HTTP 503 with an ErrorResponse body. */
@Provider
public class CapacityExceededExceptionMapper implements ExceptionMapper<CapacityExceededException> {
	@Context private UriInfo uriInfo;

	@Override
	public Response toResponse(CapacityExceededException exception) {
		/* 503 rather than 429: the exhausted ceiling belongs to the whole service, not to the
		   calling client, so no caller can clear it by slowing down and no per-client quota was
		   crossed. No Retry-After either - the estate holds this state only in memory, so the
		   headroom returns when the service restarts and not after any interval this service
		   could honestly name. */
		ErrorResponse body = new ErrorResponse(Response.Status.SERVICE_UNAVAILABLE.getStatusCode(),
				Response.Status.SERVICE_UNAVAILABLE.getReasonPhrase(), exception.getMessage(), uriInfo.getPath());

		return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(body).type(MediaType.APPLICATION_JSON).build();
	}
}
