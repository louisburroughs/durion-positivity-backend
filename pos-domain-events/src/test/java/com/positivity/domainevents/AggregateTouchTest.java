package com.positivity.domainevents;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AggregateTouchTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00.000Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void neverStampedRowGetsTheClockReading() {
        assertThat(AggregateTouch.monotonicUpdatedAt(null, CLOCK)).isEqualTo(NOW);
    }

    @Test
    void olderRowGetsTheClockReading() {
        assertThat(AggregateTouch.monotonicUpdatedAt(NOW.minusSeconds(60), CLOCK))
                .isEqualTo(NOW);
    }

    @Test
    void clockTieAdvancesOneMillisecondPastTheRow() {
        // Load-bearing (#1486): stamping an updatedAt equal to the row's current value is a
        // same-value no-op — the row stays clean, Hibernate's @Version never increments, and the
        // fact goes out carrying new content under an unchanged aggregateVersion. The bump must
        // always be a change.
        assertThat(AggregateTouch.monotonicUpdatedAt(NOW, CLOCK)).isEqualTo(NOW.plusMillis(1));
    }

    @Test
    void rowAheadOfTheClockAdvancesOneMillisecondPastTheRow() {
        Instant ahead = NOW.plusSeconds(5);
        assertThat(AggregateTouch.monotonicUpdatedAt(ahead, CLOCK)).isEqualTo(ahead.plusMillis(1));
    }
}
