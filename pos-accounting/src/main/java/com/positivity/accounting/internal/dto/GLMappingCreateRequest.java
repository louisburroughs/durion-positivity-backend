package com.positivity.accounting.internal.dto;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for creating a new GL Mapping.
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - GLMapping Request</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GLMappingCreateRequest {

    private UUID postingCategoryId;
    private UUID mappingKeyId;
    private UUID glAccountId;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private Map<String, String> dimensions;
}
