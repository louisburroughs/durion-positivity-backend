package com.positivity.inventory.internal.dto;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Standard inventory API error payload returned by global exception handling.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryErrorResponse {

    @Schema(description = "Timestamp when the error occurred", example = "2026-02-24T17:25:43.511Z")
    private String timestamp;

    @Schema(description = "Stable error code", example = "VALIDATION_ERROR")
    private String code;

    @Schema(description = "Human-readable error message", example = "reasonCode must not be blank")
    private String message;

    @Schema(description = "Optional structured context for specific business errors")
    private Map<String, Object> context;
}
