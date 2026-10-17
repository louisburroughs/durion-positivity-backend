package com.positivity.order.internal.dto.purchaseorder;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Short-close a purchase order: cancel every remaining open quantity (CAP:550 #2635). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Close a purchase order: every remaining open quantity on every line is cancelled")
public class ClosePurchaseOrderRequest {

    @Schema(description = "Why the remainder is cancelled", example = "Vendor will not ship the rest", requiredMode = REQUIRED)
    @NotBlank
    @Size(max = 500)
    private String reason;
}
