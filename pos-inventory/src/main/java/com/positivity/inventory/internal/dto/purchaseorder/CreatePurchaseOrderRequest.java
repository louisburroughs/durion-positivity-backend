package com.positivity.inventory.internal.dto.purchaseorder;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to create a new purchase order for a vendor with one or more order lines")
public class CreatePurchaseOrderRequest {

    @Schema(
            description = "Identifier of the vendor the purchase order is placed with",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID vendorId;

    @Schema(
            description = "Date the purchase order is issued",
            example = "2026-01-15",
            requiredMode = REQUIRED)
    @NotNull
    private LocalDate poDate;

    @Schema(
            description = "ISO 4217 currency code the purchase order is denominated in",
            example = "USD",
            requiredMode = REQUIRED)
    @NotNull
    @NotBlank
    private String currency;

    @Schema(
            description = "Identifier of the payment terms applied to this purchase order",
            example = "NET30",
            requiredMode = NOT_REQUIRED)
    private String paymentTermsId;

    @Schema(
            description = "Date the ordered goods are expected to be delivered",
            example = "2026-01-29",
            requiredMode = NOT_REQUIRED)
    private LocalDate expectedDeliveryDate;

    @Schema(
            description = "Identifier of the location the goods should be shipped to",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID shipToLocationId;

    @Schema(
            description = "Identifier or name of the person who requested the purchase order",
            example = "user-jdoe",
            requiredMode = NOT_REQUIRED)
    private String requestedBy;

    @Schema(
            description = "Free-text comment providing additional context for the purchase order",
            example = "Urgent restock for spring promotion",
            requiredMode = NOT_REQUIRED)
    private String comment;

    @Schema(
            description = "Order lines detailing the SKUs, quantities, and costs being purchased",
            requiredMode = REQUIRED)
    @NotNull
    @NotEmpty
    @Valid
    private List<PurchaseOrderLineRequest> lines;
}
