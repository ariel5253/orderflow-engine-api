package com.orderflow.engine.adapters.out.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderflow.engine.outbox.DeliveryException;
import com.orderflow.engine.outbox.OrderConfirmedEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The fake ERP behaves like the real one on what the relay relies on: re-sends are harmless, failures are typed. */
class FakeErpGatewayTest {

    private final FakeErpGateway erp = new FakeErpGateway();
    private final OrderConfirmedEvent event = new OrderConfirmedEvent(UUID.randomUUID(), "co-alpha", UUID.randomUUID(),
            "CO-000042", UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-10-15T12:00:00Z"));

    @Test
    void aResentEventReachesTheErpAgainButIsAppliedOnce() {
        erp.publish(event);
        erp.publish(event);

        assertThat(erp.calls(event.eventId())).as("calls").isEqualTo(2);
        assertThat(erp.effects(event.eventId())).as("upserts").isEqualTo(1);
        assertThat(erp.order("CO-000042")).contains(event);
    }

    @Test
    void whileTheErpIsDownEveryCallFailsRetryablyAndNothingIsApplied() {
        erp.failRetryably();

        assertThatThrownBy(() -> erp.publish(event))
                .isInstanceOfSatisfying(DeliveryException.class, failure -> assertThat(failure.retryable()).isTrue())
                .hasMessage("ERP is unavailable");
        assertThat(erp.calls(event.eventId())).as("calls").isEqualTo(1);
        assertThat(erp.effects(event.eventId())).as("upserts").isZero();
        assertThat(erp.order("CO-000042")).isEmpty();
    }

    @Test
    void aRejectedOrderFailsWithoutRetry() {
        erp.failNonRetryably();

        assertThatThrownBy(() -> erp.publish(event))
                .isInstanceOfSatisfying(DeliveryException.class, failure -> assertThat(failure.retryable()).isFalse())
                .hasMessage("ERP rejected order CO-000042");
        assertThat(erp.order("CO-000042")).isEmpty();
    }

    @Test
    void afterRecoveringTheErpAppliesTheOrderAgain() {
        erp.failRetryably();
        assertThatThrownBy(() -> erp.publish(event)).isInstanceOf(DeliveryException.class);

        erp.recover();
        erp.publish(event);

        assertThat(erp.effects(event.eventId())).as("upserts").isEqualTo(1);
        assertThat(erp.order("CO-000042")).contains(event);
    }
}
