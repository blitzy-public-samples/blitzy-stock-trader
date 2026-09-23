package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error;

import org.apache.catalina.Container;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.embedded.tomcat.ConfigurableTomcatWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Writer;

/** Renders a rejection the container decided before any servlet context existed as one ApiError. */
// Last, on purpose. Spring Boot installs an ErrorReportValve of its own on the same pipeline whenever
// server.error.include-stacktrace is never (the default this module keeps), and an ErrorReportValve renders on
// the way back OUT of the pipeline, so the valve added LAST reports FIRST. Reporting first is what makes this
// renderer effective: ErrorReportValve.report() writes nothing once content has been written or another valve
// has called setErrorReported(), so were Boot's valve to report first its minimal HTML page would stand and this
// one would silently do nothing.
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ApiErrorReportValveCustomizer implements WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiErrorReportValveCustomizer.class);

    private final ApiErrorResponseWriter writer;

    /**
     * Container constructor.
     *
     * @param writer the shared renderer, so a rejection decided in the connector carries the same payload and
     *     the same security headers as one decided in a controller
     */
    public ApiErrorReportValveCustomizer(ApiErrorResponseWriter writer) {
        this.writer = writer;
    }

    /**
     * Adds the renderer to the host pipeline.
     *
     * <p>The host and not the context, because the rejections this exists for have no context: Tomcat refuses a
     * request line it cannot parse, a header block over
     * {@code server.max-http-request-header-size}, or an encoded path separator before the request is mapped to
     * one, so {@code StandardHostValve} cannot dispatch to {@code /error} and
     * {@link ApiErrorController} never runs. A valve on the host pipeline is the only place inside this
     * application that such a response passes through.
     *
     * @param factory the Tomcat factory under construction
     */
    @Override
    public void customize(ConfigurableTomcatWebServerFactory factory) {
        factory.addContextCustomizers(context -> {
            Container host = context.getParent();
            if (host == null) {
                // Recorded rather than absorbed: the pipeline is reached through the context's parent, and a
                // context with none means the container was assembled differently than this customizer assumes,
                // in which case a container-level rejection keeps Tomcat's own minimal page.
                LOGGER.warn("Container-level rejections will not carry the {} payload: the servlet context has no"
                        + " parent host to install the error renderer on.", ApiError.class.getSimpleName());
                return;
            }
            host.getPipeline().addValve(new ApiErrorReportValve(writer));
        });
    }

    /** Writes the ApiError through the container's error reporter, the only writer available this early. */
    private static final class ApiErrorReportValve extends ErrorReportValve {

        private final ApiErrorResponseWriter writer;

        private ApiErrorReportValve(ApiErrorResponseWriter writer) {
            this.writer = writer;
        }

        // The guard is the superclass's own, repeated because report() is where it lives: a status below 400 is
        // not an error, content already written is a response some other layer owns, and setErrorReported()
        // returning false means a report has already been made - each of which must leave the response alone.
        @Override
        protected void report(Request request, Response response, Throwable throwable) {
            int status = response.getStatus();
            if (status < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
                return;
            }

            try {
                // Tomcat's error reporter rather than the servlet output stream: at this point the request was
                // never dispatched to a servlet, and the reporter is the writer Tomcat keeps usable for exactly
                // this purpose - it returns null when the connection can no longer carry a body.
                String body = writer.prepare(request, response, writer.errorForStatus(status));
                Writer reporter = response.getReporter();
                if (reporter != null) {
                    reporter.write(body);
                    response.finishResponse();
                }
            } catch (IOException | IllegalStateException unwritable) {
                // A caller that sent a request line Tomcat could not parse has frequently gone away already, so
                // failing to deliver the rejection is an expected outcome rather than a service fault. DEBUG and
                // the type only: the exception's message can carry the request text this payload exists to keep
                // out of the record.
                LOGGER.debug("Container-level rejection {} could not be written to the connection: {}", status,
                        unwritable.getClass().getSimpleName());
            }
        }
    }
}
