package com.positivity.inventory.internal.dto.consumption;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(
        description =
                "Response describing the outcome of consuming inventory items against a workorder, including the ledger entries created")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConsumptionResponse {

    @Schema(
            description = "Unique identifier of the consumption record",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID consumptionId;

    @Schema(
            description = "Identifier of the workorder the items were consumed for",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID workorderId;

    @Schema(
            description = "Identifier of the pick list the consumed items were drawn from, if applicable",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    private UUID pickListId;

    @Schema(description = "Total number of items consumed in this operation", example = "4", requiredMode = REQUIRED)
    private int totalItemsConsumed;

    @Schema(
            description = "Timestamp when the consumption was recorded",
            example = "2026-01-20T14:05:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant createdAt;

    @Schema(
            description = "Identifiers of the inventory ledger entries created by this consumption",
            example = "[\"01960003-0000-7000-8000-000000000004\"]",
            requiredMode = NOT_REQUIRED)
    private List<UUID> ledgerEntryIds;
}
