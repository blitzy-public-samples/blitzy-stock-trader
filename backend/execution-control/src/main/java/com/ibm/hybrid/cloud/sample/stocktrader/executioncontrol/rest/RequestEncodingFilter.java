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

//Collections and text
import java.util.Locale;

//Annotations
import jakarta.annotation.Priority;

//Jakarta REST 3.1
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;


//The priority is the one thing about this filter that is not local to it: request filters run in
//ascending priority order, and this one has to precede RequestEntityReadFilter so a coded body is
//refused rather than read.
/** Refuses a request whose body arrives under a content coding, before anything decompresses it. */
@Provider
@Priority(Priorities.ENTITY_CODER)
public class RequestEncodingFilter implements ContainerRequestFilter {

	/* The refusal names the header and the remedy, because the caller's mistake is a header rather
	   than a field and "unsupported media type" alone would send it inspecting its Content-Type.
	   The integration tests assert on this wording, so it is part of the published contract. */
	private static final String COMPRESSED_REQUEST_MESSAGE
			= "request bodies must not be compressed; send the body uncompressed and omit Content-Encoding";

	/* The one coding a request may name, and it means the body was not encoded at all (RFC 9110
	   section 8.4.1). Accepting it keeps a caller that spells out "no encoding" from being refused
	   for saying so. */
	private static final String IDENTITY_CODING = "identity";

	/* Every endpoint that takes a body accepts at most about a kilobyte of JSON, so no caller has a
	   reason to compress one, while the cost of allowing it is not proportionate to the wire: a
	   request-size ceiling bounds the bytes that arrive, and a compressed body turns those bytes
	   into however many the coding expands them to - a few kilobytes of gzip reaching megabytes of
	   heap before a single field is read. Refusing the coding outright is what makes the ceiling
	   mean what it says, and it is refused here, in a request filter, because a filter runs before
	   the entity is read and so before anything would inflate it.

	   415 with an Accept-Encoding of identity, rather than 400: the entity is unsupported in its
	   encoding rather than wrong in its content, which is the status RFC 9110 section 15.5.16
	   gives that case, and the header is how the same section says to tell the caller which
	   codings it may use instead (a response Accept-Encoding advertises what the server accepts,
	   section 12.5.3). The body is the service's own envelope, so a caller parses this refusal
	   exactly as it parses every other one. */
	@Override
	public void filter(ContainerRequestContext requestContext) {
		String encoding = requestContext.getHeaderString(HttpHeaders.CONTENT_ENCODING);

		if (!isContentCoded(encoding)) {
			return;
		}

		String path = requestContext.getUriInfo().getPath();
		ErrorResponse body = new ErrorResponse(
				Response.Status.UNSUPPORTED_MEDIA_TYPE.getStatusCode(),
				Response.Status.UNSUPPORTED_MEDIA_TYPE.getReasonPhrase(),
				COMPRESSED_REQUEST_MESSAGE, path);

		requestContext.abortWith(Response.status(Response.Status.UNSUPPORTED_MEDIA_TYPE)
				.entity(body)
				.type(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.ACCEPT_ENCODING, IDENTITY_CODING)
				.build());
	}

	//A list rather than one token, because Content-Encoding is defined as an ordered list of
	//codings: a body that is gzipped and then deflated names both, so a request is coded if any
	//element of the list is a coding other than identity.
	private static boolean isContentCoded(String encoding) {
		if ((encoding == null) || encoding.isBlank()) {
			return false;
		}

		for (String coding : encoding.split(",")) {
			String candidate = coding.trim().toLowerCase(Locale.ROOT);
			if (!candidate.isEmpty() && !IDENTITY_CODING.equals(candidate)) {
				return true;
			}
		}

		return false;
	}
}
