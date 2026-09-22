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
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.lifecycle.StateConflictException;

//Jakarta REST 3.1
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;


/** Turns a conflicting request's StateConflictException into an HTTP 409 with an ErrorResponse body. */
@Provider
public class StateConflictExceptionMapper implements ExceptionMapper<StateConflictException> {
	@Context private UriInfo uriInfo;

	@Override
	public Response toResponse(StateConflictException exception) {
		//The message is relayed verbatim: LifecycleTransitions names the refused edge with a
		//U+2192 arrow between the two state names, and the integration tests assert that text
		//character for character, so rewording, prefixing, trimming or re-encoding it here
		//would break a contract nothing is gained by changing.
		ErrorResponse body = new ErrorResponse(Response.Status.CONFLICT.getStatusCode(),
				Response.Status.CONFLICT.getReasonPhrase(), exception.getMessage(), uriInfo.getPath());

		return Response.status(Response.Status.CONFLICT).entity(body).type(MediaType.APPLICATION_JSON).build();
	}
}
