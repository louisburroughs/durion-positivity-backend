package com.positivity.poseventreceiver.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.poseventreceiver.internal.entity.EmittedEvent;
import com.positivity.poseventreceiver.internal.repository.EmittedEventHourlyRepository;
import com.positivity.poseventreceiver.internal.repository.EmittedEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code emitted_event} is the one table exempt from row-level security (ADR-0062 exception,
 * decided 2026-09-10): TimescaleDB refuses compression and continuous aggregates on a hypertable
 * with row security, and this stream keeps both. This proves the exception's premise (the
 * hypertable is compressed and feeds the continuous aggregate) and its safeguard: every query
 * names the tenant column, raw SQL sees every tenant's rows (there is no policy to hide them),
 * and a row without a tenant is refused by the column's NOT NULL rather than by a policy.
 */
@DisplayName("Tenant column on the TimescaleDB hypertable (ADR-0062 exception, pos-event-receiver)")
class TenantIsolationIT extends PostgresTenancyTestBase {

    /** An event type no other test records, so the aggregate rows below are entirely this test's. */
    private static final String HOURLY_EVENT_TYPE = "EVENT_RECEIVER_TENANT_ISOLATION_IT_HOURLY";

    @Autowired
    private EmittedEventRepository rows;

    @Autowired
    private EmittedEventHourlyRepository hourly;

    @Autowired
    private DataSource dataSource;

    @Test
    void everyQueryNamesTheTenantAndAnUnstampedRowIsRefused() {
        EmittedEvent a = event("ENTITY-1");
        a.setTenantId(TENANT_A);
        EmittedEvent b = event("ENTITY-1");
        b.setTenantId(TENANT_B);
        rows.saveAllAndFlush(List.of(a, b));

        assertThat(rows.findByTenantIdAndEntityIdAndPublishedAtGreaterThanEqual(
                                TENANT_A, "ENTITY-1", Instant.EPOCH, PageRequest.of(0, 10))
                        .getContent())
                .as("the entity query is bound on the tenant column")
                .extracting(EmittedEvent::getEventId)
                .containsExactly(a.getEventId());
        assertThat(rows.countByEventTypeIdSince(TENANT_B, Instant.EPOCH))
                .as("the count query is bound on the tenant column")
                .singleElement()
                .satisfies(row -> {
                    assertThat(row[0]).isEqualTo("ORDER_ORDER_CREATE");
                    assertThat(row[1]).isEqualTo(1L);
                });

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM emitted_event WHERE entity_id = 'ENTITY-1'", Long.class))
                .as("no row-level security: raw SQL sees both tenants' rows, so every query must name the tenant")
                .isEqualTo(2L);
        // Unbound: app_current_tenant() is NULL, so the NOT NULL column refuses the row.
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO emitted_event (event_id, id, \"timestamp\", elapsed_ms, published_at)"
                                + " VALUES (?, 'NOBODY', 0, 0, now())",
                        UUID.randomUUID()))
                .as("a row without a tenant is refused")
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void theHypertableKeepsCompressionAndTheContinuousAggregate() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject(
                        "SELECT compression_enabled FROM timescaledb_information.hypertables"
                                + " WHERE hypertable_name = 'emitted_event'",
                        Boolean.class))
                .as("compression, which row security would have excluded")
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM timescaledb_information.continuous_aggregates"
                                + " WHERE view_name = 'emitted_event_hourly'",
                        Long.class))
                .as("the continuous aggregate, which row security would have excluded")
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "SELECT relrowsecurity FROM pg_class WHERE relname = 'emitted_event'", Boolean.class))
                .as("row security is off on the hypertable (V1_1)")
                .isFalse();
    }

    /**
     * The continuous aggregate carries the tenant dimension of plan WS6: two tenants' events land
     * in their own (bucket, tenant, event type) rows, the per-tenant read returns only its tenant's
     * count, and the platform-only rollup is their sum. Refreshed through the owner (the
     * aggregate's owner runs {@code refresh_continuous_aggregate}); events sit two hours back so
     * their bucket is complete and inside any window.
     */
    @Test
    void theHourlyAggregateIsGroupedByTenantAndTheRollupIsTheSum() {
        Instant publishedAt = Instant.now()
                .minus(Duration.ofHours(2))
                .truncatedTo(ChronoUnit.HOURS)
                .plus(Duration.ofMinutes(10));
        EmittedEvent a1 = hourlyEvent(TENANT_A, publishedAt);
        EmittedEvent a2 = hourlyEvent(TENANT_A, publishedAt.plusSeconds(60));
        EmittedEvent b1 = hourlyEvent(TENANT_B, publishedAt);
        rows.saveAllAndFlush(List.of(a1, a2, b1));

        new JdbcTemplate(ownerDataSource())
                .execute("CALL refresh_continuous_aggregate('emitted_event_hourly', NULL, NULL)");

        assertThat(countOf(hourly.summarizeSince(TENANT_A, Instant.EPOCH)))
                .as("tenant A reads its own two events")
                .isEqualTo(2L);
        assertThat(countOf(hourly.summarizeSince(TENANT_B, Instant.EPOCH)))
                .as("tenant B reads its own one event")
                .isEqualTo(1L);
        assertThat(countOf(hourly.summarizeAcrossTenantsSince(Instant.EPOCH)))
                .as("the global rollup is the sum across tenants")
                .isEqualTo(3L);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM emitted_event_hourly WHERE event_type = ?",
                        Long.class,
                        HOURLY_EVENT_TYPE))
                .as("one aggregate row per tenant for the bucket: the view is grouped by tenant_id")
                .isEqualTo(2L);
    }

    private static long countOf(List<Object[]> summary) {
        return summary.stream()
                .filter(row -> HOURLY_EVENT_TYPE.equals(row[0]))
                .mapToLong(row -> ((Number) row[1]).longValue())
                .sum();
    }

    private static EmittedEvent hourlyEvent(UUID tenantId, Instant publishedAt) {
        EmittedEvent event = new EmittedEvent(HOURLY_EVENT_TYPE, "1", 1_700_000_000_000L, 12L, publishedAt, null);
        event.setTenantId(tenantId);
        return event;
    }

    private static EmittedEvent event(String entityId) {
        return new EmittedEvent("ORDER_ORDER_CREATE", "1", 1_700_000_000_000L, 12L, Instant.now(), entityId);
    }
}
