package com.positivity.accounting.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for creating a new GL Mapping.
 * Maps external source system codes to GL accounts with temporal validity.
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

    @NotBlank(message = "Source system is required")
    private String sourceSystem;

    @NotBlank(message = "External code is required")
    private String externalCode;

    @NotNull(message = "GL account ID is required")
    private UUID glAccountId;

    @NotNull(message = "Effective start date is required")
    private LocalDateTime effectiveStartDate;

    private LocalDateTime effectiveEndDate;

    /**
     * Dimensional context for this mapping (businessUnitId, locationId, etc.).
     * Optional - null or empty map for defaults.
     */
    private Map<String, String> dimensions;
}
