package com.orderflow.engine.adapters.out.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderflow.engine.outbox.DeliveryException;
import com.orderflow.engine.outbox.OrderConfirmedEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The fake push gateway collapses notifications by event id, as the real one does with its collapse id. */
class FakePushGatewayTest {

    private final FakePushGateway push = new FakePushGateway();
    private final OrderConfirmedEvent event = new OrderConfirmedEvent(UUID.randomUUID(), "ec-alpha", UUID.randomUUID(),
            "EC-000007", UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-10-15T12:00:00Z"));

    @Test
    void aResentEventReachesTheGatewayAgainButIsShownOnce() {
        push.send(event);
        push.send(event);

        assertThat(push.calls(event.eventId())).as("calls").isEqualTo(2);
        assertThat(push.effects(event.eventId())).as("notifications shown").isEqualTo(1);
    }

    @Test
    void whileTheGatewayIsDownEveryCallFailsRetryablyAndNothingIsShown() {
        push.failRetryably();

        assertThatThrownBy(() -> push.send(event))
                .isInstanceOfSatisfying(DeliveryException.class, failure -> assertThat(failure.retryable()).isTrue())
                .hasMessage("push gateway is unavailable");
        assertThat(push.effects(event.eventId())).as("notifications shown").isZero();
    }
}
