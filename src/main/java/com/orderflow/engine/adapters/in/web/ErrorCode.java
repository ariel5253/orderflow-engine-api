package com.orderflow.engine.adapters.in.web;

import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/**
 * The public error catalog of SPEC §8. Clients branch on the code, so a code never changes once
 * published. Its title and detail live only in the i18n bundles, under {@link #titleKey()} and
 * {@link #detailKey()}.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    INVALID_QUANTITY(HttpStatus.BAD_REQUEST),
    CART_EMPTY(HttpStatus.BAD_REQUEST),
    TENANT_NOT_FOUND(HttpStatus.BAD_REQUEST),
    IDEMPOTENCY_KEY_MISSING(HttpStatus.BAD_REQUEST),
    IDEMPOTENCY_KEY_INVALID(HttpStatus.BAD_REQUEST),
    QUOTE_NOT_FOUND(HttpStatus.NOT_FOUND),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND),
    CUSTOMER_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** Raised by the framework, which keeps its own status: 404, 405, 406 or 415. */
    REQUEST_NOT_SUPPORTED(HttpStatus.NOT_FOUND),
    QUOTE_EXPIRED(HttpStatus.CONFLICT),
    QUOTE_ALREADY_CONFIRMED(HttpStatus.CONFLICT),
    CREDIT_LIMIT_EXCEEDED(HttpStatus.CONFLICT),
    IDEMPOTENCY_KEY_IN_PROGRESS(HttpStatus.CONFLICT),
    UNKNOWN_SKU(HttpStatus.UNPROCESSABLE_CONTENT),
    CREDIT_NOT_ENABLED(HttpStatus.UNPROCESSABLE_CONTENT),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private static final String TYPE_BASE = "https://orderflow-engine.example/problems/";

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public URI type() {
        return URI.create(TYPE_BASE + name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    public String titleKey() {
        return "error." + name() + ".title";
    }

    public String detailKey() {
        return "error." + name() + ".detail";
    }
}
