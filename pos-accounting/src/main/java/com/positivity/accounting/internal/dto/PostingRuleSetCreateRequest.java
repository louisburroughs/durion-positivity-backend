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
 * Request DTO for creating a new Posting Rule Set.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for creating a new posting rule set")
public class PostingRuleSetCreateRequest {

    @Schema(
            description = "Display name of the posting rule set",
            example = "Workorder Revenue Posting",
            requiredMode = REQUIRED)
    @NotBlank(message = "Name is required")
    @Size(max = 100, message = "Name must not exceed 100 characters")
    private String name;

    @Schema(
            description = "Accounting event type the rule set applies to",
            example = "WORKORDER_COMPLETED",
            requiredMode = REQUIRED)
    @NotBlank(message = "Event type is required")
    @Size(max = 100, message = "Event type must not exceed 100 characters")
    private String eventType;

    @Schema(
            description = "Optional human-readable description of the rule set",
            example = "Posts revenue and tax for completed workorders",
            requiredMode = NOT_REQUIRED)
    @Size(max = 500, message = "Description must not exceed 500 characters")
    private String description;

    @Schema(
            description = "Serialized rules definition (JSON) describing GL mapping logic",
            example = "{\"debit\":\"1200-100\",\"credit\":\"4000\"}",
            requiredMode = REQUIRED)
    @NotBlank(message = "Rules definition is required")
    private String rulesDefinition;

    @Schema(
            description = "Identifier of the user creating the rule set",
            example = "ap.manager",
            requiredMode = REQUIRED)
    @NotBlank(message = "Created by is required")
    @Size(max = 50, message = "Created by must not exceed 50 characters")
    private String createdBy;
}
