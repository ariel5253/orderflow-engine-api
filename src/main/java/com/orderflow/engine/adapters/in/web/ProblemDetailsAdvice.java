package com.orderflow.engine.adapters.in.web;

import com.orderflow.engine.pricing.domain.CartRejectedException;
import com.orderflow.engine.quoting.QuoteRejectedException;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The only place where an error becomes an HTTP response: RFC 9457 problem+json with a stable
 * code, a title and detail in the caller's language, and the request's correlation id. No
 * exception message reaches the body, so internals such as SQL or stack traces never leak.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    private final MessageSource messages;

    public ProblemDetailsAdvice(MessageSource messages) {
        this.messages = messages;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> handleApiException(ApiException ex, WebRequest request) {
        return problem(ex, ex.code(), ex.code().status(), new HttpHeaders(), request);
    }

    /** A cart rejected by SPEC BR-00. Its code is named after the public code, so it maps by name. */
    @ExceptionHandler(CartRejectedException.class)
    ResponseEntity<Object> handleCartRejected(CartRejectedException ex, WebRequest request) {
        ErrorCode code = ErrorCode.valueOf(ex.code().name());
        return problem(ex, code, code.status(), new HttpHeaders(), request);
    }

    /** A quote operation rejected by the quoting rules. Its code is named after the public code, so it maps by name. */
    @ExceptionHandler(QuoteRejectedException.class)
    ResponseEntity<Object> handleQuoteRejected(QuoteRejectedException ex, WebRequest request) {
        ErrorCode code = ErrorCode.valueOf(ex.code().name());
        return problem(ex, code, code.status(), new HttpHeaders(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        return problem(ex, ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.status(), new HttpHeaders(), request);
    }

    /**
     * Requests that Spring MVC rejects itself (unreadable body, unsupported method or media type,
     * unknown route) keep the status and headers it chose, such as Allow on a 405, because they
     * are the correct HTTP answer. A 400 carries VALIDATION_FAILED, any other 4xx carries
     * REQUEST_NOT_SUPPORTED, and a 5xx carries INTERNAL_ERROR.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ErrorCode code;
        if (statusCode.is5xxServerError()) {
            code = ErrorCode.INTERNAL_ERROR;
        } else if (statusCode.value() == HttpStatus.BAD_REQUEST.value()) {
            code = ErrorCode.VALIDATION_FAILED;
        } else {
            code = ErrorCode.REQUEST_NOT_SUPPORTED;
        }
        return problem(ex, code, statusCode, headers, request);
    }

    private ResponseEntity<Object> problem(Exception ex, ErrorCode code, HttpStatusCode status, HttpHeaders headers,
            WebRequest request) {
        if (status.is5xxServerError()) {
            LOG.error("Unexpected error, answered as {} with status {}", code, status.value(), ex);
        }
        Locale locale = LocaleContextHolder.getLocale();
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(code.type());
        problem.setTitle(messages.getMessage(code.titleKey(), null, locale));
        problem.setDetail(messages.getMessage(code.detailKey(), null, locale));
        problem.setProperty("code", code.name());
        problem.setProperty("correlationId",
                request.getAttribute(CorrelationIdFilter.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST));
        return super.handleExceptionInternal(ex, problem, headers, status, request);
    }
}
