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

//Streams
import java.io.ByteArrayInputStream;
import java.io.IOException;

//Annotations
import jakarta.annotation.Priority;

//Jakarta REST 3.1
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;


/** Reads a request body of unknown length under this service's own error handling, so a transport refusal is answered in its envelope. */
@Provider
@Priority(Priorities.ENTITY_CODER + 10)
public class RequestEntityReadFilter implements ContainerRequestFilter {

	/* Only a body whose length the framing does not declare, and only on a verb that carries one.

	   What is held in memory is bounded by MAX_REQUEST_SIZE_BYTES rather than by anything a caller
	   chooses: the channel refuses any message past that ceiling, RequestEncodingFilter refuses a
	   content coding that could expand one, and the largest body any endpoint defines is about a
	   kilobyte of JSON. That is what makes reading the entity in one go safe here.

	   A message with a Content-Length over the ceiling is refused as its headers are parsed, so it
	   never reaches the application and nothing here applies to it. A chunked message declares no
	   length, so the same ceiling can only be applied while the body is read - and by then the
	   deserializer is holding the stream, which turns the channel's refusal into a JsonbException
	   reading "I/O error while parsing JSON". That is indistinguishable from a body that was
	   simply not JSON, so it was answered 400 with the malformed-body message: the wrong reason,
	   for a request whose body was perfectly well-formed and merely too large. Reading the entity
	   here, one layer above the deserializer, is what keeps the exception legible: the channel's
	   IOException arrives unwrapped and IOExceptionMapper.refusalFor turns it into the same 413
	   the Content-Length path already answers.

	   The cost is that a chunked body is now read to its terminating chunk before binding, where
	   the deserializer would have stopped at the end of the JSON document. That is the framing's
	   own definition of where the message ends, and Liberty's read timeout bounds the wait for a
	   caller that never sends it. The Content-Length path is deliberately left alone rather than
	   buffered too, so nothing changes for the framing that is already answered correctly. */
	@Override
	public void filter(ContainerRequestContext requestContext) {
		if ((requestContext.getLength() >= 0) || !carriesBody(requestContext.getMethod())) {
			return;
		}

		try {
			byte[] entity = requestContext.getEntityStream().readAllBytes();

			requestContext.setEntityStream(new ByteArrayInputStream(entity));
		} catch (IOException refusedByTheChannel) {
			//Aborting here rather than rethrowing: the response is already decided, and letting
			//the exception travel back through the runtime is what produced the unmapped 500.
			requestContext.abortWith(IOExceptionMapper.refusalFor(refusedByTheChannel,
					requestContext.getUriInfo().getPath()));
		}
	}

	//The verbs whose requests this service defines a body for. A GET or a DELETE carries none, and
	//touching its entity stream would be reading something that is not there.
	private static boolean carriesBody(String method) {
		return HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method)
				|| HttpMethod.PATCH.equals(method);
	}
}
