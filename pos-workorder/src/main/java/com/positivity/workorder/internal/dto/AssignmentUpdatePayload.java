package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Assignment context payload applied to a workorder")
public class AssignmentUpdatePayload {
    @NotNull(message = "locationId is required")
    @Schema(
            description = "Assigned location identifier",
            example = "550e8400-e29b-41d4-a716-446655440300",
            requiredMode = REQUIRED)
    private UUID locationId;

    @NotNull(message = "resourceId is required")
    @Schema(
            description = "Assigned primary resource identifier",
            example = "550e8400-e29b-41d4-a716-446655440301",
            requiredMode = REQUIRED)
    private UUID resourceId;

    @Schema(
            description = "Assigned mechanic identifiers",
            example = "[\"550e8400-e29b-41d4-a716-446655440120\"]",
            requiredMode = NOT_REQUIRED)
    private List<UUID> mechanicIds;
}
