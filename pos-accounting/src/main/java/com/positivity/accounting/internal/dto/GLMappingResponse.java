package com.positivity.accounting.internal.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for GL Mapping response.
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - GLMapping Response</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GLMappingResponse {

    private UUID glMappingId;
    private String sourceSystem;
    private String externalCode;
    private UUID glAccountId;
    private String accountCode;
    private String accountName;
    private LocalDateTime effectiveStartDate;
    private LocalDateTime effectiveEndDate;
    private Map<String, String> dimensions;
    private Integer priority;
    private Instant createdAt;
    private String createdBy;
}
