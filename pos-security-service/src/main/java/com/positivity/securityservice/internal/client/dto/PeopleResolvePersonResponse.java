package com.positivity.securityservice.internal.client.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "Result of resolving or matching a person in the people service")
public record PeopleResolvePersonResponse(
        @Schema(description = "Resolved person identifier", example = "01960003-0000-7000-8000-000000000002", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID personId,
        @Schema(description = "True when an existing person was matched rather than created", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean matchedExisting,
        @Schema(description = "Match score produced for the best candidate", example = "92", requiredMode = Schema.RequiredMode.REQUIRED)
        int score,
        @Schema(description = "Threshold that was applied during matching", example = "80", requiredMode = Schema.RequiredMode.REQUIRED)
        int thresholdApplied,
        @Schema(description = "Fields the match was made on", example = "[\"email\", \"lastName\"]", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> matchedBy,
        @Schema(description = "Given name of the resolved person", example = "Jane", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String firstName,
        @Schema(description = "Family name of the resolved person", example = "Smith", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String lastName,
        @Schema(description = "Primary email of the resolved person", example = "jane.smith@example.com", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String primaryEmail,
        @Schema(description = "Known phone numbers for the resolved person", example = "[\"+1-555-123-4567\"]", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> phoneNumbers) {}
