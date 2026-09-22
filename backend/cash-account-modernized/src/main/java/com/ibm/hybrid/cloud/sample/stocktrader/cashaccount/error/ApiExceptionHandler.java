/*
       Copyright 2025 Kyndryl, All Rights Reserved

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

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

// This class does not extend ResponseEntityExceptionHandler: that base class answers with Spring's
// ProblemDetail (RFC 7807) body, which would put a second error shape on the wire beside ApiError. The
// legacy program's single status channel is precisely what is being replaced - "MOVE SQLCODE TO WS-RETCODE"
// [backend/cash-account-cobol/COBOL/CASH00.cbl:L104] rendered every condition as ten unsigned digits - so
// one payload shape everywhere is not a preference here, it is the deliverable. Plain @ExceptionHandler
// methods are resolved by ExceptionHandlerExceptionResolver, which runs ahead of
// DefaultHandlerExceptionResolver, so these mappings win over the framework's own defaults.
/** Renders everything that escapes a controller, or that Spring MVC raises during dispatch, as one ApiError. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final String AMOUNT = "amount";
    private static final String CURRENCY = "currency";
    private static final String OWNER = "owner";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    @ExceptionHandler(CashAccountException.class)
    public ResponseEntity<ApiError> handleCashAccountException(CashAccountException exception) {
        CashAccountErrorCode code = exception.errorCode();
        if (code == CashAccountErrorCode.INTERNAL) {
            LOGGER.error("Internal failure handling a request for owner {}", exception.owner(), exception);
        } else if (code.status().is5xxServerError()) {
            // The cause matters operationally here - an unreachable exchange-rate endpoint or a datastore
            // outage - and it is recorded only in the log, never in the response body.
            LOGGER.warn("Rejecting request for owner {}: {}", exception.owner(), code.code(), exception);
        } else {
            LOGGER.debug("Rejecting request for owner {}: {} - {}", exception.owner(), code.code(),
                    exception.getMessage());
        }
        return respond(ApiError.from(exception));
    }

    // Deliberate, authorized behavioural change: the service fails closed where the legacy program fell
    // through. "EVALUATE WS-REQ" [backend/cash-account-cobol/COBOL/CASH00.cbl:L89-L102] branches on the six
    // request codes A/Q/U/X/C/D and carries no WHEN OTHER, so an unknown code executed no SQL at all -
    // SQLCODE kept whatever the SQLCA already held, which this task never sets, and "MOVE SQLCODE TO
    // WS-RETCODE" [CASH00.cbl:L104] therefore handed back a success-looking status field. The COMMAREA was
    // then copied back verbatim [CASH00.cbl:L104-L108], so the caller read its OWN submitted amount as the
    // "balance", and the unconditional history write [CASH00.cbl:L111-L131] recorded the non-event as though
    // it had happened. In the replacement the HTTP verb and path ARE the request code and Spring MVC's
    // routing supplies the catch-all the COBOL never had: an unmapped path is 404 and an unmapped verb 405,
    // both in the one ApiError shape. This advice is deliberately declared with no basePackages or
    // assignableTypes selector, because a no-handler exception is raised before any controller is chosen and
    // a selector-restricted advice is never consulted for it - which would leave Spring Boot's white-label
    // body on the wire instead of an ApiError.
    //
    // Both no-handler types are handled because which one Spring raises is decided by resource-handler and
    // "throw-exception-if-no-handler-found" configuration that lives in application.yml, not in this file:
    // NoResourceFoundException surfaces under the default static-resource mapping of Spring Framework 6.1,
    // NoHandlerFoundException when no-handler dispatch is configured to throw. Covering the pair makes the
    // fail-closed 404 independent of that setting.
    @ExceptionHandler({ NoResourceFoundException.class, NoHandlerFoundException.class })
    public ResponseEntity<ApiError> handleUnsupportedPath(Exception exception) {
        LOGGER.debug("Rejecting request: no resource mapped - {}", exception.getMessage());
        return respond(ApiError.of(CashAccountErrorCode.UNSUPPORTED_PATH));
    }

    // Same fail-closed rationale as the handler above: a known path reached with a verb this service does
    // not implement is rejected explicitly rather than dispatched to whatever the legacy fall-through would
    // have echoed back.
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleUnsupportedMethod(HttpRequestMethodNotSupportedException exception) {
        LOGGER.debug("Rejecting request: method not supported - {}", exception.getMessage());
        return respond(ApiError.of(CashAccountErrorCode.UNSUPPORTED_METHOD));
    }

    // The value's own name selects the code because the retail "amount" and the ledger query parameters
    // share this failure mode: "amount" is bound from its string form to BigDecimal, so a non-numeric or
    // absent value lands here rather than in any validator. The Idempotency-Key row exists so that a
    // missing required header renders the 400 the institutional contract promises instead of falling to the
    // catch-all as an internal error.
    @ExceptionHandler({ MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class })
    public ResponseEntity<ApiError> handleRequestValueBinding(Exception exception) {
        String name = boundValueName(exception);
        CashAccountErrorCode code = codeForRequestValue(name);
        LOGGER.debug("Rejecting request: '{}' could not be bound - {}", name, code.code());
        return respond(ApiError.of(code));
    }

    // Bean Validation on the institutional payloads would otherwise be rendered by Spring as a
    // ProblemDetail, i.e. a second error shape; the offending field name maps onto the codes that already
    // exist for it rather than onto a new constant.
    @ExceptionHandler({ MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
            ConstraintViolationException.class })
    public ResponseEntity<ApiError> handleValidationFailure(Exception exception) {
        String field = offendingField(exception);
        CashAccountErrorCode code = codeForField(field);
        LOGGER.debug("Rejecting request: validation failed on '{}' - {}", field, code.code());
        return respond(ApiError.of(code));
    }

    // A body Jackson cannot read is reported as INVALID_AMOUNT because the closed code set holds no generic
    // "malformed body" condition and widening the enum is out of scope; INVALID_AMOUNT is its designated 400
    // for a body-binding failure. Only the exception's type is logged: its message embeds the offending
    // request content, which must not reach a log any more than it reaches the response.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException exception) {
        LOGGER.debug("Rejecting request: unreadable body - {}", exception.getClass().getSimpleName());
        return respond(ApiError.of(CashAccountErrorCode.INVALID_AMOUNT));
    }

    // A lock conflict is an explicit, retryable 409 with a Retry-After hint rather than a 500, because it is
    // a normal outcome of two callers touching one account row: the balance is unchanged, no ledger row was
    // written, and the caller may simply retry. Its legacy counterpart was a CICS backout whose -911/-913
    // reached the caller as unsigned digits [backend/cash-account-cobol/COBOL/CASH00.cbl:L104], indis-
    // tinguishable from a validation failure. PessimisticLockingFailureException is declared beside
    // CannotAcquireLockException because Hibernate's jakarta.persistence.PessimisticLockException translates
    // to that supertype, not to CannotAcquireLockException, and a lock conflict must never degrade to 500.
    @ExceptionHandler({ ObjectOptimisticLockingFailureException.class, PessimisticLockingFailureException.class,
            CannotAcquireLockException.class })
    public ResponseEntity<ApiError> handleLockConflict(Exception exception) {
        LOGGER.warn("Rejecting request: concurrent modification - {}", exception.toString());
        return respond(ApiError.of(CashAccountErrorCode.CONCURRENT_MODIFICATION));
    }

    @ExceptionHandler({ DataAccessResourceFailureException.class, CannotCreateTransactionException.class,
            QueryTimeoutException.class })
    public ResponseEntity<ApiError> handleDatastoreUnavailable(Exception exception) {
        LOGGER.warn("Rejecting request: datastore unavailable - {}", exception.toString());
        return respond(ApiError.of(CashAccountErrorCode.DATASTORE_UNAVAILABLE));
    }

    // These two exist so the catch-all below can never downgrade a security rejection into a 500. They fire
    // only for a rejection raised after the filter chain has admitted the request - method security, say -
    // because Spring Security's own pre-controller rejections never reach a @RestControllerAdvice at all:
    // those are rendered by ApiErrorAuthenticationEntryPoint and ApiErrorAccessDeniedHandler, which produce
    // this identical payload, so a caller sees one shape wherever the decision was taken. Only the
    // exception's type is logged - never a token, a credential or an Authorization header value.
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

    // The payload carries the code's generic message and nothing else: no exception message, class name, SQL
    // text or stack element leaves the process. Reporting raw internal status detail to the caller is exactly
    // the legacy habit being replaced [backend/cash-account-cobol/COBOL/CASH00.cbl:L104], and an unexpected
    // failure is the one case where that detail is most likely to expose schema or infrastructure. The full
    // stack is recorded here, in the log, because this is the only place it is recorded at all.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        LOGGER.error("Unexpected failure handling a request", exception);
        return respond(ApiError.of(CashAccountErrorCode.INTERNAL));
    }

    // Status and Retry-After are derived here and only here, from the code the payload already carries, so
    // no handler can choose a status of its own and no condition can acquire or lose a retry hint by being
    // rendered in one place rather than another. The content type is set explicitly so the ApiError is
    // written as JSON even when the request's Accept header asked for something else - a request that fails
    // closed must still answer in the one shape every consumer parses.
    private ResponseEntity<ApiError> respond(ApiError body) {
        CashAccountErrorCode code = body.code();
        ResponseEntity.BodyBuilder response = ResponseEntity.status(code.status())
                .contentType(MediaType.APPLICATION_JSON);
        if (code.hasRetryAfter()) {
            response = response.header(HttpHeaders.RETRY_AFTER, code.retryAfterSeconds().toString());
        }
        return response.body(body);
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

    // Each of the three validation exceptions reports the offending member differently - a bound body
    // through its BindingResult, a constrained method parameter through per-parameter results, a programmatic
    // validator through a property path - so the name is extracted per type and the code selection stays in
    // one place.
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

    // The leaf node of a property path is the constrained member itself; the enclosing method and argument
    // nodes that precede it name the call, not the field the caller has to correct.
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
