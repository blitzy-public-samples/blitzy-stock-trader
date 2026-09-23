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

/**
 * The 403 twin of ApiErrorAuthenticationEntryPoint, deliberately a near-exact mirror of it so the two
 * filter-chain responses cannot drift apart: the chain denies an authenticated principal before any controller
 * is entered, so the controller advice never sees the denial and Spring Boot's white-label page would make a
 * role rejection the one error arriving in a different shape.
 */
@Component
public class ApiErrorAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiErrorAccessDeniedHandler.class);

    // The container's ObjectMapper, never a locally constructed one: it carries JavaTimeModule, without which
    // ApiError's Instant timestamp does not serialize at all, plus the monetary settings JacksonConfig applies,
    // so this body is the same JSON shape ApiExceptionHandler writes for every other code.
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

        // accessDeniedException is deliberately never read, and neither is the principal's groups claim: the
        // denial detail names the authority the request lacked, telling an attacker which group to obtain, so
        // the payload carries only the stable code. The log line is DEBUG, stack-free, and records method and
        // path only - never the token or the Authorization header - a role rejection being routine.
        LOGGER.debug("Rejecting unauthorized request: {} {}", request.getMethod(), request.getRequestURI());

        response.setStatus(CashAccountErrorCode.FORBIDDEN.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        objectMapper.writeValue(response.getOutputStream(), ApiError.of(CashAccountErrorCode.FORBIDDEN));
    }
}
