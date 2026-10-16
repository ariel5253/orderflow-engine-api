package com.orderflow.engine.quoting;

import java.util.Objects;

/**
 * A quote request or read that the quoting rules reject. Each code is named after the public error code of the API, so
 * the web adapter maps it without a translation table. The message is for logs only: the user text is resolved from
 * the code.
 */
public final class QuoteRejectedException extends RuntimeException {

    /** The rejections of the quote operations (api-contract E-01 and E-02) that the cart itself does not cause. */
    public enum Code {
        TENANT_NOT_FOUND,
        CUSTOMER_NOT_FOUND,
        CREDIT_NOT_ENABLED,
        QUOTE_NOT_FOUND
    }

    private final Code code;

    public QuoteRejectedException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
