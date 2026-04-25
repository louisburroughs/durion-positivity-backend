package com.positivity.bulkloader.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.bulkloader.internal.dto.AuditRecordResponse;
import com.positivity.bulkloader.internal.dto.BulkCorrectionItem;
import com.positivity.bulkloader.internal.dto.BulkCorrectionRequest;
import com.positivity.bulkloader.internal.dto.BulkCorrectionResponse;
import com.positivity.bulkloader.internal.entity.BulkLoadJob;
import com.positivity.bulkloader.internal.entity.BulkLoadRecordAudit;
import com.positivity.bulkloader.internal.enums.DomainType;
import com.positivity.bulkloader.internal.enums.JobStatus;
import com.positivity.bulkloader.internal.enums.ReviewStatus;
import com.positivity.bulkloader.internal.exception.JobOwnershipViolationException;
import com.positivity.bulkloader.internal.repository.BulkLoadJobRepository;
import com.positivity.bulkloader.internal.repository.BulkLoadRecordAuditRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({ "java:S100", "java:S1192" })
class ReviewQueueServiceImplTest {

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID AUDIT_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final String OPERATOR_ID = "op-001";

    @Mock
    BulkLoadRecordAuditRepository auditRepository;

    @Mock
    BulkLoadJobRepository jobRepository;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    ReviewQueueServiceImpl service;

    @Test
    void getAuditRecords_returnsMappedResponses() {
        BulkLoadRecordAudit audit = auditRecord(AUDIT_ID, JOB_ID);

        when(auditRepository.findByJobId(JOB_ID)).thenReturn(List.of(audit));

        List<AuditRecordResponse> responses = service.getAuditRecords(JOB_ID);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getId()).isEqualTo(AUDIT_ID);
        assertThat(responses.get(0).getJobId()).isEqualTo(JOB_ID);
        assertThat(responses.get(0).getReviewStatus()).isEqualTo(ReviewStatus.PENDING);
        assertThat(responses.get(0).getEntityType()).isEqualTo("PRODUCT");
    }

    @Test
    void getAuditRecords_whenNoRecords_returnsEmptyList() {
        when(auditRepository.findByJobId(JOB_ID)).thenReturn(List.of());

        List<AuditRecordResponse> responses = service.getAuditRecords(JOB_ID);

        assertThat(responses).isEmpty();
    }

    @Test
    void generateErrorReport_returnsNonNullByteArrayResource() {
        BulkLoadRecordAudit audit = auditRecord(AUDIT_ID, JOB_ID);
        audit.setReasonCodes("[\"DUPLICATE_SKU\"]");
        audit.setOriginalValues("{\"sku\": \"ABC-001\"}");

        when(auditRepository.findByJobId(JOB_ID)).thenReturn(List.of(audit));

        Resource report = service.generateErrorReport(JOB_ID);

        assertThat(report).isNotNull();
        assertThat(report.isReadable()).isTrue();
    }

    @Test
    void generateErrorReport_csvContainsHeader() throws Exception {
        when(auditRepository.findByJobId(JOB_ID)).thenReturn(List.of());

        Resource report = service.generateErrorReport(JOB_ID);

        String content = new String(report.getContentAsByteArray());
        assertThat(content).contains("row_number,entity_type,review_status,reason_codes,original_values");
    }

    @Test
    void generateErrorReport_csvContainsRecordData() throws Exception {
        BulkLoadRecordAudit audit = auditRecord(AUDIT_ID, JOB_ID);
        audit.setRowNumber(5L);

        when(auditRepository.findByJobId(JOB_ID)).thenReturn(List.of(audit));

        Resource report = service.generateErrorReport(JOB_ID);

        String content = new String(report.getContentAsByteArray());
        assertThat(content).contains("PRODUCT").contains("PENDING");
    }

    // ─── submitCorrections ────────────────────────────────────────────────────

    @Test
    void submitCorrections_happyPath_acceptsAllValidItems() {
        BulkLoadJob job = bulkLoadJob(JOB_ID, OPERATOR_ID, JobStatus.FAILED);
        BulkLoadRecordAudit audit = auditRecord(AUDIT_ID, JOB_ID);
        BulkCorrectionItem item = BulkCorrectionItem.builder()
                .auditRecordId(AUDIT_ID)
                .correctedData(Map.of("sku", "PROD-001"))
                .build();
        BulkCorrectionRequest request = BulkCorrectionRequest.builder()
                .corrections(List.of(item))
                .build();

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(auditRepository.findById(AUDIT_ID)).thenReturn(Optional.of(audit));
        when(auditRepository.save(any(BulkLoadRecordAudit.class))).thenAnswer(inv -> inv.getArgument(0));

        BulkCorrectionResponse response = service.submitCorrections(JOB_ID, request, OPERATOR_ID);

        assertThat(response.getJobId()).isEqualTo(JOB_ID);
        assertThat(response.getSubmittedCount()).isEqualTo(1);
        assertThat(response.getAcceptedCount()).isEqualTo(1);
        assertThat(response.getRejectedCount()).isZero();
        assertThat(audit.getReviewStatus()).isEqualTo(ReviewStatus.CORRECTED);
        assertThat(audit.getCorrectedValues()).contains("sku");
        verify(auditRepository).save(audit);
    }

    @Test
    void submitCorrections_whenAuditRecordBelongsToDifferentJob_rejectsItem() {
        UUID otherJobId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        BulkLoadJob job = bulkLoadJob(JOB_ID, OPERATOR_ID, JobStatus.FAILED);
        BulkLoadRecordAudit audit = auditRecord(AUDIT_ID, otherJobId);
        BulkCorrectionItem item = BulkCorrectionItem.builder()
                .auditRecordId(AUDIT_ID)
                .correctedData(Map.of("sku", "PROD-001"))
                .build();
        BulkCorrectionRequest request = BulkCorrectionRequest.builder()
                .corrections(List.of(item))
                .build();

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(auditRepository.findById(AUDIT_ID)).thenReturn(Optional.of(audit));

        BulkCorrectionResponse response = service.submitCorrections(JOB_ID, request, OPERATOR_ID);

        assertThat(response.getAcceptedCount()).isZero();
        assertThat(response.getRejectedCount()).isEqualTo(1);
        assertThat(response.getRejections()).hasSize(1).first().asString().contains(AUDIT_ID.toString());
    }

    @Test
    void submitCorrections_whenAuditRecordNotFound_rejectsItem() {
        BulkLoadJob job = bulkLoadJob(JOB_ID, OPERATOR_ID, JobStatus.FAILED);
        BulkCorrectionItem item = BulkCorrectionItem.builder()
                .auditRecordId(AUDIT_ID)
                .correctedData(Map.of("sku", "PROD-001"))
                .build();
        BulkCorrectionRequest request = BulkCorrectionRequest.builder()
                .corrections(List.of(item))
                .build();

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(auditRepository.findById(AUDIT_ID)).thenReturn(Optional.empty());

        BulkCorrectionResponse response = service.submitCorrections(JOB_ID, request, OPERATOR_ID);

        assertThat(response.getAcceptedCount()).isZero();
        assertThat(response.getRejectedCount()).isEqualTo(1);
    }

    @Test
    void submitCorrections_whenJobNotInFailedState_throwsIllegalState() {
        BulkLoadJob job = bulkLoadJob(JOB_ID, OPERATOR_ID, JobStatus.PROCESSING);
        BulkCorrectionRequest request = BulkCorrectionRequest.builder()
                .corrections(List.of())
                .build();

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.submitCorrections(JOB_ID, request, OPERATOR_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED state");
    }

    @Test
    void submitCorrections_whenNotOwner_throwsOwnershipViolation() {
        BulkLoadJob job = bulkLoadJob(JOB_ID, "other-operator", JobStatus.FAILED);
        BulkCorrectionRequest request = BulkCorrectionRequest.builder()
                .corrections(List.of())
                .build();

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.submitCorrections(JOB_ID, request, OPERATOR_ID))
                .isInstanceOf(JobOwnershipViolationException.class);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private BulkLoadJob bulkLoadJob(UUID id, String operatorId, JobStatus status) {
        BulkLoadJob job = new BulkLoadJob();
        job.setId(id);
        job.setOperatorId(operatorId);
        job.setLocationId(UUID.fromString("00000000-0000-0000-0000-000000000003"));
        job.setFileName("products.csv");
        job.setDomainType(DomainType.CATALOG_PRODUCT);
        job.setStatus(status);
        return job;
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private BulkLoadRecordAudit auditRecord(UUID id, UUID jobId) {
        BulkLoadRecordAudit audit = new BulkLoadRecordAudit();
        audit.setId(id);
        audit.setJobId(jobId);
        audit.setEntityType("PRODUCT");
        audit.setRowNumber(1L);
        audit.setReviewStatus(ReviewStatus.PENDING);
        return audit;
    }
}
