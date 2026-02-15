package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request DTO for assigning a technician to a workorder.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to assign a technician to a workorder")
public class AssignTechnicianRequest {

    @NotNull(message = "technicianId is required")
    @Schema(description = "ID of the technician to assign", example = "550e8400-e29b-41d4-a716-446655440050")
    private UUID technicianId;

    @Schema(description = "ID of the user performing the assignment (defaults from X-User-Id header if not provided)", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID assignedByUserId;

    @Schema(description = "Assignment notes or reason", example = "Assigned to senior tech for brake system work")
    private String notes;
}
