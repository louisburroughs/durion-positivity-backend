package com.positivity.inventory.internal.dto.lot;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.InventoryLotStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "An inventory lot master record (odoo-parity E1)")
public class LotResponse {

    @Schema(
            description = "Identifier of the lot",
            example = "01980003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID lotId;

    @Schema(
            description = "Stock item (catalog product id) the lot belongs to",
            example = "01980003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    private String stockItemId;

    @Schema(description = "Vendor/manufacturer lot or batch number", example = "LOT-2026-0042", requiredMode = REQUIRED)
    private String lotNumber;

    @Schema(
            description = "When the lot was first received into stock",
            example = "2026-07-01T12:00:00Z",
            requiredMode = REQUIRED)
    private Instant receivedAt;

    @Schema(
            description = "Vendor the lot was first received from, when known",
            example = "01980003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    private UUID vendorId;

    @Schema(
            description = "Expiration date; null until the expiry flows (E3) populate it",
            example = "2027-01-31",
            requiredMode = NOT_REQUIRED)
    private LocalDate expirationDate;

    @Schema(description = "Lifecycle status of the lot", example = "ACTIVE", requiredMode = REQUIRED)
    private InventoryLotStatus status;

    @Schema(description = "Creation timestamp", requiredMode = NOT_REQUIRED)
    private Instant createdAt;

    @Schema(description = "Last modification timestamp", requiredMode = NOT_REQUIRED)
    private Instant updatedAt;
}
