package com.positivity.bulkloader.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.bulkloader.internal.entity.BulkLoadJob;
import com.positivity.bulkloader.internal.enums.JobStatus;
import com.positivity.bulkloader.internal.enums.ReviewStatus;
import com.positivity.bulkloader.internal.repository.BulkLoadJobRepository;
import com.positivity.bulkloader.internal.repository.BulkLoadRecordAuditRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.test.MetaDataInstanceFactory;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"java:S100", "java:S1192"})
class BulkLoadJobExecutionListenerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    @Mock
    BulkLoadJobRepository bulkLoadJobRepository;

    @Mock
    BulkLoadRecordAuditRepository auditRepository;

    @InjectMocks
    BulkLoadJobExecutionListener listener;

    @Test
    void afterJob_whenCompletedWithSkips_reportsPartialFromStepMetrics() {
        UUID jobId = UUID.fromString("00000000-0000-0000-0000-000000000041");
        BulkLoadJob bulkLoadJob = new BulkLoadJob();
        bulkLoadJob.setId(jobId);
        bulkLoadJob.setStatus(JobStatus.PROCESSING);

        JobExecution jobExecution = MetaDataInstanceFactory.createJobExecution(
                "catalogBulkLoadJob",
                11L,
                21L,
                new JobParametersBuilder().addString("jobId", jobId.toString()).toJobParameters());
        jobExecution.setStatus(BatchStatus.COMPLETED);

        StepExecution stepExecution =
                MetaDataInstanceFactory.createStepExecution(jobExecution, "catalogBulkLoadStep", 31L);
        stepExecution.setReadCount(7);
        stepExecution.setWriteCount(5);
        stepExecution.setProcessSkipCount(1);
        stepExecution.setWriteSkipCount(1);
        jobExecution.addStepExecution(stepExecution);

        when(bulkLoadJobRepository.findById(jobId)).thenReturn(Optional.of(bulkLoadJob));
        when(bulkLoadJobRepository.save(any(BulkLoadJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

        listener.afterJob(jobExecution);

        assertThat(bulkLoadJob.getProcessedRows()).isEqualTo(7L);
        // No audit rows for this job, so the step metrics stand in.
        assertThat(bulkLoadJob.getSuccessCount()).isEqualTo(5L);
        assertThat(bulkLoadJob.getFailureCount()).isEqualTo(2L);
        // Two rows were skipped, so the run did not do what was asked of it.
        assertThat(bulkLoadJob.getStatus()).isEqualTo(JobStatus.PARTIAL);
        assertThat(bulkLoadJob.getCompletedAt()).isNotNull();
        verify(bulkLoadJobRepository).save(bulkLoadJob);
    }

    @Test
    void afterJob_whenFailed_marksJobFailed() {
        UUID jobId = UUID.fromString("00000000-0000-0000-0000-000000000042");
        BulkLoadJob bulkLoadJob = new BulkLoadJob();
        bulkLoadJob.setId(jobId);
        bulkLoadJob.setStatus(JobStatus.PROCESSING);

        JobExecution jobExecution = MetaDataInstanceFactory.createJobExecution(
                "catalogBulkLoadJob",
                12L,
                22L,
                new JobParametersBuilder().addString("jobId", jobId.toString()).toJobParameters());
        jobExecution.setStatus(BatchStatus.FAILED);

        when(bulkLoadJobRepository.findById(jobId)).thenReturn(Optional.of(bulkLoadJob));

        listener.afterJob(jobExecution);

        assertThat(bulkLoadJob.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(bulkLoadJob.getCompletedAt()).isNotNull();
        verify(bulkLoadJobRepository).save(bulkLoadJob);
    }

    @Test
    void afterJob_whenAuditRowsExist_countsThemRatherThanWriteCount() {
        // The defect this listener change exists for: writeCount counts rows *sent*, so a chunk
        // the owning service rejected in full still reported a clean run.
        UUID jobId = UUID.fromString("00000000-0000-0000-0000-000000000043");
        BulkLoadJob bulkLoadJob = new BulkLoadJob();
        bulkLoadJob.setId(jobId);
        bulkLoadJob.setStatus(JobStatus.PROCESSING);

        JobExecution jobExecution = MetaDataInstanceFactory.createJobExecution(
                "catalogBulkLoadJob",
                15L,
                25L,
                new JobParametersBuilder().addString("jobId", jobId.toString()).toJobParameters());
        jobExecution.setStatus(BatchStatus.COMPLETED);

        StepExecution stepExecution =
                MetaDataInstanceFactory.createStepExecution(jobExecution, "catalogBulkLoadStep", 35L);
        stepExecution.setReadCount(10);
        stepExecution.setWriteCount(10);
        jobExecution.addStepExecution(stepExecution);

        when(bulkLoadJobRepository.findById(jobId)).thenReturn(Optional.of(bulkLoadJob));
        when(auditRepository.countByJobIdAndReviewStatus(jobId, ReviewStatus.APPROVED))
                .thenReturn(6L);
        when(auditRepository.countByJobIdAndReviewStatus(jobId, ReviewStatus.PENDING))
                .thenReturn(4L);

        listener.afterJob(jobExecution);

        assertThat(bulkLoadJob.getSuccessCount()).isEqualTo(6L);
        assertThat(bulkLoadJob.getFailureCount()).isEqualTo(4L);
        assertThat(bulkLoadJob.getStatus()).isEqualTo(JobStatus.PARTIAL);
    }

    @Test
    void afterJob_whenEveryAuditRowApproved_reportsCompleted() {
        UUID jobId = UUID.fromString("00000000-0000-0000-0000-000000000044");
        BulkLoadJob bulkLoadJob = new BulkLoadJob();
        bulkLoadJob.setId(jobId);
        bulkLoadJob.setStatus(JobStatus.PROCESSING);

        JobExecution jobExecution = MetaDataInstanceFactory.createJobExecution(
                "catalogBulkLoadJob",
                16L,
                26L,
                new JobParametersBuilder().addString("jobId", jobId.toString()).toJobParameters());
        jobExecution.setStatus(BatchStatus.COMPLETED);

        StepExecution stepExecution =
                MetaDataInstanceFactory.createStepExecution(jobExecution, "catalogBulkLoadStep", 36L);
        stepExecution.setReadCount(3);
        stepExecution.setWriteCount(3);
        jobExecution.addStepExecution(stepExecution);

        when(bulkLoadJobRepository.findById(jobId)).thenReturn(Optional.of(bulkLoadJob));
        when(auditRepository.countByJobIdAndReviewStatus(jobId, ReviewStatus.APPROVED))
                .thenReturn(3L);
        when(auditRepository.countByJobIdAndReviewStatus(jobId, ReviewStatus.PENDING))
                .thenReturn(0L);

        listener.afterJob(jobExecution);

        assertThat(bulkLoadJob.getSuccessCount()).isEqualTo(3L);
        assertThat(bulkLoadJob.getFailureCount()).isZero();
        assertThat(bulkLoadJob.getStatus()).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    void afterJob_whenBatchFailed_staysFailedEvenWithApprovedRows() {
        // A batch that died partway may still have accepted rows; the run itself did not finish,
        // so FAILED — not PARTIAL — is what an operator has to act on.
        UUID jobId = UUID.fromString("00000000-0000-0000-0000-000000000045");
        BulkLoadJob bulkLoadJob = new BulkLoadJob();
        bulkLoadJob.setId(jobId);
        bulkLoadJob.setStatus(JobStatus.PROCESSING);

        JobExecution jobExecution = MetaDataInstanceFactory.createJobExecution(
                "catalogBulkLoadJob",
                17L,
                27L,
                new JobParametersBuilder().addString("jobId", jobId.toString()).toJobParameters());
        jobExecution.setStatus(BatchStatus.FAILED);

        when(bulkLoadJobRepository.findById(jobId)).thenReturn(Optional.of(bulkLoadJob));
        when(auditRepository.countByJobIdAndReviewStatus(jobId, ReviewStatus.APPROVED))
                .thenReturn(2L);
        when(auditRepository.countByJobIdAndReviewStatus(jobId, ReviewStatus.PENDING))
                .thenReturn(0L);

        listener.afterJob(jobExecution);

        assertThat(bulkLoadJob.getSuccessCount()).isEqualTo(2L);
        assertThat(bulkLoadJob.getStatus()).isEqualTo(JobStatus.FAILED);
    }

    @Test
    void afterJob_whenJobIdMissing_returnsWithoutSaving() {
        JobExecution jobExecution = MetaDataInstanceFactory.createJobExecution(
                "catalogBulkLoadJob", 13L, 23L, new JobParametersBuilder().toJobParameters());
        jobExecution.setStatus(BatchStatus.COMPLETED);

        listener.afterJob(jobExecution);

        verify(bulkLoadJobRepository, org.mockito.Mockito.never()).save(any(BulkLoadJob.class));
    }

    @Test
    void afterJob_whenJobIdMalformed_throwsIllegalState() {
        JobExecution jobExecution = MetaDataInstanceFactory.createJobExecution(
                "catalogBulkLoadJob",
                14L,
                24L,
                new JobParametersBuilder().addString("jobId", "not-a-uuid").toJobParameters());

        assertThatThrownBy(() -> listener.afterJob(jobExecution))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid bulk-load jobId");
    }
}
