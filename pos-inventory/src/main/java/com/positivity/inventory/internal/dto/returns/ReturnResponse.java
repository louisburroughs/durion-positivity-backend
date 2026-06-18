package com.positivity.inventory.internal.dto.returns;

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

@Schema(description = "Result of processing a return request, including the created ledger entries")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReturnResponse {

    @Schema(
            description = "Identifier of the created return record",
            example = "01960003-0000-7000-8000-000000000008",
            requiredMode = REQUIRED)
    @NotNull
    private UUID returnId;

    @Schema(
            description = "Identifier of the workorder the items were returned against",
            example = "01960003-0000-7000-8000-000000000009",
            requiredMode = REQUIRED)
    @NotNull
    private UUID workorderId;

    @Schema(
            description = "Reason supplied when the items were returned",
            example = "Customer cancelled remaining repair scope",
            requiredMode = NOT_REQUIRED)
    private String returnReason;

    @Schema(description = "Total number of items returned across all lines", example = "5", requiredMode = REQUIRED)
    private int totalItemsReturned;

    @Schema(
            description = "Timestamp when the return was created",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant createdAt;

    @Schema(description = "Identifiers of inventory ledger entries created by the return", requiredMode = REQUIRED)
    @NotNull
    private List<UUID> ledgerEntryIds;
}
