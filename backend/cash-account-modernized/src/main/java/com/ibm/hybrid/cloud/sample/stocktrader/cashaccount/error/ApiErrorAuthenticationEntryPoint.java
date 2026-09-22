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

/**
 * Renders an unauthenticated rejection as this service's ApiError payload, which ApiExceptionHandler cannot do
 * for it: the filter chain rejects before any controller is entered, so the controller advice never sees a 401
 * and the framework would answer with a bodiless challenge or Spring Boot's white-label page instead.
 */
@Component
public class ApiErrorAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiErrorAuthenticationEntryPoint.class);

    private static final String BEARER_CHALLENGE = "Bearer";

    // The container's ObjectMapper, never a locally constructed one: it carries JavaTimeModule, without which
    // ApiError's Instant timestamp does not serialize at all, plus the monetary settings JacksonConfig applies,
    // so this body is the same JSON shape ApiExceptionHandler writes for every other code.
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

        // authException is deliberately never read: its message - absent, expired, bad signature, wrong
        // issuer or audience - would tell a caller probing this endpoint how close a forged token came to
        // being accepted, so the payload carries only the stable code and the challenge names the scheme
        // with no realm or error parameters. The log line is DEBUG, stack-free, and never carries the
        // Authorization header value, an unauthenticated request being routine rather than an incident.
        LOGGER.debug("Rejecting unauthenticated request: {} {}", request.getMethod(), request.getRequestURI());

        response.setStatus(CashAccountErrorCode.UNAUTHORIZED.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE);

        objectMapper.writeValue(response.getOutputStream(), ApiError.of(CashAccountErrorCode.UNAUTHORIZED));
    }
}
