package com.positivity.inventory.internal.dto.picklist;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.PickListStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Pick list details returned after creation, generation, or lookup")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PickListResponse {

    @Schema(
            description = "Unique identifier of the pick list",
            example = "01960003-0000-7000-8000-000000000030",
            requiredMode = REQUIRED)
    @NotNull
    private UUID pickListId;

    @Schema(
            description = "Identifier of the workorder the pick list fulfills",
            example = "01960003-0000-7000-8000-000000000031",
            requiredMode = REQUIRED)
    @NotNull
    private UUID workorderId;

    @Schema(
            description = "Current lifecycle status of the pick list",
            example = "READY_TO_PICK",
            requiredMode = REQUIRED)
    @NotNull
    private PickListStatus status;

    @Schema(
            description = "Relative priority of the pick list; higher values are picked sooner",
            example = "5",
            requiredMode = REQUIRED)
    private int priority;

    @Schema(
            description = "Timestamp by which the pick list should be completed",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant dueAt;

    @Schema(
            description = "Timestamp when the pick list was created",
            example = "2026-01-15T08:00:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant createdAt;

    @Schema(
            description = "Timestamp when the pick list was last updated",
            example = "2026-01-15T08:45:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant updatedAt;
}
