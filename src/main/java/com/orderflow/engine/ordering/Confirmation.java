package com.orderflow.engine.ordering;

import java.util.Objects;
import java.util.UUID;

/**
 * The answer to a confirmation request, once its transaction has committed: the order and the exact bytes of the
 * response body. {@code replayed} tells a repeated request, answered with the response stored under its key, from the
 * request that created the order.
 */
public record Confirmation(UUID orderId, byte[] responseBody, boolean replayed) {

    public Confirmation {
        Objects.requireNonNull(orderId, "orderId");
        responseBody = Objects.requireNonNull(responseBody, "responseBody").clone();
    }

    static Confirmation created(UUID orderId, byte[] responseBody) {
        return new Confirmation(orderId, responseBody, false);
    }

    static Confirmation replayed(UUID orderId, byte[] responseBody) {
        return new Confirmation(orderId, responseBody, true);
    }

    @Override
    public byte[] responseBody() {
        return responseBody.clone();
    }
}
