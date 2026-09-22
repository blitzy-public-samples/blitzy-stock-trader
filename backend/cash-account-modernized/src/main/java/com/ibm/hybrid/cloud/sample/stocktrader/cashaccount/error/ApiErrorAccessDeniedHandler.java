/*
       Copyright 2025 Kyndryl, All Rights Reserved

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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

// The 403 twin of ApiErrorAuthenticationEntryPoint, and it exists for the same structural reason: Spring
// Security decides an authenticated principal lacks the required role inside the filter chain, before any
// controller is entered, so error/ApiExceptionHandler's @RestControllerAdvice never sees the denial. Left to
// the framework the response would be Spring Boot's white-label {"timestamp","status","error","path"} page,
// which would make a role rejection the one error in this service arriving in a different shape from every
// other one. Deliberately a near-exact mirror of the entry point - same committed-response guard, same
// content type, character encoding and writer - so the two filter-chain responses cannot drift apart.
/** Renders an authorization denial from the security filter chain as this service's ApiError payload. */
@Component
public class ApiErrorAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiErrorAccessDeniedHandler.class);

    // The container's ObjectMapper, never a locally constructed one. This bean carries the modules Spring
    // Boot registers - JavaTimeModule among them, without which ApiError's java.time.Instant timestamp does
    // not serialize at all - together with the monetary settings config/JacksonConfig applies, so the body
    // written here is the same JSON shape ApiExceptionHandler writes for every other error code.
    private final ObjectMapper objectMapper;

    public ApiErrorAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {

        if (response.isCommitted()) {
            return;
        }

        // accessDeniedException is deliberately never read, and neither is the principal's groups claim. The
        // denial detail names the authority the request lacked, which is precisely the escalation hint a
        // caller probing this service wants: told that StockTrader is missing, an attacker knows which group
        // to obtain. The payload therefore carries only the stable FORBIDDEN code and its fixed message. The
        // log line is DEBUG and stack-free because a role rejection is a routine authorization outcome, not
        // an incident, and it records the method and path only - never the token or the Authorization header.
        LOGGER.debug("Rejecting unauthorized request: {} {}", request.getMethod(), request.getRequestURI());

        response.setStatus(CashAccountErrorCode.FORBIDDEN.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        objectMapper.writeValue(response.getOutputStream(), ApiError.of(CashAccountErrorCode.FORBIDDEN));
    }
}
