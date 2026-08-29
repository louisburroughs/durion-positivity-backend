package com.positivity.bulkloader.internal.service;

import com.positivity.bulkloader.internal.entity.BulkLoadJob;
import com.positivity.bulkloader.internal.enums.JobStatus;
import com.positivity.bulkloader.internal.enums.ReviewStatus;
import com.positivity.bulkloader.internal.repository.BulkLoadJobRepository;
import com.positivity.bulkloader.internal.repository.BulkLoadRecordAuditRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class BulkLoadJobExecutionListener implements JobExecutionListener {

    private final BulkLoadJobRepository bulkLoadJobRepository;
    private final BulkLoadRecordAuditRepository auditRepository;
    private final Clock clock;

    @Override
    public void afterJob(JobExecution jobExecution) {
        String jobIdValue = jobExecution.getJobParameters().getString("jobId");
        if (jobIdValue == null || jobIdValue.isBlank()) {
            log.warn("Spring Batch execution {} completed without a bulk-load jobId parameter", jobExecution.getId());
            return;
        }

        UUID jobId = parseJobId(jobIdValue);
        BulkLoadJob bulkLoadJob = bulkLoadJobRepository
                .findById(jobId)
                .orElseThrow(() -> new NoSuchElementException("BulkLoadJob not found: " + jobId));

        long processedRows = 0L;
        long writtenRows = 0L;
        long skippedRows = 0L;
        for (StepExecution stepExecution : jobExecution.getStepExecutions()) {
            processedRows += stepExecution.getReadCount();
            writtenRows += stepExecution.getWriteCount();
            skippedRows += stepExecution.getProcessSkipCount()
                    + stepExecution.getWriteSkipCount()
                    + stepExecution.getReadSkipCount();
        }

        // Spring Batch's writeCount counts rows this job *sent*, not rows the owning service
        // accepted, so it reported a full success for a chunk every row of which was rejected.
        // The audit rows the writers now persist carry the real outcome; the step metrics remain
        // the fallback for a run that produced none (an empty file, or a step that died before
        // its first write).
        long auditedSuccesses = auditRepository.countByJobIdAndReviewStatus(jobId, ReviewStatus.APPROVED);
        long auditedFailures = auditRepository.countByJobIdAndReviewStatus(jobId, ReviewStatus.PENDING);
        boolean haveAuditRows = auditedSuccesses + auditedFailures > 0;

        long successCount = haveAuditRows ? auditedSuccesses : writtenRows;
        // A row skipped before the call never reaches the audit table, so it is counted here in
        // both branches; otherwise a validation-rejected row would vanish from the totals.
        long failureCount = (haveAuditRows ? auditedFailures : 0L) + skippedRows;

        bulkLoadJob.setProcessedRows(processedRows);
        bulkLoadJob.setSuccessCount(successCount);
        bulkLoadJob.setFailureCount(failureCount);
        bulkLoadJob.setCompletedAt(Instant.now(clock));
        bulkLoadJob.setStatus(resolveStatus(jobExecution.getStatus(), failureCount));
        bulkLoadJobRepository.save(bulkLoadJob);
    }

    /**
     * FAILED means the batch itself did not finish; PARTIAL means it did but rejected rows.
     * Keeping them apart is what makes the review queue reachable — corrections are for rows,
     * and a run that lost rows must not read as COMPLETED.
     */
    private JobStatus resolveStatus(BatchStatus batchStatus, long failureCount) {
        if (batchStatus != BatchStatus.COMPLETED) {
            return JobStatus.FAILED;
        }
        return failureCount > 0 ? JobStatus.PARTIAL : JobStatus.COMPLETED;
    }

    private UUID parseJobId(String jobIdValue) {
        try {
            return UUID.fromString(jobIdValue);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid bulk-load jobId in Spring Batch parameters: " + jobIdValue, ex);
        }
    }
}
