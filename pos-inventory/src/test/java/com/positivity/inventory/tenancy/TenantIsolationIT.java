package com.positivity.inventory.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.inventory.internal.entity.ReplenishmentPolicy;
import com.positivity.inventory.internal.repository.ReplenishmentPolicyRepository;
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
 * read nor write a scoped table.
 */
@DisplayName("Tenant isolation on Postgres (ADR-0062, pos-inventory)")
class TenantIsolationIT extends PostgresTenancyTestBase {

    @Autowired
    private ReplenishmentPolicyRepository policies;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static ReplenishmentPolicy policy(String sku) {
        return ReplenishmentPolicy.builder()
                .locationId(UUID.randomUUID())
                .itemSKU(sku)
                .minimumQuantity(1)
                .maximumQuantity(10)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    void aRowWrittenAsOneTenantIsInvisibleToAnotherAndToNoTenant() {
        String sku = "isolation-" + UUID.randomUUID();
        UUID id = asTenant(TENANT_A, () -> policies.saveAndFlush(policy(sku)).getPolicyId());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        asTenant(TENANT_A, () -> {
            assertThat(policies.findById(id))
                    .as("owner reads through the repository")
                    .isPresent();
            assertThat(policies.findById(id).orElseThrow().getTenantId()).isEqualTo(TENANT_A);
            assertThat(countBySku(jdbc, sku)).as("owner reads through raw SQL").isEqualTo(1);
        });
        asTenant(TENANT_B, () -> {
            assertThat(policies.findById(id))
                    .as("Hibernate filter hides the other tenant's row")
                    .isEmpty();
            assertThat(countBySku(jdbc, sku))
                    .as("RLS hides it from raw SQL too")
                    .isZero();
            assertThat(jdbc.update("UPDATE replenishment_policy SET maximum_quantity = 99 WHERE policy_id = ?", id))
                    .as("RLS makes the row unreachable for UPDATE")
                    .isZero();
        });
        // Unbound: the pool RESETs app.current_tenant, so pos_app sees an empty table and cannot insert.
        assertThat(countBySku(jdbc, sku)).isZero();
        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO replenishment_policy (policy_id, location_id, itemsku, minimum_quantity,
                            maximum_quantity, preferred_source_type, active, created_at, updated_at)
                        VALUES (?, ?, ?, 1, 10, 'EITHER', true, now(), now())
                        """, UUID.randomUUID(), UUID.randomUUID(), "unbound-" + UUID.randomUUID()))
                .as("no tenant bound: the NOT NULL default is NULL and the policy's WITH CHECK refuses the row")
                .isInstanceOf(DataAccessException.class);
        asTenant(
                TENANT_A,
                () -> assertThat(policies.findById(id).orElseThrow().getMaximumQuantity())
                        .as("tenant B's UPDATE touched nothing")
                        .isEqualTo(10));
    }

    private static int countBySku(JdbcTemplate jdbc, String sku) {
        Integer count =
                jdbc.queryForObject("SELECT count(*) FROM replenishment_policy WHERE itemsku = ?", Integer.class, sku);
        return count == null ? 0 : count;
    }
}
