package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.workorder.internal.enums.ResourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Where a workorder is and who is working on it (#1983).
 *
 * <p>Position and technician are independent assignments — changing one never changes the other —
 * but they are read together, because "what is the state of this job" is one question. This is the
 * successor the story asks for to {@code GET .../operationalContext}, which answers the position
 * half only and has no history.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A workorder's current service position and technician, with position history")
public class ServicePositionResponse {

    @Schema(description = "The workorder", example = "550e8400-e29b-41d4-a716-446655440001", requiredMode = REQUIRED)
    private UUID workorderId;

    @Schema(
            description = "Site the workorder belongs to",
            example = "550e8400-e29b-41d4-a716-446655440300",
            requiredMode = NOT_REQUIRED)
    private UUID locationId;

    @Schema(
            description = "Kind of position held now; null when the workorder holds none",
            example = "BAY",
            requiredMode = NOT_REQUIRED)
    private ResourceType resourceType;

    @Schema(
            description = "Position held now; null when the workorder holds none. Null exactly when "
                    + "resourceType is null",
            example = "550e8400-e29b-41d4-a716-446655440301",
            requiredMode = NOT_REQUIRED)
    private UUID resourceId;

    @Schema(
            description = "Technician holding the workorder now; null when none is assigned",
            example = "550e8400-e29b-41d4-a716-446655440120",
            requiredMode = NOT_REQUIRED)
    private UUID technicianId;

    @Schema(
            description = "Workorder status, which decides whether the position may still be changed",
            example = "ASSIGNED",
            requiredMode = NOT_REQUIRED)
    private String workorderStatus;

    @Schema(
            description = "Every placement this workorder has had, newest first; the first entry is current "
                    + "when the workorder holds a position",
            requiredMode = NOT_REQUIRED)
    private List<ServicePositionAssignmentRecord> history;
}
