package com.positivity.order.internal.repository;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.order.PostgresSliceTestBase;
import com.positivity.order.internal.entity.OutboxEvent;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The outbox queries behind the reconciliation manifest and its replay (ADR-0044 §4, #2579), against the
 * real PostgreSQL schema. A replay re-queues only the requesting tenant's published {@code order.events.v1}
 * rows of the window: the commands this module queues on the same outbox for other owners, another tenant's
 * facts and rows outside the window keep their publication, and a row not yet published is left alone.
 * A re-queued row is back in the drain head, with its original payload and so its original event id.
 */
@DisplayName("Order outbox manifest and replay queries on PostgreSQL")
class OutboxEventRepositoryTest extends PostgresSliceTestBase {

    private static final String FACTS = "order.events.v1";
    private static final String COMMANDS = "supplier.commands.v1";
    /** Long past, so no row another test commits to the shared container (stamped "now") falls in it. */
    private static final Instant WINDOW_START = Instant.parse("2025-01-15T10:00:00Z");

    private static final Instant WINDOW_END = Instant.parse("2025-01-15T11:00:00Z");

    @Autowired
    private OutboxEventRepository outbox;

    /** A row created at {@code createdAt}, published a second later unless {@code published} is false. */
    private OutboxEvent row(UUID tenantId, String topic, Instant createdAt, boolean published) {
        OutboxEvent saved = outbox.saveAndFlush(OutboxEvent.builder()
                .tenantId(tenantId)
                .topic(topic)
                .recordKey("key")
                .payload("{\"eventId\":\"" + UUID.randomUUID() + "\"}")
                .build());
        // Auditing stamps createdAt with the clock on insert; set the instant under test afterwards.
        saved.setCreatedAt(createdAt);
        saved.setPublishedAt(published ? createdAt.plusSeconds(1) : null);
        return outbox.saveAndFlush(saved);
    }

    private boolean requeued(OutboxEvent row) {
        return outbox.findById(row.getId()).orElseThrow().getPublishedAt() == null;
    }

    @Test
    @DisplayName("the manifest query returns the window's published rows of the facts topic only")
    void manifestQueryReadsPublishedFactsOfTheWindow() {
        OutboxEvent fact = row(TENANT, FACTS, WINDOW_START.plusSeconds(60), true);
        OutboxEvent otherTenant = row(TENANT_B, FACTS, WINDOW_START.plusSeconds(90), true);
        row(TENANT, FACTS, WINDOW_START.plusSeconds(120), false);
        row(TENANT, COMMANDS, WINDOW_START.plusSeconds(60), true);
        row(TENANT, FACTS, WINDOW_END.plus(1, ChronoUnit.HOURS), true);

        List<OutboxEvent> rows =
                outbox.findByTopicAndPublishedAtIsNotNullAndCreatedAtBetween(FACTS, WINDOW_START, WINDOW_END);

        assertThat(rows).extracting(OutboxEvent::getId).containsExactlyInAnyOrder(fact.getId(), otherTenant.getId());
    }

    @Test
    @DisplayName("a bounded replay re-queues the tenant's published facts of [since, until) and nothing else")
    void replayBetweenRequeuesOnlyTheTenantsFactsOfTheWindow() {
        OutboxEvent inWindow = row(TENANT, FACTS, WINDOW_START.plusSeconds(60), true);
        OutboxEvent atStart = row(TENANT, FACTS, WINDOW_START, true);
        OutboxEvent atEnd = row(TENANT, FACTS, WINDOW_END, true);
        OutboxEvent before = row(TENANT, FACTS, WINDOW_START.minusSeconds(1), true);
        OutboxEvent otherTenant = row(TENANT_B, FACTS, WINDOW_START.plusSeconds(60), true);
        OutboxEvent command = row(TENANT, COMMANDS, WINDOW_START.plusSeconds(60), true);

        int queued = outbox.markForReplayBetween(TENANT, FACTS, WINDOW_START, WINDOW_END);

        assertThat(queued).isEqualTo(2);
        assertThat(requeued(inWindow)).isTrue();
        assertThat(requeued(atStart)).isTrue();
        assertThat(requeued(atEnd)).as("until is exclusive").isFalse();
        assertThat(requeued(before)).isFalse();
        assertThat(requeued(otherTenant)).as("another tenant's facts").isFalse();
        assertThat(requeued(command))
                .as("a command queued for another owner is not a fact")
                .isFalse();
    }

    @Test
    @DisplayName("an open replay re-queues the tenant's published facts since its start")
    void replaySinceRequeuesEverythingAfterItsStart() {
        OutboxEvent before = row(TENANT, FACTS, WINDOW_START.minusSeconds(1), true);
        OutboxEvent later = row(TENANT, FACTS, WINDOW_END.plus(5, ChronoUnit.HOURS), true);
        OutboxEvent otherTenant = row(TENANT_B, FACTS, WINDOW_END, true);

        int queued = outbox.markForReplaySince(TENANT, FACTS, WINDOW_START);

        assertThat(queued).isEqualTo(1);
        assertThat(requeued(later)).isTrue();
        assertThat(requeued(before)).isFalse();
        assertThat(requeued(otherTenant)).isFalse();
    }

    @Test
    @DisplayName("a re-queued fact is back in the drain queue with its original payload, attempts and error reset")
    void requeuedFactIsDrainedAgainWithItsOriginalEnvelope() {
        OutboxEvent fact = row(TENANT, FACTS, WINDOW_START.plusSeconds(60), true);
        fact.setAttempts(3);
        fact.setLastError("earlier broker trouble");
        outbox.saveAndFlush(fact);
        String payload = fact.getPayload();

        outbox.markForReplayBetween(TENANT, FACTS, WINDOW_START, WINDOW_END);

        assertThat(outbox.findTop100ByPublishedAtIsNullOrderByIdAsc())
                .filteredOn(drained -> drained.getId().equals(fact.getId()))
                .singleElement()
                .satisfies(drained -> {
                    assertThat(drained.getId()).isEqualTo(fact.getId());
                    assertThat(drained.getPayload()).isEqualTo(payload);
                    assertThat(drained.getAttempts()).isZero();
                    assertThat(drained.getLastError()).isNull();
                });
    }
}
