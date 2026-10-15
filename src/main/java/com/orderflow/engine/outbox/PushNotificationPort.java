package com.orderflow.engine.outbox;

/** Sends the push notification of a confirmed order. */
public interface PushNotificationPort {

    /**
     * Sends the notification, using the event id as its collapse id, so a re-sent event is shown once.
     *
     * @throws DeliveryException when the push gateway does not take the notification
     */
    void send(OrderConfirmedEvent event);
}
