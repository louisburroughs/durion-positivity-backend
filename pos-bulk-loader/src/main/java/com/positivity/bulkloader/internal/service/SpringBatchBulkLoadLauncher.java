package com.positivity.bulkloader.internal.service;

import com.positivity.bulkloader.internal.entity.BulkLoadJob;
import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class SpringBatchBulkLoadLauncher implements BulkLoadBatchLauncher {

    private final BulkLoadAuthorizationContext bulkLoadAuthorizationContext;
    private final JobOperator jobOperator;

    /**
     * Every batch job in the context, keyed by bean name.
     *
     * <p>Injected as a map rather than one constructor parameter per domain: the loader is growing
     * a domain per seed pack it absorbs, and a constructor that gains an argument each time makes
     * every call site in every test churn for a change that has nothing to do with them.
     */
    private final Map<String, Job> jobsByName;

    public SpringBatchBulkLoadLauncher(
            BulkLoadAuthorizationContext bulkLoadAuthorizationContext,
            JobOperator jobOperator,
            Map<String, Job> jobsByName) {
        this.bulkLoadAuthorizationContext = bulkLoadAuthorizationContext;
        this.jobOperator = jobOperator;
        this.jobsByName = jobsByName;
    }

    @Override
    public void launch(@NonNull BulkLoadJob job, @Nullable String authorizationHeader) {
        // Defensive/internal invariants on this interface's contract (issue #1694 audit),
        // deliberately left as bare IllegalArgumentException rather than a module-owned type:
        // this method DOES run synchronously on the calling HTTP thread (unlike the @StepScope
        // guards further down the pipeline), but its only caller today —
        // BulkLoadJobServiceImpl.startProcessing — already rejects a missing storage path or
        // locationId with its own 409 IllegalStateException, and operatorId is populated at job
        // creation and never blank, before this method is ever reached. None of the three is
        // reachable from a client request; if one ever fires it means a future caller broke this
        // precondition, which is correctly a 500 (a server-side bug), not a 400.
        if (!StringUtils.hasText(job.getOriginalFilePath())) {
            throw new IllegalArgumentException("Bulk load job must include a persisted storage path before launch");
        }
        if (job.getLocationId() == null) {
            throw new IllegalArgumentException("Bulk load job must include a locationId before launch");
        }
        if (!StringUtils.hasText(job.getOperatorId())) {
            throw new IllegalArgumentException("Bulk load job must include an operatorId before launch");
        }

        try {
            bulkLoadAuthorizationContext.setAuthorizationHeader(authorizationHeader);
            jobOperator.start(
                    resolveJob(job.getDomainType()),
                    new JobParametersBuilder()
                            .addString("jobId", job.getId().toString())
                            .addString("storagePath", job.getOriginalFilePath())
                            .addString("locationId", job.getLocationId().toString())
                            .addString("operatorId", job.getOperatorId())
                            .addLong("launchEpochMillis", System.currentTimeMillis())
                            .toJobParameters());
            log.info("Launched batch job for bulk load job {} domain {}", job.getId(), job.getDomainType());
        } catch (JobExecutionAlreadyRunningException
                | JobRestartException
                | JobInstanceAlreadyCompleteException
                | InvalidJobParametersException ex) {
            throw new IllegalStateException(
                    "Failed to launch Spring Batch job for bulk load job %s".formatted(job.getId()), ex);
        } finally {
            bulkLoadAuthorizationContext.clear();
        }
    }

    /**
     * The batch job that loads a domain.
     *
     * <p>Deliberately an exhaustive switch with no default: adding a {@link DomainType} constant
     * then fails to compile until someone says which job runs it, which is the check that stops a
     * domain being advertised to callers before it can actually be processed.
     */
    private Job resolveJob(DomainType domainType) {
        String beanName =
                switch (domainType) {
                    case CATALOG_PRODUCT -> "catalogBulkLoadJob";
                    case CUSTOMER -> "customerBulkLoadJob";
                    case COMMERCIAL_CUSTOMER -> "commercialCustomerBulkLoadJob";
                    case LOCATION -> "locationBulkLoadJob";
                    case PERSON -> "peopleBulkLoadJob";
                    case BASE_PRICE -> "priceBulkLoadJob";
                    case VEHICLE -> "vehicleBulkLoadJob";
                    case VEHICLE_FITMENT -> "vehicleFitmentBulkLoadJob";
                    case INVENTORY_STOCK_COUNT -> "inventoryStockCountBulkLoadJob";
                    case STORAGE_LOCATION -> "storageLocationBulkLoadJob";
                    case BAY -> "bayBulkLoadJob";
                    case MOBILE_UNIT -> "mobileUnitBulkLoadJob";
                    case STAFFING_ASSIGNMENT -> "staffingAssignmentBulkLoadJob";
                    case PUTAWAY_RULE -> "putawayRuleBulkLoadJob";
                    case CYCLE_COUNT_PLAN -> "cycleCountPlanBulkLoadJob";
                    case SECURITY_ROLE -> "securityRoleBulkLoadJob";
                    case SECURITY_ROLE_PERMISSION -> "securityRolePermissionBulkLoadJob";
                    case SECURITY_USER -> "securityUserBulkLoadJob";
                    case USER_PERSON_LINK -> "userPersonLinkBulkLoadJob";
                    case MECHANIC_SKILL -> "mechanicSkillBulkLoadJob";
                };

        Job job = jobsByName.get(beanName);
        if (job == null) {
            throw new IllegalStateException(
                    "No Spring Batch job bean named '%s' for domain type: %s".formatted(beanName, domainType));
        }
        return job;
    }
}
