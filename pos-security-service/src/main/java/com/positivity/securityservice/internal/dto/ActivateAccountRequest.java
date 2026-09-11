package com.positivity.securityservice.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /v1/auth/activate}: exchanges a one-time activation token for the account's
 * first password (ADR-0062 §7, WS2b-3). Unauthenticated; the token is the credential.
 */
@Schema(description = "Exchange a one-time activation token for the account's first password")
public record ActivateAccountRequest(
        @JsonProperty("token")
        @NotBlank
        @Schema(
                description = "The activation token the operator handed over",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String token,

        @JsonProperty("newPassword")
        @NotBlank
        @Schema(
                description = "The password to set; hashed server-side before storage",
                example = "Sup3rS3cret!",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String newPassword) {}
