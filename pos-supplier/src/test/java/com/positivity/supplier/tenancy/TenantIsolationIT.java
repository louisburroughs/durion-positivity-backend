package com.positivity.supplier.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.internal.entity.ExtProductCodeReplica;
import com.positivity.supplier.internal.repository.ExtProductCodeReplicaRepository;
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
 * read nor write a scoped table. The catalog code replica is the subject: the simplest scoped table
 * here, and the one the PRICAT matcher reads on every line.
 */
@DisplayName("Tenant isolation on Postgres (ADR-0062, pos-supplier)")
class TenantIsolationIT extends PostgresTenancyTestBase {

    @Autowired
    private ExtProductCodeReplicaRepository replicas;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aRowWrittenAsOneTenantIsInvisibleToAnotherAndToNoTenant() {
        UUID productId = UUID.randomUUID();
        asTenant(
                TENANT_A,
                () -> replicas.saveAndFlush(ExtProductCodeReplica.builder()
                        .productId(productId)
                        .codeType("EAN")
                        .code("4006633000017")
                        .sku("TYRE-1")
                        .aggregateVersion(1L)
                        .build()));

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        asTenant(TENANT_A, () -> {
            assertThat(replicas.findById(productId))
                    .as("owner reads through the repository")
                    .isPresent();
            assertThat(replicas.findById(productId).orElseThrow().getTenantId()).isEqualTo(TENANT_A);
            assertThat(countByProduct(jdbc, productId))
                    .as("owner reads through raw SQL")
                    .isEqualTo(1);
        });

        asTenant(TENANT_B, () -> {
            assertThat(replicas.findById(productId))
                    .as("Hibernate filter hides the other tenant's row")
                    .isEmpty();
            assertThat(countByProduct(jdbc, productId))
                    .as("RLS hides it from raw SQL too")
                    .isZero();
            assertThat(jdbc.update("UPDATE ext_product_code SET code = '0' WHERE product_id = ?", productId))
                    .as("RLS makes the row unreachable for UPDATE")
                    .isZero();
        });

        // Unbound: the pool RESETs app.current_tenant, so pos_app sees an empty table and cannot insert.
        assertThat(countByProduct(jdbc, productId)).isZero();
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO ext_product_code (product_id, aggregate_version, updated_at) VALUES (?, 1, now())",
                        UUID.randomUUID()))
                .as("no tenant bound: the NOT NULL default is NULL and the policy's WITH CHECK refuses the row")
                .isInstanceOf(DataAccessException.class);

        asTenant(
                TENANT_A,
                () -> assertThat(replicas.findById(productId).orElseThrow().getCode())
                        .as("tenant B's UPDATE touched nothing")
                        .isEqualTo("4006633000017"));
    }

    private static int countByProduct(JdbcTemplate jdbc, UUID productId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ext_product_code WHERE product_id = ?", Integer.class, productId);
        return count == null ? 0 : count;
    }
}
