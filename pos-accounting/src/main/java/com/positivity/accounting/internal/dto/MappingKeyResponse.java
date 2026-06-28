package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Mapping key details")
public class MappingKeyResponse {

    @Schema(
            description = "Unique identifier of the mapping key",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID mappingKeyId;

    @Schema(
            description = "Identifier of the posting category this mapping key belongs to",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    private UUID postingCategoryId;

    @Schema(
            description = "Name of the posting category this mapping key belongs to",
            example = "Customer Payments",
            requiredMode = NOT_REQUIRED)
    private String postingCategoryName;

    @Schema(description = "Name of the mapping key", example = "PAYMENT_RECEIVED", requiredMode = REQUIRED)
    private String keyName;

    @Schema(
            description = "Description of the mapping key",
            example = "Maps cleared customer payments to the receivable account",
            requiredMode = NOT_REQUIRED)
    private String description;

    @Schema(description = "Whether the mapping key is active", example = "true", requiredMode = NOT_REQUIRED)
    private Boolean isActive;

    @Schema(
            description = "Timestamp when the mapping key was created (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant createdAt;

    @Schema(
            description = "Identifier of the user who created the mapping key",
            example = "jdoe",
            requiredMode = NOT_REQUIRED)
    private String createdBy;

    @Schema(
            description = "Timestamp when the mapping key was last modified (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant modifiedAt;

    @Schema(
            description = "Identifier of the user who last modified the mapping key",
            example = "asmith",
            requiredMode = NOT_REQUIRED)
    private String modifiedBy;
}
