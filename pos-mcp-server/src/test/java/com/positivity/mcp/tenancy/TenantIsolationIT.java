package com.positivity.mcp.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.mcp.internal.entity.NltiSession;
import com.positivity.mcp.internal.repository.NltiSessionRepository;
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
 * read nor write a scoped table. The NLTI session is the subject: the root of every conversation row.
 */
@DisplayName("Tenant isolation on Postgres (ADR-0062, pos-mcp-server)")
class TenantIsolationIT extends PostgresTenancyTestBase {

    @Autowired
    private NltiSessionRepository rows;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aRowWrittenAsOneTenantIsInvisibleToAnotherAndToNoTenant() {
        UUID id = asTenant(TENANT_A, () -> rows.saveAndFlush(session()).getId());

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
            assertThat(jdbc.update("UPDATE nlti_session SET subject_id = 'hijacked' WHERE id = ?", id))
                    .as("RLS makes the row unreachable for UPDATE")
                    .isZero();
        });

        // Unbound: the pool RESETs app.current_tenant, so pos_app sees an empty table and cannot insert.
        assertThat(countById(jdbc, id)).isZero();
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO nlti_session (id, subject_id, created_at, updated_at) VALUES (?, 'nobody', now(), now())",
                        UUID.randomUUID()))
                .as("no tenant bound: the NOT NULL default is NULL and the policy's WITH CHECK refuses the row")
                .isInstanceOf(DataAccessException.class);

        asTenant(
                TENANT_A,
                () -> assertThat(rows.findById(id).orElseThrow().getSubjectId())
                        .as("tenant B's UPDATE touched nothing")
                        .isEqualTo("user-1"));
    }

    private static NltiSession session() {
        NltiSession session = new NltiSession();
        session.setSubjectId("user-1");
        return session;
    }

    private static int countById(JdbcTemplate jdbc, UUID id) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM nlti_session WHERE id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }
}
