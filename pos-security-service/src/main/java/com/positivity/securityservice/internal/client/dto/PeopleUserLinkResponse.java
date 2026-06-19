package com.positivity.securityservice.internal.client.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Result of linking a user account to a person in the people service")
public record PeopleUserLinkResponse(
        @Schema(description = "Link identifier", example = "01960003-0000-7000-8000-000000000090", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID linkId,
        @Schema(description = "Linked user identifier", example = "01960003-0000-7000-8000-000000000001", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID userId,
        @Schema(description = "Linked person identifier", example = "01960003-0000-7000-8000-000000000002", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID personId,
        @Schema(description = "Type of link created", example = "SELF", requiredMode = Schema.RequiredMode.REQUIRED)
        String linkType,
        @Schema(description = "Timestamp the link was created", example = "2026-01-15T09:30:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
        Instant createdAt,
        @Schema(description = "Actor that created the link", example = "system", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String createdBy,
        @Schema(description = "Notes recorded with the link", example = "Created during self-registration", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String notes) {}
