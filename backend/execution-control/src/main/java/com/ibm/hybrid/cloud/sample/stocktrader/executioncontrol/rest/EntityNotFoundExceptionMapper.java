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
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.lifecycle.EntityNotFoundException;

//Jakarta REST 3.1
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;


@Provider
/** Turns an identifier that names no stored entity into an HTTP 404 with an ErrorResponse body. */
public class EntityNotFoundExceptionMapper implements ExceptionMapper<EntityNotFoundException> {
	@Context private UriInfo uriInfo;

	@Override
	public Response toResponse(EntityNotFoundException exception) {
		//Mapped for the concrete lifecycle exception only, so an absent order or settlement
		//exception is the sole condition answered here. An unknown clientId on submit is a bad
		//field in an otherwise well-formed request and reaches the client as a ValidationException
		//(400); covering it here would report a caller's own mistake as a missing resource.
		//The message is relayed verbatim: the lifecycle services raise this exception with
		//client-safe text that names the missing identifier, and the integration tests assert on
		//those literals, so rewording, prefixing or defaulting it here would break both.
		ErrorResponse body = new ErrorResponse(Response.Status.NOT_FOUND.getStatusCode(),
				Response.Status.NOT_FOUND.getReasonPhrase(), exception.getMessage(), uriInfo.getPath());

		return Response.status(Response.Status.NOT_FOUND).entity(body).type(MediaType.APPLICATION_JSON).build();
	}
}
