package com.positivity.securityservice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

@Builder
@Schema(description = "Anonymous self-registration request")
public record SelfRegistrationRequest(
        @NotBlank
        @Email
        @Schema(
                description = "Primary email address",
                example = "jane.smith@example.com",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String email,

        @NotBlank
        @Schema(
                description = "Password for the new account",
                example = "Sup3rS3cret!",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String password,

        @NotBlank @Schema(description = "Given name", example = "Jane", requiredMode = Schema.RequiredMode.REQUIRED)
        String firstName,

        @NotBlank @Schema(description = "Family name", example = "Smith", requiredMode = Schema.RequiredMode.REQUIRED)
        String lastName,

        @Nullable
        @Schema(
                description = "Optional primary phone number",
                example = "+1-555-123-4567",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String phone,

        @Nullable
        @Schema(
                description = "Optional requested username. If omitted, the email local part is used.",
                example = "jane.smith",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String username,

        @Nullable
        @Schema(
                description = "Optional external identity subject for future federation support",
                example = "auth0|abc123",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String idpSubject,

        @Nullable
        @Schema(
                description =
                        "Optional idempotency key used to replay a completed registration attempt without creating duplicate side effects",
                example = "5f1d8c2e-1b2a-4c3d-9e8f-0a1b2c3d4e5f",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String idempotencyKey) {}
