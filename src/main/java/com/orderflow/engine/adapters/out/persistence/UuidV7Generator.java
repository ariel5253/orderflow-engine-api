package com.orderflow.engine.adapters.out.persistence;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Mints the ids the application gives to the rows it inserts. They are UUIDv7 (RFC 9562): the Unix time in
 * milliseconds comes first and random bits follow, so new ids sort after older ones and inserts stay local in the
 * primary key index (data model conventions).
 */
@Component
public class UuidV7Generator {

    private static final long VERSION_7 = 0x7000L;
    private static final long RAND_A_MASK = 0x0FFFL;
    private static final long VARIANT_RFC_9562 = 0x8000_0000_0000_0000L;

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public UuidV7Generator(Clock clock) {
        this.clock = clock;
    }

    public UUID next() {
        long mostSignificant = (clock.millis() << 16) | VERSION_7 | (random.nextLong() & RAND_A_MASK);
        long leastSignificant = VARIANT_RFC_9562 | (random.nextLong() >>> 2);
        return new UUID(mostSignificant, leastSignificant);
    }
}
