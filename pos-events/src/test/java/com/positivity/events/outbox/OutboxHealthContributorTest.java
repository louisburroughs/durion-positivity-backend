package com.positivity.events.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.events.outbox.OutboxHealthContributor.DrainHead;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class OutboxHealthContributorTest {

    private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration LAG_THRESHOLD = Duration.ofMinutes(5);

    private final AtomicReference<Optional<DrainHead>> head = new AtomicReference<>(Optional.empty());
    private final AtomicReference<Long> pending = new AtomicReference<>(0L);

    private OutboxHealthContributor contributor(SimpleMeterRegistry registry) {
        return new OutboxHealthContributor(
                "test.outbox", CLOCK, LAG_THRESHOLD, registry, () -> pending.get(), head::get);
    }

    @Test
    @DisplayName("Gauges report pending count and oldest unpublished age (0 when drained)")
    void gaugesReportBacklogAndHeadAge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        pending.set(7L);
        head.set(Optional.of(new DrainHead(NOW.minusSeconds(300), 2, null)));

        contributor(registry);

        assertThat(registry.get("test.outbox.pending").gauge().value()).isEqualTo(7.0);
        assertThat(registry.get("test.outbox.oldest.age.seconds").gauge().value())
                .isEqualTo(300.0);

        head.set(Optional.empty());
        assertThat(registry.get("test.outbox.oldest.age.seconds").gauge().value())
                .isZero();
    }

    @Test
    @DisplayName("Gauges survive a failing read: NaN instead of a scrape-breaking throw")
    void gaugesNeverThrow() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LongSupplier throwingCount = () -> {
            throw new IllegalStateException("db down");
        };
        Supplier<Optional<DrainHead>> throwingHead = () -> {
            throw new IllegalStateException("db down");
        };
        new OutboxHealthContributor("test.outbox", CLOCK, LAG_THRESHOLD, registry, throwingCount, throwingHead);

        assertThat(registry.get("test.outbox.pending").gauge().value()).isNaN();
        assertThat(registry.get("test.outbox.oldest.age.seconds").gauge().value())
                .isNaN();
    }

    @Test
    @DisplayName("Drained outbox reports UP with drainState=drained")
    void drainedState() {
        Health health = contributor(null).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("drainState", "drained");
    }

    @Test
    @DisplayName("Head younger than the threshold reports draining")
    void drainingState() {
        head.set(Optional.of(new DrainHead(NOW.minusSeconds(60), 1, "transient broker timeout")));

        Health health = contributor(null).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("drainState", "draining")
                .containsEntry("oldestAgeSeconds", 60L)
                .containsEntry("headAttempts", 1)
                .containsEntry("headLastError", "transient broker timeout");
    }

    @Test
    @DisplayName("Old head with zero attempts reports stalled-never-attempted (publisher not running)")
    void stalledNeverAttempted() {
        head.set(Optional.of(new DrainHead(NOW.minus(Duration.ofMinutes(20)), 0, null)));

        Health health = contributor(null).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("drainState", "stalled-never-attempted")
                .doesNotContainKey("headLastError");
    }

    @Test
    @DisplayName("Old head with recorded attempts reports stalled-retrying and truncates the error")
    void stalledRetryingTruncatesError() {
        String longError = "x".repeat(1000);
        head.set(Optional.of(new DrainHead(NOW.minus(Duration.ofMinutes(20)), 17_000, longError)));

        Health health = contributor(null).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("drainState", "stalled-retrying")
                .containsEntry("headAttempts", 17_000);
        assertThat(health.getDetails().get("headLastError"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .hasSize(OutboxHealthContributor.LAST_ERROR_DETAIL_MAX_CHARS);
    }

    @Test
    @DisplayName("Writer clock ahead of ours floors the age at zero instead of going negative")
    void futureHeadFloorsAtZero() {
        head.set(Optional.of(new DrainHead(NOW.plusSeconds(30), 0, null)));

        Health health = contributor(null).health();

        assertThat(health.getDetails()).containsEntry("oldestAgeSeconds", 0L).containsEntry("drainState", "draining");
    }

    @Test
    @DisplayName("A failing head read still reports UP, with drainState=unknown")
    void healthNeverThrows() {
        Supplier<Optional<DrainHead>> throwingHead = () -> {
            throw new IllegalStateException("db down");
        };
        OutboxHealthContributor broken =
                new OutboxHealthContributor("test.outbox", CLOCK, LAG_THRESHOLD, null, () -> 0L, throwingHead);

        Health health = broken.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("drainState", "unknown")
                .containsEntry("error", "IllegalStateException");
    }
}
