package com.positivity.securityservice.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;

/**
 * Request DTO for JWT token generation (login).
 * 
 * Used by POST /v1/auth/login endpoint.
 * Implements BACKEND_CONTRACT_GUIDE.md requirements:
 * - camelCase field naming
 * - Validation: subject required, roles optional
 * 
 * @since 1.0
 * @see com.positivity.securityservice.internal.controller.JwtController#generateToken
 */
@Schema(description = "Request to generate a single JWT token for user authentication")
public record LoginRequest(
        @JsonProperty("subject") @Schema(description = "User identifier (subject claim)", example = "user123", requiredMode = Schema.RequiredMode.REQUIRED) String subject,

        @JsonProperty("roles") @Schema(description = "Optional set of role names to include in token", example = "[\"SHOP_MGR\", \"ACCOUNTING_CLERK\"]") Set<String> roles) {
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
