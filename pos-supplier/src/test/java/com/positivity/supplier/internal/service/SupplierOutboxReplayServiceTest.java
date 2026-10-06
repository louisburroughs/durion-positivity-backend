package com.positivity.supplier.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.supplier.PostgresSliceTestBase;
import com.positivity.supplier.TestClockConfig;
import com.positivity.supplier.internal.config.JpaConfig;
import com.positivity.supplier.internal.entity.SupplierOutboxEventEntity;
import com.positivity.supplier.internal.repository.SupplierOutboxEventRepository;
import com.positivity.tenancy.testing.TenantTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The {@code supplier.outbox.replay-requested} work (#2516, ADR-0044 §4): only the bound tenant's
 * published {@code supplier.events.v1} rows inside the window are re-queued, each keeping its row —
 * and so its stored envelope and original event id.
 */
@Import({JpaConfig.class, TestClockConfig.class, SupplierOutboxReplayService.class})
@DisplayName("SupplierOutboxReplayService — tenant- and window-bounded re-queue (#2516)")
class SupplierOutboxReplayServiceTest extends PostgresSliceTestBase {

    private static final Instant SINCE = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant UNTIL = Instant.parse("2026-10-01T11:00:00Z");

    @Autowired
    private SupplierOutboxReplayService service;

    @Autowired
    private SupplierOutboxEventRepository outboxRepository;

    @Autowired
    private DataSource dataSource;

    private UUID row(UUID tenantId, String topic, Instant createdAt, boolean published) {
        UUID id = UUID.randomUUID();
        new JdbcTemplate(dataSource)
                .update(
                        "INSERT INTO supplier_event_outbox (id, tenant_id, topic, record_key, event_type, payload,"
                                + " created_at, published_at, attempts) VALUES (?, ?, ?, 'k', 'supplier.vendor.updated',"
                                + " '{\"eventId\":\"" + id + "\"}', ?, ?, 0)",
                        id,
                        tenantId,
                        topic,
                        Timestamp.from(createdAt),
                        published ? Timestamp.from(createdAt.plusSeconds(5)) : null);
        return id;
    }

    @Test
    @DisplayName("AC 11: only this tenant's published fact rows in the window are re-queued, with their original rows")
    void reQueuesOnlyTheTenantsFactRowsInTheWindow() {
        UUID inWindow = row(TENANT, "supplier.events.v1", SINCE.plusSeconds(60), true);
        UUID beforeWindow = row(TENANT, "supplier.events.v1", SINCE.minusSeconds(60), true);
        UUID afterWindow = row(TENANT, "supplier.events.v1", UNTIL.plusSeconds(60), true);
        UUID otherTopic = row(TENANT, "supplier.manifest.v1", SINCE.plusSeconds(60), true);
        UUID otherTenant = row(TenantTestSupport.TENANT_B, "supplier.events.v1", SINCE.plusSeconds(60), true);

        int queued = service.replayEventsBetween(SINCE, UNTIL);

        assertThat(queued).isEqualTo(1);
        assertThat(published(inWindow)).as("re-queued: publishedAt cleared").isFalse();
        assertThat(outboxRepository.findById(inWindow).orElseThrow().getPayload())
                .as("the original envelope, and so the original event id, is what is re-sent")
                .contains(inWindow.toString());
        assertThat(published(beforeWindow)).isTrue();
        assertThat(published(afterWindow)).isTrue();
        assertThat(published(otherTopic)).isTrue();
        assertThat(published(otherTenant))
                .as("another tenant's events are never re-sent")
                .isTrue();
    }

    private boolean published(UUID id) {
        SupplierOutboxEventEntity row = outboxRepository.findById(id).orElseThrow();
        return row.getPublishedAt() != null;
    }
}
