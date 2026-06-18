package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.PostingRuleSetState;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for Posting Rule Version.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A single version of a posting rule set")
public class PostingRuleVersionResponse {

    @Schema(
            description = "Unique identifier of the rule version",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID versionId;

    @Schema(
            description = "Identifier of the owning posting rule set",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID postingRuleSetId;

    @Schema(description = "Sequential version number within the rule set", example = "3", requiredMode = NOT_REQUIRED)
    private Integer versionNumber;

    @Schema(description = "Lifecycle state of the version", example = "PUBLISHED", requiredMode = NOT_REQUIRED)
    private PostingRuleSetState state;

    @Schema(
            description = "Serialized rules definition (JSON) for this version",
            example = "{\"debit\":\"1200-100\",\"credit\":\"4000\"}",
            requiredMode = NOT_REQUIRED)
    private String rulesDefinition;

    @Schema(
            description = "Timestamp when the version was created (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant createdAt;

    @Schema(description = "Identifier of the user who created the version", example = "ap.manager", requiredMode = NOT_REQUIRED)
    private String createdBy;

    @Schema(
            description = "Timestamp when the version was last modified (ISO 8601)",
            example = "2026-06-18T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant modifiedAt;

    @Schema(description = "Identifier of the user who last modified the version", example = "ap.lead", requiredMode = NOT_REQUIRED)
    private String modifiedBy;
}
