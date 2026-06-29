package com.positivity.inventory.internal.dto.putaway;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Putaway task describing a product to move from staging to a suggested storage location")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PutawayTaskResponse {

    @Schema(
            description = "Unique identifier of the putaway task",
            example = "01960003-0000-7000-8000-000000000090",
            requiredMode = REQUIRED)
    @NotNull
    private String taskId;

    @Schema(
            description = "Identifier of the receipt the goods were received against",
            example = "01960003-0000-7000-8000-000000000091",
            requiredMode = REQUIRED)
    @NotNull
    private String sourceReceiptId;

    @Schema(
            description = "Identifier of the product to be put away",
            example = "01960003-0000-7000-8000-000000000092",
            requiredMode = REQUIRED)
    @NotNull
    private String productId;

    @Schema(description = "Quantity of the product to be put away", example = "12", requiredMode = REQUIRED)
    @NotNull
    private Integer quantity;

    @Schema(
            description = "Identifier of the staging location the goods are moved from",
            example = "01960003-0000-7000-8000-000000000093",
            requiredMode = NOT_REQUIRED)
    private UUID sourceLocationId;

    @Schema(
            description = "Currently suggested destination location for the goods",
            example = "01960003-0000-7000-8000-000000000094",
            requiredMode = NOT_REQUIRED)
    private UUID suggestedDestinationLocationId;

    @Schema(
            description = "Originally suggested destination location before any fallback",
            example = "01960003-0000-7000-8000-000000000095",
            requiredMode = NOT_REQUIRED)
    private UUID originalSuggestedLocationId;

    @Schema(
            description = "Final suggested destination location after fallback resolution",
            example = "01960003-0000-7000-8000-000000000096",
            requiredMode = NOT_REQUIRED)
    private UUID finalSuggestedLocationId;

    @Schema(
            description = "Actual destination location where the goods were placed",
            example = "01960003-0000-7000-8000-000000000097",
            requiredMode = NOT_REQUIRED)
    private UUID actualDestinationLocationId;

    @Schema(
            description = "Reason the suggested location fell back to an alternate, when applicable",
            example = "Suggested bin at capacity",
            requiredMode = NOT_REQUIRED)
    private String fallbackReason;

    @Schema(description = "Current status of the putaway task", example = "PENDING", requiredMode = REQUIRED)
    @NotNull
    private String status;

    @Schema(
            description = "Identifier of the worker assigned to the task, when assigned",
            example = "01960003-0000-7000-8000-000000000098",
            requiredMode = NOT_REQUIRED)
    private String assigneeId;

    @Schema(
            description = "Timestamp when the putaway task was created",
            example = "2026-01-15T08:00:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant createdAt;

    @Schema(
            description = "Timestamp when the putaway task was last updated",
            example = "2026-01-15T08:45:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant updatedAt;
}
