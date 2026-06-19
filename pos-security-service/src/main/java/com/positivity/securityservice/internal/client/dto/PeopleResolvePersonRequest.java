package com.positivity.securityservice.internal.client.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "Outbound request to resolve or match a person in the people service")
public record PeopleResolvePersonRequest(
        @Schema(description = "Email address to match on", example = "jane.smith@example.com", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String email,
        @Schema(description = "Phone number to match on", example = "+1-555-123-4567", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String phone,
        @Schema(description = "Family name to match on", example = "Smith", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String lastName,
        @Schema(description = "Given name to match on", example = "Jane", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String firstName,
        @Schema(description = "Minimum match score threshold to apply", example = "80", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Integer threshold) {}
