package com.positivity.bulkloader.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.positivity.bulkloader.internal.dto.BulkLoadJobResponse;
import com.positivity.bulkloader.internal.entity.BulkLoadJob;
import com.positivity.bulkloader.internal.enums.DomainType;
import com.positivity.bulkloader.internal.enums.JobStatus;
import com.positivity.bulkloader.internal.repository.BulkLoadJobRepository;
import com.positivity.bulkloader.internal.service.BulkLoadJobService;
import com.positivity.tenancy.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #2070: CAP-328 removed {@code DomainType.MECHANIC_SKILL}, but the jobs the alpha seed pack had
 * already run for it stayed in {@code bulk_load_job}. One such row made the operator's whole job
 * list a 500. The list must still load, show that job as RETIRED, and leave the stored name alone.
 */
@DisplayName("Job list with a retired domain on Postgres (#2070)")
class RetiredDomainJobListingIT extends PostgresTenancyTestBase {

    @Autowired
    private BulkLoadJobService jobService;

    @Autowired
    private BulkLoadJobRepository jobs;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void anEmptyListLoads() {
        Page<BulkLoadJobResponse> page = asTenant(
                TENANT_A, () -> jobService.listJobsForOperator("nobody-" + UUID.randomUUID(), PageRequest.of(0, 20)));

        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void aJobForARetiredDomainIsListedAsRetiredAndKeepsItsStoredName() {
        String operator = "retired-" + UUID.randomUUID();
        UUID retiredId = UUID.randomUUID();
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        owner.update(
                "INSERT INTO bulk_load_job (tenant_id, id, operator_id, file_name, domain_type, status,"
                        + " processed_rows, success_count, failure_count, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'mechanic-skills.csv', 'MECHANIC_SKILL', 'COMPLETED', 0, 0, 0,"
                        + " now(), now())",
                TENANT_A,
                retiredId,
                operator);
        UUID liveId = asTenant(TENANT_A, () -> jobs.saveAndFlush(job(operator)).getId());

        Page<BulkLoadJobResponse> page =
                asTenant(TENANT_A, () -> jobService.listJobsForOperator(operator, PageRequest.of(0, 20)));

        assertThat(page.getContent())
                .extracting(BulkLoadJobResponse::getId, BulkLoadJobResponse::getDomainType)
                .containsExactlyInAnyOrder(tuple(retiredId, DomainType.RETIRED), tuple(liveId, DomainType.LOCATION));

        asTenant(TENANT_A, () -> {
            BulkLoadJob retired = jobs.findById(retiredId).orElseThrow();
            retired.setStatus(JobStatus.FAILED);
            return jobs.saveAndFlush(retired);
        });
        assertThat(owner.queryForObject("SELECT domain_type FROM bulk_load_job WHERE id = ?", String.class, retiredId))
                .as("updating a retired job must not overwrite its stored domain with RETIRED")
                .isEqualTo("MECHANIC_SKILL");
    }

    private static BulkLoadJob job(String operator) {
        BulkLoadJob job = new BulkLoadJob();
        job.setOperatorId(operator);
        job.setFileName("locations.csv");
        job.setDomainType(DomainType.LOCATION);
        job.setStatus(JobStatus.CREATED);
        return job;
    }
}
