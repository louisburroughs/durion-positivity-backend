package com.positivity.shopmanager.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.shopmanager.internal.entity.Certification;
import com.positivity.shopmanager.internal.repository.CertificationRepository;
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
@DisplayName("Tenant isolation on Postgres (ADR-0062, pos-shop-manager)")
class TenantIsolationIT extends PostgresTenancyTestBase {

    @Autowired
    private CertificationRepository certifications;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aRowWrittenAsOneTenantIsInvisibleToAnotherAndToNoTenant() {
        String aseCode = "isolation-" + UUID.randomUUID();
        UUID id = asTenant(
                TENANT_A,
                () -> certifications
                        .saveAndFlush(Certification.builder()
                                .aseCode(aseCode)
                                .description("isolation")
                                .build())
                        .getId());

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        asTenant(TENANT_A, () -> {
            assertThat(certifications.findById(id))
                    .as("owner reads through the repository")
                    .isPresent();
            assertThat(certifications.findById(id).orElseThrow().getTenantId()).isEqualTo(TENANT_A);
            assertThat(countByCode(jdbc, aseCode))
                    .as("owner reads through raw SQL")
                    .isEqualTo(1);
        });

        asTenant(TENANT_B, () -> {
            assertThat(certifications.findById(id))
                    .as("Hibernate filter hides the other tenant's row")
                    .isEmpty();
            assertThat(countByCode(jdbc, aseCode))
                    .as("RLS hides it from raw SQL too")
                    .isZero();
            assertThat(jdbc.update("UPDATE certification SET description = 'stolen' WHERE id = ?", id))
                    .as("RLS makes the row unreachable for UPDATE")
                    .isZero();
        });

        // Unbound: the pool RESETs app.current_tenant, so pos_app sees an empty table and cannot insert.
        assertThat(countByCode(jdbc, aseCode)).isZero();
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO certification (id, ase_code, created_at, updated_at) VALUES (?, ?, now(), now())",
                        UUID.randomUUID(),
                        "unbound-" + UUID.randomUUID()))
                .as("no tenant bound: the NOT NULL default is NULL and the policy's WITH CHECK refuses the row")
                .isInstanceOf(DataAccessException.class);

        asTenant(
                TENANT_A,
                () -> assertThat(certifications.findById(id).orElseThrow().getDescription())
                        .as("tenant B's UPDATE touched nothing")
                        .isEqualTo("isolation"));
    }

    private static int countByCode(JdbcTemplate jdbc, String aseCode) {
        Integer count =
                jdbc.queryForObject("SELECT count(*) FROM certification WHERE ase_code = ?", Integer.class, aseCode);
        return count == null ? 0 : count;
    }
}
