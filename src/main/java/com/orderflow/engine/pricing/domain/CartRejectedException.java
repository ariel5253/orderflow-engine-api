package com.orderflow.engine.pricing.domain;

import java.util.Objects;

/**
 * A cart that SPEC BR-00 rejects. Each code is named after the public error code of the API, so the
 * web adapter maps it without a translation table. The message is for logs only: the user text is
 * resolved from the code.
 */
public final class CartRejectedException extends RuntimeException {

    /** The rejections of SPEC BR-00. */
    public enum Code {
        CART_EMPTY,
        INVALID_QUANTITY,
        UNKNOWN_SKU
    }

    private final Code code;

    public CartRejectedException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
