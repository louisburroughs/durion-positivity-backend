package com.positivity.workorder.event;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantIterator;
import com.positivity.workorder.internal.entity.OutboxEvent;
import com.positivity.workorder.internal.repository.OutboxEventRepository;
import com.positivity.workorder.internal.service.OutboxReplayServiceImpl;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * ADR-0044 backfill: replaying re-queues only published events in the window; unpublished and
 * older events are untouched, and replayed rows reset their retry bookkeeping. Replay is scoped to
 * the bound tenant (ADR-0062 §3): another tenant's rows in the same window stay published.
 */
@DataJpaTest(properties = "spring.flyway.enabled=false")
@Import({OutboxReplayServiceImpl.class, OutboxReplayServiceImplIntegrationTest.RegistryConfig.class})
class OutboxReplayServiceImplIntegrationTest {

    /** The registry a platform operator's replay fans out over: tenants A and B. */
    @TestConfiguration
    static class RegistryConfig {
        @Bean
        TenantIterator tenantIterator() {
            return new TenantIterator(() -> List.of(TENANT_A, TENANT_B));
        }
    }

    @Autowired
    private OutboxReplayServiceImpl service;

    @Autowired
    private OutboxEventRepository repository;

    private OutboxEvent save(String key, Instant createdAt, Instant publishedAt, int attempts) {
        return save(TENANT_A, key, createdAt, publishedAt, attempts);
    }

    private OutboxEvent save(UUID tenantId, String key, Instant createdAt, Instant publishedAt, int attempts) {
        return repository.save(OutboxEvent.builder()
                .tenantId(tenantId)
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
        OutboxEvent otherTenant =
                save(TENANT_B, "other-tenant", Instant.parse("2026-07-02T00:00:00Z"), Instant.now(), 2);

        int queued = asTenant(TENANT_A, () -> service.replaySince(cutoff));

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
        // Tenant B's row sits in the window but belongs to another tenant: never re-sent by A's replay.
        OutboxEvent other = all.stream()
                .filter(e -> e.getRecordKey().equals("other-tenant"))
                .findFirst()
                .orElseThrow();
        assertThat(other.getPublishedAt()).isNotNull();
        assertThat(other.getAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("Replays only the bound tenant's rows of a bounded window")
    void replayBetweenIsScopedToTheBoundTenant() {
        Instant since = Instant.parse("2026-07-01T00:00:00Z");
        Instant until = Instant.parse("2026-07-02T00:00:00Z");
        save(TENANT_A, "a-in-window", Instant.parse("2026-07-01T12:00:00Z"), Instant.now(), 1);
        save(TENANT_A, "a-after-window", Instant.parse("2026-07-02T12:00:00Z"), Instant.now(), 1);
        save(TENANT_B, "b-in-window", Instant.parse("2026-07-01T12:00:00Z"), Instant.now(), 1);

        int queued = asTenant(TENANT_B, () -> service.replayBetween(since, until));

        assertThat(queued).isEqualTo(1);
        assertThat(repository.findAll())
                .filteredOn(e -> e.getPublishedAt() == null)
                .extracting(OutboxEvent::getRecordKey)
                .containsExactly("b-in-window");
    }

    @Test
    @DisplayName("Admin replay by an ordinary tenant re-queues only that tenant's rows")
    void adminReplayForOrdinaryTenantIsScopedToIt() {
        Instant cutoff = Instant.parse("2026-07-01T00:00:00Z");
        save(TENANT_A, "a", Instant.parse("2026-07-02T00:00:00Z"), Instant.now(), 1);
        save(TENANT_B, "b", Instant.parse("2026-07-02T00:00:00Z"), Instant.now(), 1);

        int queued = asTenant(TENANT_A, () -> service.replaySinceForCaller(cutoff));

        assertThat(queued).isEqualTo(1);
        assertThat(repository.findAll())
                .filteredOn(e -> e.getPublishedAt() == null)
                .extracting(OutboxEvent::getRecordKey)
                .containsExactly("a");
    }

    @Test
    @DisplayName("Admin replay by a platform-tenant operator re-queues every active tenant's rows")
    void adminReplayForPlatformOperatorFansOutOverActiveTenants() {
        Instant cutoff = Instant.parse("2026-07-01T00:00:00Z");
        save(TENANT_A, "a", Instant.parse("2026-07-02T00:00:00Z"), Instant.now(), 1);
        save(TENANT_B, "b", Instant.parse("2026-07-02T00:00:00Z"), Instant.now(), 2);
        save(TENANT_A, "a-old", Instant.parse("2026-06-01T00:00:00Z"), Instant.now(), 0);

        int queued = asTenant(PlatformTenant.ID, () -> service.replaySinceForCaller(cutoff));

        assertThat(queued).isEqualTo(2);
        assertThat(repository.findAll())
                .filteredOn(e -> e.getPublishedAt() == null)
                .extracting(OutboxEvent::getRecordKey)
                .containsExactlyInAnyOrder("a", "b");
    }

    @Test
    @DisplayName("Refuses to replay with no tenant bound rather than re-sending every tenant's rows")
    void unboundReplayFailsClosed() {
        save(TENANT_A, "a", Instant.parse("2026-07-01T12:00:00Z"), Instant.now(), 1);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.replaySince(Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(com.positivity.tenancy.TenantContextMissingException.class);
        assertThat(repository.findAll())
                .allSatisfy(e -> assertThat(e.getPublishedAt()).isNotNull());
    }
}
