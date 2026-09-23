package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Publishes the single bounded HTTP client used for the outbound exchange-rate lookup, carrying one compiled-in
 * default header and nothing caller-derived, the rate lookup itself and its {@code ExchangeRateSource} bean staying
 * in {@code fx}.
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

    /**
     * The one default header this client carries: a fixed, version-free identity for the outbound rate request.
     *
     * <p>A deliberate deviation from AAP 0.7.5, whose parenthetical describes this class as setting <em>no</em>
     * default headers. The normative requirement behind that parenthetical is untouched - the caller's credential
     * is never forwarded to the public rate provider, and the block in {@code fxRestClient} still attaches nothing
     * caller-derived. Carrying no User-Agent at all turned out to be the weaker posture: the JDK client supplies
     * its own {@code Java-http-client/<patch>} whenever a request arrives without one, so the absence published
     * this runtime's exact patch level to a third party and to anything on the path between - the CWE-200
     * disclosure that helps an attacker pick which JDK CVE to try against the base image.
     *
     * <p>No version, no host and nothing derived from the caller, which is the point rather than brevity: a
     * version string would re-create the same disclosure one layer up, and a caller-derived value would make this
     * header the very leak the {@code Authorization} prohibition exists to prevent. Public so
     * {@code fx/CurrencyConversionTest}, which lives in another package, asserts this constant rather than a copy
     * of its text.
     */
    public static final String USER_AGENT = "cash-account-service";

    /**
     * Floor for the budget of the ONE start-up exchange whose only product is a handshaken, pooled connection.
     *
     * <p>It exists because {@code cashaccount.fx.timeout} is a budget for the whole exchange <em>including</em>
     * connection establishment, and a cold TLS handshake to a public provider does not reliably fit in it: measured
     * from a pod against the endpoint the chart ships, the handshake alone took 3.6 s against a shipped budget of
     * PT2S, so the first cross-currency credit or debit after a pod started was refused
     * {@code 503 EXCHANGE_RATE_UNAVAILABLE} and only succeeded once a connection happened to be warm. The budget
     * itself is fixed by AAP 0.6.1 and is not what changes here; what changes is who pays the handshake - this
     * warm-up, before any caller arrives, instead of the first caller.
     *
     * <p>Deliberately a compiled-in floor and not a property, for the same reason as {@link #MAX_RESPONSE_BYTES}:
     * it is spent off the request path, at most once per process, so there is nothing for an operator to tune, and
     * a knob here would only be a second way to express {@code cashaccount.fx.timeout}. A floor rather than a fixed
     * value so that a deployment which has already widened the caller-facing budget past it never ends up with a
     * warm-up stingier than an ordinary request.
     */
    static final Duration PREWARM_FLOOR = Duration.ofSeconds(10);

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
     * The one HTTP transport - and therefore the one connection pool - every client below is built on.
     *
     * <p>A bean rather than a local, because the JDK's connection pool belongs to an {@code HttpClient} instance:
     * a second instance would have a pool of its own, and the start-up warm-up of
     * {@link FxConnectionPrewarm} would then leave its handshaken connection somewhere the request path can never
     * reach. Wrapped in a record instead of published as a bare {@code java.net.http.HttpClient} so that no other
     * bean in the context can acquire an HTTP transport by asking for one, and so that this module - not Spring's
     * destroy-method inference - decides whether shutdown blocks on {@code HttpClient.close()}, which waits for
     * every exchange in flight.
     *
     * @param properties source of {@code cashaccount.fx.timeout}, which is the floor for the connect bound below
     * @return the shared transport and the warm-up budget derived from that property
     */
    @Bean
    public FxTransport fxTransport(CashAccountProperties properties) {
        Duration prewarmBudget = prewarmBudget(properties);

        // The endpoint the chart ships answers a redirect - api.frankfurter.app/latest returns 301 to
        // api.frankfurter.dev/v1 - and the JDK client declines redirects unless asked, which would turn every
        // cross-currency conversion into a rate failure. NORMAL rather than ALWAYS is the security half of that
        // choice and must stay: it refuses a redirect from HTTPS down to HTTP, so the HTTPS-only endpoint
        // fx/FrankfurterExchangeRateClient enforces at start-up cannot be walked back to plaintext by a hop the
        // operator never configured.
        //
        // connectTimeout is the WIDEST budget any client on this transport may spend rather than the caller's own,
        // because it is a property of the shared instance and the warm-up needs room for a cold handshake the
        // caller-facing budget deliberately refuses to wait for (see PREWARM_FLOOR). No caller-facing guarantee
        // rides on it: every client below arms its own per-exchange bound - the request factory's timeout covers
        // connect, request and response headers and the interceptor's deadline covers body reception - so a
        // caller's exchange still fails inside cashaccount.fx.timeout whatever this value is. Bounded all the
        // same, so a black-holed endpoint cannot leave connect attempts open indefinitely.
        return new FxTransport(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(prewarmBudget)
                .build(), prewarmBudget);
    }

    /**
     * The one {@link RestClient} bean in the application context, named so that injection by type, by name and by
     * {@code @Qualifier("fxRestClient")} all resolve to it.
     *
     * @param properties source of {@code cashaccount.fx.timeout}, the budget for the whole exchange applied below
     * @return a client with a bounded exchange deadline and response size, exactly one default header - the fixed
     *         {@link #USER_AGENT} - and no base URL
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
        //
        // The transport comes from the bean method, so under the container this is the shared pool the warm-up
        // fills; called directly - as fx/CurrencyConversionTest builds the real bean without a container - it is a
        // transport of this client's own, which is what an isolated drive wants.
        return boundedClient(fxTransport(properties).httpClient(), properties.getFx().getTimeout());
    }

    /**
     * Opens the exchange-rate connection once, at start-up, so that no caller pays for establishing it.
     *
     * <p>Only in a web application: the migration tooling runs with no web server
     * ({@code spring.main.web-application-type=none} in {@code application-tool.yml}) and prices a replay from the
     * staged legacy rate table, so an outbound rate request while it loads an export would be a call nothing asked
     * for.
     *
     * @param properties source of the endpoint, the base currency and the accepted-currency set
     * @param transport  the shared transport whose pool this warm-up fills, and the budget it may spend
     * @return the start-up listener; it contributes to no health group, so an unreachable provider still cannot
     *         flap pods (AAP 0.9.1)
     */
    @Bean
    @ConditionalOnWebApplication
    public FxConnectionPrewarm fxConnectionPrewarm(CashAccountProperties properties, FxTransport transport) {
        return new FxConnectionPrewarm(boundedClient(transport.httpClient(), transport.prewarmBudget()),
                warmUpTarget(properties), transport.prewarmBudget());
    }

    // A floor and not a maximum: a deployment that has already widened cashaccount.fx.timeout past PREWARM_FLOOR
    // has said that its provider needs longer than this, and a warm-up stingier than an ordinary request would
    // then be the one thing that cannot connect.
    private static Duration prewarmBudget(CashAccountProperties properties) {
        Duration caller = properties.getFx().getTimeout();
        return caller.compareTo(PREWARM_FLOOR) > 0 ? caller : PREWARM_FLOOR;
    }

    // The request the warm-up sends is the request shape a caller sends - the configured endpoint with from and to
    // - so the connection it leaves behind is the one a conversion reuses, and a third party sees nothing it does
    // not already serve. The quote is the lowest accepted code that is not the base rather than a currency named
    // here: the accepted set is configuration (cashaccount.fx.accepted-currencies), and a hardcoded code would
    // send a pair a narrowed deployment never asks for. Null when the set offers no other code, which leaves the
    // warm-up out rather than inventing a currency.
    private static URI warmUpTarget(CashAccountProperties properties) {
        CashAccountProperties.Fx fx = properties.getFx();
        String base = normalizedCode(fx.getBaseCurrency());
        String quote = fx.getAcceptedCurrencies().stream()
                .map(FxClientConfig::normalizedCode)
                .filter(code -> !code.isEmpty() && !code.equals(base))
                .sorted()
                .findFirst()
                .orElse(null);
        if (quote == null) {
            return null;
        }
        // Built onto the configured value rather than concatenated, exactly as fx/FrankfurterExchangeRateClient
        // builds it, so a query string already in that value survives here too.
        return UriComponentsBuilder.fromUriString(fx.getUrl())
                .queryParam("from", base)
                .queryParam("to", quote)
                .build()
                .toUri();
    }

    private static String normalizedCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    // Everything a client on the shared transport needs and nothing it may choose for itself: the two bounds no
    // timeout on this transport can express, the one default header, and no base URL.
    private static RestClient boundedClient(HttpClient httpClient, Duration timeout) {
        // Bounds the header phase only, and is kept for exactly that: Spring's JdkClientHttpRequestFactory reads
        // the answer with HttpResponse.BodyHandlers.ofInputStream and waits out this timeout on the resulting
        // future, which the JDK completes as soon as the response HEADERS arrive. Everything after that point is
        // bounded by the deadline in the interceptor below instead.
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);

        // Broker propagates the caller's credential into this service
        // [backend/broker/src/main/resources/META-INF/microprofile-config.properties:L1] and the public FX provider
        // requires none, so the only default header attached is the compiled-in USER_AGENT and nothing
        // caller-derived - no credential, no cookie, no forwarded request attribute - ever travels with a rate
        // lookup. The plain builder is used rather than the auto-configured one for that same reason: a
        // customizer contributed elsewhere in the context could reach the auto-configured one and add a header
        // this class never chose. No base URL either: the chart supplies the endpoint as CURRENCY_API_URL and the
        // client sends it as an absolute URI. The one interceptor exists to take something away rather than add
        // anything: it sets no header and reads nothing of the request, and it bounds the answer in both
        // dimensions no timeout on this transport can - its total time (see boundedAnswer) and its size (see
        // MAX_RESPONSE_BYTES) - before a message converter sees it.
        return RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
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

    /** The one HTTP transport every exchange-rate client shares, and the budget a start-up warm-up on it may spend. */
    public record FxTransport(HttpClient httpClient, Duration prewarmBudget) {
    }

    /**
     * One exchange at start-up whose product is a handshaken, pooled connection rather than a rate.
     *
     * <p>It exists because {@code cashaccount.fx.timeout} budgets the whole exchange, connection establishment
     * included, and a cold TLS handshake to a public provider can cost more than the whole of it (see
     * {@link FxClientConfig#PREWARM_FLOOR}). Paying that cost here moves it off the first caller's request without
     * widening any caller's budget, which AAP 0.6.1 fixes.
     *
     * <p>No rate is bound, cached or handed to anything: the answer is read under the same size and deadline bounds
     * every other exchange gets and then discarded, and any status at all counts as success because the connection
     * - not the body - is the point. Nothing here reaches a health group either, so an unreachable provider at
     * start-up still cannot flap pods (AAP 0.9.1): the warm-up logs one line and the service runs exactly as it
     * did before, with the first conversion paying connection set-up inside its own budget.
     */
    public static final class FxConnectionPrewarm {

        private static final Logger PREWARM_LOGGER = LoggerFactory.getLogger(FxConnectionPrewarm.class);

        private final RestClient client;
        private final URI target;
        private final Duration budget;

        FxConnectionPrewarm(RestClient client, URI target, Duration budget) {
            this.client = client;
            this.target = target;
            this.budget = budget;
        }

        // ApplicationReadyEvent and not a @PostConstruct or an ApplicationRunner: a bean initialised mid-refresh
        // would add a third party to the critical path of start-up itself, and a runner would do the same before
        // the port is bound. By the time this event fires the server is listening, so a warm-up that never
        // completes costs nothing that start-up needed.
        @EventListener(ApplicationReadyEvent.class)
        void open() {
            if (target == null) {
                // cashaccount.fx.accepted-currencies offers no code other than the base, so every conversion this
                // deployment can ask for short-circuits to a rate of exactly 1 with no call at all.
                PREWARM_LOGGER.debug("No accepted currency other than the base, so no exchange-rate connection is "
                        + "warmed: this deployment makes no cross-currency rate request.");
                return;
            }
            // Handed to a thread rather than run here: Spring Boot publishes the readiness change to
            // ACCEPTING_TRAFFIC immediately after this event's listeners return, so waiting out a handshake in
            // this method would hold readiness back for exactly as long as the warm-up exists to save. Daemon, so
            // it can never hold a JVM open, and one-shot, so nothing lingers between start-ups.
            Thread warmUp = new Thread(this::exchange, "fx-connection-prewarm");
            warmUp.setDaemon(true);
            warmUp.start();
        }

        private void exchange() {
            long startedAt = System.nanoTime();
            try {
                client.get()
                        .uri(target)
                        .retrieve()
                        // Every status is accepted, including an error one: a provider that answers 404 for the
                        // pair this warm-up happened to ask for has still completed DNS, TCP and TLS, which is the
                        // whole product. Without this the default handler would raise on 4xx/5xx and report a
                        // successful warm-up as a failure.
                        .onStatus(status -> true, (request, response) -> { })
                        .toBodilessEntity();
                PREWARM_LOGGER.info("Exchange-rate connection opened in {} ms, so the first cross-currency credit "
                        + "or debit does not pay connection set-up inside its cashaccount.fx.timeout budget.",
                        Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
            } catch (RuntimeException unreachable) {
                // One bounded line, and the cause's simple class name rather than its message: the message would
                // echo the operator-supplied endpoint into a log, which this class does not do anywhere else. No
                // stack either - nothing here is a fault to diagnose, and the next conversion reports its own
                // failure through 503 EXCHANGE_RATE_UNAVAILABLE.
                PREWARM_LOGGER.warn("Exchange-rate connection was not opened ({}) inside the {} start-up warm-up "
                        + "budget. No request was affected and readiness is unchanged; the first cross-currency "
                        + "credit or debit will pay connection set-up inside its cashaccount.fx.timeout budget.",
                        NestedExceptionUtils.getMostSpecificCause(unreachable).getClass().getSimpleName(),
                        budget);
            }
        }
    }
}
