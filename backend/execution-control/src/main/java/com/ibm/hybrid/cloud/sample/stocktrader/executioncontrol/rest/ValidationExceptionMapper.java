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
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.lifecycle.ValidationException;

//Jakarta REST 3.1
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;


/** Turns a rejected request's ValidationException into an HTTP 400 with an ErrorResponse body. */
@Provider
public class ValidationExceptionMapper implements ExceptionMapper<ValidationException> {
	@Context private UriInfo uriInfo;

	@Override
	public Response toResponse(ValidationException exception) {
		//The message is relayed verbatim: the lifecycle services raise this exception with
		//client-safe text that names the offending field, and the integration tests assert on
		//those literals, so rewording, prefixing or defaulting it here would break both.
		ErrorResponse body = new ErrorResponse(Response.Status.BAD_REQUEST.getStatusCode(),
				Response.Status.BAD_REQUEST.getReasonPhrase(), exception.getMessage(), uriInfo.getPath());

		return Response.status(Response.Status.BAD_REQUEST).entity(body).type(MediaType.APPLICATION_JSON).build();
	}
}
