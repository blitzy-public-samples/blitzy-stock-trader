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

//Jakarta JSON Processing 2.1
import jakarta.json.JsonException;

//Jakarta JSON Binding 3.0
import jakarta.json.bind.JsonbException;

//Jakarta REST 3.1
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;


/** Turns a request whose entity the runtime could not read into an HTTP 400 with an ErrorResponse body. */
@Provider
public class ProcessingExceptionMapper implements ExceptionMapper<ProcessingException> {
	private static Logger logger = Logger.getLogger(ProcessingExceptionMapper.class.getName());

	/* A cause chain is a handful of links deep in practice, while Throwable.initCause permits a
	   self-referential or cyclic one, so the walk below is bounded rather than trusting the chain to
	   end. The bound is far above any chain a provider builds, which is what keeps the guard from
	   changing the level a real failure is logged at. */
	private static final int MAX_CAUSE_DEPTH = 32;

	@Context private UriInfo uriInfo;

	@Override
	public Response toResponse(ProcessingException exception) {
		/* One answer for the whole exception type, and it is the 400 JsonbExceptionMapper gives: an
		   entity provider that wraps the deserializer's failure instead of letting it propagate -
		   which is what a RESTEasy-supplied JSON binding provider does, where Liberty's own lets
		   JsonbException through to that mapper - delivers the caller's malformed body here, and the
		   status a caller reads must not depend on which provider is installed or on how deeply it
		   wrapped the cause. Nothing in the REST API distinguishes a failure reading a request entity
		   from one writing a response, and entity handling is the only thing that raises this
		   exception in a service that makes no outbound REST call (AAP 0.4.3), so the distinction
		   lives in the log rather than in the status: a recognised JSON failure is one caller's
		   mistake, logged at FINE, while anything else is unusual enough that an operator should see
		   it at the default level. Neither relays the cause text, which is what keeps the
		   deserializer's internals off the wire (CWE-209). */
		if (hasJsonFailureCause(exception)) {
			logger.log(Level.FINE, "Rejecting an unreadable request body on " + uriInfo.getPath(), exception);
		} else {
			logger.log(Level.WARNING, "Rejecting a request whose entity could not be read on "
					+ uriInfo.getPath(), exception);
		}

		ErrorResponse body = new ErrorResponse(Response.Status.BAD_REQUEST.getStatusCode(),
				Response.Status.BAD_REQUEST.getReasonPhrase(),
				JsonbExceptionMapper.MALFORMED_BODY_MESSAGE, uriInfo.getPath());

		return Response.status(Response.Status.BAD_REQUEST).entity(body).type(MediaType.APPLICATION_JSON).build();
	}

	//Chooses the log level only, never the status. The whole chain is examined rather than just the
	//immediate cause, because a provider is free to wrap the binding failure at any depth;
	//JsonException sits alongside JsonbException because the parser reports a body that is not JSON
	//at all, while the binding layer reports a body that is JSON but does not fit the target type.
	private static boolean hasJsonFailureCause(ProcessingException exception) {
		Throwable cause = exception.getCause();
		for (int depth = 0; (cause != null) && (depth < MAX_CAUSE_DEPTH); depth++) {
			if ((cause instanceof JsonbException) || (cause instanceof JsonException)) {
				return true;
			}
			Throwable next = cause.getCause();
			//A throwable whose cause is itself reports the same link forever; stopping here keeps the
			//depth bound from being the only thing that ends the commonest cyclic chain.
			cause = (next == cause) ? null : next;
		}
		return false;
	}
}
