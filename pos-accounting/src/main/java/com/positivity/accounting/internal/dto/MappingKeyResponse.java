package com.positivity.accounting.internal.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for Mapping Key.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MappingKeyResponse {

    private UUID mappingKeyId;
    private UUID postingCategoryId;
    private String postingCategoryName;
    private String keyName;
    private String description;
    private Boolean isActive;
    private Instant createdAt;
    private String createdBy;
    private Instant modifiedAt;
    private String modifiedBy;
}
