package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for Posting Rule Set.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Posting rule set with its versions and audit metadata")
public class PostingRuleSetResponse {

    @Schema(
            description = "Unique identifier of the posting rule set",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID postingRuleSetId;

    @Schema(
            description = "Display name of the posting rule set",
            example = "Workorder Revenue Posting",
            requiredMode = NOT_REQUIRED)
    private String name;

    @Schema(
            description = "Accounting event type the rule set applies to",
            example = "WORKORDER_COMPLETED",
            requiredMode = NOT_REQUIRED)
    private String eventType;

    @Schema(
            description = "Human-readable description of the rule set",
            example = "Posts revenue and tax for completed workorders",
            requiredMode = NOT_REQUIRED)
    private String description;

    @Schema(description = "Versions belonging to this posting rule set", requiredMode = NOT_REQUIRED)
    private List<PostingRuleVersionResponse> versions;

    @Schema(
            description = "Timestamp when the rule set was created (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant createdAt;

    @Schema(
            description = "Identifier of the user who created the rule set",
            example = "ap.manager",
            requiredMode = NOT_REQUIRED)
    private String createdBy;

    @Schema(
            description = "Timestamp when the rule set was last modified (ISO 8601)",
            example = "2026-06-18T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant modifiedAt;

    @Schema(
            description = "Identifier of the user who last modified the rule set",
            example = "ap.lead",
            requiredMode = NOT_REQUIRED)
    private String modifiedBy;
}
