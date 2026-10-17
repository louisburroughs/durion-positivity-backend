package com.positivity.order.internal.dto.purchaseorder;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Cancel a remainder of one purchase-order line the vendor will not ship (CAP:550 #2635). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Cancel part or all of a line's open quantity: the vendor will not ship it")
public class CancelPurchaseOrderLineRequest {

    @Schema(
            description = "Base units to cancel; at most the line's open quantity",
            example = "3",
            requiredMode = REQUIRED)
    @NotNull
    @Positive
    private BigDecimal quantity;

    @Schema(
            description = "Why the remainder is cancelled",
            example = "Vendor discontinued the part",
            requiredMode = REQUIRED)
    @NotBlank
    @Size(max = 500)
    private String reason;
}
