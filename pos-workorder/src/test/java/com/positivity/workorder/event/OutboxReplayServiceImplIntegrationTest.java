package com.positivity.workorder.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.workorder.internal.entity.OutboxEvent;
import com.positivity.workorder.internal.repository.OutboxEventRepository;
import com.positivity.workorder.internal.service.OutboxReplayServiceImpl;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * ADR-0044 backfill: replaying re-queues only published events in the window; unpublished and
 * older events are untouched, and replayed rows reset their retry bookkeeping.
 */
@DataJpaTest(properties = "spring.flyway.enabled=false")
@Import(OutboxReplayServiceImpl.class)
class OutboxReplayServiceImplIntegrationTest {

    @Autowired
    private OutboxReplayServiceImpl service;

    @Autowired
    private OutboxEventRepository repository;

    private OutboxEvent save(String key, Instant createdAt, Instant publishedAt, int attempts) {
        return repository.save(OutboxEvent.builder()
                .topic("workorder.events.v1")
                .recordKey(key)
                .payload("{\"eventId\":\"" + key + "\"}")
                .createdAt(createdAt)
                .publishedAt(publishedAt)
                .attempts(attempts)
                .lastError(attempts > 0 ? "previous failure" : null)
                .build());
    }

    @Test
    @DisplayName("Replays only published events at or after the cutoff and resets retry state")
    void replaysPublishedEventsInWindow() {
        Instant cutoff = Instant.parse("2026-07-01T00:00:00Z");
        OutboxEvent oldPublished = save("old", Instant.parse("2026-06-01T00:00:00Z"), Instant.now(), 2);
        OutboxEvent recentPublished = save("recent", Instant.parse("2026-07-02T00:00:00Z"), Instant.now(), 3);
        OutboxEvent recentUnpublished = save("pending", Instant.parse("2026-07-03T00:00:00Z"), null, 1);

        int queued = service.replaySince(cutoff);

        assertThat(queued).isEqualTo(1);
        List<OutboxEvent> all = repository.findAll();
        OutboxEvent old = all.stream()
                .filter(e -> e.getRecordKey().equals("old"))
                .findFirst()
                .orElseThrow();
        OutboxEvent recent = all.stream()
                .filter(e -> e.getRecordKey().equals("recent"))
                .findFirst()
                .orElseThrow();
        OutboxEvent pending = all.stream()
                .filter(e -> e.getRecordKey().equals("pending"))
                .findFirst()
                .orElseThrow();

        assertThat(old.getPublishedAt()).isNotNull();
        assertThat(recent.getPublishedAt()).isNull();
        assertThat(recent.getAttempts()).isZero();
        assertThat(recent.getLastError()).isNull();
        assertThat(pending.getPublishedAt()).isNull();
        assertThat(pending.getAttempts()).isEqualTo(1);
    }
}
