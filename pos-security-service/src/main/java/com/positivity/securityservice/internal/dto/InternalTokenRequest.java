package com.positivity.securityservice.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;

/**
 * Request DTO for internal JWT token issuance.
 *
 * Used by POST /v1/auth/internal/token endpoint (trusted internal callers
 * only).
 * Renamed from LoginRequest to disambiguate from user-facing LoginRequest.
 *
 * @since 1.0
 * @see com.positivity.securityservice.internal.controller.JwtController#issueInternalToken
 */
@Schema(description = "Request to generate a single JWT token for an internal trusted caller")
public record InternalTokenRequest(
        @JsonProperty("subject")
        @Schema(
                description = "User identifier (subject claim)",
                example = "user123",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String subject,

        @JsonProperty("roles")
        @Schema(
                description = "Optional set of role names to include in token",
                example = "[\"SHOP_MGR\", \"ACCOUNTING_CLERK\"]",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Set<String> roles) {

    public void validate() {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject is required and cannot be blank");
        }
    }
}
