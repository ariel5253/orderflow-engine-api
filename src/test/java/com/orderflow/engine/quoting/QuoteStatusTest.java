package com.orderflow.engine.quoting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/** SPEC BR-07: the state of a quote is derived from its order and its expiry, at the moment it is read. */
class QuoteStatusTest {

    private static final Instant EXPIRES_AT = Instant.parse("2026-10-15T15:15:00Z");

    @Test
    void aQuoteIsOpenBeforeItExpires() {
        assertThat(QuoteStatus.of(false, EXPIRES_AT, EXPIRES_AT.minus(1, ChronoUnit.MINUTES)))
                .isEqualTo(QuoteStatus.OPEN);
    }

    @Test
    void aQuoteIsStillOpenAtTheInstantItExpires() {
        assertThat(QuoteStatus.of(false, EXPIRES_AT, EXPIRES_AT)).isEqualTo(QuoteStatus.OPEN);
    }

    @Test
    void aQuoteIsExpiredRightAfterItsExpiry() {
        assertThat(QuoteStatus.of(false, EXPIRES_AT, EXPIRES_AT.plus(1, ChronoUnit.MICROS)))
                .isEqualTo(QuoteStatus.EXPIRED);
    }

    @Test
    void aConfirmedQuoteStaysConfirmedAfterItsExpiry() {
        assertThat(QuoteStatus.of(true, EXPIRES_AT, EXPIRES_AT.plus(1, ChronoUnit.DAYS)))
                .isEqualTo(QuoteStatus.CONFIRMED);
    }
}
