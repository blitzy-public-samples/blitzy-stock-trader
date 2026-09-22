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

//Logging (JSR 47)
import java.util.logging.Level;
import java.util.logging.Logger;

//Jakarta JSON Binding 3.0
import jakarta.json.bind.JsonbException;

//Jakarta REST 3.1
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;


/** Turns a request body JSON-B cannot read into an HTTP 400 with an ErrorResponse body. */
@Provider
public class JsonbExceptionMapper implements ExceptionMapper<JsonbException> {
	private static Logger logger = Logger.getLogger(JsonbExceptionMapper.class.getName());

	/* The one place the client-facing text for an unreadable body is written. ProcessingExceptionMapper
	   answers the very same condition when the entity provider wraps the failure instead of letting it
	   propagate, and reuses this literal, so the two routes into a malformed body can never drift into
	   two different answers. The integration test asserts this exact string. */
	static final String MALFORMED_BODY_MESSAGE = "request body is not valid JSON";

	@Context private UriInfo uriInfo;

	@Override
	public Response toResponse(JsonbException exception) {
		/* Liberty's JSON-B entity provider lets this exception propagate unwrapped, so without this
		   mapper the container's fallback answers 500 carrying the deserializer's own text - which
		   names the failing property, the Java type it could not fill and, for a body of the wrong
		   JSON shape, this application's own class name (CWE-209). Nothing from the cause therefore
		   reaches the client: the caller already knows what it posted, and a fixed message is the
		   whole of what it needs. The cause is logged at FINE rather than relayed so an operator can
		   still diagnose one caller's body by raising the trace level, while a flood of malformed
		   requests cannot fill the log at Liberty's default *=info.

		   Reading an entity is the only thing in this service that can raise this exception: JSON-B
		   is never called from application code, and the serialization side cannot fail - every
		   response type is a plain getter-based POJO with no adapter, and each one is already written
		   by the integration tests. The REST API exposes no way for a mapper to tell the two sides
		   apart, which is why 400 is answered unconditionally. */
		logger.log(Level.FINE, "Rejecting an unreadable request body on " + uriInfo.getPath(), exception);

		ErrorResponse body = new ErrorResponse(Response.Status.BAD_REQUEST.getStatusCode(),
				Response.Status.BAD_REQUEST.getReasonPhrase(), MALFORMED_BODY_MESSAGE, uriInfo.getPath());

		return Response.status(Response.Status.BAD_REQUEST).entity(body).type(MediaType.APPLICATION_JSON).build();
	}
}
