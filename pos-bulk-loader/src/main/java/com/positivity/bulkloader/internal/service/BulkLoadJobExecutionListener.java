package com.positivity.bulkloader.internal.service;

import com.positivity.bulkloader.internal.entity.BulkLoadJob;
import com.positivity.bulkloader.internal.enums.JobStatus;
import com.positivity.bulkloader.internal.repository.BulkLoadJobRepository;
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
        long successCount = 0L;
        long failureCount = 0L;
        for (StepExecution stepExecution : jobExecution.getStepExecutions()) {
            processedRows += stepExecution.getReadCount();
            successCount += stepExecution.getWriteCount();
            failureCount += stepExecution.getProcessSkipCount()
                    + stepExecution.getWriteSkipCount()
                    + stepExecution.getReadSkipCount();
        }

        bulkLoadJob.setProcessedRows(processedRows);
        bulkLoadJob.setSuccessCount(successCount);
        bulkLoadJob.setFailureCount(failureCount);
        bulkLoadJob.setCompletedAt(Instant.now());
        bulkLoadJob.setStatus(
                jobExecution.getStatus() == BatchStatus.COMPLETED ? JobStatus.COMPLETED : JobStatus.FAILED);
        bulkLoadJobRepository.save(bulkLoadJob);
    }

    private UUID parseJobId(String jobIdValue) {
        try {
            return UUID.fromString(jobIdValue);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid bulk-load jobId in Spring Batch parameters: " + jobIdValue, ex);
        }
    }
}
