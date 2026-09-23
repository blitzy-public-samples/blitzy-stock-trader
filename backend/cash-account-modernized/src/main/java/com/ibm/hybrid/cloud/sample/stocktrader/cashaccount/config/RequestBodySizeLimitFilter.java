package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.ApiError;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.RequestBodyTooLargeException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Refuses a request body larger than {@code server.max-request-body-size} with 413, before it is parsed. */
@Component
// Ordered after the security filter chain (SecurityProperties.DEFAULT_FILTER_ORDER, -100) so the counter sits
// inside the authenticated flow: an unauthenticated oversized request is already refused by
// ApiErrorAuthenticationEntryPoint without the body being read, the authorization decision stays first, and
// config/SecurityConfig's matcher order stays free of a transport concern. Nothing else in this stack bounds a
// JSON body - Tomcat's maxPostSize applies to form encodings only and Jackson materializes whatever arrives
// before any Bean Validation constraint runs - so one large body per connection buys heap and CPU in a 2Gi pod
// (.../templates/cash-account.yaml:L224-L232) for the cost of sending bytes (CWE-400). The container still
// accepts the bytes on the wire, so an ingress-level cap remains the outer control (AAP 0.3.4 bars a chart edit);
// server.tomcat.max-swallow-size bounds how much of a refused body Tomcat drains, this filter how much is read.
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 1)
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

    /** The configured cap; a {@code server.*} key because it bounds the server's intake, not the ledger's rules. */
    public static final String LIMIT_PROPERTY = "server.max-request-body-size";

    /*
     * The largest body this service defines is the retail {owner, balance, currency} payload and the
     * institutional hold, whose order reference is capped at 64 characters and whose owner is capped at 32 - a
     * few hundred bytes with every field at its maximum. 8 KB is therefore ample headroom for whitespace and for
     * unknown members a future caller may send, while being small enough that a refused body costs nothing. It
     * is the fallback only: application.yml declares the same value as the property above, so an operator can
     * change it without a rebuild.
     */
    static final DataSize DEFAULT_LIMIT = DataSize.ofKilobytes(8);

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestBodySizeLimitFilter.class);

    private final long limitBytes;

    private final ObjectMapper objectMapper;

    /**
     * @param environment source of {@value #LIMIT_PROPERTY}, read through {@link Binder} rather than injected
     *     with {@code @Value}: a placeholder's resolved value is then evaluated as an expression, which turns
     *     write access to a ConfigMap into code execution, so no externally supplied value in this module is
     *     bound that way
     * @param objectMapper the container's mapper, so the 413 body is byte-for-byte the ApiError shape every
     *     other error of this service uses - including the JavaTimeModule its Instant timestamp needs
     */
    public RequestBodySizeLimitFilter(Environment environment, ObjectMapper objectMapper) {
        this.limitBytes = resolveLimit(environment);
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // Enforced twice, in two ways, because either alone leaves the hole open: a declared length is refused
        // before a byte is read, while a chunked body declares no length (getContentLengthLong() answers -1) and
        // can only be bounded by counting what is actually read and failing the read, which the wrapper below does.
        if (request.getContentLengthLong() > limitBytes) {
            // Only the declared length and the limit are logged. The body is never read, so there is nothing of
            // the caller's content to leak into a record, and that is the point of refusing here.
            LOGGER.debug("Rejecting request: declared body of {} bytes exceeds the {}-byte limit",
                    request.getContentLengthLong(), limitBytes);
            reject(response);
            return;
        }

        // Every request is wrapped, including those that carry no body: a wrapper whose stream nobody reads
        // counts nothing, and a method-based exemption would have to decide which verbs may carry a body - a
        // judgement HTTP does not support, since a body is legal on any of them.
        chain.doFilter(new BoundedBodyRequest(request, limitBytes), response);
    }

    /*
     * Written straight to the response for the same reason error/ApiErrorAuthenticationEntryPoint writes a 401
     * there: a rejection decided inside the filter chain never reaches @RestControllerAdvice, and the caller
     * must still receive the one ApiError shape rather than the container's HTML error page. The status comes
     * from CashAccountErrorCode, never from a literal here, so this class chooses no status of its own.
     */
    private void reject(HttpServletResponse response) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(CashAccountErrorCode.REQUEST_TOO_LARGE.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ApiError.of(CashAccountErrorCode.REQUEST_TOO_LARGE));
    }

    // DataSize so the property reads as 8KB rather than as a digit count, matching every other size key under
    // server.*. An unparsable or absent value falls back to the compiled-in default instead of failing start-up:
    // a pod that refuses to start over a malformed size knob is a worse outcome than one enforcing the shipped
    // limit, and the value is logged so the effective cap is never a guess.
    private static long resolveLimit(Environment environment) {
        DataSize configured = Binder.get(environment)
                .bind(LIMIT_PROPERTY, Bindable.of(DataSize.class))
                .orElse(DEFAULT_LIMIT);
        long bytes = configured.toBytes() > 0 ? configured.toBytes() : DEFAULT_LIMIT.toBytes();
        LOGGER.info("Request bodies are limited to {} bytes ({}).", bytes, LIMIT_PROPERTY);
        return bytes;
    }

    /*
     * The wrapper exists only to hand out a counting stream. Both accessors are overridden because the servlet
     * contract offers two ways to read a body and a control that covers one of them is not a control: Spring's
     * Jackson converter takes getInputStream(), while a String or form-mapped payload would take getReader().
     */
    private static final class BoundedBodyRequest extends HttpServletRequestWrapper {

        private final long limitBytes;

        private ServletInputStream stream;

        private BufferedReader reader;

        private BoundedBodyRequest(HttpServletRequest request, long limitBytes) {
            super(request);
            this.limitBytes = limitBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new CountingServletInputStream(super.getInputStream(), limitBytes);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                reader = new BufferedReader(new InputStreamReader(getInputStream(), requestCharset()));
            }
            return reader;
        }

        // A declared charset the JVM does not know must not become an exception from a size control: the request
        // is already malformed and the body still has to be bounded, so the bytes are decoded as UTF-8 - the
        // charset this JSON API's callers use - and the container's own parsing reports the bad declaration.
        private Charset requestCharset() {
            String declared = getCharacterEncoding();
            if (declared == null || declared.isBlank()) {
                return StandardCharsets.UTF_8;
            }
            try {
                return Charset.forName(declared);
            } catch (IllegalArgumentException unsupported) {
                return StandardCharsets.UTF_8;
            }
        }
    }

    /*
     * Counting happens AFTER each delegated read returns, so the limit is judged on bytes that have actually been
     * handed to the parser and the first read past the cap fails. The check cannot be skipped by reading in one
     * large block: the array overload is what Jackson uses, and it is counted here as well as the single-byte
     * overload that readLine() and InputStreamReader fall back to.
     */
    private static final class CountingServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;

        private final long limitBytes;

        private long consumed;

        private CountingServletInputStream(ServletInputStream delegate, long limitBytes) {
            this.delegate = delegate;
            this.limitBytes = limitBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                consumed++;
                requireWithinLimit();
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                consumed += read;
                requireWithinLimit();
            }
            return read;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        private void requireWithinLimit() throws RequestBodyTooLargeException {
            if (consumed > limitBytes) {
                throw new RequestBodyTooLargeException(limitBytes);
            }
        }
    }
}
