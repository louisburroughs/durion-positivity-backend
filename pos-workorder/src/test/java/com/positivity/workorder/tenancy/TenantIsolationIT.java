package com.positivity.workorder.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.tenancy.TenantContext;
import com.positivity.workorder.internal.entity.ApprovalConfiguration;
import com.positivity.workorder.internal.repository.ApprovalConfigurationRepository;
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
 * read nor write a scoped table.
 */
@DisplayName("Tenant isolation on Postgres (ADR-0062, pos-workorder)")
class TenantIsolationIT extends PostgresTenancyTestBase {

    @Autowired
    private ApprovalConfigurationRepository configurations;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aRowWrittenAsOneTenantIsInvisibleToAnotherAndToNoTenant() {
        UUID locationId = UUID.randomUUID();
        UUID id = asTenant(
                TENANT_A,
                () -> configurations
                        .saveAndFlush(ApprovalConfiguration.builder()
                                .locationId(locationId)
                                .approvalWindowDays(7)
                                .build())
                        .getId());

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        asTenant(TENANT_A, () -> {
            assertThat(configurations.findById(id))
                    .as("owner reads through the repository")
                    .isPresent();
            assertThat(configurations.findById(id).orElseThrow().getTenantId()).isEqualTo(TENANT_A);
            assertThat(countByLocation(jdbc, locationId))
                    .as("owner reads through raw SQL")
                    .isEqualTo(1);
        });

        asTenant(TENANT_B, () -> {
            assertThat(configurations.findById(id))
                    .as("Hibernate filter hides the other tenant's row")
                    .isEmpty();
            assertThat(countByLocation(jdbc, locationId))
                    .as("RLS hides it from raw SQL too")
                    .isZero();
            assertThat(jdbc.update("UPDATE approval_configuration SET approval_window_days = 99 WHERE id = ?", id))
                    .as("RLS makes the row unreachable for UPDATE")
                    .isZero();
        });

        // Unbound: the pool RESETs app.current_tenant, so pos_app sees an empty table and cannot insert.
        assertThat(countByLocation(jdbc, locationId)).isZero();
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO approval_configuration (id, location_id, created_at, updated_at) VALUES (?, ?, now(), now())",
                        UUID.randomUUID(),
                        UUID.randomUUID()))
                .as("no tenant bound: the NOT NULL default is NULL and the policy's WITH CHECK refuses the row")
                .isInstanceOf(DataAccessException.class);

        asTenant(
                TENANT_A,
                () -> assertThat(configurations.findById(id).orElseThrow().getApprovalWindowDays())
                        .as("tenant B's UPDATE touched nothing")
                        .isEqualTo(7));
    }

    private static int countByLocation(JdbcTemplate jdbc, UUID locationId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM approval_configuration WHERE location_id = ?", Integer.class, locationId);
        return count == null ? 0 : count;
    }
}
