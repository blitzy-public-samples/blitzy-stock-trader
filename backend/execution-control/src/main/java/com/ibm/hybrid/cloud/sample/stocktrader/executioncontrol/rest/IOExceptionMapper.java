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

//Streams
import java.io.IOException;

//Collections and text
import java.util.Locale;

//Logging (JSR 47)
import java.util.logging.Level;
import java.util.logging.Logger;

//Jakarta REST 3.1
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;


/** Turns a request whose entity the HTTP channel refused mid-read into a 413 or 400 with an ErrorResponse body. */
@Provider
public class IOExceptionMapper implements ExceptionMapper<IOException> {
	private static Logger logger = Logger.getLogger(IOExceptionMapper.class.getName());

	/* The two client-facing texts for a transfer the channel would not finish. Separate from
	   JsonbExceptionMapper's "not valid JSON", because neither condition is about the body's
	   syntax: the first says the message was too big to accept and the second that it never
	   arrived whole, and telling a caller its JSON was malformed in either case would send it
	   looking for a fault that is not there. The integration tests assert on the oversize
	   wording, so it is part of the published contract. */
	private static final String OVERSIZE_MESSAGE
			= "request message exceeds the configured size limit, so the body was not read";
	private static final String INCOMPLETE_MESSAGE = "request body was not received in full";

	/* Bounded and self-cause-guarded for the same reason ProcessingExceptionMapper's walk is: a
	   cause chain is a few links deep in practice, while initCause permits a cyclic one. */
	private static final int MAX_CAUSE_DEPTH = 32;

	/* The size refusal is recognised by the exception's own name and message rather than by its
	   type, deliberately. The channel raises com.ibm.wsspi.http.channel.exception
	   .MessageTooLargeException and its IllegalHttpBodyException parent, which are Liberty
	   internal SPI: they ship in the runtime, not in any Maven artefact this module can compile
	   against, so naming one in an instanceof would tie the WAR to a server jar and break the
	   moment the module is built against anything else.

	   Two independent routes, because neither covers the condition alone. MessageTooLargeException
	   says what it is in its name and nothing in its text ("Size=8437"), so it is matched on the
	   simple name. A chunk whose declared length alone exceeds the limit arrives as the parent
	   type, which the channel also raises for a body that is illegal in other ways, so that one is
	   matched on its text instead - the parent name would otherwise answer 413 to a malformed
	   chunk header, which is a bad request rather than an oversize one. Anything unrecognised
	   falls to 400, so a channel failure neither route knows still carries the envelope. */
	private static final String[] SIZE_LIMIT_EXCEPTIONS = {"MessageTooLargeException"};
	private static final String SIZE_LIMIT_MESSAGE_MARKER = "message size limit";

	@Context private UriInfo uriInfo;

	/* Without this mapper the HTTP channel's refusal escapes the application unmapped and the
	   runtime's fallback answers 500 carrying the channel's own sentence as a plain-text body
	   under a Content-Type of application/json - a status the service does not mean, a body a JSON
	   client cannot parse, and the one hole in the uniform {status, error, message, path}
	   contract. It is reached where anything in the application asked the channel for the entity
	   and the channel would not supply it: a chunked body over MessageSizeLimit, whose total
	   length is unknown until it is read and so cannot be refused at header-parse time the way a
	   declared Content-Length is, or a transfer the caller abandoned part way through. Both are
	   decided by the caller's own framing, which is why neither is logged with a stack trace at
	   the default level: a refusal one caller can repeat must not be a way to write 8 KB of ERROR
	   into the log per attempt.

	   RequestEntityReadFilter answers the chunked case ahead of this mapper, because a failure
	   raised inside the deserializer is wrapped as a JsonbException and can no longer be told from
	   a body that was simply not JSON. What reaches here is therefore every other read the
	   application performs - and it stays registered for exactly that reason: the answer to an
	   entity the channel refused must not depend on which layer was holding the stream. */
	@Override
	public Response toResponse(IOException exception) {
		return refusalFor(exception, path());
	}

	/* Shared with RequestEntityReadFilter, which reads a length-unknown entity itself and so meets
	   the same failures one layer earlier, where they can still be told apart, and with
	   ProcessingExceptionMapper, which meets them already wrapped. One implementation of the
	   verdict, because a caller must not get two different answers to one condition depending on
	   which layer happened to notice it. */
	static Response refusalFor(Throwable exception, String path) {
		if (isSizeLimitFailure(exception)) {
			//FINE, with the throwable: the configured ceiling doing exactly its job is not an
			//operator's problem, and an operator diagnosing one caller can raise the level.
			logger.log(Level.FINE, "Refusing an oversize request message on " + path, exception);

			return envelope(Response.Status.REQUEST_ENTITY_TOO_LARGE, OVERSIZE_MESSAGE, path);
		}

		//One line and no throwable: an unfinished transfer is worth an operator's attention, since
		//it can also mean a proxy cutting bodies short, but it stays caller-triggered, so the
		//stack trace that would make it an amplification vector is left to the FINE level above.
		logger.log(Level.WARNING, "Request body on " + path + " could not be read in full: "
				+ exception.getClass().getSimpleName());

		return envelope(Response.Status.BAD_REQUEST, INCOMPLETE_MESSAGE, path);
	}

	private static Response envelope(Response.Status status, String message, String path) {
		ErrorResponse body = new ErrorResponse(status.getStatusCode(), status.getReasonPhrase(),
				message, path);

		return Response.status(status).entity(body).type(MediaType.APPLICATION_JSON).build();
	}

	//The channel closes the connection on a body it refused, so this mapper can run with no
	//injected UriInfo; the path is then reported as the root rather than failing the answer.
	private String path() {
		return (uriInfo == null) ? "/" : uriInfo.getPath();
	}

	//Package-private so ProcessingExceptionMapper can ask the same question of a cause chain it
	//received already wrapped, rather than deciding the verdict a second time and differently.
	static boolean isSizeLimitFailure(Throwable exception) {
		Throwable candidate = exception;
		for (int depth = 0; (candidate != null) && (depth < MAX_CAUSE_DEPTH); depth++) {
			if (namesSizeLimitFailure(candidate) || describesSizeLimitFailure(candidate)) {
				return true;
			}

			Throwable next = candidate.getCause();
			candidate = (next == candidate) ? null : next;
		}

		return false;
	}

	private static boolean namesSizeLimitFailure(Throwable candidate) {
		String name = candidate.getClass().getSimpleName();
		for (String sizeLimitException : SIZE_LIMIT_EXCEPTIONS) {
			if (sizeLimitException.equals(name)) {
				return true;
			}
		}

		return false;
	}

	private static boolean describesSizeLimitFailure(Throwable candidate) {
		String message = candidate.getMessage();

		return (message != null)
				&& message.toLowerCase(Locale.ROOT).contains(SIZE_LIMIT_MESSAGE_MARKER);
	}
}
