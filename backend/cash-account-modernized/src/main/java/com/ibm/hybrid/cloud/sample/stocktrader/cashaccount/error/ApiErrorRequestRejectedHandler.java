package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Renders a request the firewall refuses before the chain runs as this service's ApiError payload. */
@Component
public class ApiErrorRequestRejectedHandler implements RequestRejectedHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiErrorRequestRejectedHandler.class);

    // The condition stated as what it is. The firewall refuses the request LINE - a path holding "//", a matrix
    // parameter, an encoded path separator, a control character, or a method outside its allowed set - so the
    // sentence names the request line and never quotes the rejected value, which is attacker-chosen text.
    private static final String MESSAGE =
            "The request line carries a path or method this service cannot accept.";

    private final ApiErrorResponseWriter writer;

    /**
     * Container constructor.
     *
     * @param writer the shared renderer, which also supplies the security headers the chain's own
     *     HeaderWriterFilter cannot write here because the chain never ran
     */
    public ApiErrorRequestRejectedHandler(ApiErrorResponseWriter writer) {
        this.writer = writer;
    }

    /**
     * Answers the refused request with {@code 400 INVALID_QUERY}.
     *
     * <p>Spring Security's default handler calls {@code sendError(400)} instead of writing a body, and the
     * consequences were neither a 400 nor a body: the container turned that into an ERROR dispatch to
     * {@code /error}, the filter chain ran again on it, {@code BearerTokenAuthenticationFilter} was skipped
     * because {@code OncePerRequestFilter} does not filter error dispatches, and so an authenticated caller was
     * anonymous by the time {@code anyRequest().denyAll()} saw the dispatch -- which answered
     * {@code 401 UNAUTHORIZED}. A proxy emitting a double slash was therefore diagnosed as an authentication
     * failure, and so was an unauthenticated probe of {@code /actuator/health;x=y}, on a path that requires no
     * authentication at all. Writing the response here ends the request with no {@code sendError}, so no
     * dispatch occurs and the status, the code and the headers are the ones this service chose.
     *
     * <p>The code is {@code INVALID_QUERY} for every refusal, including a method outside the firewall's allowed
     * set: {@code UNSUPPORTED_METHOD} would promise an {@code Allow} header naming what the path does accept,
     * and the refusal happens before any routing, so no accurate list exists to name. It is also the code
     * {@code ApiExceptionHandler} already answers a header-level {@code RequestRejectedException} with, so one
     * firewall refusal carries one code wherever the firewall happens to raise it.
     *
     * @param request the refused request, read only for the log line and the header writers
     * @param response the response to write
     * @param rejection the firewall's refusal, whose message is logged at DEBUG and never sent
     * @throws IOException when the body cannot be written to the connection
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            RequestRejectedException rejection) throws IOException {

        if (response.isCommitted()) {
            return;
        }

        // DEBUG and encoded: a refused request line is routine (scanners, mis-configured proxies), and both the
        // firewall's message and the request's own path embed the value the caller chose, so an unencoded
        // interpolation would let any caller forge log structure (CWE-117).
        LOGGER.debug("Rejecting request: refused by the request firewall - {} {} - {}",
                LogSafeText.of(request.getMethod()), LogSafeText.ofMessage(request.getRequestURI()),
                LogSafeText.ofMessage(rejection.getMessage()));

        writer.write(request, response, ApiError.of(CashAccountErrorCode.INVALID_QUERY, MESSAGE));
    }
}
