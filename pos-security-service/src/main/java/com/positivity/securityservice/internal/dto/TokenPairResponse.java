package com.positivity.securityservice.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response DTO for JWT token pair generation.
 *
 * Returned by POST /v1/auth/token-pair and POST /v1/auth/refresh endpoints.
 * Implements BACKEND_CONTRACT_GUIDE.md requirements:
 * - camelCase field naming
 * - Returns both access token (short-lived) and refresh token (long-lived)
 *
 * **Token Expiration:**
 * - accessToken: 1 hour (3600 seconds)
 * - refreshToken: 7 days (604800 seconds)
 *
 * @since 1.0
 * @see com.positivity.securityservice.internal.controller.JwtController#generateTokenPair
 * @see com.positivity.securityservice.internal.controller.JwtController#refreshAccessToken
 */
@Schema(description = "Response containing both access and refresh JWT tokens")
public record TokenPairResponse(
        @JsonProperty("accessToken")
        @Schema(
                description = "Short-lived JWT access token (1 hour expiration)",
                example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String accessToken,

        @JsonProperty("refreshToken")
        @Schema(
                description = "Long-lived JWT refresh token (7 days expiration)",
                example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String refreshToken) {
    /**
     * Factory method to create a token pair response.
     *
     * @param accessToken  the short-lived access token
     * @param refreshToken the long-lived refresh token
     * @return a new TokenPairResponse instance
     */
    public static TokenPairResponse of(String accessToken, String refreshToken) {
        return new TokenPairResponse(accessToken, refreshToken);
    }
}
