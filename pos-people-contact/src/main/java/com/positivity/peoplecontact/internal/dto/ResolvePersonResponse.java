package com.positivity.peoplecontact.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response payload for person resolve (match or create).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Resolve person result")
public class ResolvePersonResponse {

    @Schema(
            description = "Canonical person ID",
            example = "01960011-0000-7000-8000-000000000001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID personId;

    @Schema(
            description = "True when an existing person matched threshold",
            example = "true",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean matchedExisting;

    @Schema(
            description = "Score of selected match (0 when created)",
            example = "85",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private int score;

    @Schema(description = "Threshold used for decision", example = "30", requiredMode = Schema.RequiredMode.REQUIRED)
    private int thresholdApplied;

    @Schema(
            description = "Which fields contributed to the winning match",
            example = "[\"email\",\"lastName\"]",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private List<String> matchedBy;

    @Schema(description = "Resolved first name", example = "Jane", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String firstName;

    @Schema(description = "Resolved last name", example = "Smith", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String lastName;

    @Schema(
            description = "Resolved primary email",
            example = "jane.smith@example.com",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String primaryEmail;

    @Schema(
            description = "Resolved phone numbers",
            example = "[\"+1-555-123-4567\"]",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private List<String> phoneNumbers;
}
