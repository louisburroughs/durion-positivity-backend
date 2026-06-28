package com.positivity.inventory.internal.dto.asn;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Schema(
        description =
                "Goods receipt representing inventory received against a purchase order, including its received line items and accrued totals")
@Data
@Builder
public class GoodsReceiptResponse {

    @Schema(
            description = "Unique identifier of the goods receipt",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID receiptId;

    @Schema(
            description = "Human-readable reference number assigned to the goods receipt",
            example = "GR-2026-000456",
            requiredMode = REQUIRED)
    @NotNull
    private String receiptNumber;

    @Schema(
            description = "Identifier of the purchase order the goods were received against",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID poId;

    @Schema(
            description = "Identifier of the advance shipping notice (ASN) this receipt fulfilled, if applicable",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    private UUID asnId;

    @Schema(
            description = "Identifier of the location where the goods were received into inventory",
            example = "01960003-0000-7000-8000-000000000004",
            requiredMode = REQUIRED)
    @NotNull
    private UUID locationId;

    @Schema(
            description = "Total accrued cost for the goods receipt expressed in minor currency units (e.g. cents)",
            example = "11992",
            requiredMode = NOT_REQUIRED)
    private Long totalAccruedAmountMinor;

    @Schema(
            description = "Identifier of the user who recorded the goods receipt",
            example = "user-jdoe",
            requiredMode = NOT_REQUIRED)
    private String createdBy;

    @Schema(
            description = "Timestamp when the goods receipt was created",
            example = "2026-01-20T14:05:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant createdAt;

    @Schema(
            description = "Received line items detailing the SKUs and quantities in this goods receipt",
            requiredMode = NOT_REQUIRED)
    private List<GoodsReceiptLineResponse> lines;
}
