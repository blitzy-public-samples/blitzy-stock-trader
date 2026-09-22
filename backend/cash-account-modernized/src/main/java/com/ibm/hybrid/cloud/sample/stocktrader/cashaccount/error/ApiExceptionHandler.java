package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * Renders everything that escapes a controller, or that Spring MVC raises during dispatch, as one ApiError -
 * deliberately not extending ResponseEntityExceptionHandler, whose ProblemDetail body would put a second error
 * shape on the wire.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final String AMOUNT = "amount";
    private static final String CURRENCY = "currency";
    private static final String OWNER = "owner";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private static final int MAX_CAUSE_DEPTH = 8;

    // The owner is the one caller-controlled value these records carry, and it reaches them RAW: every rejection
    // raised through CashAccountException.forOwner echoes back what the caller sent, precisely so the payload can
    // name what was refused, which means the value most likely to be logged here is the one least likely to have
    // been normalized. Interpolated unencoded it forges log structure - a newline splits one record into two, the
    // second indistinguishable from one this service wrote (CWE-117) - so it is passed through LogSafeText, which
    // escapes the code points that could do that and bounds the field's length. domain/OwnerNormalizer is
    // deliberately NOT narrowed to compensate: the accepted owner set is fixed by AAP 0.4.2 and 0.6.2, and the
    // sink is this log format rather than the stored value.
    //
    // Every OTHER caller-derived string this class logs goes through the same encoder, because a control applied
    // to one field of a record and not its neighbours is not a control: a no-handler message is built around the
    // percent-DECODED request path, a method-not-supported message around the request's verb, and a message
    // carried by a CashAccountException may quote a rejected value. Only the arguments that are a Throwable are
    // passed unencoded - those are rendered by the logging framework as a stack trace, which is multi-line by
    // nature and which every log consumer already parses as one event.
    @ExceptionHandler(CashAccountException.class)
    public ResponseEntity<ApiError> handleCashAccountException(CashAccountException exception) {
        CashAccountErrorCode code = exception.errorCode();
        String owner = LogSafeText.of(exception.owner());
        if (code == CashAccountErrorCode.INTERNAL) {
            LOGGER.error("Internal failure handling a request for owner {}", owner, exception);
        } else if (code.status().is5xxServerError()) {
            // The cause chain stays in the log and never in the response body, and this is the only place an
            // exchange-rate or datastore failure is recorded: the fx client logs the same failure at DEBUG so
            // one provider outage cannot cost two WARN records per request.
            LOGGER.warn("Rejecting request for owner {}: {}", owner, code.code(), exception);
        } else {
            LOGGER.debug("Rejecting request for owner {}: {} - {}", owner, code.code(),
                    LogSafeText.ofMessage(exception.getMessage()));
        }
        return respond(ApiError.from(exception));
    }

    // Deliberate, authorized behavioural change - the service fails closed where the legacy program fell
    // through: "EVALUATE WS-REQ" [backend/cash-account-cobol/COBOL/CASH00.cbl:L89-L102] carries no WHEN
    // OTHER, so an unknown request code ran no SQL, handed back a success-looking status field
    // [CASH00.cbl:L104], echoed the caller's own submitted amount back as the balance [CASH00.cbl:L104-L108]
    // and still wrote its history record [CASH00.cbl:L111-L131]. Here an unmapped path is 404 and an
    // unmapped verb 405, both in the one ApiError shape. The advice carries no basePackages or
    // assignableTypes selector because a no-handler exception is raised before any controller is chosen, and
    // a selector-restricted advice is never consulted for it - leaving Spring Boot's white-label body on the
    // wire. Both no-handler types are declared so the 404 does not depend on which one Spring raises:
    // NoResourceFoundException is the live route under Spring Framework 6.1's default static-resource
    // handling, which this module configures nothing about, and NoHandlerFoundException covers a build that
    // configures no-handler dispatch to throw instead.
    @ExceptionHandler({ NoResourceFoundException.class, NoHandlerFoundException.class })
    public ResponseEntity<ApiError> handleUnsupportedPath(Exception exception) {
        LOGGER.debug("Rejecting request: no resource mapped - {}", LogSafeText.ofMessage(exception.getMessage()));
        return respond(ApiError.of(CashAccountErrorCode.UNSUPPORTED_PATH));
    }

    // Fail-closed for the same reason as the handler above. The exception's own headers travel with the
    // response because they carry Allow whenever Spring MVC knew which verbs the path supports, sparing the
    // caller the discovery-by-trial the legacy status channel forced; Allow is method metadata, so the body
    // is still the one error shape.
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleUnsupportedMethod(HttpRequestMethodNotSupportedException exception) {
        LOGGER.debug("Rejecting request: method not supported - {}",
                LogSafeText.ofMessage(exception.getMessage()));
        return respond(ApiError.of(CashAccountErrorCode.UNSUPPORTED_METHOD), exception.getHeaders());
    }

    // The value's own name selects the code. One route in is live - an absent "?amount=" on retail debit or
    // credit - because every present-but-invalid value is parsed in code and arrives as a
    // CashAccountException instead, keeping each status inside the closed set its contract fixes. The other
    // two types are defensive coverage: no handler method declares a typed query parameter, and the
    // mandatory Idempotency-Key is read with required = false so ReservationService reports it missing.
    @ExceptionHandler({ MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class })
    public ResponseEntity<ApiError> handleRequestValueBinding(Exception exception) {
        String name = boundValueName(exception);
        CashAccountErrorCode code = codeForRequestValue(name);
        LOGGER.debug("Rejecting request: '{}' could not be bound - {}", name, code.code());
        return respond(ApiError.of(code));
    }

    // Spring would render Bean Validation failures as a ProblemDetail, a second error shape; the offending
    // field name maps onto the codes that already exist for it rather than onto a new constant.
    @ExceptionHandler({ MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
            ConstraintViolationException.class })
    public ResponseEntity<ApiError> handleValidationFailure(Exception exception) {
        String field = offendingField(exception);
        CashAccountErrorCode code = codeForField(field);
        LOGGER.debug("Rejecting request: validation failed on '{}' - {}", field, code.code());
        return respond(ApiError.of(code));
    }

    // INVALID_AMOUNT is the closed code set's designated 400 for a body-binding failure; the set holds no
    // generic "malformed body" condition. Only the exception's type is logged, because its message embeds
    // the offending request content, which must not reach a log any more than it reaches the response.
    // The cause chain is inspected first because that is the only route by which an oversized chunked body is
    // reported correctly: config/RequestBodySizeLimitFilter's counting stream fails the read mid-parse with a
    // RequestBodyTooLargeException, which Spring's Jackson converter re-throws wrapped in this type; without the
    // unwrap an over-limit body would answer 400 INVALID_AMOUNT for a body that was never parsed at all.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException exception) {
        RequestBodyTooLargeException oversized = oversizedBodyCause(exception);
        if (oversized != null) {
            return handleOversizedBody(oversized);
        }
        LOGGER.debug("Rejecting request: unreadable body - {}", exception.getClass().getSimpleName());
        return respond(ApiError.of(CashAccountErrorCode.INVALID_AMOUNT));
    }

    // Declared for the direct throw as well as the wrapped one, so the status does not depend on whether the
    // stream failed inside a message converter or outside one. Nothing but the limit is logged: the body was
    // refused precisely so its content would never be materialized, in a log or anywhere else.
    @ExceptionHandler(RequestBodyTooLargeException.class)
    public ResponseEntity<ApiError> handleOversizedBody(RequestBodyTooLargeException exception) {
        LOGGER.debug("Rejecting request: body exceeds the {}-byte limit", exception.limitBytes());
        return respond(ApiError.of(CashAccountErrorCode.REQUEST_TOO_LARGE));
    }

    // A retryable 409 with a Retry-After hint rather than a 500: two callers touching one account row is a
    // normal outcome that leaves the balance unchanged and writes no ledger row, where the legacy -911/-913
    // reached the caller as unsigned digits indistinguishable from a validation failure
    // [backend/cash-account-cobol/COBOL/CASH00.cbl:L104] (docs/legacy-characterization.md section 9.9).
    // PessimisticLockingFailureException is declared beside CannotAcquireLockException because Hibernate's
    // jakarta.persistence.PessimisticLockException translates to that supertype, and a lock conflict must
    // never degrade to 500.
    @ExceptionHandler({ ObjectOptimisticLockingFailureException.class, PessimisticLockingFailureException.class,
            CannotAcquireLockException.class })
    public ResponseEntity<ApiError> handleLockConflict(Exception exception) {
        LOGGER.warn("Rejecting request: concurrent modification - {}", LogSafeText.ofMessage(exception.toString()));
        return respond(ApiError.of(CashAccountErrorCode.CONCURRENT_MODIFICATION));
    }

    @ExceptionHandler({ DataAccessResourceFailureException.class, CannotCreateTransactionException.class,
            QueryTimeoutException.class })
    public ResponseEntity<ApiError> handleDatastoreUnavailable(Exception exception) {
        LOGGER.warn("Rejecting request: datastore unavailable - {}", LogSafeText.ofMessage(exception.toString()));
        return respond(ApiError.of(CashAccountErrorCode.DATASTORE_UNAVAILABLE));
    }

    // These two exist so the catch-all below can never downgrade a security rejection into a 500, and they
    // fire only for a rejection raised after the filter chain has admitted the request: Spring Security's
    // own pre-controller 401 and 403 never reach a @RestControllerAdvice, which is why
    // ApiErrorAuthenticationEntryPoint and ApiErrorAccessDeniedHandler render them into this same payload.
    // Only the exception's type is logged - never a token, a credential or an Authorization header value.
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthenticationFailure(AuthenticationException exception) {
        LOGGER.info("Rejecting request: authentication failed - {}", exception.getClass().getSimpleName());
        return respond(ApiError.of(CashAccountErrorCode.UNAUTHORIZED));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException exception) {
        LOGGER.info("Rejecting request: access denied - {}", exception.getClass().getSimpleName());
        return respond(ApiError.of(CashAccountErrorCode.FORBIDDEN));
    }

    // The payload carries the code's generic message and nothing else - no exception message, class name,
    // SQL text or stack element - because an unexpected failure is where that detail is most likely to
    // expose schema or infrastructure. The full stack is recorded here, in the log, and nowhere else.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        LOGGER.error("Unexpected failure handling a request", exception);
        return respond(ApiError.of(CashAccountErrorCode.INTERNAL));
    }

    // Status and Retry-After are derived here and only here, from the code the payload already carries, so no
    // handler chooses a status of its own and no condition gains or loses a retry hint by where it was
    // rendered. The content type is explicit so a request that fails closed still answers in the one shape
    // every consumer parses even when Accept asked for something else, and it is applied after the caller's
    // headers so the overload cannot displace it.
    private ResponseEntity<ApiError> respond(ApiError body) {
        return respond(body, HttpHeaders.EMPTY);
    }

    private ResponseEntity<ApiError> respond(ApiError body, HttpHeaders additionalHeaders) {
        CashAccountErrorCode code = body.code();
        ResponseEntity.BodyBuilder response = ResponseEntity.status(code.status())
                .headers(additionalHeaders)
                .contentType(MediaType.APPLICATION_JSON);
        if (code.hasRetryAfter()) {
            response = response.header(HttpHeaders.RETRY_AFTER, code.retryAfterSeconds().toString());
        }
        return response.body(body);
    }

    // Depth-bounded rather than a plain walk to the end of the chain: a cause graph that references itself, which
    // a wrapping converter can produce, would otherwise spin here while rendering an error. Eight levels is more
    // than the two this path actually produces (converter wrapping the stream failure) and is reached by nothing
    // legitimate.
    private static RequestBodyTooLargeException oversizedBodyCause(Throwable exception) {
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (cause instanceof RequestBodyTooLargeException oversized) {
                return oversized;
            }
            Throwable next = cause.getCause();
            if (next == cause) {
                break;
            }
            cause = next;
        }
        return null;
    }

    private static String boundValueName(Exception exception) {
        if (exception instanceof MethodArgumentTypeMismatchException mismatch) {
            return mismatch.getName();
        }
        if (exception instanceof MissingServletRequestParameterException missingParameter) {
            return missingParameter.getParameterName();
        }
        return ((MissingRequestHeaderException) exception).getHeaderName();
    }

    private static CashAccountErrorCode codeForRequestValue(String name) {
        if (IDEMPOTENCY_KEY_HEADER.equalsIgnoreCase(name)) {
            return CashAccountErrorCode.IDEMPOTENCY_KEY_REQUIRED;
        }
        if (AMOUNT.equalsIgnoreCase(name)) {
            return CashAccountErrorCode.INVALID_AMOUNT;
        }
        return CashAccountErrorCode.INVALID_QUERY;
    }

    private static CashAccountErrorCode codeForField(String field) {
        if (CURRENCY.equalsIgnoreCase(field)) {
            return CashAccountErrorCode.INVALID_CURRENCY;
        }
        if (OWNER.equalsIgnoreCase(field)) {
            return CashAccountErrorCode.INVALID_OWNER;
        }
        return CashAccountErrorCode.INVALID_AMOUNT;
    }

    // The three validation exceptions report the offending member differently - a BindingResult,
    // per-parameter results, a property path - so extraction is per type and code selection stays in one place.
    private static String offendingField(Exception exception) {
        if (exception instanceof MethodArgumentNotValidException invalidArgument) {
            return firstNamed(invalidArgument.getBindingResult().getFieldErrors().stream()
                    .map(FieldError::getField).toList());
        }
        if (exception instanceof HandlerMethodValidationException methodValidation) {
            return offendingParameter(methodValidation);
        }
        ConstraintViolationException violations = (ConstraintViolationException) exception;
        return firstNamed(violations.getConstraintViolations().stream()
                .map(violation -> leafName(violation.getPropertyPath())).toList());
    }

    private static String offendingParameter(HandlerMethodValidationException exception) {
        for (ParameterValidationResult result : exception.getAllValidationResults()) {
            if (result instanceof ParameterErrors errors) {
                String field = firstNamed(errors.getFieldErrors().stream().map(FieldError::getField).toList());
                if (field != null) {
                    return field;
                }
            }
            String parameter = result.getMethodParameter().getParameterName();
            if (isNamed(parameter)) {
                return parameter;
            }
        }
        return null;
    }

    // The leaf node is the constrained member itself; the method and argument nodes preceding it name the
    // call, not the field the caller has to correct.
    private static String leafName(Path propertyPath) {
        String leaf = null;
        for (Path.Node node : propertyPath) {
            if (isNamed(node.getName())) {
                leaf = node.getName();
            }
        }
        return leaf;
    }

    private static String firstNamed(List<String> names) {
        for (String name : names) {
            if (isNamed(name)) {
                return name;
            }
        }
        return null;
    }

    private static boolean isNamed(String value) {
        return value != null && !value.isBlank();
    }
}
