package com.positivity.bulkloader.internal.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ColumnMappingResponse {

    private UUID id;
    private UUID jobId;
    private String sourceColumn;
    private String targetField;
    private Double confidence;
    private Boolean overriddenByUser;
    private Instant createdAt;
}
