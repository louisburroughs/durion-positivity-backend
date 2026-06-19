package com.positivity.securityservice.internal.client.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Builder;

@Builder
@Schema(description = "Outbound request to link a user account to a person in the people service")
public record PeopleLinkUserRequest(
        @Schema(description = "User identifier to link", example = "01960003-0000-7000-8000-000000000001", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID userId,
        @Schema(description = "Person identifier to link the user to", example = "01960003-0000-7000-8000-000000000002", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID personId,
        @Schema(description = "Type of link to create", example = "SELF", requiredMode = Schema.RequiredMode.REQUIRED)
        String linkType,
        @Schema(description = "Optional notes recorded with the link", example = "Created during self-registration", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String notes) {}
