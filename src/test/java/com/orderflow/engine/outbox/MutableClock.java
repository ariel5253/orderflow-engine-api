package com.orderflow.engine.outbox;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock the test moves by hand, so leases and retry times are reached exactly, without waiting. */
final class MutableClock extends Clock {

    private volatile Instant now;

    MutableClock(Instant start) {
        this.now = start;
    }

    void set(Instant instant) {
        now = instant;
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("The outbox reads instants only, in UTC");
    }
}
