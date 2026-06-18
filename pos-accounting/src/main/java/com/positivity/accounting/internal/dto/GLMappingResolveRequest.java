package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to resolve an external code to a GL account.
 * Uses temporal matching to find the effective GL mapping at transaction time.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to resolve an external code to a GL account at a point in time")
public class GLMappingResolveRequest {

    @NotBlank(message = "Source system is required")
    @Schema(description = "Source system identifier", example = "INVENTORY", requiredMode = REQUIRED)
    private String sourceSystem;

    @NotBlank(message = "External code is required")
    @Schema(description = "External code from the source system", example = "SKU-1234", requiredMode = REQUIRED)
    private String externalCode;

    @NotNull(message = "Transaction date is required")
    @Schema(
            description = "Transaction date used for temporal mapping resolution",
            example = "2026-01-15T10:30:00",
            requiredMode = REQUIRED)
    private LocalDateTime transactionDate;
}
