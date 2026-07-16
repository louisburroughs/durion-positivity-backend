package com.positivity.warranty.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.warranty.internal.entity.OutboxEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

/**
 * Database-level contract of the outbox drain/replay queries (ADR-0044 §4): pending-row
 * selection order, replay re-queueing of already-published rows only, and the half-open
 * {@code [since, until)} replay window. Runs against the Flyway baseline on H2 in PostgreSQL
 * mode with {@code ddl-auto=validate} (same recipe as {@code WarrantyPersistenceTest}).
 *
 * <p>{@code createdAt} is pinned to exact window boundaries with a bulk update after insert,
 * since entity-listener auditing stamps it with now() on persist.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_warranty_outbox;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=validate"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OutboxEventRepositoryTest {

    private static final Instant SINCE = Instant.parse("2026-07-15T10:00:00Z");
    private static final Instant UNTIL = Instant.parse("2026-07-15T11:00:00Z");
    private static final String TOPIC = "warranty.events.v1";

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private OutboxEvent persist(String key, Instant createdAt, Instant publishedAt, int attempts, String lastError) {
        OutboxEvent saved = repository.saveAndFlush(OutboxEvent.builder()
                .topic(TOPIC)
                .recordKey(key)
                .payload("{\"eventId\":\"" + key + "\"}")
                .createdAt(createdAt)
                .publishedAt(publishedAt)
                .attempts(attempts)
                .lastError(lastError)
                .build());
        // Pin createdAt to the exact window boundary via a bulk update: entity-listener
        // auditing stamps @CreatedDate with now() on persist, which would move the row out of
        // the boundary being tested.
        entityManager
                .createQuery("update OutboxEvent e set e.createdAt = :createdAt where e.id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", saved.getId())
                .executeUpdate();
        entityManager.clear();
        return repository.findById(saved.getId()).orElseThrow();
    }

    @Test
    void pendingSelectionReturnsOnlyUnpublishedRowsInIdOrder() {
        OutboxEvent published = persist("published", SINCE, SINCE.plusSeconds(1), 0, null);
        OutboxEvent oldest = persist("pending-1", SINCE, null, 0, null);
        OutboxEvent newest = persist("pending-2", SINCE.plusSeconds(5), null, 2, "broker down");

        List<OutboxEvent> pending = repository.findTop100ByPublishedAtIsNullOrderByIdAsc();

        // UUIDv7 ids are time-ordered, so id order == insertion order: oldest first.
        assertThat(pending).extracting(OutboxEvent::getId).containsExactly(oldest.getId(), newest.getId());
        assertThat(pending).extracting(OutboxEvent::getRecordKey).doesNotContain(published.getRecordKey());
    }

    @Test
    void markForReplaySinceRequeuesOnlyPublishedRowsAtOrAfterTheBoundary() {
        OutboxEvent beforeWindow = persist("before", SINCE.minusSeconds(10), SINCE, 0, null);
        OutboxEvent atBoundary = persist("at-since", SINCE, SINCE.plusSeconds(1), 3, "stale error");
        OutboxEvent afterBoundary = persist("after", SINCE.plusSeconds(10), SINCE.plusSeconds(11), 0, null);
        OutboxEvent unpublished = persist("unpublished", SINCE.plusSeconds(20), null, 4, "still failing");

        int requeued = repository.markForReplaySince(SINCE);

        assertThat(requeued).isEqualTo(2);
        OutboxEvent reloadedAtBoundary = reload(atBoundary.getId());
        assertThat(reloadedAtBoundary.getPublishedAt()).isNull();
        assertThat(reloadedAtBoundary.getAttempts()).isZero();
        assertThat(reloadedAtBoundary.getLastError()).isNull();
        assertThat(reload(afterBoundary.getId()).getPublishedAt()).isNull();
        // Published before the window: untouched.
        assertThat(reload(beforeWindow.getId()).getPublishedAt()).isNotNull();
        // Never published: not double-reset — its failure bookkeeping is preserved.
        OutboxEvent reloadedUnpublished = reload(unpublished.getId());
        assertThat(reloadedUnpublished.getAttempts()).isEqualTo(4);
        assertThat(reloadedUnpublished.getLastError()).isEqualTo("still failing");
    }

    @Test
    void markForReplayBetweenIsHalfOpenSinceInclusiveUntilExclusive() {
        OutboxEvent atSince = persist("at-since", SINCE, SINCE.plusSeconds(1), 1, "err");
        OutboxEvent inside = persist("inside", UNTIL.minusSeconds(1), UNTIL, 0, null);
        OutboxEvent atUntil = persist("at-until", UNTIL, UNTIL.plusSeconds(1), 0, null);
        OutboxEvent beforeSince = persist("before-since", SINCE.minusSeconds(1), SINCE, 0, null);

        int requeued = repository.markForReplayBetween(SINCE, UNTIL);

        assertThat(requeued).isEqualTo(2);
        // Row at exactly :since IS requeued (inclusive lower bound).
        assertThat(reload(atSince.getId()).getPublishedAt()).isNull();
        assertThat(reload(inside.getId()).getPublishedAt()).isNull();
        // Row at exactly :until is NOT requeued (exclusive upper bound).
        assertThat(reload(atUntil.getId()).getPublishedAt()).isNotNull();
        assertThat(reload(beforeSince.getId()).getPublishedAt()).isNotNull();
    }

    private OutboxEvent reload(UUID id) {
        return repository.findById(id).orElseThrow();
    }
}
