package com.positivity.location.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.location.internal.entity.LocationType;
import com.positivity.location.internal.repository.LocationTypeRepository;
import com.positivity.tenancy.TenantContext;
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
@DisplayName("Tenant isolation on Postgres (ADR-0062)")
class TenantIsolationIT extends PostgresTenancyTestBase {

    @Autowired
    private LocationTypeRepository locationTypes;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aRowWrittenAsOneTenantIsInvisibleToAnotherAndToNoTenant() {
        String name = "isolation-" + UUID.randomUUID();
        UUID id = asTenant(
                TENANT_A,
                () -> locationTypes
                        .saveAndFlush(LocationType.builder()
                                .name(name)
                                .description("tenant A")
                                .build())
                        .getId());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        asTenant(TENANT_A, () -> {
            assertThat(locationTypes.findById(id))
                    .as("owner reads through the repository")
                    .isPresent();
            assertThat(locationTypes.findById(id).orElseThrow().getTenantId()).isEqualTo(TENANT_A);
            assertThat(countByName(jdbc, name))
                    .as("owner reads through raw SQL")
                    .isEqualTo(1);
        });

        asTenant(TENANT_B, () -> {
            assertThat(locationTypes.findById(id))
                    .as("Hibernate filter hides the other tenant's row")
                    .isEmpty();
            assertThat(countByName(jdbc, name))
                    .as("RLS hides it from raw SQL too")
                    .isZero();
            assertThat(jdbc.update("UPDATE location_type SET description = 'stolen' WHERE id = ?", id))
                    .as("RLS makes the row unreachable for UPDATE")
                    .isZero();
        });

        // Unbound: the pool RESETs app.current_tenant, so pos_app sees an empty table and cannot insert.
        assertThat(countByName(jdbc, name)).isZero();
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO location_type (id, name, created_at, updated_at) VALUES (?, ?, now(), now())",
                        UUID.randomUUID(),
                        "unbound-" + UUID.randomUUID()))
                .as("no tenant bound: the NOT NULL default is NULL and the policy's WITH CHECK refuses the row")
                .isInstanceOf(DataAccessException.class);

        asTenant(
                TENANT_A,
                () -> assertThat(locationTypes.findById(id).orElseThrow().getDescription())
                        .as("tenant B's UPDATE touched nothing")
                        .isEqualTo("tenant A"));
    }

    @Test
    void theSameBusinessKeyIsUniquePerTenantNotPerPlatform() {
        String name = "shared-name-" + UUID.randomUUID();
        asTenant(
                TENANT_A,
                () -> locationTypes.saveAndFlush(
                        LocationType.builder().name(name).build()));
        asTenant(
                TENANT_B,
                () -> locationTypes.saveAndFlush(
                        LocationType.builder().name(name).build()));

        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        assertThat(owner.queryForObject("SELECT count(*) FROM location_type WHERE name = ?", Integer.class, name))
                .as("the owner (which bypasses RLS) sees one row per tenant under the tenant-scoped unique key")
                .isEqualTo(2);
    }

    private static int countByName(JdbcTemplate jdbc, String name) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM location_type WHERE name = ?", Integer.class, name);
        return count == null ? 0 : count;
    }
}
