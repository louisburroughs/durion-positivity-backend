package com.positivity.shopmanager.service.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.shopmanager.service.enums.AssignmentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/** Representation of a mechanic assignment returned from the API. */
@Value
@Builder
@Schema(description = "Representation of a mechanic assignment returned from the API")
public class AssignmentResponse {
    @Schema(
            description = "Unique assignment identifier",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    UUID assignmentId;

    @Schema(
            description = "Appointment identifier the assignment belongs to",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    UUID appointmentId;

    @Schema(description = "Mechanics assigned to the appointment", requiredMode = REQUIRED)
    List<AssignedMechanicInfo> mechanics;

    @Schema(
            description = "Associated resource (bay or mobile unit) identifier",
            example = "01960003-0000-7000-8000-000000000005",
            requiredMode = NOT_REQUIRED)
    UUID resourceId;

    @Schema(description = "Type of the associated resource", example = "BAY", requiredMode = NOT_REQUIRED)
    String resourceType;

    @Schema(description = "Current assignment status", example = "CONFIRMED", requiredMode = REQUIRED)
    AssignmentStatus status;

    @Schema(
            description = "Whether the assignment was created with a conflict override",
            example = "false",
            requiredMode = REQUIRED)
    boolean override;

    /** Optional notes for this assignment (max 500 chars). */
    @Schema(
            description = "Optional notes for this assignment (max 500 chars)",
            example = "Customer requested senior mechanic",
            requiredMode = NOT_REQUIRED)
    String assignmentNotes;

    /** When the assignment was first created/confirmed. */
    @Schema(
            description = "Instant the assignment was first created/confirmed in UTC (ISO-8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = REQUIRED)
    Instant assignedAt;

    /** When the assignment was last modified. */
    @Schema(
            description = "Instant the assignment was last modified in UTC (ISO-8601)",
            example = "2026-06-18T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    Instant lastUpdatedAt;
}
