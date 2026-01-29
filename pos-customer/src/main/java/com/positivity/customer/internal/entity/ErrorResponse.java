package com.positivity.customer.internal.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Standard error response DTO for CRM domain endpoints.
 * Aligns with DECISION-INVENTORY-XXX error handling standards.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    /**
     * Stable error code (e.g., DUPLICATE_PARTY, INVALID_ROLE, PERMISSION_DENIED)
     */
    private String errorCode;

    /**
     * Human-readable error message
     */
    private String message;

    /**
     * Timestamp of error occurrence
     */
    private Long timestamp;

    /**
     * Correlation id for tracing (populated by gateway if missing)
     */
    private String correlationId;

    /**
     * Field-level validation errors (field name -> error message)
     */
    private Map<String, String> fieldErrors;

    /**
     * Additional error context (e.g., duplicate candidates, current version for
     * optimistic locking)
     */
    private Map<String, Object> context;
}
