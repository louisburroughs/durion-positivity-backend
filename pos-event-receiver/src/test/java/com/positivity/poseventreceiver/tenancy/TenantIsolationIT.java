package com.positivity.poseventreceiver.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.poseventreceiver.internal.entity.EmittedEvent;
import com.positivity.poseventreceiver.internal.repository.EmittedEventRepository;
import com.positivity.tenancy.TenantContext;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the isolation, not just the mapping (plan R-B7): a row written as tenant A is invisible to
 * tenant B through the repository (Hibernate's {@code @TenantId} filter) and through a raw {@code
 * JdbcTemplate} on the same pool (row-level security alone), and an unbound connection can neither
 * read nor write a scoped table. The emitted event is the subject: the hypertable every read here
 * starts from, so this also proves row-level security on a TimescaleDB hypertable.
 */
@DisplayName("Tenant isolation on TimescaleDB (ADR-0062, pos-event-receiver)")
class TenantIsolationIT extends PostgresTenancyTestBase {

    @Autowired
    private EmittedEventRepository rows;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aRowWrittenAsOneTenantIsInvisibleToAnotherAndToNoTenant() {
        UUID id = asTenant(TENANT_A, () -> rows.saveAndFlush(event()).getEventId());

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        asTenant(TENANT_A, () -> {
            assertThat(rows.findById(id))
                    .as("owner reads through the repository")
                    .isPresent();
            assertThat(rows.findById(id).orElseThrow().getTenantId()).isEqualTo(TENANT_A);
            assertThat(countById(jdbc, id)).as("owner reads through raw SQL").isEqualTo(1);
        });

        asTenant(TENANT_B, () -> {
            assertThat(rows.findById(id))
                    .as("Hibernate filter hides the other tenant's row")
                    .isEmpty();
            assertThat(countById(jdbc, id)).as("RLS hides it from raw SQL too").isZero();
            assertThat(jdbc.update("UPDATE emitted_event SET id = 'HIJACKED' WHERE event_id = ?", id))
                    .as("RLS makes the row unreachable for UPDATE")
                    .isZero();
        });

        // Unbound: the pool RESETs app.current_tenant, so pos_app sees an empty table and cannot insert.
        assertThat(countById(jdbc, id)).isZero();
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO emitted_event (event_id, id, \"timestamp\", elapsed_ms, published_at)"
                                + " VALUES (?, 'NOBODY', 0, 0, now())",
                        UUID.randomUUID()))
                .as("no tenant bound: the NOT NULL default is NULL and the policy's WITH CHECK refuses the row")
                .isInstanceOf(DataAccessException.class);

        asTenant(
                TENANT_A,
                () -> assertThat(rows.findById(id).orElseThrow().getId())
                        .as("tenant B's UPDATE touched nothing")
                        .isEqualTo("ORDER_ORDER_CREATE"));
    }

    private static EmittedEvent event() {
        return new EmittedEvent("ORDER_ORDER_CREATE", "1", 1_700_000_000_000L, 12L, Instant.now(), null);
    }

    private static int countById(JdbcTemplate jdbc, UUID id) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM emitted_event WHERE event_id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }
}
