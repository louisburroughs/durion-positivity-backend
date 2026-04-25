package com.positivity.bulkloader.internal.service;

import com.positivity.bulkloader.internal.dto.AuditRecordResponse;
import com.positivity.bulkloader.internal.dto.BulkCorrectionItem;
import com.positivity.bulkloader.internal.dto.BulkCorrectionRequest;
import com.positivity.bulkloader.internal.dto.BulkCorrectionResponse;
import com.positivity.bulkloader.internal.entity.BulkLoadRecordAudit;
import com.positivity.bulkloader.internal.enums.JobStatus;
import com.positivity.bulkloader.internal.enums.ReviewStatus;
import com.positivity.bulkloader.internal.exception.JobOwnershipViolationException;
import com.positivity.bulkloader.internal.repository.BulkLoadJobRepository;
import com.positivity.bulkloader.internal.repository.BulkLoadRecordAuditRepository;
import com.positivity.bulkloader.service.ReviewQueueService;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class ReviewQueueServiceImpl implements ReviewQueueService {

    private final BulkLoadRecordAuditRepository auditRepository;
    private final BulkLoadJobRepository jobRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public List<AuditRecordResponse> getAuditRecords(@NonNull UUID jobId) {
        return auditRepository.findByJobId(jobId).stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Resource generateErrorReport(@NonNull UUID jobId) {
        List<BulkLoadRecordAudit> auditRecords = auditRepository.findByJobId(jobId);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8));
            writer.println("row_number,entity_type,review_status,reason_codes,original_values");
            for (BulkLoadRecordAudit auditRecord : auditRecords) {
                writer.printf(
                        "%s,%s,%s,%s,%s%n",
                        auditRecord.getRowNumber(),
                        auditRecord.getEntityType(),
                        auditRecord.getReviewStatus(),
                        escapeCsv(auditRecord.getReasonCodes()),
                        escapeCsv(auditRecord.getOriginalValues()));
            }
            writer.flush();
            return new ByteArrayResource(baos.toByteArray());
        } catch (Exception e) {
            throw new UncheckedIOException(
                    "Failed to generate error report for job " + jobId, new java.io.IOException(e));
        }
    }

    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private AuditRecordResponse toResponse(BulkLoadRecordAudit audit) {
        return AuditRecordResponse.builder()
                .id(audit.getId())
                .jobId(audit.getJobId())
                .entityType(audit.getEntityType())
                .entityId(audit.getEntityId())
                .rowNumber(audit.getRowNumber())
                .reviewStatus(audit.getReviewStatus())
                .reasonCodes(audit.getReasonCodes())
                .originalValues(audit.getOriginalValues())
                .createdAt(audit.getCreatedAt())
                .build();
    }

    @Override
    @Transactional
    public BulkCorrectionResponse submitCorrections(
            @NonNull UUID jobId, @NonNull BulkCorrectionRequest request, @NonNull String operatorId) {
        var job = jobRepository
                .findById(jobId)
                .orElseThrow(() -> new NoSuchElementException("BulkLoadJob not found: " + jobId));
        if (!job.getOperatorId().equals(operatorId)) {
            throw new JobOwnershipViolationException(jobId.toString());
        }
        if (job.getStatus() != JobStatus.FAILED) {
            throw new IllegalStateException(
                    "Corrections can only be submitted for jobs in FAILED state, current state: " + job.getStatus());
        }

        int acceptedCount = 0;
        int rejectedCount = 0;
        List<String> rejections = new ArrayList<>();

        for (BulkCorrectionItem item : request.getCorrections()) {
            var auditOpt = auditRepository.findById(item.getAuditRecordId());
            if (auditOpt.isEmpty() || !auditOpt.get().getJobId().equals(jobId)) {
                rejectedCount++;
                rejections.add("Audit record not found or does not belong to this job: " + item.getAuditRecordId());
            } else {
                var audit = auditOpt.get();
                String serialized = trySerialize(item.getCorrectedData());
                if (serialized == null) {
                    rejectedCount++;
                    rejections.add("Failed to serialize corrected values for record: " + item.getAuditRecordId());
                } else {
                    audit.setCorrectedValues(serialized);
                    audit.setReviewStatus(ReviewStatus.CORRECTED);
                    auditRepository.save(audit);
                    acceptedCount++;
                }
            }
        }

        return BulkCorrectionResponse.builder()
                .jobId(jobId)
                .submittedCount(request.getCorrections().size())
                .acceptedCount(acceptedCount)
                .rejectedCount(rejectedCount)
                .rejections(rejections)
                .build();
    }

    private String trySerialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception _) {
            return null;
        }
    }
}
