package com.positivity.bulkloader.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.bulkloader.internal.dto.BulkLoadJobCreateRequest;
import com.positivity.bulkloader.internal.dto.BulkLoadJobResponse;
import com.positivity.bulkloader.internal.entity.BulkLoadJob;
import com.positivity.bulkloader.internal.enums.DomainType;
import com.positivity.bulkloader.internal.enums.JobStatus;
import com.positivity.bulkloader.internal.repository.BulkLoadJobRepository;
import com.positivity.bulkloader.service.BulkLoadBatchLauncher;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({ "java:S100", "java:S1192" })
class BulkLoadJobServiceImplTest {

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String OPERATOR_ID = "operator-001";

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

        assertThatThrownBy(() -> service.startProcessing(JOB_ID, OPERATOR_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("uploaded file is persisted");
    }

    @Test
    void startProcessing_whenUploadedFilePresent_transitionsToProcessing() {
        BulkLoadJob job = savedJob(JOB_ID, OPERATOR_ID, JobStatus.UPLOADING);
        job.setOriginalFilePath("00000000-0000-0000-0000-000000000001/products.csv");

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(jobRepository.save(any(BulkLoadJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.startProcessing(JOB_ID, OPERATOR_ID);

        assertThat(job.getStatus()).isEqualTo(JobStatus.PROCESSING);
        assertThat(job.getStartedAt()).isNotNull();
        verify(bulkLoadBatchLauncher).launch(job);
        verify(jobRepository).save(job);
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
        job.setFileName("products.csv");
        job.setDomainType(DomainType.CATALOG_PRODUCT);
        job.setStatus(status);
        return job;
    }
}
