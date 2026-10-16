package com.orderflow.engine.ordering;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The idempotency keys of order confirmations (SPEC section 7.3). A key belongs to one customer of one tenant. It is
 * written in the confirmation's transaction, so a key is stored only together with the order it produced: a failed
 * confirmation leaves no key behind, and the client may retry with it (BD-02).
 */
public interface IdempotencyStore {

    /**
     * Takes the key for the current transaction, or returns the request already completed under it. The key is
     * inserted unless it exists, and then read back. While a concurrent transaction holds the same key uncommitted,
     * this waits for it to end, so the outcome is either that request's completed answer or the key itself.
     *
     * @return empty when the current transaction now owns the key
     * @throws OrderRejectedException with {@code IDEMPOTENCY_KEY_IN_PROGRESS} when the wait passes the lock timeout
     */
    Optional<CompletedRequest> claim(String tenantId, UUID customerId, String key, byte[] requestHash,
            Instant createdAt);

    /** Records on a key owned by the current transaction the order it produced and the bytes of its response. */
    void complete(String tenantId, UUID customerId, String key, UUID orderId, byte[] responseBody);

    /**
     * A request completed under a key: the hash that identifies it, the order it produced and the exact body of the
     * response it received, which a replay returns byte for byte.
     */
    record CompletedRequest(byte[] requestHash, UUID orderId, byte[] responseBody) {

        public CompletedRequest {
            requestHash = Objects.requireNonNull(requestHash, "requestHash").clone();
            Objects.requireNonNull(orderId, "orderId");
            responseBody = Objects.requireNonNull(responseBody, "responseBody").clone();
        }

        @Override
        public byte[] requestHash() {
            return requestHash.clone();
        }

        @Override
        public byte[] responseBody() {
            return responseBody.clone();
        }
    }
}
