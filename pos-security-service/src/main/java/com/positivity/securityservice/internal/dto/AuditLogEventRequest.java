package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for creating immutable audit events.
 *
 * Issue: #41
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Request to record an immutable audit event")
public class AuditLogEventRequest {

    @NotBlank
    @Schema(description = "Event type code", example = "ROLE_ASSIGNED", requiredMode = REQUIRED)
    private String eventType;

    // Ignored — actorId is always resolved from SecurityContextHolder per ADR-0018. This field is accepted for
    // structural compatibility only.
    @Schema(
            description = "Ignored; actor is resolved server-side from the security context per ADR-0018",
            example = "jane.doe",
            requiredMode = NOT_REQUIRED)
    private String actorId;

    @Schema(
            description = "Identifier of the affected entity",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private String entityId;

    @Schema(description = "Type of the affected entity", example = "USER", requiredMode = NOT_REQUIRED)
    private String entityType;

    @Schema(description = "Value before the change", requiredMode = NOT_REQUIRED)
    private Object oldValue;

    @Schema(description = "Value after the change", requiredMode = NOT_REQUIRED)
    private Object newValue;

    @Schema(description = "Additional contextual metadata", requiredMode = NOT_REQUIRED)
    private Object context;
}
