package com.positivity.bulkloader.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.bulkloader.internal.dto.BulkLoadJobCreateRequest;
import com.positivity.bulkloader.internal.dto.BulkLoadJobResponse;
import com.positivity.bulkloader.internal.entity.BulkLoadJob;
import com.positivity.bulkloader.internal.enums.DomainType;
import com.positivity.bulkloader.internal.enums.JobStatus;
import com.positivity.bulkloader.internal.exception.JobOwnershipViolationException;
import com.positivity.bulkloader.internal.repository.BulkLoadJobRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"java:S100", "java:S1192"})
class BulkLoadJobServiceImplTest {

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String OPERATOR_ID = "operator-001";
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    @Mock
    BulkLoadJobRepository jobRepository;

    @Mock
    BulkLoadBatchLauncher bulkLoadBatchLauncher;

    @InjectMocks
    BulkLoadJobServiceImpl service;

    // ─── createJob ───────────────────────────────────────────────────────────

    @Test
    void createJob_happyPath_savesAndReturnsResponse() {
        BulkLoadJobCreateRequest request = new BulkLoadJobCreateRequest();
        request.setFileName("products.csv");
        request.setDomainType(DomainType.CATALOG_PRODUCT);

        BulkLoadJob saved = savedJob(JOB_ID, OPERATOR_ID, JobStatus.CREATED);

        when(jobRepository.countByOperatorIdAndStatusIn(any(), any())).thenReturn(0L);
        when(jobRepository.save(any(BulkLoadJob.class))).thenReturn(saved);

        BulkLoadJobResponse response = service.createJob(request, OPERATOR_ID);

        assertThat(response.getId()).isEqualTo(JOB_ID);
        assertThat(response.getOperatorId()).isEqualTo(OPERATOR_ID);
        assertThat(response.getStatus()).isEqualTo(JobStatus.CREATED);
        verify(jobRepository).save(any(BulkLoadJob.class));
    }

    @Test
    void createJob_whenOperatorHasActiveJob_throwsIllegalState() {
        BulkLoadJobCreateRequest request = new BulkLoadJobCreateRequest();
        request.setFileName("products.csv");
        request.setDomainType(DomainType.CATALOG_PRODUCT);

        when(jobRepository.countByOperatorIdAndStatusIn(any(), any())).thenReturn(1L);

        assertThatThrownBy(() -> service.createJob(request, OPERATOR_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active bulk load job");
    }

    @Test
    void markUploadStored_whenJobOwned_persistsStoragePathAndTransitionsToUploading() {
        BulkLoadJob job = savedJob(JOB_ID, OPERATOR_ID, JobStatus.CREATED);

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(jobRepository.save(any(BulkLoadJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.markUploadStored(JOB_ID, OPERATOR_ID, "00000000-0000-0000-0000-000000000001/products.csv");

        assertThat(job.getOriginalFilePath()).isEqualTo("00000000-0000-0000-0000-000000000001/products.csv");
        assertThat(job.getStatus()).isEqualTo(JobStatus.UPLOADING);
        verify(jobRepository).save(job);
    }

    @Test
    void startProcessing_whenUploadedFileMissing_throwsIllegalState() {
        BulkLoadJob job = savedJob(JOB_ID, OPERATOR_ID, JobStatus.CREATED);

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.startProcessing(JOB_ID, OPERATOR_ID, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("uploaded file is persisted");
    }

    @Test
    void startProcessing_whenUploadedFilePresent_transitionsToProcessing() {
        BulkLoadJob job = savedJob(JOB_ID, OPERATOR_ID, JobStatus.UPLOADING);
        job.setOriginalFilePath("00000000-0000-0000-0000-000000000001/products.csv");
        job.setLocationId(UUID.fromString("00000000-0000-0000-0000-000000000002"));

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(jobRepository.save(any(BulkLoadJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.startProcessing(JOB_ID, OPERATOR_ID, "Bearer token-123");

        assertThat(job.getStatus()).isEqualTo(JobStatus.PROCESSING);
        assertThat(job.getStartedAt()).isNotNull();
        verify(bulkLoadBatchLauncher).launch(job, "Bearer token-123");
        verify(jobRepository).save(job);
    }

    /**
     * Issue #1712: the batch runs synchronously (no {@code TaskExecutor} is configured), so
     * {@code BulkLoadJobExecutionListener#afterJob} has already written the terminal status — on
     * this same managed entity instance — by the time {@code launch} returns. Stamping PROCESSING
     * after the launch overwrote it, and every finished job then read as PROCESSING for ever
     * while its row counters said the run was done. The launcher here stands in for that
     * listener, so the test fails if the two writes are ever reordered again.
     */
    @Test
    void startProcessing_whenLaunchCompletesTheRun_leavesTheTerminalStatusIntact() {
        BulkLoadJob job = savedJob(JOB_ID, OPERATOR_ID, JobStatus.UPLOADING);
        job.setOriginalFilePath("00000000-0000-0000-0000-000000000001/products.csv");
        job.setLocationId(UUID.fromString("00000000-0000-0000-0000-000000000002"));

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(jobRepository.save(any(BulkLoadJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
                    BulkLoadJob launched = invocation.getArgument(0);
                    assertThat(launched.getStatus())
                            .as("the entity handed to the launcher must already carry PROCESSING, so the"
                                    + " listener's terminal status is written after it and not over it")
                            .isEqualTo(JobStatus.PROCESSING);
                    launched.setStatus(JobStatus.COMPLETED);
                    launched.setSuccessCount(12L);
                    launched.setProcessedRows(12L);
                    return null;
                })
                .when(bulkLoadBatchLauncher)
                .launch(any(BulkLoadJob.class), nullable(String.class));

        service.startProcessing(JOB_ID, OPERATOR_ID, "Bearer token-123");

        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.getStartedAt()).isNotNull();
        assertThat(job.getSuccessCount()).isEqualTo(12L);
    }

    @Test
    void startProcessing_whenLocationIdMissing_throwsIllegalState() {
        BulkLoadJob job = savedJob(JOB_ID, OPERATOR_ID, JobStatus.UPLOADING);
        job.setOriginalFilePath("00000000-0000-0000-0000-000000000001/products.csv");
        job.setLocationId(null);

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.startProcessing(JOB_ID, OPERATOR_ID, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("locationId");
    }

    // ─── cancelJob ───────────────────────────────────────────────────────────

    @Test
    void cancelJob_whenJobFound_transitionsToCancelled() {
        BulkLoadJob job = savedJob(JOB_ID, OPERATOR_ID, JobStatus.CREATED);
        BulkLoadJob cancelled = savedJob(JOB_ID, OPERATOR_ID, JobStatus.CANCELLED);

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(jobRepository.save(any(BulkLoadJob.class))).thenReturn(cancelled);

        BulkLoadJobResponse response = service.cancelJob(JOB_ID, OPERATOR_ID);

        assertThat(response.getStatus()).isEqualTo(JobStatus.CANCELLED);
        verify(jobRepository).save(job);
    }

    @Test
    void cancelJob_whenJobNotFound_throwsNoSuchElement() {
        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelJob(JOB_ID, OPERATOR_ID))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(JOB_ID.toString());
    }

    @Test
    void cancelJob_whenJobAlreadyTerminal_throwsIllegalState() {
        BulkLoadJob job = savedJob(JOB_ID, OPERATOR_ID, JobStatus.COMPLETED);

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.cancelJob(JOB_ID, OPERATOR_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal state");
    }

    // ─── retryJob ────────────────────────────────────────────────────────────

    @Test
    void retryJob_happyPath_resetsCountersAndSetsCreated() {
        BulkLoadJob job = savedJob(JOB_ID, OPERATOR_ID, JobStatus.FAILED);
        BulkLoadJob saved = savedJob(JOB_ID, OPERATOR_ID, JobStatus.CREATED);

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(jobRepository.countByOperatorIdAndStatusIn(any(), any())).thenReturn(0L);
        when(jobRepository.save(any(BulkLoadJob.class))).thenReturn(saved);

        BulkLoadJobResponse response = service.retryJob(JOB_ID, OPERATOR_ID);

        assertThat(response.getStatus()).isEqualTo(JobStatus.CREATED);
        assertThat(job.getStatus()).isEqualTo(JobStatus.CREATED);
        assertThat(job.getProcessedRows()).isZero();
        assertThat(job.getSuccessCount()).isZero();
        assertThat(job.getFailureCount()).isZero();
        assertThat(job.getTotalRows()).isNull();
        verify(jobRepository).save(job);
    }

    @Test
    void retryJob_whenJobNotOwned_throwsOwnershipViolation() {
        BulkLoadJob job = savedJob(JOB_ID, "other-operator", JobStatus.FAILED);

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.retryJob(JOB_ID, OPERATOR_ID))
                .isInstanceOf(JobOwnershipViolationException.class);
    }

    @Test
    void retryJob_whenJobNotFailed_throwsIllegalState() {
        BulkLoadJob job = savedJob(JOB_ID, OPERATOR_ID, JobStatus.PROCESSING);

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.retryJob(JOB_ID, OPERATOR_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED or PARTIAL state");
    }

    @Test
    void retryJob_whenJobPartial_isAllowed() {
        // A run that lost rows is exactly the run worth re-running.
        BulkLoadJob job = savedJob(JOB_ID, OPERATOR_ID, JobStatus.PARTIAL);

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(jobRepository.countByOperatorIdAndStatusIn(any(), any())).thenReturn(0L);
        when(jobRepository.save(any(BulkLoadJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.retryJob(JOB_ID, OPERATOR_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.CREATED);
        assertThat(job.getFailureCount()).isZero();
    }

    @Test
    void cancelJob_whenJobPartial_isRefusedAsTerminal() {
        BulkLoadJob job = savedJob(JOB_ID, OPERATOR_ID, JobStatus.PARTIAL);

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.cancelJob(JOB_ID, OPERATOR_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal state");
    }

    @Test
    void retryJob_whenOperatorHasActiveJob_throwsIllegalState() {
        BulkLoadJob job = savedJob(JOB_ID, OPERATOR_ID, JobStatus.FAILED);

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(jobRepository.countByOperatorIdAndStatusIn(any(), any())).thenReturn(1L);

        assertThatThrownBy(() -> service.retryJob(JOB_ID, OPERATOR_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active bulk load job");
    }

    // ─── getJob ──────────────────────────────────────────────────────────────

    @Test
    void getJob_whenFound_returnsResponse() {
        BulkLoadJob job = savedJob(JOB_ID, OPERATOR_ID, JobStatus.CREATED);

        when(jobRepository.findByIdAndOperatorId(JOB_ID, OPERATOR_ID)).thenReturn(Optional.of(job));

        BulkLoadJobResponse response = service.getJob(JOB_ID, OPERATOR_ID);

        assertThat(response.getId()).isEqualTo(JOB_ID);
        assertThat(response.getStatus()).isEqualTo(JobStatus.CREATED);
    }

    @Test
    void getJob_whenNotFound_throwsNoSuchElement() {
        when(jobRepository.findByIdAndOperatorId(JOB_ID, OPERATOR_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getJob(JOB_ID, OPERATOR_ID))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(JOB_ID.toString());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private BulkLoadJob savedJob(UUID id, String operatorId, JobStatus status) {
        BulkLoadJob job = new BulkLoadJob();
        job.setId(id);
        job.setOperatorId(operatorId);
        job.setLocationId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        job.setFileName("products.csv");
        job.setDomainType(DomainType.CATALOG_PRODUCT);
        job.setStatus(status);
        return job;
    }
}
