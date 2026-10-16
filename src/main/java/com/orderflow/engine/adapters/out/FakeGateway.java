package com.orderflow.engine.adapters.out;

import com.orderflow.engine.outbox.DeliveryException;
import com.orderflow.engine.outbox.OrderConfirmedEvent;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * What the fake push and ERP gateways share. Real integrations are out of scope (SPEC §1), so the service delivers to
 * these fakes. They behave like the real systems on the two points the relay relies on: an event id that was already
 * applied is ignored, so a re-send is harmless, and a failure says whether it is worth retrying. They can be told to
 * fail either way, and they count what reached them and what took effect.
 */
public abstract class FakeGateway {

    private enum Mode { WORKING, UNAVAILABLE, REJECTING }

    private final String system;
    private final Set<UUID> applied = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> calls = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> effects = new ConcurrentHashMap<>();
    private volatile Mode mode = Mode.WORKING;

    protected FakeGateway(String system) {
        this.system = system;
    }

    /**
     * Takes the event, applying {@code effect} only the first time its id arrives.
     *
     * @throws DeliveryException while the gateway is told to fail
     */
    protected final void receive(OrderConfirmedEvent event, Consumer<OrderConfirmedEvent> effect) {
        calls.merge(event.eventId(), 1, Integer::sum);
        switch (mode) {
            case UNAVAILABLE -> throw DeliveryException.retryable(system + " is unavailable");
            case REJECTING -> throw DeliveryException.nonRetryable(system + " rejected order " + event.orderNumber());
            case WORKING -> {
                if (applied.add(event.eventId())) {
                    effects.merge(event.eventId(), 1, Integer::sum);
                    effect.accept(event);
                }
            }
        }
    }

    /** From now on every call fails as if the system were down: worth retrying. */
    public void failRetryably() {
        mode = Mode.UNAVAILABLE;
    }

    /** From now on every call is rejected, like an ERP 4xx: retrying would fail the same way. */
    public void failNonRetryably() {
        mode = Mode.REJECTING;
    }

    /** From now on every call succeeds. */
    public void recover() {
        mode = Mode.WORKING;
    }

    /** How many times the event reached the gateway, failed calls and duplicates included. */
    public int calls(UUID eventId) {
        return calls.getOrDefault(eventId, 0);
    }

    /** How many times the event took effect: once at most, however often it was sent. */
    public int effects(UUID eventId) {
        return effects.getOrDefault(eventId, 0);
    }
}
