package com.positivity.bulkloader.internal.dto;

import com.positivity.bulkloader.internal.enums.DomainType;
import com.positivity.bulkloader.internal.enums.JobStatus;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BulkLoadJobResponse {

    private UUID id;
    private String operatorId;
    private UUID locationId;
    private String fileName;
    private DomainType domainType;
    private JobStatus status;
    private Long totalRows;
    private Long processedRows;
    private Long successCount;
    private Long failureCount;
    private Instant startedAt;
    private Instant completedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
