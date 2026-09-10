package com.positivity.poseventreceiver.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.poseventreceiver.internal.entity.EmittedEvent;
import com.positivity.poseventreceiver.internal.repository.EmittedEventRepository;
import java.time.Instant;
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

    @Autowired
    private EmittedEventRepository rows;

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
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM emitted_event WHERE entity_id = 'ENTITY-1'", Integer.class))
                .as("no row-level security: raw SQL sees both tenants' rows, so every query must name the tenant")
                .isEqualTo(2);
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
                        Integer.class))
                .as("the continuous aggregate, which row security would have excluded")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT relrowsecurity FROM pg_class WHERE relname = 'emitted_event'", Boolean.class))
                .as("row security is off on the hypertable (V1_1)")
                .isFalse();
    }

    private static EmittedEvent event(String entityId) {
        return new EmittedEvent("ORDER_ORDER_CREATE", "1", 1_700_000_000_000L, 12L, Instant.now(), entityId);
    }
}
