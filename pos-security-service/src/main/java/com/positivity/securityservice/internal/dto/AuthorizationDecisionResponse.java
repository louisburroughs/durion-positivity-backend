package com.positivity.securityservice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Authorization decision response payload.
 *
 * Issue: #42
 */
@Data
@AllArgsConstructor
@Schema(description = "Result of an authorization decision check")
public class AuthorizationDecisionResponse {
    @Schema(
            description = "Authorization decision (PERMIT|DENY)",
            example = "PERMIT",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String decision;
}
