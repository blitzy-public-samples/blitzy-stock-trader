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

/** One clamped offset and limit, so no collection endpoint can be asked to serialize the whole estate. */
final class PageBounds {

	static final int MAX_PAGE_SIZE = 500;

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
	   caller, none of which sends either parameter today. */
	static PageBounds clamp(int offset, int limit) {
		int fromIndex = (offset < 0) ? 0 : offset;
		int pageSize = (limit <= 0 || limit > MAX_PAGE_SIZE) ? MAX_PAGE_SIZE : limit;

		return new PageBounds(fromIndex, pageSize);
	}

	int getOffset() {
		return offset;
	}

	int getLimit() {
		return limit;
	}
}
