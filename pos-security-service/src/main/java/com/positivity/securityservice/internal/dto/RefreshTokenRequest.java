package com.positivity.securityservice.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request DTO for JWT token refresh.
 *
 * Used by POST /v1/auth/refresh endpoint.
 * Implements BACKEND_CONTRACT_GUIDE.md requirements:
 * - camelCase field naming
 * - Validation: refreshToken required
 *
 * @since 1.0
 * @see com.positivity.securityservice.internal.controller.JwtController#refreshAccessToken
 */
@Schema(description = "Request to refresh an access token using a refresh token")
public record RefreshTokenRequest(
        @JsonProperty("refreshToken")
        @Schema(description = "Long-lived refresh token", requiredMode = Schema.RequiredMode.REQUIRED)
        String refreshToken) {
    /**
     * Validates request constraints.
     *
     * @throws IllegalArgumentException if refreshToken is null or blank
     */
    public void validate() {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalArgumentException("refreshToken is required and cannot be blank");
        }
    }
}
