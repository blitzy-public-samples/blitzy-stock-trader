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

//JSON Binding 3.0 - the same provider the exception mappers serialize ErrorResponse with
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

//Servlet 6.0
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

//Jakarta REST 3.1 - for the reason phrases the exception mappers already use
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;


/** Renders the service's JSON error envelope for the statuses the container, not the application, decides. */
public class ContainerErrorServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	private static final Logger logger = Logger.getLogger(ContainerErrorServlet.class.getName());

	//The Servlet 6.0 error-dispatch attributes. Only these two are read: error.message and
	//error.exception carry the container's own description of the failure, which is exactly
	//the text that names internal runtime classes and line numbers, so neither is relayed.
	private static final String ERROR_STATUS_CODE = "jakarta.servlet.error.status_code";
	private static final String ERROR_REQUEST_URI = "jakarta.servlet.error.request_uri";

	private static final String JSON_UTF8 = "application/json; charset=UTF-8";
	private static final String UTF8 = "UTF-8";
	private static final String HEAD = "HEAD";

	//One message per status the web.xml error-page list maps, fixed here rather than taken from
	//the request: a client learns what to change without the service disclosing how it failed.
	private static final String BAD_REQUEST_MESSAGE =
		"The request could not be read; check the request line, the URI encoding and the headers";
	private static final String ENCODED_SEPARATOR_MESSAGE =
		"The request URI contains an encoded path separator, which this service does not accept";
	private static final String NOT_FOUND_MESSAGE = "No resource exists at this path";
	private static final String METHOD_NOT_ALLOWED_MESSAGE =
		"The HTTP method is not supported for this path";
	private static final String NOT_ACCEPTABLE_MESSAGE =
		"No acceptable representation is available; this service produces application/json";
	private static final String UNSUPPORTED_MEDIA_TYPE_MESSAGE =
		"The request media type is not supported; this service consumes application/json";
	private static final String INTERNAL_ERROR_MESSAGE =
		"The request could not be completed; the failure is recorded in the service log";
	private static final String GENERIC_MESSAGE = "The request could not be completed";

	private static final String GENERIC_ERROR = "Error";

	//Encoded '/' and '\' are refused by the web container while it resolves the URI, before any
	//application code runs, so this servlet is the first place able to explain the refusal.
	private static final String ENCODED_SLASH = "%2f";
	private static final String ENCODED_BACKSLASH = "%5c";

	//Emitted only if serialization itself fails, which would otherwise escape as a second error
	//and return the container's HTML page - the very thing this servlet exists to prevent.
	private static final String FALLBACK_BODY =
		"{\"error\":\"Internal Server Error\",\"message\":\"" + INTERNAL_ERROR_MESSAGE
			+ "\",\"path\":\"/\",\"status\":500}";

	//transient because HttpServlet is Serializable and a Jsonb instance is not; the field is
	//rebuilt by init() in any container that recreates the servlet.
	private transient Jsonb jsonb;

	@Override
	public void init() throws ServletException {
		try {
			jsonb = JsonbBuilder.create();
		} catch (RuntimeException e) {
			throw new ServletException("The JSON provider for the container error surface could not be created", e);
		}
	}

	@Override
	public void destroy() {
		Jsonb current = jsonb;
		jsonb = null;
		if (current != null) {
			try {
				current.close();
			} catch (Exception e) {
				//Shutdown-time only, and the server is going away regardless, so this is recorded
				//rather than propagated: throwing here would replace a clean stop with a failure.
				logger.log(Level.FINE, "Closing the JSON provider for the container error surface failed", e);
			}
		}
	}

	/* service(), not doGet()/doPost(): an ERROR dispatch arrives with the method of the request
	   that failed - which for this service includes the verbs HttpServlet answers with its own 405
	   - and every one of them must render the same envelope as the failure it describes. */
	@Override
	protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
		int status = statusOf(request);
		String path = pathOf(request);

		response.setStatus(status);
		response.setContentType(JSON_UTF8);
		response.setCharacterEncoding(UTF8);

		//A HEAD response carries the headers of the GET it stands for and no body, so the envelope
		//is built for its status and Content-Type and then deliberately not written.
		if (HEAD.equalsIgnoreCase(request.getMethod())) {
			return;
		}

		PrintWriter writer = response.getWriter();
		writer.write(render(status, path));
		writer.flush();
	}

	//Serializing ErrorResponse through JSON-B rather than assembling the JSON by hand is what keeps
	//this body identical - field names, ordering and escaping - to the one the exception mappers
	//return for an application-level 400, 404 or 409.
	private String render(int status, String path) {
		ErrorResponse body = new ErrorResponse(status, reasonPhraseOf(status), messageFor(status, path), path);
		Jsonb provider = jsonb;

		if (provider != null) {
			try {
				return provider.toJson(body);
			} catch (RuntimeException e) {
				logger.log(Level.WARNING, "The container error envelope could not be serialized", e);
			}
		}

		return FALLBACK_BODY;
	}

	//An error dispatch always carries a status; a request sent straight to this path carries none,
	//and answers 404 because the error surface holds no resource of its own to serve.
	private static int statusOf(HttpServletRequest request) {
		Object status = request.getAttribute(ERROR_STATUS_CODE);

		if (status instanceof Integer) {
			int code = (Integer) status;
			if (code >= 400) {
				return code;
			}
		}

		return Response.Status.NOT_FOUND.getStatusCode();
	}

	/* The path is reported relative to the context root, matching the path the exception mappers
	   take from UriInfo, so one client-side rule locates the failure whichever layer refused it.
	   It is read from the error attribute rather than from getPathInfo() because the URI that
	   triggered this dispatch may be one the container could not decode. */
	private static String pathOf(HttpServletRequest request) {
		Object attribute = request.getAttribute(ERROR_REQUEST_URI);
		String uri = (attribute instanceof String) ? (String) attribute : request.getRequestURI();

		if (uri == null) {
			return "/";
		}

		String context = request.getContextPath();
		if ((context != null) && !context.isEmpty() && uri.startsWith(context)) {
			uri = uri.substring(context.length());
		}

		return uri.isEmpty() ? "/" : uri;
	}

	//Package-visible, and called by WebApplicationExceptionMapper: the container and the Jakarta
	//REST runtime refuse a request in different places but answer the same clients, so the wording
	//of an error surface with two entrances is written once here rather than kept in step by hand.
	static String reasonPhraseOf(int status) {
		Response.Status known = Response.Status.fromStatusCode(status);
		return (known == null) ? GENERIC_ERROR : known.getReasonPhrase();
	}

	static String messageFor(int status, String path) {
		if (status == Response.Status.BAD_REQUEST.getStatusCode()) {
			return hasEncodedSeparator(path) ? ENCODED_SEPARATOR_MESSAGE : BAD_REQUEST_MESSAGE;
		}
		if (status == Response.Status.NOT_FOUND.getStatusCode()) {
			return NOT_FOUND_MESSAGE;
		}
		if (status == Response.Status.METHOD_NOT_ALLOWED.getStatusCode()) {
			return METHOD_NOT_ALLOWED_MESSAGE;
		}
		if (status == Response.Status.NOT_ACCEPTABLE.getStatusCode()) {
			return NOT_ACCEPTABLE_MESSAGE;
		}
		if (status == Response.Status.UNSUPPORTED_MEDIA_TYPE.getStatusCode()) {
			return UNSUPPORTED_MEDIA_TYPE_MESSAGE;
		}
		if (status == Response.Status.INTERNAL_SERVER_ERROR.getStatusCode()) {
			return INTERNAL_ERROR_MESSAGE;
		}

		return GENERIC_MESSAGE;
	}

	private static boolean hasEncodedSeparator(String path) {
		String lowered = path.toLowerCase(Locale.ROOT);
		return lowered.contains(ENCODED_SLASH) || lowered.contains(ENCODED_BACKSLASH);
	}
}
