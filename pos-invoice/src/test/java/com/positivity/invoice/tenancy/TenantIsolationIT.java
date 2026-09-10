package com.positivity.invoice.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.invoice.internal.entity.ExtLocationReplica;
import com.positivity.invoice.internal.repository.ExtLocationReplicaRepository;
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
 * read nor write a scoped table. The location replica is the subject: the simplest scoped table here,
 * and the one every location-scoped read consults.
 */
@DisplayName("Tenant isolation on Postgres (ADR-0062, pos-invoice)")
class TenantIsolationIT extends PostgresTenancyTestBase {

    @Autowired
    private ExtLocationReplicaRepository locations;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aRowWrittenAsOneTenantIsInvisibleToAnotherAndToNoTenant() {
        UUID locationId = UUID.randomUUID();
        asTenant(
                TENANT_A,
                () -> locations.saveAndFlush(ExtLocationReplica.builder()
                        .locationId(locationId)
                        .name("Depot A")
                        .active(true)
                        .aggregateVersion(1L)
                        .updatedAt(Instant.now())
                        .build()));

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        asTenant(TENANT_A, () -> {
            assertThat(locations.findById(locationId))
                    .as("owner reads through the repository")
                    .isPresent();
            assertThat(locations.findById(locationId).orElseThrow().getTenantId())
                    .isEqualTo(TENANT_A);
            assertThat(countById(jdbc, locationId))
                    .as("owner reads through raw SQL")
                    .isEqualTo(1);
        });

        asTenant(TENANT_B, () -> {
            assertThat(locations.findById(locationId))
                    .as("Hibernate filter hides the other tenant's row")
                    .isEmpty();
            assertThat(countById(jdbc, locationId))
                    .as("RLS hides it from raw SQL too")
                    .isZero();
            assertThat(jdbc.update("UPDATE ext_location SET name = 'Hijacked' WHERE location_id = ?", locationId))
                    .as("RLS makes the row unreachable for UPDATE")
                    .isZero();
        });

        // Unbound: the pool RESETs app.current_tenant, so pos_app sees an empty table and cannot insert.
        assertThat(countById(jdbc, locationId)).isZero();
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO ext_location (location_id, active, aggregate_version, updated_at)"
                                + " VALUES (?, true, 1, now())",
                        UUID.randomUUID()))
                .as("no tenant bound: the NOT NULL default is NULL and the policy's WITH CHECK refuses the row")
                .isInstanceOf(DataAccessException.class);

        asTenant(
                TENANT_A,
                () -> assertThat(locations.findById(locationId).orElseThrow().getName())
                        .as("tenant B's UPDATE touched nothing")
                        .isEqualTo("Depot A"));
    }

    private static int countById(JdbcTemplate jdbc, UUID locationId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ext_location WHERE location_id = ?", Integer.class, locationId);
        return count == null ? 0 : count;
    }
}
