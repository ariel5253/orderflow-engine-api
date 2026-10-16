package com.orderflow.engine.outbox;

/** Publishes a confirmed order to the ERP. */
public interface ErpPublisherPort {

    /**
     * Publishes the order. The ERP upserts it by order number and ignores an event id it has already applied, so a
     * re-sent event never creates a second order.
     *
     * @throws DeliveryException when the ERP does not take the order
     */
    void publish(OrderConfirmedEvent event);
}
