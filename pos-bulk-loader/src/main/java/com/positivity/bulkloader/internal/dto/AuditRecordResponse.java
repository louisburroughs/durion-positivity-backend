package com.positivity.bulkloader.internal.dto;

import com.positivity.bulkloader.internal.enums.ReviewStatus;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuditRecordResponse {

    private UUID id;
    private UUID jobId;
    private String entityType;
    private UUID entityId;
    private Long rowNumber;
    private ReviewStatus reviewStatus;
    private String reasonCodes;
    private String originalValues;
    private Instant createdAt;
}
