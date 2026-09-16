package com.positivity.shopmanager.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.shopmanager.internal.entity.ExtPersonCredentialReplica;
import com.positivity.shopmanager.internal.repository.ExtPersonCredentialReplicaRepository;
import com.positivity.tenancy.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
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
    private ExtPersonCredentialReplicaRepository credentials;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aRowWrittenAsOneTenantIsInvisibleToAnotherAndToNoTenant() {
        String skillCode = "iso-" + UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID id = asTenant(
                TENANT_A,
                () -> credentials
                        .saveAndFlush(ExtPersonCredentialReplica.builder()
                                .credentialId(credentialId)
                                .personId(UUID.randomUUID())
                                .skillId(UUID.randomUUID())
                                .skillCode(skillCode)
                                .competenceCode("ISOLATION")
                                .minGvwrClass(1)
                                .maxGvwrClass(8)
                                .issuer("SHOP")
                                .issuedOn(LocalDate.now())
                                .status("ACTIVE")
                                .aggregateVersion(1)
                                .updatedAt(Instant.now())
                                .build())
                        .getCredentialId());

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        asTenant(TENANT_A, () -> {
            assertThat(credentials.findById(id))
                    .as("owner reads through the repository")
                    .isPresent();
            assertThat(credentials.findById(id).orElseThrow().getTenantId()).isEqualTo(TENANT_A);
            assertThat(countBySkillCode(jdbc, skillCode))
                    .as("owner reads through raw SQL")
                    .isEqualTo(1);
        });

        asTenant(TENANT_B, () -> {
            assertThat(credentials.findById(id))
                    .as("Hibernate filter hides the other tenant's row")
                    .isEmpty();
            assertThat(countBySkillCode(jdbc, skillCode))
                    .as("RLS hides it from raw SQL too")
                    .isZero();
            assertThat(jdbc.update("UPDATE ext_person_credential SET issuer = 'stolen' WHERE credential_id = ?", id))
                    .as("RLS makes the row unreachable for UPDATE")
                    .isZero();
        });

        // Unbound: the pool RESETs app.current_tenant, so pos_app sees an empty table and cannot insert.
        assertThat(countBySkillCode(jdbc, skillCode)).isZero();
        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO ext_person_credential
                            (credential_id, person_id, skill_id, skill_code, competence_code,
                             min_gvwr_class, max_gvwr_class, issuer, issued_on, status,
                             aggregate_version, updated_at)
                        VALUES (?, ?, ?, ?, 'ISOLATION', 1, 8, 'SHOP', now(), 'ACTIVE', 1, now())
                        """, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "unbound-" + UUID.randomUUID()))
                .as("no tenant bound: the NOT NULL default is NULL and the policy's WITH CHECK refuses the row")
                .isInstanceOf(DataAccessException.class);

        asTenant(
                TENANT_A,
                () -> assertThat(credentials.findById(id).orElseThrow().getIssuer())
                        .as("tenant B's UPDATE touched nothing")
                        .isEqualTo("SHOP"));
    }

    private static int countBySkillCode(JdbcTemplate jdbc, String skillCode) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ext_person_credential WHERE skill_code = ?", Integer.class, skillCode);
        return count == null ? 0 : count;
    }
}
