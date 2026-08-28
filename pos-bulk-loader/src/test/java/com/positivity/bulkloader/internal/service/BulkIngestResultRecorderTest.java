package com.positivity.bulkloader.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.bulkloader.internal.entity.BulkLoadRecordAudit;
import com.positivity.bulkloader.internal.enums.DomainType;
import com.positivity.bulkloader.internal.enums.ReviewStatus;
import com.positivity.bulkloader.internal.repository.BulkLoadRecordAuditRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

/**
 * The audit trail is the only per-row record an operator gets, so these tests pin the cases where
 * losing a row would be silent: a rejected row, a row the service answered for out of order, and a
 * response that never arrived.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"java:S100", "java:S1192"})
class BulkIngestResultRecorderTest {

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ENTITY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    @Mock
    BulkLoadRecordAuditRepository auditRepository;

    @Captor
    ArgumentCaptor<List<BulkLoadRecordAudit>> auditCaptor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private BulkIngestResultRecorder recorder() {
        return new BulkIngestResultRecorder(auditRepository, objectMapper);
    }

    private record Row(String sku) {}

    @Test
    void record_writesOneRowPerSubmittedRecord_withOutcomeAndPayload() {
        BulkIngestResponse response = BulkIngestResponse.builder()
                .totalSubmitted(2)
                .successCount(1)
                .failureCount(1)
                .results(List.of(
                        BulkIngestResult.builder()
                                .rowIndex(0)
                                .success(true)
                                .entityId(ENTITY_ID)
                                .build(),
                        BulkIngestResult.builder()
                                .rowIndex(1)
                                .success(false)
                                .errorCode("CATALOG_INGEST_FAILED")
                                .errorMessage("unknown category")
                                .build()))
                .build();

        recorder().record(JOB_ID, DomainType.CATALOG_PRODUCT, 0L, List.of(new Row("A"), new Row("B")), response);

        verify(auditRepository).saveAll(auditCaptor.capture());
        List<BulkLoadRecordAudit> audits = auditCaptor.getValue();
        assertThat(audits).hasSize(2);

        assertThat(audits.getFirst().getJobId()).isEqualTo(JOB_ID);
        assertThat(audits.getFirst().getEntityType()).isEqualTo("CATALOG_PRODUCT");
        assertThat(audits.getFirst().getRowNumber()).isZero();
        assertThat(audits.getFirst().getReviewStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(audits.getFirst().getEntityId()).isEqualTo(ENTITY_ID);
        assertThat(audits.getFirst().getOriginalValues()).contains("\"sku\":\"A\"");

        assertThat(audits.get(1).getReviewStatus()).isEqualTo(ReviewStatus.PENDING);
        assertThat(audits.get(1).getReasonCodes())
                .contains("CATALOG_INGEST_FAILED")
                .contains("unknown category");
    }

    @Test
    void record_appliesRowOffset_soRowNumbersReadAgainstTheFile() {
        BulkIngestResponse response = BulkIngestResponse.builder()
                .results(List.of(
                        BulkIngestResult.builder().rowIndex(0).success(true).build()))
                .build();

        recorder().record(JOB_ID, DomainType.LOCATION, 500L, List.of(new Row("A")), response);

        verify(auditRepository).saveAll(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getFirst().getRowNumber()).isEqualTo(500L);
    }

    @Test
    void record_whenServiceOmitsARow_recordsItAsAFailureRatherThanDroppingIt() {
        BulkIngestResponse response = BulkIngestResponse.builder()
                .results(List.of(
                        BulkIngestResult.builder().rowIndex(0).success(true).build()))
                .build();

        recorder().record(JOB_ID, DomainType.PERSON, 0L, List.of(new Row("A"), new Row("B")), response);

        verify(auditRepository).saveAll(auditCaptor.capture());
        List<BulkLoadRecordAudit> audits = auditCaptor.getValue();
        assertThat(audits).hasSize(2);
        assertThat(audits.get(1).getReviewStatus()).isEqualTo(ReviewStatus.PENDING);
        assertThat(audits.get(1).getReasonCodes()).contains(BulkIngestResultRecorder.MISSING_RESULT_CODE);
    }

    @Test
    void record_whenNoResponseBody_marksEveryRowFailed() {
        recorder().record(JOB_ID, DomainType.VEHICLE, 0L, List.of(new Row("A"), new Row("B")), null);

        verify(auditRepository).saveAll(auditCaptor.capture());
        List<BulkLoadRecordAudit> audits = auditCaptor.getValue();
        assertThat(audits).hasSize(2);
        assertThat(audits)
                .allSatisfy(audit -> assertThat(audit.getReviewStatus()).isEqualTo(ReviewStatus.PENDING));
        assertThat(audits.getFirst().getReasonCodes()).contains(BulkIngestResultRecorder.MISSING_RESPONSE_CODE);
    }

    @Test
    void record_whenResponseCarriesNoResults_marksEveryRowFailed() {
        BulkIngestResponse response = BulkIngestResponse.builder().build();

        recorder().record(JOB_ID, DomainType.BASE_PRICE, 0L, List.of(new Row("A")), response);

        verify(auditRepository).saveAll(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getFirst().getReviewStatus()).isEqualTo(ReviewStatus.PENDING);
    }
}
