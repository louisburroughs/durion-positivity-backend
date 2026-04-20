package com.positivity.bulkloader.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.bulkloader.internal.dto.AuditRecordResponse;
import com.positivity.bulkloader.internal.entity.BulkLoadRecordAudit;
import com.positivity.bulkloader.internal.enums.ReviewStatus;
import com.positivity.bulkloader.internal.repository.BulkLoadRecordAuditRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"java:S100", "java:S1192"})
class ReviewQueueServiceImplTest {

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID AUDIT_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");

    @Mock
    BulkLoadRecordAuditRepository auditRepository;

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
