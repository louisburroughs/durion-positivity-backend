package com.positivity.bulkloader.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.bulkloader.internal.entity.BulkLoadJob;
import com.positivity.bulkloader.internal.enums.DomainType;
import com.positivity.bulkloader.internal.enums.JobStatus;
import com.positivity.bulkloader.internal.repository.BulkLoadJobRepository;
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
 * Proves the isolation, not just the mapping (plan R-B7, WS8): a job written as tenant A is
 * invisible to tenant B through the repository (Hibernate's {@code @TenantId} filter) and through a
 * raw {@code JdbcTemplate} on the same pool (row-level security alone), and an unbound connection
 * can neither read nor write a scoped table. The "one active job per operator" unique index is
 * per tenant, so the same operator can have an active job in two tenants.
 */
@DisplayName("Tenant isolation on Postgres (ADR-0062)")
class TenantIsolationIT extends PostgresTenancyTestBase {

    @Autowired
    private BulkLoadJobRepository jobs;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aJobWrittenAsOneTenantIsInvisibleToAnotherAndToNoTenant() {
        String operator = "isolation-" + UUID.randomUUID();
        UUID id = asTenant(
                TENANT_A, () -> jobs.saveAndFlush(job(operator, "tenant-a.csv")).getId());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        asTenant(TENANT_A, () -> {
            assertThat(jobs.findById(id))
                    .as("owner reads through the repository")
                    .isPresent();
            assertThat(jobs.findById(id).orElseThrow().getTenantId()).isEqualTo(TENANT_A);
            assertThat(countByOperator(jdbc, operator))
                    .as("owner reads through raw SQL")
                    .isEqualTo(1);
        });

        asTenant(TENANT_B, () -> {
            assertThat(jobs.findById(id))
                    .as("Hibernate filter hides the other tenant's job")
                    .isEmpty();
            assertThat(jobs.findByOperatorId(operator))
                    .as("the operator's listing is empty in the other tenant")
                    .isEmpty();
            assertThat(countByOperator(jdbc, operator))
                    .as("RLS hides it from raw SQL too")
                    .isZero();
            assertThat(jdbc.update("UPDATE bulk_load_job SET status = 'CANCELLED' WHERE id = ?", id))
                    .as("RLS makes the row unreachable for UPDATE")
                    .isZero();
        });

        // Unbound: the pool RESETs app.current_tenant, so pos_app sees an empty table and cannot insert.
        assertThat(countByOperator(jdbc, operator)).isZero();
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO bulk_load_job (id, operator_id, file_name, domain_type, status, processed_rows,"
                                + " success_count, failure_count, created_at, updated_at)"
                                + " VALUES (?, ?, 'unbound.csv', 'LOCATION', 'CREATED', 0, 0, 0, now(), now())",
                        UUID.randomUUID(),
                        "unbound-" + UUID.randomUUID()))
                .as("no tenant bound: the NOT NULL default is NULL and the policy's WITH CHECK refuses the row")
                .isInstanceOf(DataAccessException.class);

        asTenant(
                TENANT_A,
                () -> assertThat(jobs.findById(id).orElseThrow().getStatus())
                        .as("tenant B's UPDATE touched nothing")
                        .isEqualTo(JobStatus.CREATED));
    }

    @Test
    void theSameOperatorMayHaveOneActiveJobPerTenantNotPerPlatform() {
        String operator = "shared-operator-" + UUID.randomUUID();
        asTenant(TENANT_A, () -> jobs.saveAndFlush(job(operator, "a.csv")));
        asTenant(TENANT_B, () -> jobs.saveAndFlush(job(operator, "b.csv")));

        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM bulk_load_job WHERE operator_id = ?", Integer.class, operator))
                .as("the owner (which bypasses RLS) sees one active job per tenant under the tenant-scoped index")
                .isEqualTo(2);
    }

    private static BulkLoadJob job(String operator, String fileName) {
        BulkLoadJob job = new BulkLoadJob();
        job.setOperatorId(operator);
        job.setFileName(fileName);
        job.setDomainType(DomainType.LOCATION);
        job.setStatus(JobStatus.CREATED);
        return job;
    }

    private static int countByOperator(JdbcTemplate jdbc, String operator) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM bulk_load_job WHERE operator_id = ?", Integer.class, operator);
        return count == null ? 0 : count;
    }
}
