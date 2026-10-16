package com.orderflow.engine.outbox;

/**
 * A gateway that did not take an event. A retryable failure (the system is down, a timeout) is tried again after a
 * backoff. A non-retryable one (the ERP rejects the order with a 4xx) would fail the same way every time, so the
 * event becomes DEAD at once instead of using up its attempts.
 */
public class DeliveryException extends RuntimeException {

    private final boolean retryable;

    private DeliveryException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public static DeliveryException retryable(String message) {
        return new DeliveryException(message, true);
    }

    public static DeliveryException nonRetryable(String message) {
        return new DeliveryException(message, false);
    }

    public boolean retryable() {
        return retryable;
    }
}
