package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Request payload for creating a GL mapping")
public class GLMappingCreateRequest {

    @NotBlank(message = "Source system is required")
    @Schema(description = "Source system identifier", example = "INVENTORY", requiredMode = REQUIRED)
    private String sourceSystem;

    @NotBlank(message = "External code is required")
    @Schema(description = "External code from the source system", example = "SKU-1234", requiredMode = REQUIRED)
    private String externalCode;

    @NotNull(message = "GL account ID is required")
    @Schema(
            description = "GL account UUID this code maps to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID glAccountId;

    @NotNull(message = "Effective start date is required")
    @Schema(
            description = "Inclusive start of the mapping's temporal validity",
            example = "2026-01-15T10:30:00",
            requiredMode = REQUIRED)
    private LocalDateTime effectiveStartDate;

    @Schema(
            description = "Exclusive end of the mapping's temporal validity (open-ended when null)",
            example = "2026-06-18T08:00:00",
            requiredMode = NOT_REQUIRED)
    private LocalDateTime effectiveEndDate;

    /**
     * Dimensional context for this mapping (businessUnitId, locationId, etc.).
     * Optional - null or empty map for defaults.
     */
    @Schema(
            description = "Dimensional context for this mapping (businessUnitId, locationId, etc.)",
            example = "{\"businessUnitId\":\"BU-001\"}",
            requiredMode = NOT_REQUIRED)
    private Map<String, String> dimensions;
}
