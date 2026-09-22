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

//Arbitrary-precision arithmetic
import java.math.BigInteger;

//Collections
import java.util.List;

//Jakarta REST 3.1
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;

/** One clamped offset and limit, and the paged response that says how much it left behind. */
final class PageBounds {

	static final int MAX_PAGE_SIZE = 500;

	/* The metadata a page carries about the collection it was cut from. It travels in headers and
	   not in a body envelope: a collection response is a bare JSON array on every one of these
	   endpoints, and wrapping the records would change the shape every caller, every integration
	   test and every documented example already reads. X-Total-Count is the count of the whole
	   filtered collection, so a caller holding a full page can tell a collection that ends there
	   from one that was truncated, and Link carries the offsets to walk rather than leaving the
	   caller to compute them. X-Page-Limit reports the limit that was *applied*, which is the one
	   number a traversal has to step by: a caller asking for 1000 is served 500, and a walk
	   stepping by what it asked for would skip half the collection. */
	static final String TOTAL_COUNT_HEADER = "X-Total-Count";
	static final String PAGE_OFFSET_HEADER = "X-Page-Offset";
	static final String PAGE_LIMIT_HEADER = "X-Page-Limit";
	static final String LINK_HEADER = "Link";

	private static final String OFFSET_PARAMETER = "offset";
	private static final String LIMIT_PARAMETER = "limit";

	private static final BigInteger MAX_OFFSET = BigInteger.valueOf(Integer.MAX_VALUE);

	private final int offset;
	private final int limit;

	private PageBounds(int offset, int limit) {
		this.offset = offset;
		this.limit = limit;
	}

	/* Clamped rather than refused with a 400: an out-of-range page is answered with the nearest
	   page that exists, so a caller that sends nothing, sends zero or asks for more than the
	   service will serialize still gets a well-formed first page. Refusing those would turn a
	   bound introduced for the service's own protection into a contract change for every existing
	   caller, none of which sends either parameter today.
	   The parameters arrive as strings, and that is the whole point: bound as int they would be
	   converted by the JAX-RS runtime, which answers a conversion failure with a bodyless 404 -
	   a status that says the collection does not exist and a response that carries none of this
	   service's {status, error, message, path} envelope. Parsing here keeps the clamp policy in
	   force for malformed input too, which is the one behaviour a page bound can offer that a
	   filter value cannot: there is a nearest page to serve, whereas an uninterpretable filter has
	   no honest reading and is refused as a 400 by the resource that declares it. */
	static PageBounds clamp(String offset, String limit) {
		int requestedOffset = parse(offset, 0);
		int requestedLimit = parse(limit, 0);

		int fromIndex = Math.max(requestedOffset, 0);
		int pageSize = (requestedLimit <= 0 || requestedLimit > MAX_PAGE_SIZE) ? MAX_PAGE_SIZE : requestedLimit;

		return new PageBounds(fromIndex, pageSize);
	}

	/* Anything that is not a whole number reads as "not supplied" and takes the default, while a
	   whole number too large for an int saturates at Integer.MAX_VALUE instead of falling back.
	   The distinction matters for offset: an offset past the end must answer with an empty page,
	   and defaulting a 30-digit offset to 0 would instead serve the first page to a caller who
	   asked for neither. BigInteger rather than Long.parseLong for the same reason - a digit
	   string longer than a long is still a whole number, and still means "past the end". */
	private static int parse(String value, int fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}

		try {
			BigInteger parsed = new BigInteger(value.strip());
			return (parsed.compareTo(MAX_OFFSET) > 0) ? Integer.MAX_VALUE : parsed.max(BigInteger.ZERO).intValue();
		} catch (NumberFormatException notAWholeNumber) {
			return fallback;
		}
	}

	int getOffset() {
		return offset;
	}

	int getLimit() {
		return limit;
	}

	/* The one place a paged collection is turned into a response, so every paged endpoint reports
	   its truncation the same way. The records are handed back as the bare array they have always
	   been; total is the size of the whole filtered collection, which is what makes the truncation
	   visible and the traversal terminable. */
	Response pagedResponse(List<?> records, int total, UriInfo uriInfo) {
		Response.ResponseBuilder response = Response.ok(records)
				.header(TOTAL_COUNT_HEADER, total)
				.header(PAGE_OFFSET_HEADER, offset)
				.header(PAGE_LIMIT_HEADER, limit);

		String links = links(total, uriInfo);
		if (!links.isEmpty()) {
			response.header(LINK_HEADER, links);
		}

		return response.build();
	}

	/* RFC 8288 link relations for the page, built from the request URI so every other query
	   parameter - status, owner, entityType, entityId - survives into the links and a walk stays
	   inside the filter it started in. first and last are always offered because they always
	   exist; prev and next only when there is a page on that side, so their absence is itself the
	   answer to "is there more" and a caller need not compare counts to find out. */
	private String links(int total, UriInfo uriInfo) {
		StringBuilder links = new StringBuilder();
		int lastOffset = (total <= 0) ? 0 : ((total - 1) / limit) * limit;

		append(links, uriInfo, 0, "first");
		if (offset > 0) {
			append(links, uriInfo, Math.max(offset - limit, 0), "prev");
		}
		//A next page exists only when records remain beyond this one, which is also false for
		//every offset at or past the end - those pages are empty and have nothing after them.
		if ((long) offset + limit < total) {
			append(links, uriInfo, offset + limit, "next");
		}
		append(links, uriInfo, lastOffset, "last");

		return links.toString();
	}

	private void append(StringBuilder links, UriInfo uriInfo, int linkOffset, String relation) {
		UriBuilder target = uriInfo.getRequestUriBuilder()
				.replaceQueryParam(OFFSET_PARAMETER, linkOffset)
				.replaceQueryParam(LIMIT_PARAMETER, limit);

		if (links.length() > 0) {
			links.append(", ");
		}
		links.append('<').append(target.build()).append(">; rel=\"").append(relation).append('"');
	}
}
