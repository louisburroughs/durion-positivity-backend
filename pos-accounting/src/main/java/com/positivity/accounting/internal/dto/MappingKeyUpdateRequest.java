package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for updating an existing Mapping Key.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for updating an existing mapping key")
public class MappingKeyUpdateRequest {

    @Schema(description = "Name of the mapping key", example = "PAYMENT_RECEIVED", requiredMode = REQUIRED)
    @NotBlank(message = "Key name is required")
    @Size(max = 100, message = "Key name must not exceed 100 characters")
    private String keyName;

    @Schema(
            description = "Optional description of the mapping key",
            example = "Maps cleared customer payments to the receivable account",
            requiredMode = NOT_REQUIRED)
    @Size(max = 500, message = "Description must not exceed 500 characters")
    private String description;

    @Schema(
            description = "Identifier of the user modifying the mapping key",
            example = "asmith",
            requiredMode = REQUIRED)
    @NotBlank(message = "Modified by is required")
    @Size(max = 50, message = "Modified by must not exceed 50 characters")
    private String modifiedBy;
}
