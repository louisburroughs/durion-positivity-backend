package com.positivity.inventory.internal.dto.picklist;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Request to create a pick list for a workorder, optionally linked to a parts reservation")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreatePickListRequest {

    @Schema(
            description = "Identifier of the workorder the pick list fulfills",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = REQUIRED)
    @NotNull
    private UUID workorderId;

    @Schema(
            description = "Timestamp by which the pick list should be completed",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant dueAt;

    @Schema(
            description = "Relative priority of the pick list; higher values are picked sooner",
            example = "5",
            requiredMode = NOT_REQUIRED)
    @PositiveOrZero
    private int priority = 0;

    @Schema(
            description = "Identifier of the reservation backing the pick list, when applicable",
            example = "01960003-0000-7000-8000-000000000011",
            requiredMode = NOT_REQUIRED)
    private UUID reservationId;
}
