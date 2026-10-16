package com.orderflow.engine.config;

import com.orderflow.engine.ordering.ConfirmOrderUseCase;
import com.orderflow.engine.ordering.CreditLedger;
import com.orderflow.engine.ordering.IdempotencyStore;
import com.orderflow.engine.ordering.OrderRepository;
import com.orderflow.engine.ordering.OutboxWriter;
import com.orderflow.engine.quoting.QuoteRepository;
import com.orderflow.engine.quoting.SnapshotProvider;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the order confirmation to its ports. The use case is a bean of its own, so the controller calls it through its
 * transaction proxy and the transaction can never be skipped by a call from within the same class.
 */
@Configuration(proxyBeanMethods = false)
public class OrderingConfig {

    @Bean
    ConfirmOrderUseCase confirmOrderUseCase(SnapshotProvider snapshots, IdempotencyStore idempotency,
            OrderRepository orders, QuoteRepository quotes, CreditLedger credit, OutboxWriter outbox, Clock clock) {
        return new ConfirmOrderUseCase(snapshots, idempotency, orders, quotes, credit, outbox, clock);
    }
}
