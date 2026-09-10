package com.positivity.warranty.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.tenancy.TenantContext;
import com.positivity.warranty.internal.entity.WarrantyProvider;
import com.positivity.warranty.internal.enums.ProviderType;
import com.positivity.warranty.internal.repository.WarrantyProviderRepository;
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
 * read nor write a scoped table. The warranty provider is the subject: the simplest scoped table
 * here, and one the seed never touches.
 */
@DisplayName("Tenant isolation on Postgres (ADR-0062, pos-warranty)")
class TenantIsolationIT extends PostgresTenancyTestBase {

    @Autowired
    private WarrantyProviderRepository providers;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aRowWrittenAsOneTenantIsInvisibleToAnotherAndToNoTenant() {
        UUID providerId = asTenant(
                TENANT_A,
                () -> providers
                        .saveAndFlush(WarrantyProvider.builder()
                                .name("Acme Tyres")
                                .providerType(ProviderType.MANUFACTURER)
                                .build())
                        .getId());

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        asTenant(TENANT_A, () -> {
            assertThat(providers.findById(providerId))
                    .as("owner reads through the repository")
                    .isPresent();
            assertThat(providers.findById(providerId).orElseThrow().getTenantId())
                    .isEqualTo(TENANT_A);
            assertThat(countById(jdbc, providerId))
                    .as("owner reads through raw SQL")
                    .isEqualTo(1);
        });

        asTenant(TENANT_B, () -> {
            assertThat(providers.findById(providerId))
                    .as("Hibernate filter hides the other tenant's row")
                    .isEmpty();
            assertThat(countById(jdbc, providerId))
                    .as("RLS hides it from raw SQL too")
                    .isZero();
            assertThat(jdbc.update("UPDATE warranty_provider SET name = 'Hijacked' WHERE id = ?", providerId))
                    .as("RLS makes the row unreachable for UPDATE")
                    .isZero();
        });

        // Unbound: the pool RESETs app.current_tenant, so pos_app sees an empty table and cannot insert.
        assertThat(countById(jdbc, providerId)).isZero();
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO warranty_provider (id, name, status, provider_type, created_at, updated_at, version)"
                                + " VALUES (?, 'Nobody', 'ACTIVE', 'MANUFACTURER', now(), now(), 0)",
                        UUID.randomUUID()))
                .as("no tenant bound: the NOT NULL default is NULL and the policy's WITH CHECK refuses the row")
                .isInstanceOf(DataAccessException.class);

        asTenant(
                TENANT_A,
                () -> assertThat(providers.findById(providerId).orElseThrow().getName())
                        .as("tenant B's UPDATE touched nothing")
                        .isEqualTo("Acme Tyres"));
    }

    private static int countById(JdbcTemplate jdbc, UUID providerId) {
        Integer count =
                jdbc.queryForObject("SELECT count(*) FROM warranty_provider WHERE id = ?", Integer.class, providerId);
        return count == null ? 0 : count;
    }
}
