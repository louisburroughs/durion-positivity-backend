package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
@Schema(description = "GL mapping response payload")
public class GLMappingResponse {

    @Schema(
            description = "GL mapping UUID",
            example = "01960003-0000-7000-8000-000000000005",
            requiredMode = REQUIRED)
    @NotNull
    private UUID glMappingId;

    @Schema(description = "Source system identifier", example = "INVENTORY", requiredMode = NOT_REQUIRED)
    private String sourceSystem;

    @Schema(description = "External code from the source system", example = "SKU-1234", requiredMode = NOT_REQUIRED)
    private String externalCode;

    @Schema(
            description = "GL account UUID this code maps to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID glAccountId;

    @Schema(description = "GL account code", example = "1200-100", requiredMode = NOT_REQUIRED)
    private String accountCode;

    @Schema(description = "GL account name", example = "Accounts Receivable", requiredMode = NOT_REQUIRED)
    private String accountName;

    @Schema(
            description = "Inclusive start of the mapping's temporal validity",
            example = "2026-01-15T10:30:00",
            requiredMode = NOT_REQUIRED)
    private LocalDateTime effectiveStartDate;

    @Schema(
            description = "Exclusive end of the mapping's temporal validity (open-ended when null)",
            example = "2026-06-18T08:00:00",
            requiredMode = NOT_REQUIRED)
    private LocalDateTime effectiveEndDate;

    @Schema(
            description = "Dimensional context for this mapping",
            example = "{\"businessUnitId\":\"BU-001\"}",
            requiredMode = NOT_REQUIRED)
    private Map<String, String> dimensions;

    @Schema(description = "Mapping resolution priority", example = "1", requiredMode = NOT_REQUIRED)
    private Integer priority;

    @Schema(description = "Created timestamp", example = "2026-06-18T08:00:00Z", requiredMode = NOT_REQUIRED)
    private Instant createdAt;

    @Schema(description = "Created by username", example = "accounting.user", requiredMode = NOT_REQUIRED)
    private String createdBy;
}
