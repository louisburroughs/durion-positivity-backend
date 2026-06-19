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
@Schema(description = "Request to revise an existing purchase order, replacing its lines and recording a revision reason")
public class RevisePurchaseOrderRequest {

    @Schema(
            description = "Date the revised purchase order is issued",
            example = "2026-01-20",
            requiredMode = REQUIRED)
    @NotNull
    private LocalDate poDate;

    @Schema(
            description = "Identifier of the payment terms applied to the revised purchase order",
            example = "NET30",
            requiredMode = NOT_REQUIRED)
    private String paymentTermsId;

    @Schema(
            description = "Date the ordered goods are expected to be delivered",
            example = "2026-02-03",
            requiredMode = NOT_REQUIRED)
    private LocalDate expectedDeliveryDate;

    @Schema(
            description = "Identifier of the location the goods should be shipped to",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    private UUID shipToLocationId;

    @Schema(
            description = "Identifier or name of the person who requested the revision",
            example = "user-jdoe",
            requiredMode = NOT_REQUIRED)
    private String requestedBy;

    @Schema(
            description = "Free-text comment providing additional context for the revised purchase order",
            example = "Increased quantities after vendor confirmed availability",
            requiredMode = NOT_REQUIRED)
    private String comment;

    @Schema(
            description = "Order lines replacing the existing purchase order lines",
            requiredMode = REQUIRED)
    @NotNull
    @NotEmpty
    @Valid
    private List<PurchaseOrderLineRequest> lines;

    @Schema(
            description = "Reason explaining why the purchase order is being revised",
            example = "Vendor price change required updated unit costs",
            requiredMode = REQUIRED)
    @NotNull
    @NotBlank
    private String revisionReason;
}
