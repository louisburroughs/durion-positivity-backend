package com.positivity.inventory.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Schema(description = "Optional filter and pagination parameters for querying inventory ledger entries")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryLedgerFilterParams {

    @Schema(
            description = "Filter to a single product stock-keeping unit",
            example = "SKU-10042",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private String productSku;

    @Schema(
            description = "Filter to a single location",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID locationId;

    @Schema(
            description = "Filter to a single storage sub-location",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID storageLocationId;

    @Schema(
            description = "Inclusive lower bound on entry timestamp",
            example = "2026-01-01T00:00:00Z",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private Instant dateFrom;

    @Schema(
            description = "Inclusive upper bound on entry timestamp",
            example = "2026-01-31T23:59:59Z",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private Instant dateTo;

    @Schema(
            description = "Filter to entries originating from a specific source transaction",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID sourceTransactionId;

    @Schema(
            description = "Filter to entries associated with a specific workorder",
            example = "01960003-0000-7000-8000-000000000004",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID workorderId;

    @Schema(
            description = "Filter to entries associated with a specific workorder line",
            example = "01960003-0000-7000-8000-000000000005",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID workorderLineId;

    @Schema(
            description = "Filter to one or more ledger movement (event) types",
            example = "[\"GOODS_RECEIPT\", \"GOODS_ISSUE\"]",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private List<String> movementTypes;

    @Schema(
            description = "Opaque cursor identifying the next page of results",
            example = "eyJvZmZzZXQiOjUwfQ==",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private String pageToken;

    @Schema(description = "Maximum number of entries to return per page", example = "50", requiredMode = NOT_REQUIRED)
    @Min(1)
    @Builder.Default
    private int pageSize = 50;
}
