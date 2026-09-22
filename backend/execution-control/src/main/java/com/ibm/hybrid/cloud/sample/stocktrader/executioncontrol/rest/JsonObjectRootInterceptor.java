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

import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json.AssignRequest;

//Streams
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;

//Jakarta JSON Binding 3.0
import jakarta.json.bind.JsonbException;

//Jakarta REST 3.1
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;


/** Refuses a request body whose JSON root is not an object before it is bound to one of this service's request models. */
@Provider
public class JsonObjectRootInterceptor implements ReaderInterceptor {

	/* Every body this service accepts is a JSON object - an order submission, an assignment, a
	   resolution - and the request models are the only types ever read from a request entity, so the
	   check is scoped by the package they share rather than by a list of class names that would have
	   to be kept in step with them. The name is derived from a member of that package instead of
	   written out, so moving or renaming the package cannot leave a stale string matching nothing. */
	private static final String REQUEST_MODEL_PACKAGE = AssignRequest.class.getPackageName();

	private static final int JSON_OBJECT_START = '{';

	/* JSON written for interchange is UTF-8 (RFC 8259 section 8.1), where every character that can
	   open a root value is a printable ASCII byte. These bounds are what the shape test is applied
	   within; outside them the body is in some other encoding - a UTF-16 object root leads with NUL,
	   a byte-order mark with 0xEF or 0xFF - whose root this one-byte scan cannot read. */
	private static final int FIRST_PRINTABLE_ASCII = 0x20;
	private static final int LAST_PRINTABLE_ASCII = 0x7E;

	/* The four characters RFC 8259 permits between tokens. Skipped rather than refused because an
	   indented or newline-prefixed body is a valid object, and callers do send one. */
	private static final String JSON_WHITESPACE = " \t\n\r";

	@Override
	public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException {
		/* The service's own answer for a body of the wrong JSON shape, rather than whatever the
		   deserializer happens to tolerate. Yasson binds an array root to a POJO target by taking
		   its first object element and discarding the rest, so without this check a body that is not
		   a request entity at all - [{"owner":"x"}] for an assignment, or an array of order
		   submissions - reaches the resource fully formed and its transition proceeds with 200,
		   while every other non-object root is refused with 400 because the deserializer happens to
		   fail on it. One shape rule, applied before binding, is what makes that refusal the
		   contract instead of a side effect of which JSON-B implementation is installed. */
		if (bindsRequestModel(context.getType()) && isJson(context.getMediaType())) {
			context.setInputStream(requireJsonObjectRoot(context.getInputStream()));
		}

		return context.proceed();
	}

	//Scoped to this service's request models: the entity read for a JsonStructure, a String, a
	//stream or a collection is not one of them, and an array of a request model reports the model's
	//own package, so it is excluded explicitly rather than by package name.
	private static boolean bindsRequestModel(Class<?> type) {
		return (type != null) && !type.isArray() && REQUEST_MODEL_PACKAGE.equals(type.getPackageName());
	}

	//The endpoints that take a body all declare @Consumes(APPLICATION_JSON), so the container has
	//already matched the media type by the time a request model is read; the test is kept because
	//what is being enforced is a JSON shape, and a body read as anything else is not this check's
	//to judge.
	private static boolean isJson(MediaType mediaType) {
		return (mediaType != null) && (MediaType.APPLICATION_JSON_TYPE.isCompatible(mediaType)
				|| mediaType.getSubtype().endsWith("+json"));
	}

	/* Classifies the root from the first significant byte and hands back a stream the deserializer
	   can still read in full. One byte of pushback is the whole cost: the significant byte is put
	   back, and only the insignificant whitespace ahead of it is consumed, so nothing buffers the
	   body and a large entity is neither held in memory nor read twice. */
	private static InputStream requireJsonObjectRoot(InputStream entityStream) throws IOException {
		PushbackInputStream stream = new PushbackInputStream(entityStream, 1);

		int candidate = stream.read();
		while (isJsonWhitespace(candidate)) {
			candidate = stream.read();
		}

		/* An empty or whitespace-only body has no root to classify. Left to the deserializer, which
		   answers it with the very same 400 this method raises - refusing it here would only move
		   which line logs it. */
		if (candidate == -1) {
			return stream;
		}

		stream.unread(candidate);

		/* Refused before binding: the root is a JSON value that cannot represent a request model -
		   an array, a string, a number, a literal - or not JSON at all. The message reaching the
		   client is JsonbExceptionMapper's fixed text, so all three routes into an unreadable body
		   give one answer and none relays the shape it found (CWE-209); the detail below is for the
		   log that mapper writes at FINE. A byte outside the printable ASCII range is left alone
		   instead: it means an encoding this scan cannot read a root from, and refusing a body that
		   may well be a valid object is the one outcome worse than tolerating a malformed one. */
		if ((candidate != JSON_OBJECT_START) && isPrintableAscii(candidate)) {
			throw new JsonbException(JsonbExceptionMapper.MALFORMED_BODY_MESSAGE
					+ " - its root is not a JSON object");
		}

		return stream;
	}

	private static boolean isJsonWhitespace(int candidate) {
		return (candidate != -1) && (JSON_WHITESPACE.indexOf(candidate) >= 0);
	}

	private static boolean isPrintableAscii(int candidate) {
		return (candidate >= FIRST_PRINTABLE_ASCII) && (candidate <= LAST_PRINTABLE_ASCII);
	}
}
