package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

/**
 * Dispatch-board status of one mobile service unit (#1656).
 *
 * <p>Deliberately mirrors {@link BayStatus} field-for-field: a mobile unit and a bay are different
 * aggregates in pos-location, but on the dispatch board they answer the same question — is this
 * resource holding work right now, and which workorder. Keeping the two shapes identical is what
 * lets the board render both panels the same way.
 */
@Value
@Builder
@Schema(description = "Current dispatch status of a mobile service unit")
public class MobileUnitStatus {
    @Schema(
            description = "Identifier of the mobile unit",
            example = "550e8400-e29b-41d4-a716-446655440401",
            requiredMode = REQUIRED)
    String unitId;

    @Schema(
            description = "Human-readable mobile unit name, resolved from the location replica. Null when the "
                    + "unit's own replica row has not arrived yet.",
            example = "Van 3",
            requiredMode = NOT_REQUIRED)
    String unitName;

    @Schema(description = "Operational status of the unit", example = "OCCUPIED", requiredMode = REQUIRED)
    String status;

    @Schema(
            description = "Identifier of the workorder currently assigned to the unit, or null when idle",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = NOT_REQUIRED)
    String assignedWorkorderId;

    @Schema(description = "Whether the unit is currently available", example = "false", requiredMode = REQUIRED)
    boolean available;
}
