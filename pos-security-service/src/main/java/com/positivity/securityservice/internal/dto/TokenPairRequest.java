package com.positivity.securityservice.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;

/**
 * Request DTO for JWT token pair generation (access + refresh tokens).
 *
 * Used by POST /v1/auth/token-pair endpoint.
 * Implements BACKEND_CONTRACT_GUIDE.md requirements:
 * - camelCase field naming
 * - Returns both short-lived access token and long-lived refresh token
 *
 * @since 1.0
 * @see com.positivity.securityservice.internal.controller.JwtController#generateTokenPair
 */
@Schema(description = "Request to generate an access token and refresh token pair")
public record TokenPairRequest(
        @JsonProperty("subject")
        @Schema(
                description = "User identifier (subject claim)",
                example = "user123",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String subject,

        @JsonProperty("roles")
        @Schema(
                description = "Optional set of role names to include in token",
                example = "[\"SHOP_MGR\"]",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Set<String> roles) {
    /**
     * Validates request constraints.
     *
     * @throws IllegalArgumentException if subject is null or blank
     */
    public void validate() {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject is required and cannot be blank");
        }
    }
}
