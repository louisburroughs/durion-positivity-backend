package com.positivity.inventory.internal.dto.picklist;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.PickTaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Pick task details describing a single SKU-and-location pick within a pick list")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PickTaskResponse {

    @Schema(
            description = "Unique identifier of the pick task",
            example = "01960003-0000-7000-8000-000000000040",
            requiredMode = REQUIRED)
    @NotNull
    private UUID pickTaskId;

    @Schema(
            description = "Identifier of the pick list this task belongs to",
            example = "01960003-0000-7000-8000-000000000041",
            requiredMode = REQUIRED)
    @NotNull
    private UUID pickListId;

    @Schema(
            description = "Identifier of the SKU to be picked",
            example = "01960003-0000-7000-8000-000000000042",
            requiredMode = REQUIRED)
    @NotNull
    private UUID skuId;

    @Schema(
            description = "Identifier of the location the SKU is picked from",
            example = "01960003-0000-7000-8000-000000000043",
            requiredMode = REQUIRED)
    @NotNull
    private UUID locationId;

    @Schema(description = "Quantity required to satisfy this pick task", example = "12", requiredMode = REQUIRED)
    private int quantityRequired;

    @Schema(description = "Quantity already picked against this task", example = "8", requiredMode = REQUIRED)
    private int quantityPicked;

    @Schema(description = "Current status of the pick task", example = "PENDING", requiredMode = REQUIRED)
    @NotNull
    private PickTaskStatus status;

    @Schema(description = "Ordinal position of this task in the pick sequence", example = "1", requiredMode = REQUIRED)
    private int sortOrder;

    @Schema(
            description = "Advisory lot suggestion for LOT-tracked SKUs (FIFO by lot receivedAt at the suggested"
                    + " location; FEFO once expiry data exists). Null for untracked SKUs or when no lot has stock",
            example = "LOT-2026-A",
            requiredMode = NOT_REQUIRED)
    private String suggestedLotNumber;

    @Schema(
            description = "Identifier of the lot actually keyed at pick confirmation for LOT-tracked SKUs;"
                    + " null for untracked SKUs and before confirmation",
            example = "01960003-0000-7000-8000-000000000044",
            requiredMode = NOT_REQUIRED)
    private UUID pickedLotId;

    /**
     * Pre-E2 arity kept for existing callers/tests: lot fields default to null (odoo-parity
     * E2, issue #1042).
     */
    public PickTaskResponse(
            UUID pickTaskId,
            UUID pickListId,
            UUID skuId,
            UUID locationId,
            int quantityRequired,
            int quantityPicked,
            PickTaskStatus status,
            int sortOrder) {
        this(
                pickTaskId,
                pickListId,
                skuId,
                locationId,
                quantityRequired,
                quantityPicked,
                status,
                sortOrder,
                null,
                null);
    }
}
