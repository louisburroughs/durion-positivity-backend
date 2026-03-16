package com.positivity.securityservice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Standardized error response DTO for JWT authentication endpoints.
 * 
 * Used in OpenAPI responses to document error payloads with correlation
 * tracking.
 */
@Schema(description = "Standardized error response with correlation ID")
public record ErrorResponse(
                @Schema(description = "Error code for client processing") String code,
                @Schema(description = "Human-readable error message") String message,
                @Schema(description = "HTTP status code") int status,
                @Schema(description = "ISO 8601 timestamp") String timestamp,
                @Schema(description = "Unique request correlation ID for tracking") String correlationId,
                @Schema(description = "Workflow or review-case reference identifier, when available") String referenceId,
                @Schema(description = "Recommended next step for the caller, when available") String nextAction,
                @Schema(description = "Support/admin investigation guidance, when available") String supportAction) {
}
