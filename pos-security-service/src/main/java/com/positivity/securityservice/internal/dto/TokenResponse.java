package com.positivity.securityservice.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response DTO for single JWT token generation.
 *
 * Returned by POST /v1/auth/internal/token endpoint.
 * Implements BACKEND_CONTRACT_GUIDE.md requirements:
 * - camelCase field naming
 * - Compact JWT string as response
 *
 * @since 1.0
 * @see com.positivity.securityservice.internal.controller.JwtController#issueInternalToken
 */
@Schema(description = "Response containing a JWT access token")
public record TokenResponse(
        @JsonProperty("token")
        @Schema(
                description = "JWT token (Bearer token for Authorization header)",
                example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
        String token) {
    /**
     * Factory method to create a token response.
     *
     * @param token the JWT token string
     * @return a new TokenResponse instance
     */
    public static TokenResponse of(String token) {
        return new TokenResponse(token);
    }
}
