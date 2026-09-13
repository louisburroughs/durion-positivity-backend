package com.positivity.securityservice.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /v1/auth/activate-starter}: trades the shared starter password a
 * bulk-provisioned account was created with for a password of its own. Unauthenticated; the
 * starter password is the credential, and it buys nothing but this exchange.
 */
@Schema(description = "Exchange a bulk-provisioned account's starter password for its own password")
public record ActivateWithStarterRequest(
        @JsonProperty("username")
        @NotBlank
        @Schema(
                description = "The account to claim",
                example = "marcus.webb",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String username,

        @JsonProperty("starterPassword")
        @NotBlank
        @Schema(
                description = "The shared starter password the operator handed out",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String starterPassword,

        @JsonProperty("newPassword")
        @NotBlank
        @Schema(
                description = "The password to set; hashed server-side before storage",
                example = "Sup3rS3cret!",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String newPassword,

        @JsonProperty("tenantSlug")
        @Pattern(regexp = "^[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])$")
        @Schema(
                description =
                        "Slug of the tenant to claim the account in; optional when the request host already names it",
                example = "alpha",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable
        String tenantSlug) {}
