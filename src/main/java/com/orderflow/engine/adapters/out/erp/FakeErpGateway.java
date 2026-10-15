package com.orderflow.engine.adapters.out.erp;

import com.orderflow.engine.adapters.out.FakeGateway;
import com.orderflow.engine.outbox.ErpPublisherPort;
import com.orderflow.engine.outbox.OrderConfirmedEvent;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** An ERP that upserts orders by order number, so one order is recorded once however often it arrives. */
@Component
public class FakeErpGateway extends FakeGateway implements ErpPublisherPort {

    private static final Logger LOG = LoggerFactory.getLogger(FakeErpGateway.class);

    private final Map<String, OrderConfirmedEvent> ordersByNumber = new ConcurrentHashMap<>();

    public FakeErpGateway() {
        super("ERP");
    }

    @Override
    public void publish(OrderConfirmedEvent event) {
        receive(event, order -> {
            ordersByNumber.put(order.orderNumber(), order);
            LOG.info("ERP order {} upserted from event {}", order.orderNumber(), order.eventId());
        });
    }

    /** The order the ERP holds under this number, if it ever received it. */
    public Optional<OrderConfirmedEvent> order(String orderNumber) {
        return Optional.ofNullable(ordersByNumber.get(orderNumber));
    }
}
