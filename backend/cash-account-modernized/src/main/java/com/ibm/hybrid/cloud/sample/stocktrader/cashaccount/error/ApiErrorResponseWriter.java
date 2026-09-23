package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.security.web.header.writers.CacheControlHeadersWriter;
import org.springframework.security.web.header.writers.XContentTypeOptionsHeaderWriter;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Renders an ApiError onto a raw servlet response, for the rejections decided before or instead of a handler. */
@Component
public class ApiErrorResponseWriter {

    // Spring Security's own writers rather than literal header names, so these responses carry exactly what the
    // filter chain writes on every other response and cannot drift from it when the chain's defaults change.
    // They have to be applied here at all because HeaderWriterFilter is a OncePerRequestFilter that does not
    // override shouldNotFilterErrorDispatch, so it is skipped on an ERROR dispatch - which is why a container
    // rejection carried no X-Content-Type-Options, X-Frame-Options or Cache-Control even when Spring Boot's own
    // error controller answered it. Strict-Transport-Security is deliberately absent: the chain's HstsHeaderWriter
    // writes it only on a secure request, and the chart terminates no TLS on port 8080
    // (infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L199-L201).
    private static final List<HeaderWriter> SECURITY_HEADER_WRITERS = List.of(
            new XContentTypeOptionsHeaderWriter(),
            new XXssProtectionHeaderWriter(),
            new CacheControlHeadersWriter(),
            new XFrameOptionsHeaderWriter());

    // What the caller is told when the container refused the request line or a header. The code's own wording
    // names query parameters, which is true of the ledger query that raises INVALID_QUERY from a controller and
    // untrue here, and the rejected value is never quoted: it is attacker-chosen text, and a rejection is also
    // the one response a caller can provoke at will (CWE-117 at the log sink, probing feedback at the wire).
    private static final String MALFORMED_REQUEST =
            "The request could not be read; its request line or one of its headers carries a value this service"
                    + " cannot accept.";

    private final ObjectMapper objectMapper;

    /**
     * Container constructor.
     *
     * @param objectMapper the context's mapper, carrying JavaTimeModule and the monetary settings
     *     config/JacksonConfig applies, so this body is byte-for-byte the shape error/ApiExceptionHandler writes
     */
    public ApiErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Reads the condition a status chosen by the container or the servlet layer stands for.
     *
     * <p>The returned code's own status is what the response then carries, so the code-to-status binding
     * {@link CashAccountErrorCode} exists to hold stays true on the wire: a status this service has no code for
     * -- {@code 414}, {@code 431} and their kin -- is answered as the malformed request it is rather than as a
     * status carrying some other condition's code.
     *
     * @param status the status the container or servlet layer set
     * @return the condition to report, never null
     */
    public ApiError errorForStatus(int status) {
        CashAccountErrorCode code = codeForStatus(status);
        return code == CashAccountErrorCode.INVALID_QUERY
                ? ApiError.of(code, MALFORMED_REQUEST)
                : ApiError.of(code);
    }

    /**
     * Writes the whole response: status, headers and body.
     *
     * @param request the request being refused, read only by the header writers
     * @param response the response to write
     * @param error the condition to report
     * @throws IOException when the body cannot be written to the connection
     */
    public void write(HttpServletRequest request, HttpServletResponse response, ApiError error)
            throws IOException {

        byte[] body = prepare(request, response, error).getBytes(StandardCharsets.UTF_8);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }

    /**
     * Sets the status, the headers and the content type, and hands back the body for a caller that must write it
     * through a stream of its own -- the Tomcat valve, which writes through the container's error reporter
     * because at that point there is no servlet response to stream through.
     *
     * @param request the request being refused, read only by the header writers
     * @param response the response to prepare
     * @param error the condition to report
     * @return the JSON body to write
     */
    public String prepare(HttpServletRequest request, HttpServletResponse response, ApiError error) {
        CashAccountErrorCode code = error.code();

        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (code.hasRetryAfter()) {
            response.setHeader(HttpHeaders.RETRY_AFTER, code.retryAfterSeconds().toString());
        }
        for (HeaderWriter writer : SECURITY_HEADER_WRITERS) {
            writer.writeHeaders(request, response);
        }
        return serialize(error);
    }

    // An ApiError is a record of a code, a sentence and an instant, and the mapper is the context's own, so a
    // failure here means the mapper has been reconfigured into one that cannot write the only payload this
    // service has. That is a start-up defect to be read in a stack trace, not a condition to absorb while
    // rendering a rejection, and every caller of this method is inside a path that already answers 500 for an
    // unexpected failure.
    private String serialize(ApiError error) {
        try {
            return objectMapper.writeValueAsString(error);
        } catch (JsonProcessingException unwritable) {
            throw new IllegalStateException("The ApiError payload could not be serialized", unwritable);
        }
    }

    // The table is deliberately short: only the statuses a container-level or filter-level rejection can actually
    // set, each mapped to the condition that is true of it. Everything else collapses by class - a client error
    // this service has no code for is a malformed request, and anything else is INTERNAL - because inventing a
    // code per status would put conditions in the vocabulary that no code path raises.
    private static CashAccountErrorCode codeForStatus(int status) {
        return switch (status) {
            case 401 -> CashAccountErrorCode.UNAUTHORIZED;
            case 403 -> CashAccountErrorCode.FORBIDDEN;
            case 404 -> CashAccountErrorCode.UNSUPPORTED_PATH;
            case 405 -> CashAccountErrorCode.UNSUPPORTED_METHOD;
            case 406 -> CashAccountErrorCode.NOT_ACCEPTABLE;
            case 413 -> CashAccountErrorCode.REQUEST_TOO_LARGE;
            case 415 -> CashAccountErrorCode.UNSUPPORTED_MEDIA_TYPE;
            // Compared numerically rather than through HttpStatus.valueOf, which throws for a status outside the
            // enum - and throwing while rendering a rejection would replace it with the 500 this method exists
            // to keep away from a caller's fault.
            default -> status >= HttpStatus.BAD_REQUEST.value() && status < HttpStatus.INTERNAL_SERVER_ERROR.value()
                    ? CashAccountErrorCode.INVALID_QUERY
                    : CashAccountErrorCode.INTERNAL;
        };
    }
}
