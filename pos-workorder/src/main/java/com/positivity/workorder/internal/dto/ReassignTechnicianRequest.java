package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request DTO for reassigning a workorder to a different technician.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to reassign a workorder to a different technician")
public class ReassignTechnicianRequest {

    @NotNull(message = "newTechnicianId is required")
    @Schema(description = "ID of the new technician", example = "550e8400-e29b-41d4-a716-446655440051")
    private UUID newTechnicianId;

    @Schema(description = "ID of the user performing the reassignment (defaults from X-User-Id header if not provided)", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID reassignedByUserId;

    @Schema(description = "Reason for reassignment", example = "Original technician unavailable, reassigning to available tech")
    private String reason;

    @Schema(description = "Additional notes for the reassignment")
    private String notes;

    @Schema(description = "Whether to send notification to previous technician", defaultValue = "false")
    @Builder.Default
    private Boolean notifyPreviousTechnician = false;
}
