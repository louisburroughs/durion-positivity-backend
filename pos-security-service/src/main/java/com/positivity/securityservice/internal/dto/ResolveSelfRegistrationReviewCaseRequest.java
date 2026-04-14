package com.positivity.securityservice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Administrative request to resolve a blocked self-registration case")
public record ResolveSelfRegistrationReviewCaseRequest(
        @NotBlank
        @Schema(
                description = "Resolution note recorded with the case",
                example = "Recovered existing disabled account and asked user to sign in.")
        String resolutionNotes) {}
