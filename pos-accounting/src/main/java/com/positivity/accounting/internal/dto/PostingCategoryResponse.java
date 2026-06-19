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
 * Response DTO for Posting Category.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Posting category details")
public class PostingCategoryResponse {

    @Schema(
            description = "Unique identifier of the posting category",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID postingCategoryId;

    @Schema(description = "Name of the posting category", example = "Customer Payments", requiredMode = REQUIRED)
    private String categoryName;

    @Schema(
            description = "Description of the posting category",
            example = "Categories for cleared customer payments",
            requiredMode = NOT_REQUIRED)
    private String description;

    @Schema(description = "Whether the posting category is active", example = "true", requiredMode = NOT_REQUIRED)
    private Boolean isActive;

    @Schema(
            description = "Timestamp when the posting category was created (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant createdAt;

    @Schema(
            description = "Identifier of the user who created the posting category",
            example = "jdoe",
            requiredMode = NOT_REQUIRED)
    private String createdBy;

    @Schema(
            description = "Timestamp when the posting category was last modified (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant modifiedAt;

    @Schema(
            description = "Identifier of the user who last modified the posting category",
            example = "asmith",
            requiredMode = NOT_REQUIRED)
    private String modifiedBy;
}
