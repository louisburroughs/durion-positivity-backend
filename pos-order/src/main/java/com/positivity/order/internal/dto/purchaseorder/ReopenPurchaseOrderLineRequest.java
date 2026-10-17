package com.positivity.order.internal.dto.purchaseorder;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Reopen a returned quantity of a purchase-order line for a replacement shipment (CAP:550 #2635). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Reopen a returned quantity of a line: the vendor will replace the goods on this order")
public class ReopenPurchaseOrderLineRequest {

    @Schema(
            description = "Base units to reopen; at most the line's returned quantity",
            example = "2",
            requiredMode = REQUIRED)
    @NotNull
    @Positive
    private BigDecimal quantity;

    @Schema(
            description = "The vendor return the replacement answers (pos-inventory's vendorReturnId), kept for the"
                    + " audit trail",
            example = "019a0000-0000-7000-8000-000000002635",
            requiredMode = NOT_REQUIRED)
    private UUID vendorReturnId;
}
