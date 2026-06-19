package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for creating a new Mapping Key.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for creating a new mapping key")
public class MappingKeyCreateRequest {

    @Schema(
            description = "Identifier of the posting category this mapping key belongs to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull(message = "Posting category ID is required")
    private UUID postingCategoryId;

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

    @Schema(description = "Identifier of the user creating the mapping key", example = "jdoe", requiredMode = REQUIRED)
    @NotBlank(message = "Created by is required")
    @Size(max = 50, message = "Created by must not exceed 50 characters")
    private String createdBy;
}
