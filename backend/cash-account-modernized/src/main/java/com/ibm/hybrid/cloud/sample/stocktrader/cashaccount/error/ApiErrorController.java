package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;

/** Renders an error the container dispatched here, rather than a controller raised, as one ApiError. */
@Controller
public class ApiErrorController implements ErrorController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiErrorController.class);

    private final ApiErrorResponseWriter writer;

    /**
     * Container constructor.
     *
     * @param writer the shared renderer; it writes the security headers too, because HeaderWriterFilter is
     *     skipped on an ERROR dispatch
     */
    public ApiErrorController(ApiErrorResponseWriter writer) {
        this.writer = writer;
    }

    /**
     * Answers an ERROR dispatch with the ApiError for the status the container set.
     *
     * <p>Declaring this bean is what removes Spring Boot's {@code BasicErrorController}, which is
     * {@code @ConditionalOnMissingBean(ErrorController.class)}: every error the container routes here -- a
     * rejection decided before Spring MVC could choose a handler, a failure thrown out of the filter chain, an
     * asynchronous dispatch that failed after the request left a controller -- was answered in Boot's
     * {@code {timestamp,status,error,path}} shape or its white-label HTML, a second and a third error shape on
     * the wire beside the one AAP 0.6.2 fixes.
     *
     * <p>The mapping is deliberately method-less and produces-less: an error dispatch keeps the original
     * request's verb, so a rejected {@code PROPFIND} or {@code TRACE} must be rendered here too, and negotiating
     * the body against an {@code Accept} header the request may have got wrong is what would fail a second time
     * while reporting the first failure. Nothing from the dispatch is read except the status: the
     * {@code jakarta.servlet.error.exception} and {@code .message} attributes carry framework and request text
     * that AAP 0.7.5's error-body discipline keeps out of a payload, and a condition raised inside a controller
     * has already been rendered by {@code ApiExceptionHandler} before it could reach here.
     *
     * @param request the dispatched request, whose error-status attribute names the condition
     * @param response the response to write
     * @throws IOException when the body cannot be written to the connection
     */
    @RequestMapping("${server.error.path:${error.path:/error}}")
    public void render(HttpServletRequest request, HttpServletResponse response) throws IOException {
        int status = statusOf(request, response);
        ApiError error = writer.errorForStatus(status);

        // The status is the whole diagnostic; the path is encoded because an error dispatch preserves the
        // original request URI attribute, which is caller-chosen text.
        LOGGER.debug("Rejecting request: container-level rejection {} on {} - {}", status,
                LogSafeText.ofMessage(request.getRequestURI()), error.code().code());

        writer.write(request, response, error);
    }

    // The container's own status, read from the dispatch attribute and only then from the response, because a
    // response's status can already have been reset by the time an error page is entered. A dispatch that names
    // no error status, or names a successful one, is not an error this service can describe: 500 is the honest
    // answer to a condition it cannot name, and it is the one status Spring Boot's own error controller falls
    // back to for the same reason.
    private static int statusOf(HttpServletRequest request, HttpServletResponse response) {
        Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = attribute instanceof Integer dispatched ? dispatched : response.getStatus();
        return status >= HttpStatus.BAD_REQUEST.value() ? status : HttpStatus.INTERNAL_SERVER_ERROR.value();
    }
}
