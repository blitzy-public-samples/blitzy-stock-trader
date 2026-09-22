package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.config;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Publishes the single bounded, header-free HTTP client used for the outbound exchange-rate lookup, the rate lookup
 * itself and its {@code ExchangeRateSource} bean staying in {@code fx}.
 */
@Configuration
public class FxClientConfig {

    /**
     * Hard ceiling on the bytes accepted from the exchange-rate endpoint, enforced before anything is bound.
     *
     * <p>A timeout bounds how long a third party may keep a request thread; it does nothing about how much that
     * third party may make this process allocate. The answer is deserialized into a {@code Map} of rates, so an
     * endpoint that has been compromised, misrouted or merely replaced by an error page of another shape can drive
     * heap consumption from the outside - the remote-resource-exhaustion class of CWE-400. 64 KiB is roughly a
     * hundred times the largest legitimate answer (the whole published set is under a kilobyte, a single pair
     * under a hundred bytes), so it can only ever reject an answer that is already not the contract.
     *
     * <p>Deliberately a constant and not a property: it is a safety ceiling rather than a tuning knob, and an
     * operator raising it through relaxed binding would remove the bound at the moment it mattered. Public so
     * {@code fx/CurrencyConversionTest}, which lives in another package, asserts the limit rather than a copy
     * of it.
     */
    public static final int MAX_RESPONSE_BYTES = 64 * 1024;

    /**
     * The one {@link RestClient} bean in the application context, named so that injection by type, by name and by
     * {@code @Qualifier("fxRestClient")} all resolve to it.
     *
     * @param properties source of {@code cashaccount.fx.timeout}, the connect and read budget applied below
     * @return a client with bounded timeouts, no default headers and no base URL
     */
    @Bean
    public RestClient fxRestClient(CashAccountProperties properties) {
        // One budget for both phases, read once so the two can never drift: a connect that never completes and a
        // response that never arrives are the same outage from the caller's seat. Readiness deliberately excludes
        // the FX endpoint so a third-party outage never flaps pods, and an unbounded wait would defeat that from
        // the other side by parking request threads; bounded, the failure surfaces as 503
        // EXCHANGE_RATE_UNAVAILABLE with the balance untouched, where the legacy program ran its COMPUTE and UPDATE
        // even after the rate SELECT found no row [backend/cash-account-cobol/COBOL/CASH00.cbl:L214-L231].
        Duration timeout = properties.getFx().getTimeout();

        // The endpoint the chart ships answers a redirect - api.frankfurter.app/latest returns 301 to
        // api.frankfurter.dev/v1 - and the JDK client declines redirects unless asked, which would turn every
        // cross-currency conversion into a rate failure. NORMAL rather than ALWAYS is the security half of that
        // choice and must stay: it refuses a redirect from HTTPS down to HTTP, so the HTTPS-only endpoint
        // fx/FrankfurterExchangeRateClient enforces at start-up cannot be walked back to plaintext by a hop the
        // operator never configured.
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(timeout)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);

        // Broker propagates the caller's credential into this service
        // [backend/broker/src/main/resources/META-INF/microprofile-config.properties:L1] and the public FX provider
        // requires none, so no default header is attached and the plain builder is used rather than the
        // auto-configured one, which a customizer contributed elsewhere in the context could reach. No base URL
        // either: the chart supplies the endpoint as CURRENCY_API_URL and the client sends it as an absolute URI.
        // The one interceptor exists to take something away rather than add anything: it sets no header and reads
        // nothing of the request, so no credential can be attached here; it bounds the response before a message
        // converter sees it, which no timeout can do (see MAX_RESPONSE_BYTES).
        return RestClient.builder()
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) ->
                        new SizeBoundedResponse(execution.execute(request, body), MAX_RESPONSE_BYTES))
                .build();
    }

    // Both body framings are covered, because either alone leaves the hole open: a declared Content-Length above
    // the ceiling is refused before a single byte is read, and a body that declares nothing - chunked, or
    // delimited by connection close - is metered as it is consumed. The stream bound also catches a response that
    // declares a small length and then sends more, which is precisely what a hostile endpoint would do.
    private static final class SizeBoundedResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final long maxBytes;
        private InputStream boundedBody;

        private SizeBoundedResponse(ClientHttpResponse delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        // Cached rather than wrapped afresh on each call: Spring introspects the body for emptiness before handing
        // it to a converter, so a second wrapper would restart the count and the ceiling would apply per call
        // instead of per response.
        @Override
        public InputStream getBody() throws IOException {
            if (boundedBody == null) {
                long declared = delegate.getHeaders().getContentLength();
                if (declared > maxBytes) {
                    throw oversize(HttpHeaders.CONTENT_LENGTH + " " + declared, maxBytes);
                }
                boundedBody = new SizeBoundedBody(delegate.getBody(), maxBytes);
            }
            return boundedBody;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static final class SizeBoundedBody extends FilterInputStream {

        private final long maxBytes;
        private long consumed;
        private long markedAt;

        private SizeBoundedBody(InputStream body, long maxBytes) {
            super(body);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value != -1) {
                meter(1);
            }
            return value;
        }

        // FilterInputStream.read(byte[]) delegates to this method on `this`, so both array forms are metered here.
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = in.read(buffer, offset, length);
            if (count > 0) {
                meter(count);
            }
            return count;
        }

        @Override
        public long skip(long count) throws IOException {
            long skipped = in.skip(count);
            if (skipped > 0) {
                meter(skipped);
            }
            return skipped;
        }

        // Rewinding rewinds the meter too, so the ceiling stays a property of the response rather than of how many
        // times something peeked at it: Spring checks whether the body is empty before handing it to a converter,
        // and on a markable stream that check reads a byte and resets.
        @Override
        public synchronized void mark(int readLimit) {
            in.mark(readLimit);
            markedAt = consumed;
        }

        @Override
        public synchronized void reset() throws IOException {
            in.reset();
            consumed = markedAt;
        }

        private void meter(long count) {
            consumed += count;
            if (consumed > maxBytes) {
                throw oversize("read " + consumed + " bytes", maxBytes);
            }
        }
    }

    // A RestClientException and deliberately not an IOException, because the two are read differently one layer
    // up: fx/FrankfurterExchangeRateClient retries a ResourceAccessException - Spring's wrapper for an I/O fault,
    // the only failure a second attempt could change - and treats a RestClientException as the settled, no-retry
    // outcome it maps to 503 EXCHANGE_RATE_UNAVAILABLE. An oversize answer is settled: retrying it would read the
    // same flood twice and report it as an unreachable endpoint. Spring re-throws a RestClientException raised
    // during conversion unchanged, which is what keeps that distinction intact. The message names the limit and
    // the observed size and never the endpoint or any response content.
    private static RestClientException oversize(String observed, long maxBytes) {
        return new RestClientException("exchange-rate answer exceeds the " + maxBytes + " byte limit (" + observed
                + "); no rate was bound");
    }
}
