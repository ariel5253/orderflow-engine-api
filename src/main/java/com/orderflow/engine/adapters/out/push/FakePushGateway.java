package com.orderflow.engine.adapters.out.push;

import com.orderflow.engine.adapters.out.FakeGateway;
import com.orderflow.engine.outbox.OrderConfirmedEvent;
import com.orderflow.engine.outbox.PushNotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** A push gateway that collapses notifications by their collapse id, the event id, as real push services do. */
@Component
public class FakePushGateway extends FakeGateway implements PushNotificationPort {

    private static final Logger LOG = LoggerFactory.getLogger(FakePushGateway.class);

    public FakePushGateway() {
        super("push gateway");
    }

    @Override
    public void send(OrderConfirmedEvent event) {
        receive(event, shown -> LOG.info("Push notification {} shown to customer {} for order {}",
                shown.eventId(), shown.customerId(), shown.orderNumber()));
    }
}
