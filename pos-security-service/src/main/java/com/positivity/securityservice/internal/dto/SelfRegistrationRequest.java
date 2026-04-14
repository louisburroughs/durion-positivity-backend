package com.positivity.securityservice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

@Builder
@Schema(description = "Anonymous self-registration request")
public record SelfRegistrationRequest(
        @NotBlank @Email @Schema(description = "Primary email address", example = "jane.smith@example.com")
        String email,

        @NotBlank @Schema(description = "Password for the new account", example = "Sup3rS3cret!")
        String password,

        @NotBlank @Schema(description = "Given name", example = "Jane")
        String firstName,

        @NotBlank @Schema(description = "Family name", example = "Smith")
        String lastName,

        @Nullable @Schema(description = "Optional primary phone number", example = "+1-555-123-4567")
        String phone,

        @Nullable @Schema(description = "Optional requested username. If omitted, the email local part is used.")
        String username,

        @Nullable @Schema(description = "Optional external identity subject for future federation support")
        String idpSubject,

        @Nullable
        @Schema(
                description =
                        "Optional idempotency key used to replay a completed registration attempt without creating duplicate side effects")
        String idempotencyKey) {}
