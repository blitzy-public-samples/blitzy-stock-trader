package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(FxClientConfig.class);

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

    // Not a @Bean, and that is a wiring constraint rather than a style choice: this application runs
    // @EnableScheduling for the reservation expiry sweep, and Spring's ScheduledAnnotationBeanPostProcessor adopts
    // a context ScheduledExecutorService when no TaskScheduler is defined, which would move that sweep onto this
    // one thread. Static also keeps `new FxClientConfig().fxRestClient(properties)` - how fx/CurrencyConversionTest
    // builds the real bean - working without a container. One daemon thread is enough and holds no JVM open: each
    // task is a single non-blocking close of a response body stream, armed only for the duration of one body read
    // and cancelled the moment that read returns, with setRemoveOnCancelPolicy so cancelled tasks do not
    // accumulate in the queue between arrivals.
    private static final ScheduledExecutorService DEADLINE_WATCHDOG = newDeadlineWatchdog();

    /**
     * The one {@link RestClient} bean in the application context, named so that injection by type, by name and by
     * {@code @Qualifier("fxRestClient")} all resolve to it.
     *
     * @param properties source of {@code cashaccount.fx.timeout}, the budget for the whole exchange applied below
     * @return a client with a bounded exchange deadline and response size, no default headers and no base URL
     */
    @Bean
    public RestClient fxRestClient(CashAccountProperties properties) {
        // One budget for every phase, read once so they can never drift: a connect that never completes, a
        // response that never starts and an answer that starts and then stops are the same outage from the
        // caller's seat. Readiness deliberately excludes the FX endpoint so a third-party outage never flaps pods,
        // and an unbounded wait would defeat that from the other side by parking request threads; bounded, the
        // failure surfaces as 503 EXCHANGE_RATE_UNAVAILABLE with the balance untouched, where the legacy program
        // ran its COMPUTE and UPDATE even after the rate SELECT found no row
        // [backend/cash-account-cobol/COBOL/CASH00.cbl:L214-L231].
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

        // Bounds the header phase only, and is kept for exactly that: Spring's JdkClientHttpRequestFactory reads
        // the answer with HttpResponse.BodyHandlers.ofInputStream and waits out this timeout on the resulting
        // future, which the JDK completes as soon as the response HEADERS arrive. Everything after that point is
        // bounded by the deadline in the interceptor below instead.
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);

        // Broker propagates the caller's credential into this service
        // [backend/broker/src/main/resources/META-INF/microprofile-config.properties:L1] and the public FX provider
        // requires none, so no default header is attached and the plain builder is used rather than the
        // auto-configured one, which a customizer contributed elsewhere in the context could reach. No base URL
        // either: the chart supplies the endpoint as CURRENCY_API_URL and the client sends it as an absolute URI.
        // The one interceptor exists to take something away rather than add anything: it sets no header and reads
        // nothing of the request, and it bounds the answer in both dimensions no timeout on this transport can -
        // its total time (see boundedAnswer) and its size (see MAX_RESPONSE_BYTES) - before a message converter
        // sees it.
        return RestClient.builder()
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    // Taken before the exchange starts, so the one configured budget covers connect, headers and
                    // body reception together for this attempt rather than each phase separately: the deadline is
                    // what the caller of a money operation actually experiences, and fx/FrankfurterExchangeRateClient
                    // makes at most two attempts, so the worst case it can impose is twice this value.
                    long deadlineNanos = System.nanoTime() + timeout.toNanos();
                    return boundedAnswer(execution.execute(request, body), deadlineNanos, timeout);
                })
                .build();
    }

    // The answer is received here, in full, under the deadline - not handed on as a stream - because a stream is
    // the provider deciding when this thread continues. A provider that sends a status line, headers and ten body
    // bytes and then stalls satisfies every transport timeout available on the JDK client (HttpRequest.timeout is
    // disarmed once the response future completes, which is at headers, whatever body handler is used) and holds a
    // Tomcat request thread for as long as it cares to - and transitively a broker thread, since broker's REST
    // client configures no timeout at all
    // [backend/broker/src/main/resources/META-INF/microprofile-config.properties:L1].
    //
    // Both body framings stay covered, because either alone leaves the size hole open: a declared Content-Length
    // above the ceiling is refused before a single byte is read, and a body that declares nothing - chunked, or
    // delimited by connection close - is read only to one byte past the ceiling, which is enough to detect the
    // breach while keeping the allocation bounded. That also catches an answer declaring a small length and then
    // sending more, which is precisely what a hostile endpoint would do.
    private static ClientHttpResponse boundedAnswer(ClientHttpResponse response, long deadlineNanos, Duration budget)
            throws IOException {
        // Obtained before any check so every failure path below can close the stream itself; on this transport it
        // opens nothing and reads nothing.
        InputStream body = response.getBody();

        long declared = response.getHeaders().getContentLength();
        if (declared > MAX_RESPONSE_BYTES) {
            throw releasing(response, body, oversize(HttpHeaders.CONTENT_LENGTH + " " + declared, MAX_RESPONSE_BYTES));
        }

        byte[] answer;
        try {
            answer = readWithin(body, deadlineNanos, budget);
        } catch (IOException unreceived) {
            throw releasing(response, body, unreceived);
        }
        if (answer.length > MAX_RESPONSE_BYTES) {
            throw releasing(response, body,
                    oversize("read more than " + MAX_RESPONSE_BYTES + " bytes", MAX_RESPONSE_BYTES));
        }
        return new BufferedAnswer(response, answer);
    }

    // readNBytes rather than a read loop, and one byte past the ceiling rather than the ceiling: it blocks until
    // the limit or end of stream, allocates only what it actually receives, and the extra byte makes a breach
    // detectable without ever holding more than the ceiling plus one of a third party's bytes.
    //
    // The deadline is armed by closing the stream from another thread, which is the only mechanism that bounds
    // this phase on the JDK client: it has no per-read socket timeout, and its response timer is disarmed at
    // headers. A blocked read of a closed body stream fails immediately (verified on this JDK), and the close is
    // what releases the connection to a provider that has stopped sending.
    private static byte[] readWithin(InputStream body, long deadlineNanos, Duration budget) throws IOException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            // Already spent on connect and headers: failing here rather than scheduling a task that would fire at
            // once keeps a saturated budget from costing a watchdog dispatch per request during an outage.
            throw exhausted(budget);
        }

        AtomicBoolean expired = new AtomicBoolean();
        ScheduledFuture<?> deadline = DEADLINE_WATCHDOG.schedule(() -> {
            expired.set(true);
            closeQuietly(body);
        }, remainingNanos, TimeUnit.NANOSECONDS);
        try {
            return body.readNBytes(MAX_RESPONSE_BYTES + 1);
        } catch (IOException unblocked) {
            // The flag and not the clock decides, so a transport fault that happens to land near the deadline is
            // still reported as itself. Either way the outcome one layer up is the same class of failure, which is
            // what makes this an IOException: Spring turns it into a ResourceAccessException, the one failure
            // fx/FrankfurterExchangeRateClient retries, and the second failure becomes the caller's 503.
            throw expired.get() ? exhausted(budget) : unblocked;
        } finally {
            // Cancelled the instant the read returns, so a slow-but-legitimate answer is never closed under a
            // reader and the watchdog holds nothing between requests.
            deadline.cancel(false);
        }
    }

    // Names the budget and the property that sets it, and nothing about the endpoint or the bytes received: the
    // message reaches a log line, and the value it would echo is operator-supplied. The stream close this exception
    // reports is deliberately not chained as a cause - it is this class's own doing, carries no diagnosis beyond
    // "closed", and error/ApiExceptionHandler renders one bounded line per rate failure.
    private static IOException exhausted(Duration budget) {
        return new IOException("exchange-rate answer was not fully received within the " + budget
                + " cashaccount.fx.timeout budget; no rate was bound");
    }

    // Body stream first and the response second, in that order on every failure path: Spring's
    // JdkClientHttpResponse.close() drains the body before closing it, so closing the response of a dripping or
    // flooding answer would wait out exactly what these bounds exist to prevent, while a stream already closed
    // makes that drain return at once. The close failure travels as a suppressed exception rather than being
    // swallowed, so nothing is lost and no second log line is produced.
    private static <E extends Throwable> E releasing(ClientHttpResponse response, InputStream body, E failure) {
        try {
            body.close();
        } catch (IOException alreadyGone) {
            failure.addSuppressed(alreadyGone);
        }
        response.close();
        return failure;
    }

    // Called from the watchdog thread, where nothing can be thrown to: the close races a reader that is about to
    // fail anyway, and its own failure means the connection this was releasing is already gone. Suppressed onto
    // the reader's exception is not available here - that exception does not exist yet.
    private static void closeQuietly(InputStream body) {
        try {
            body.close();
        } catch (IOException alreadyGone) {
            LOGGER.debug("Exchange-rate response stream was already closed when the cashaccount.fx.timeout "
                    + "deadline fired: {}", alreadyGone.getMessage());
        }
    }

    private static ScheduledExecutorService newDeadlineWatchdog() {
        ScheduledThreadPoolExecutor watchdog = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "fx-deadline-watchdog");
            // Daemon so this bound never keeps a JVM - a test JVM above all - alive, and low priority is not used:
            // the task must run promptly or the deadline it enforces is not one.
            thread.setDaemon(true);
            return thread;
        });
        watchdog.setRemoveOnCancelPolicy(true);
        return watchdog;
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

    // The answer is held as bytes rather than as a stream, which is what makes the deadline above real: a stream
    // handed to a message converter would put the provider back in control of when this thread continues.
    private static final class BufferedAnswer implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final byte[] answer;

        private BufferedAnswer(ClientHttpResponse delegate, byte[] answer) {
            this.delegate = delegate;
            this.answer = answer;
        }

        // A fresh reader per call rather than one cached instance: Spring introspects the body for emptiness
        // before handing it to a converter, so every caller must see the whole answer from its first byte.
        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(answer);
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

        // Safe to delegate here and only here: the body was read to end of stream before this response existed,
        // so the drain inside the transport's own close has nothing left to wait for.
        @Override
        public void close() {
            delegate.close();
        }
    }
}
