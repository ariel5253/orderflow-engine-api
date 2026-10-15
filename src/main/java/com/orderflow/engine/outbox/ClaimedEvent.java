package com.orderflow.engine.outbox;

import java.time.Instant;

/**
 * An event leased by one relay.
 *
 * @param attempts the failed attempts recorded so far. The attempt this claim starts is not included
 * @param leasedUntil the lease as the database stored it. An outcome is recorded only while the row still carries
 *     this exact value, so a relay whose lease expired and was taken over never overwrites the new holder's outcome
 */
public record ClaimedEvent(OrderConfirmedEvent event, Destination destination, int attempts, Instant leasedUntil) {
}
