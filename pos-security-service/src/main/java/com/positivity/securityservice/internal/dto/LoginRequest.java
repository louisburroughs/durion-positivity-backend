package com.positivity.securityservice.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Request DTO for user-facing authentication at POST /v1/auth/login.
 *
 * <p>The tenant is resolved before the user is looked up (ADR-0062 §3): from the gateway's
 * {@code X-Tenant-Slug} header (derived from the {@code Host}), else from {@code tenantSlug} here,
 * else the transitional default tenant while one is configured. An unknown or inactive slug
 * answers the same 401 as bad credentials.
 *
 * @since 1.0
 * @see com.positivity.securityservice.internal.controller.AuthController#login
 */
@Schema(description = "Request to authenticate a user with username and password")
public record LoginRequest(
        @JsonProperty("username")
        @NotBlank
        @Schema(
                description = "The user's login username",
                example = "jane.doe",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String username,

        @JsonProperty("password")
        @NotBlank
        @Schema(description = "The user's login password", requiredMode = Schema.RequiredMode.REQUIRED)
        String password,

        @JsonProperty("tenantSlug")
        @Pattern(regexp = "^[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])$")
        @Schema(
                description = "Slug of the tenant to sign in to; optional when the request host already names it",
                example = "acme-tire",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable
        String tenantSlug) {

    /** Credentials only; the tenant comes from the host or the transitional default. */
    public LoginRequest(String username, String password) {
        this(username, password, null);
    }
}
