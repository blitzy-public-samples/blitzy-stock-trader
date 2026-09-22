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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

// A 401 is the one condition this service cannot report through the class that reports every other one:
// Spring Security rejects an unauthenticated request inside the filter chain, before any controller is
// entered, so error/ApiExceptionHandler's @RestControllerAdvice never sees it. Left to the framework the
// response would be a bodiless resource-server challenge, or Spring Boot's white-label
// {"timestamp","status","error","path"} page, and a caller would then have to parse one structure for an
// authentication failure and another for everything else. That is precisely the ambiguity the program
// being replaced imposed on its callers, where every outcome - success, not-found, duplicate, deadlock -
// arrived as unsigned digits in a single X(10) return field [backend/cash-account-cobol/COBOL/CASH00.cbl:
// L104]. This entry point closes it by rendering the rejection as the module's one ApiError payload.
/** Renders an unauthenticated rejection from the security filter chain as this service's ApiError payload. */
@Component
public class ApiErrorAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiErrorAuthenticationEntryPoint.class);

    private static final String BEARER_CHALLENGE = "Bearer";

    // The container's ObjectMapper, never a locally constructed one. This bean carries the modules Spring
    // Boot registers - JavaTimeModule among them, without which ApiError's java.time.Instant timestamp
    // does not serialize at all - together with the monetary settings config/JacksonConfig applies, so the
    // body written here is the same JSON shape ApiExceptionHandler writes for every other error code.
    private final ObjectMapper objectMapper;

    public ApiErrorAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {

        if (response.isCommitted()) {
            return;
        }

        // authException is deliberately never read. Its message states why the credential was refused -
        // absent, expired, bad signature, wrong issuer or audience - which tells a caller probing this
        // endpoint how close a forged token came to being accepted. The payload therefore carries only the
        // stable UNAUTHORIZED code and its fixed message, the challenge names the scheme with no realm and
        // no error parameters, and the log line is DEBUG and stack-free because an unauthenticated request
        // is a routine event rather than an incident. The Authorization header value is never logged.
        LOGGER.debug("Rejecting unauthenticated request: {} {}", request.getMethod(), request.getRequestURI());

        response.setStatus(CashAccountErrorCode.UNAUTHORIZED.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE);

        objectMapper.writeValue(response.getOutputStream(), ApiError.of(CashAccountErrorCode.UNAUTHORIZED));
    }
}
