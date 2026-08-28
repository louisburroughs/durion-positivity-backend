package com.positivity.bulkloader.internal.service;

import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.bulkloader.internal.entity.BulkLoadRecordAudit;
import com.positivity.bulkloader.internal.enums.DomainType;
import com.positivity.bulkloader.internal.enums.ReviewStatus;
import com.positivity.bulkloader.internal.repository.BulkLoadRecordAuditRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a bulk-ingest response into one {@link BulkLoadRecordAudit} row per submitted record.
 *
 * <p>Until this existed the writers posted each chunk with {@code toBodilessEntity()} and threw
 * the per-row results away, so {@code bulk_load_record_audit} was only ever written by the
 * correction endpoint. That left the whole review queue — the audit listing, the error report,
 * and the corrections it feeds — with a read side and no write side, and made a job's
 * {@code successCount} a count of rows *sent* rather than rows *accepted*.
 *
 * <p>Every submitted row gets a row here, accepted or not: the accepted ones are what make
 * {@code successCount} true, and the rejected ones are what an operator corrects. A response the
 * service could not produce (a body that did not parse, or a row index it did not answer for) is
 * recorded as a failure rather than dropped — silence would read as success.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BulkIngestResultRecorder {

    /** Recorded when the owning service accepted the batch but said nothing about a given row. */
    static final String MISSING_RESULT_CODE = "BULK_INGEST_NO_RESULT";

    /** Recorded when the owning service returned no parseable body at all. */
    static final String MISSING_RESPONSE_CODE = "BULK_INGEST_NO_RESPONSE";

    private final BulkLoadRecordAuditRepository auditRepository;
    private final ObjectMapper objectMapper;

    /**
     * Persists the audit trail for one chunk.
     *
     * <p>Runs in its own transaction so the trail survives a later failure in the same step: the
     * rows describing what happened are the only record an operator has, and losing them to a
     * rollback would leave a failed job with nothing to review.
     *
     * @param jobId the bulk-load job the chunk belongs to
     * @param domainType recorded as the audit row's entity type
     * @param rowOffset the file-absolute index of the chunk's first record, so row numbers read
     *     against the uploaded file rather than restarting at zero every chunk
     * @param submitted the records posted, in the order they were posted
     * @param response the owning service's reply, or null when it returned no usable body
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            @NonNull UUID jobId,
            @NonNull DomainType domainType,
            long rowOffset,
            @NonNull List<?> submitted,
            @Nullable BulkIngestResponse response) {

        Map<Integer, BulkIngestResult> byRowIndex = indexResults(response);
        List<BulkLoadRecordAudit> audits = new ArrayList<>(submitted.size());

        for (int i = 0; i < submitted.size(); i++) {
            BulkIngestResult result = byRowIndex.get(i);
            audits.add(toAudit(jobId, domainType, rowOffset + i, submitted.get(i), result, response != null));
        }

        auditRepository.saveAll(audits);
    }

    private Map<Integer, BulkIngestResult> indexResults(@Nullable BulkIngestResponse response) {
        Map<Integer, BulkIngestResult> byRowIndex = new LinkedHashMap<>();
        if (response == null || response.getResults() == null) {
            return byRowIndex;
        }
        for (BulkIngestResult result : response.getResults()) {
            byRowIndex.put(result.getRowIndex(), result);
        }
        return byRowIndex;
    }

    private BulkLoadRecordAudit toAudit(
            UUID jobId,
            DomainType domainType,
            long rowNumber,
            Object submittedRecord,
            @Nullable BulkIngestResult result,
            boolean hadResponse) {

        BulkLoadRecordAudit audit = new BulkLoadRecordAudit();
        audit.setJobId(jobId);
        audit.setEntityType(domainType.name());
        audit.setRowNumber(rowNumber);
        audit.setOriginalValues(serialize(submittedRecord));

        if (result != null && result.isSuccess()) {
            audit.setEntityId(result.getEntityId());
            audit.setReviewStatus(ReviewStatus.APPROVED);
            return audit;
        }

        audit.setReviewStatus(ReviewStatus.PENDING);
        if (result != null) {
            audit.setEntityId(result.getEntityId());
            audit.setReasonCodes(serializeReason(result.getErrorCode(), result.getErrorMessage()));
        } else if (hadResponse) {
            audit.setReasonCodes(
                    serializeReason(MISSING_RESULT_CODE, "The owning service returned no result for row " + rowNumber));
        } else {
            audit.setReasonCodes(
                    serializeReason(MISSING_RESPONSE_CODE, "The owning service returned no parseable response body"));
        }
        return audit;
    }

    private String serializeReason(@Nullable String errorCode, @Nullable String errorMessage) {
        Map<String, String> reason = new LinkedHashMap<>();
        reason.put("errorCode", errorCode);
        reason.put("errorMessage", errorMessage);
        return serialize(reason);
    }

    /**
     * A record that cannot be serialized must not sink the chunk it belongs to — the audit row is
     * still worth having for its status and row number, so the payload column is left null and the
     * cause is logged.
     */
    private String serialize(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to serialize bulk-load audit payload of type {}",
                    value.getClass().getName(),
                    e);
            return null;
        }
    }
}
