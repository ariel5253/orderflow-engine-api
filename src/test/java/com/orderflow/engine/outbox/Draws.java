package com.orderflow.engine.outbox;

import java.util.random.RandomGenerator;

/** Random generators that always draw one end of the requested range, so a jittered wait becomes predictable. */
final class Draws {

    private Draws() {
    }

    static RandomGenerator lowest() {
        return new Fixed(false);
    }

    static RandomGenerator highest() {
        return new Fixed(true);
    }

    private record Fixed(boolean highest) implements RandomGenerator {

        @Override
        public long nextLong() {
            throw new AssertionError("A wait must be drawn from a bounded range");
        }

        @Override
        public long nextLong(long bound) {
            return highest ? bound - 1 : 0;
        }
    }
}
