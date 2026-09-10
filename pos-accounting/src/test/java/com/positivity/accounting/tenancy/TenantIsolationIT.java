package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.audit.entity.OverridePolicyThreshold;
import com.positivity.accounting.internal.audit.repository.OverridePolicyThresholdRepository;
import com.positivity.tenancy.TenantContext;
import java.math.BigDecimal;
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
 * read nor write a scoped table.
 */
@DisplayName("Tenant isolation on Postgres (ADR-0062, pos-accounting)")
class TenantIsolationIT extends PostgresTenancyTestBase {

    @Autowired
    private OverridePolicyThresholdRepository policies;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static OverridePolicyThreshold policy(String role) {
        return OverridePolicyThreshold.builder()
                .role(role)
                .maxAbsoluteAmount(BigDecimal.valueOf(50))
                .maxPercentOff(BigDecimal.valueOf(10))
                .version("1.0")
                .effectiveDate(Instant.now())
                .active(true)
                .build();
    }

    @Test
    void aRowWrittenAsOneTenantIsInvisibleToAnotherAndToNoTenant() {
        String role = "isolation-" + UUID.randomUUID();
        UUID id = asTenant(TENANT_A, () -> policies.saveAndFlush(policy(role)).getPolicyId());

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        asTenant(TENANT_A, () -> {
            assertThat(policies.findById(id))
                    .as("owner reads through the repository")
                    .isPresent();
            assertThat(policies.findById(id).orElseThrow().getTenantId()).isEqualTo(TENANT_A);
            assertThat(countByRole(jdbc, role))
                    .as("owner reads through raw SQL")
                    .isEqualTo(1);
        });

        asTenant(TENANT_B, () -> {
            assertThat(policies.findById(id))
                    .as("Hibernate filter hides the other tenant's row")
                    .isEmpty();
            assertThat(countByRole(jdbc, role))
                    .as("RLS hides it from raw SQL too")
                    .isZero();
            assertThat(jdbc.update("UPDATE override_policy_threshold SET max_percent_off = 99 WHERE policy_id = ?", id))
                    .as("RLS makes the row unreachable for UPDATE")
                    .isZero();
        });

        // Unbound: the pool RESETs app.current_tenant, so pos_app sees an empty table and cannot insert.
        assertThat(countByRole(jdbc, role)).isZero();
        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO override_policy_threshold (policy_id, role, max_absolute_amount, max_percent_off,
                            effective_date, version, active, created_at, updated_at)
                        VALUES (?, ?, 50, 10, now(), '1.0', true, now(), now())
                        """, UUID.randomUUID(), "unbound-" + UUID.randomUUID()))
                .as("no tenant bound: the NOT NULL default is NULL and the policy's WITH CHECK refuses the row")
                .isInstanceOf(DataAccessException.class);

        asTenant(
                TENANT_A,
                () -> assertThat(policies.findById(id).orElseThrow().getMaxPercentOff())
                        .as("tenant B's UPDATE touched nothing")
                        .isEqualByComparingTo(BigDecimal.valueOf(10)));
    }

    private static int countByRole(JdbcTemplate jdbc, String role) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM override_policy_threshold WHERE role = ?", Integer.class, role);
        return count == null ? 0 : count;
    }
}
