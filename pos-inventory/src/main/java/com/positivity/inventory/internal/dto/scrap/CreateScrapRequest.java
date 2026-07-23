package com.positivity.inventory.internal.dto.scrap;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.ScrapReasonCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for creating a scrap/write-off document (odoo-parity D1, issue #1030).
 */
@Schema(description = "Request to scrap (write off) stock at a location")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateScrapRequest {

    @Schema(
            description = "SKU / stock item identifier being scrapped",
            example = "OIL-5W30-5QT",
            requiredMode = REQUIRED)
    @NotBlank(message = "Stock item ID is required")
    private String stockItemId;

    @Schema(description = "Quantity to write off; must be positive", example = "3", requiredMode = REQUIRED)
    @NotNull(message = "Quantity is required")
    @Positive(message = "Quantity must be positive")
    private Integer quantity;

    @Schema(
            description = "Location the stock is scrapped from",
            example = "96dd346a-047c-86f5-3c9a-7c8cac53da86",
            requiredMode = REQUIRED)
    @NotNull(message = "Location ID is required")
    private UUID locationId;

    @Schema(
            description = "Bin-level storage location, when the scrap is bin-scoped",
            example = "01960004-0001-7000-8000-000000000009",
            requiredMode = NOT_REQUIRED)
    private UUID storageLocationId;

    @Schema(description = "Scrap reason; OTHER requires notes", example = "DAMAGED", requiredMode = REQUIRED)
    @NotNull(message = "Reason code is required")
    private ScrapReasonCode reasonCode;

    @Schema(
            description = "Free-text notes; mandatory when reasonCode is OTHER",
            example = "Dropped during putaway, casing cracked",
            requiredMode = NOT_REQUIRED)
    @Size(max = 2000, message = "Notes must not exceed 2000 characters")
    private String notes;

    @Schema(
            description = "Workorder the scrapped part belonged to, when linked",
            example = "01960005-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID workorderId;

    @Schema(
            description = "Photo/attachment reference (storage out of scope)",
            example = "s3://scrap-evidence/2026/07/scrap-123.jpg",
            requiredMode = NOT_REQUIRED)
    @Size(max = 500, message = "Attachment reference must not exceed 500 characters")
    private String attachmentReference;

    @Schema(
            description = "When true, posting triggers a pick-face replenishment evaluation for the SKU/location",
            example = "false",
            requiredMode = NOT_REQUIRED)
    @Builder.Default
    private Boolean shouldReplenish = false;

    @Schema(
            description = "Explicit negative-stock override for the auto-approve posting path; honored only when"
                    + " the caller holds inventory:adjustment:override",
            example = "false",
            requiredMode = NOT_REQUIRED)
    @Builder.Default
    private Boolean negativeStockOverride = false;
}
