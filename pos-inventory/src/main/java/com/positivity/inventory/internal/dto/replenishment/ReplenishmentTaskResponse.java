package com.positivity.inventory.internal.dto.replenishment;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A replenishment task describing a recommended or in-progress stock movement between locations")
public class ReplenishmentTaskResponse {

    @Schema(
            description = "Unique identifier of the replenishment task",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private String taskId;

    @Schema(description = "SKU of the item to be replenished", example = "SKU-10042", requiredMode = REQUIRED)
    @NotNull
    private String itemSKU;

    @Schema(
            description = "Quantity of the item to be moved by this replenishment task",
            example = "12",
            requiredMode = REQUIRED)
    private int quantity;

    @Schema(
            description = "Identifier of the location stock is sourced from",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID sourceLocationId;

    @Schema(
            description = "Identifier of the location stock is moved to",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = REQUIRED)
    @NotNull
    private UUID destinationLocationId;

    @Schema(description = "Current status of the replenishment task", example = "PENDING", requiredMode = REQUIRED)
    @NotNull
    private String status;

    @Schema(
            description = "Mechanism that triggered the task, such as a manual request or automatic threshold breach",
            example = "AUTOMATIC",
            requiredMode = NOT_REQUIRED)
    private String triggerType;

    @Schema(
            description = "Explanation of why this replenishment was decided",
            example = "On-hand quantity 3 fell below minimum threshold of 5",
            requiredMode = NOT_REQUIRED)
    private String decisionReason;

    @Schema(
            description = "Explanation of why the chosen source location was selected to supply the stock",
            example = "Nearest location with sufficient available stock",
            requiredMode = NOT_REQUIRED)
    private String sourcingReason;

    @Schema(
            description = "Identifier or name of the user the replenishment task is assigned to",
            example = "user-jdoe",
            requiredMode = NOT_REQUIRED)
    private String assignedTo;

    @Schema(
            description = "Timestamp at which the replenishment task was created",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private String createdAt;
}
