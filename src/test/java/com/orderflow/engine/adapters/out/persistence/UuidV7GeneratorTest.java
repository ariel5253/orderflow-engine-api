package com.orderflow.engine.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Ids are UUIDv7 (RFC 9562), so they sort by creation time and keep inserts local in the index. */
class UuidV7GeneratorTest {

    private static final Instant NOW = Instant.parse("2026-10-15T15:00:00.123Z");

    @Test
    void anIdIsAnRfc9562Version7UuidThatStartsWithTheUnixMilliseconds() {
        UUID id = generatorAt(NOW).next();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
        assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(NOW.toEpochMilli());
    }

    @Test
    void anIdMintedLaterSortsAfterAnEarlierOne() {
        UUID earlier = generatorAt(NOW).next();
        UUID later = generatorAt(NOW.plusMillis(1)).next();

        // PostgreSQL orders uuid values by their bytes, which is the order of their text form.
        assertThat(later.toString()).isGreaterThan(earlier.toString());
    }

    @Test
    void idsMintedInTheSameMillisecondDiffer() {
        UuidV7Generator generator = generatorAt(NOW);

        assertThat(generator.next()).isNotEqualTo(generator.next());
    }

    private static UuidV7Generator generatorAt(Instant now) {
        return new UuidV7Generator(Clock.fixed(now, ZoneOffset.UTC));
    }
}
