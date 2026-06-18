package com.positivity.inventory.internal.dto.putaway;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Result of executing a putaway move, including the ledger entry and final placement")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PutawayExecutionResponse {

    @Schema(
            description = "Identifier of the inventory ledger entry recorded for the move",
            example = "01960003-0000-7000-8000-000000000070",
            requiredMode = REQUIRED)
    @NotNull
    private String ledgerEntryId;

    @Schema(
            description = "Identifier of the putaway task that was executed",
            example = "01960003-0000-7000-8000-000000000071",
            requiredMode = REQUIRED)
    @NotNull
    private String taskId;

    @Schema(
            description = "Identifier of the SKU that was moved",
            example = "01960003-0000-7000-8000-000000000072",
            requiredMode = REQUIRED)
    @NotNull
    private String skuId;

    @Schema(
            description = "Identifier of the staging location the goods moved from",
            example = "01960003-0000-7000-8000-000000000073",
            requiredMode = NOT_REQUIRED)
    private UUID sourceLocationId;

    @Schema(
            description = "Identifier of the storage location the goods moved to",
            example = "01960003-0000-7000-8000-000000000074",
            requiredMode = REQUIRED)
    @NotNull
    private UUID destinationLocationId;

    @Schema(
            description = "Quantity of units moved during the putaway",
            example = "12",
            requiredMode = REQUIRED)
    private int quantityMoved;

    @Schema(
            description = "Ledger transaction type recorded for the move",
            example = "PUT_AWAY",
            requiredMode = REQUIRED)
    @NotNull
    private String transactionType;

    @Schema(
            description = "Outcome status of the putaway execution",
            example = "COMPLETED",
            requiredMode = REQUIRED)
    @NotNull
    private String status;

    @Schema(
            description = "Timestamp when the putaway was executed",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private String executedAt;

    @Schema(
            description = "Identifier of the actor who executed the putaway",
            example = "01960003-0000-7000-8000-000000000075",
            requiredMode = NOT_REQUIRED)
    private String actorId;
}
