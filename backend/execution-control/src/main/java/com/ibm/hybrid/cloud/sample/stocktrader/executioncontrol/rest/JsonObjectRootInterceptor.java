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
import com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.lifecycle.ValidationException;

//Streams
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

//Reflection, over a request model's own declared properties
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

//Collections
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

//Jakarta JSON Processing 2.1
import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.stream.JsonParser;
import jakarta.json.stream.JsonParserFactory;

//Jakarta JSON Binding 3.0
import jakarta.json.bind.JsonbException;

//Jakarta REST 3.1
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;


/** Holds a request body to one complete JSON object of scalar-valued fields before it is bound to one of this service's request models. */
@Provider
public class JsonObjectRootInterceptor implements ReaderInterceptor {

	/* Every body this service accepts is a JSON object - an order submission, an assignment, a
	   resolution - and the request models are the only types ever read from a request entity, so the
	   check is scoped by the package they share rather than by a list of class names that would have
	   to be kept in step with them. The name is derived from a member of that package instead of
	   written out, so moving or renaming the package cannot leave a stale string matching nothing. */
	private static final String REQUEST_MODEL_PACKAGE = AssignRequest.class.getPackageName();

	/* One factory for the life of the application, because JsonParserFactory is thread-safe and
	   looking a provider up per request would put a service-loader scan on the submit path. */
	private static final JsonParserFactory PARSER_FACTORY = Json.createParserFactory(Collections.emptyMap());

	/* Which members of a request model carry a single value, worked out from the model itself and
	   cached per class: the alternative is a list of field names here, which would silently stop
	   covering a field the day one is added to OrderRequest. Reflection runs once per request type
	   for the life of the application, never per request. */
	private static final Map<Class<?>, Set<String>> SCALAR_PROPERTIES = new ConcurrentHashMap<>();

	private static final String SETTER_PREFIX = "set";

	/* Read size and starting buffer for the entity copy. The chunk matches the HTTP channel's
	   default message ceiling, and the buffer a request model's whole body several times over, so an
	   ordinary submission is copied without a single reallocation. */
	private static final int ENTITY_CHUNK_SIZE = 8192;
	private static final int INITIAL_ENTITY_BUFFER = 512;

	//No length was declared, or none this check can hold the entity to.
	private static final long UNDECLARED_LENGTH = -1L;

	/* The four characters RFC 8259 permits between tokens. Skipped rather than refused because an
	   indented or newline-padded body is a valid object, and callers do send one. */
	private static final String JSON_WHITESPACE = " \t\n\r";

	/* Why a body was refused, appended to the client-facing text for the log JsonbExceptionMapper
	   writes at FINE. None of it reaches the caller - the mapper answers its own fixed message - so
	   an operator can tell the four refusals apart without the wire relaying the shape it found
	   (CWE-209). */
	private static final String NOT_AN_OBJECT_ROOT = " - its root is not a JSON object";
	private static final String TRAILING_CONTENT = " - content follows the end of the JSON document";
	private static final String UNREADABLE_DOCUMENT = " - it is not one well-formed JSON document";
	private static final String INCOMPLETE_ENTITY = " - it is shorter than its declared Content-Length";

	@Override
	public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException {
		/* The service's own answer for a body that is not exactly one JSON object of single-valued
		   fields, rather than whatever the deserializer happens to tolerate. Yasson is lenient in
		   three directions that each let a caller smuggle a value past the validation: it binds an
		   array root to a POJO target by taking its first object element, it binds an array to a
		   scalar field by taking its LAST element - so limitPrice [1,2,3] fills at 3 while any
		   inline control, gateway or audit consumer reading the first element sees 1 - and it stops
		   reading at the end of the first document, so anything appended to the entity is discarded
		   unseen. One rule applied before binding is what makes the refusal this service's contract
		   instead of a side effect of which JSON-B implementation is installed.

		   The entity is read here in full so the rule can be applied to the whole of it and the
		   deserializer can still be handed the original bytes: nothing about how a value binds -
		   which duplicate key wins, how a quantity written as a string or an exponent is read, what
		   an invalid UTF-8 sequence becomes - is changed by this check. The HTTP channel's message
		   size limit bounds what can arrive, so the copy is bounded by configuration rather than by
		   the caller. */
		if (bindsRequestModel(context.getType()) && isJson(context.getMediaType())) {
			byte[] entity = readWholeEntity(context.getInputStream(),
					declaredEntityLength(context.getHeaders()));

			requireOneScalarValuedJsonObject(entity, context.getType());

			context.setInputStream(new ByteArrayInputStream(entity));
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

	/* Copies the entity so the checks below can see all of it, and refuses a truncated HTTP message
	   as the caller's malformed body. An entity shorter than the length its own request declared is
	   a document the caller never finished sending, which a complete JSON object hides perfectly:
	   the object ends before the bytes were due to, so a deserializer reading only as far as the
	   closing brace binds it and never learns the rest never arrived. A connection that ends mid
	   entity reaches this method as an EOFException, and a delivery that simply stops short reaches
	   it as an early end of stream, so both are answered the same way.

	   Only the end of the connection is read as truncation, which is why the catch names
	   EOFException rather than IOException. The HTTP channel abandons a read of its own accord when
	   the message outgrows the configured size limit, and it raises a different failure to say so:
	   that request is too large rather than too short, its answer is the channel's own 413 - or the
	   mapper covering the failure where the channel leaves it to the application - and turning it
	   into a malformed-body refusal here would decide the wrong answer in the wrong place. */
	private static byte[] readWholeEntity(InputStream entityStream, long declaredLength) throws IOException {
		ByteArrayOutputStream entity = new ByteArrayOutputStream(INITIAL_ENTITY_BUFFER);
		byte[] chunk = new byte[ENTITY_CHUNK_SIZE];

		try {
			int read = entityStream.read(chunk);
			while (read != -1) {
				entity.write(chunk, 0, read);
				read = entityStream.read(chunk);
			}
		} catch (EOFException connectionEnded) {
			if (isTruncated(entity.size(), declaredLength)) {
				throw new JsonbException(JsonbExceptionMapper.MALFORMED_BODY_MESSAGE + INCOMPLETE_ENTITY);
			}

			throw connectionEnded;
		}

		if (isTruncated(entity.size(), declaredLength)) {
			throw new JsonbException(JsonbExceptionMapper.MALFORMED_BODY_MESSAGE + INCOMPLETE_ENTITY);
		}

		return entity.toByteArray();
	}

	private static boolean isTruncated(int entityLength, long declaredLength) {
		return (declaredLength != UNDECLARED_LENGTH) && (entityLength < declaredLength);
	}

	/* How many bytes the request said its entity would be, or UNDECLARED_LENGTH when there is
	   nothing to hold it to: no header, one that is not a number, or an encoded entity - whose
	   declared length counts wire bytes while the stream yields decoded ones, so the two are not
	   comparable. A longer entity than declared cannot occur, because the container stops the
	   stream at the declared length. */
	private static long declaredEntityLength(MultivaluedMap<String, String> headers) {
		if ((headers == null) || (headers.getFirst(HttpHeaders.CONTENT_ENCODING) != null)) {
			return UNDECLARED_LENGTH;
		}

		String declaredLength = headers.getFirst(HttpHeaders.CONTENT_LENGTH);
		if (declaredLength == null) {
			return UNDECLARED_LENGTH;
		}

		try {
			return Long.parseLong(declaredLength.trim());
		} catch (NumberFormatException notALength) {
			return UNDECLARED_LENGTH;
		}
	}

	/* Reads the buffered entity with JSON-P - the same parser the deserializer itself is built on,
	   so the two agree on encoding, numbers and escapes - and refuses the body when it is not one
	   whole JSON object whose declared fields each carry a single value. */
	private static void requireOneScalarValuedJsonObject(byte[] entity, Class<?> type) {
		/* An empty or whitespace-only body has no document to inspect. Left to the deserializer,
		   which answers it with the very same 400 this method raises - refusing it here would only
		   move which line logs it. */
		if (isWhitespaceOnly(entity)) {
			return;
		}

		String refusal;
		try (JsonParser parser = PARSER_FACTORY.createParser(new ByteArrayInputStream(entity))) {
			refusal = inspect(parser, scalarPropertiesOf(type));
		} catch (JsonException unreadable) {
			/* Every way the parser can fail on a caller's body arrives here - a document that ends
			   mid token, text that is not JSON at all, a raw control character inside a string, a
			   number written in a form JSON does not permit - and all of them are the one condition
			   the fixed message describes. A field-level refusal is a ValidationException, which is
			   unrelated to this type and passes through untouched. */
			refusal = UNREADABLE_DOCUMENT;
		}

		if (refusal != null) {
			throw new JsonbException(JsonbExceptionMapper.MALFORMED_BODY_MESSAGE + refusal);
		}
	}

	/* Walks the root object's members, returning why the body is unusable or null when it is fine.
	   Only the root's own members are examined: the request models are flat, so a structure nested
	   deeper belongs to a field the model does not declare and is skipped whole - which is what
	   keeps an unknown property harmless rather than fatal, the behaviour a caller that sends a
	   response entity back as a request relies on. */
	private static String inspect(JsonParser parser, Set<String> scalarProperties) {
		if (!parser.hasNext() || (parser.next() != JsonParser.Event.START_OBJECT)) {
			return NOT_AN_OBJECT_ROOT;
		}

		while (parser.hasNext()) {
			JsonParser.Event event = parser.next();
			if (event == JsonParser.Event.END_OBJECT) {
				break;
			}

			if (event != JsonParser.Event.KEY_NAME) {
				continue;
			}

			String member = parser.getString();
			JsonParser.Event value = parser.next();

			if (value == JsonParser.Event.START_ARRAY) {
				requireScalarValue(member, scalarProperties, "array");
				parser.skipArray();
			} else if (value == JsonParser.Event.START_OBJECT) {
				requireScalarValue(member, scalarProperties, "object");
				parser.skipObject();
			}
		}

		/* The document must be the whole entity. JSON-P reports anything after the root value by
		   raising on the next token, so the caller's catch turns trailing text, a second appended
		   document or a stray byte into the same refusal; whitespace after the object is the one
		   thing RFC 8259 allows there, and it reports no further token at all. */
		return parser.hasNext() ? TRAILING_CONTENT : null;
	}

	/* An array or object where the model declares one value is refused by naming the field, not with
	   the malformed-body text: the body is readable JSON and the caller's mistake is a specific
	   field of it, which is the same thing every other field rule in this service reports. Left to
	   the deserializer, quantity [1,2] would bind 2 and limitPrice [1,2,3] would fill at 3. */
	private static void requireScalarValue(String member, Set<String> scalarProperties, String shape) {
		if (scalarProperties.contains(member)) {
			throw new ValidationException(member + " must be a single value, not a JSON " + shape);
		}
	}

	private static Set<String> scalarPropertiesOf(Class<?> type) {
		return SCALAR_PROPERTIES.computeIfAbsent(type, JsonObjectRootInterceptor::readScalarProperties);
	}

	//The JSON-B property names of the model's single-valued fields, read from the setters JSON-B
	//itself binds through, under the default naming strategy that leaves a property name as it is.
	//A field whose type is a structure is not listed, so the day a request model declares one, its
	//JSON object or array is bound rather than refused.
	private static Set<String> readScalarProperties(Class<?> type) {
		Set<String> properties = new HashSet<>();

		for (Method method : type.getMethods()) {
			if (isScalarPropertySetter(method)) {
				properties.add(propertyNameOf(method.getName()));
			}
		}

		return Collections.unmodifiableSet(properties);
	}

	private static boolean isScalarPropertySetter(Method method) {
		return method.getName().startsWith(SETTER_PREFIX)
				&& (method.getName().length() > SETTER_PREFIX.length())
				&& (method.getParameterCount() == 1)
				&& Modifier.isPublic(method.getModifiers())
				&& !Modifier.isStatic(method.getModifiers())
				&& isScalarType(method.getParameterTypes()[0]);
	}

	//The Java types a single JSON value binds to: the request models hold strings and decimals
	//today, and enums, booleans, characters and primitives are the rest of that set.
	private static boolean isScalarType(Class<?> parameterType) {
		return parameterType.isPrimitive() || parameterType.isEnum()
				|| CharSequence.class.isAssignableFrom(parameterType)
				|| Number.class.isAssignableFrom(parameterType)
				|| Boolean.class.isAssignableFrom(parameterType)
				|| Character.class.isAssignableFrom(parameterType);
	}

	private static String propertyNameOf(String setterName) {
		String property = setterName.substring(SETTER_PREFIX.length());

		return Character.toLowerCase(property.charAt(0)) + property.substring(1);
	}

	private static boolean isWhitespaceOnly(byte[] entity) {
		for (byte candidate : entity) {
			if (JSON_WHITESPACE.indexOf(candidate) < 0) {
				return false;
			}
		}

		return true;
	}
}
