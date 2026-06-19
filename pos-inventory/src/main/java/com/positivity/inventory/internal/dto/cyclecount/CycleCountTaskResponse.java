package com.positivity.inventory.internal.dto.cyclecount;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.TaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for cycle count task query operations.
 */
@Schema(description = "A cycle count task describing an item to be counted at a location and its current state")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CycleCountTaskResponse {

    @Schema(
            description = "Unique identifier of the cycle count task",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID taskId;

    @Schema(
            description = "Bin or location code where the item is stored",
            example = "LOC-001",
            requiredMode = REQUIRED)
    @NotNull
    private String binLocation;

    @Schema(
            description = "Stock keeping unit of the item to count",
            example = "SKU-10042",
            requiredMode = REQUIRED)
    @NotNull
    private String itemSku;

    @Schema(
            description = "Human-readable description of the item",
            example = "Stainless steel water bottle, 750ml",
            requiredMode = NOT_REQUIRED)
    private String itemDescription;

    @Schema(
            description = "Quantity expected on hand for the item",
            example = "15",
            requiredMode = REQUIRED)
    @NotNull
    private Integer expectedQuantity;

    @Schema(
            description = "Identifier of the auditor assigned to the task, if assigned",
            example = "auditor-10042",
            requiredMode = NOT_REQUIRED)
    private String auditorId;

    @Schema(
            description = "Current status of the cycle count task",
            example = "ASSIGNED",
            requiredMode = REQUIRED)
    @NotNull
    private TaskStatus status;

    @Schema(
            description = "Identifier of the most recent count entry recorded for the task, if any",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID latestCountEntryId;

    @Schema(
            description = "Total number of count entries recorded for the task",
            example = "2",
            requiredMode = REQUIRED)
    @NotNull
    private Integer countEntriesCount;

    @Schema(
            description = "Timestamp when the task was created",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private LocalDateTime createdAt;

    @Schema(
            description = "Timestamp when the task was last updated",
            example = "2026-01-15T10:05:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private LocalDateTime updatedAt;
}
