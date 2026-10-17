package com.positivity.order.internal.dto.purchaseorder;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import com.positivity.order.internal.enums.PurchaseOrderVendorStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Optional filters of the purchase-order vendor list (CAP:550, #2680). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Optional filter criteria for listing the vendors a purchase order may name")
public class ListPurchaseOrderVendorsRequest {

    @Schema(
            description = "Only vendors in this status; omit for every vendor of the copy, active or inactive",
            example = "ACTIVE",
            requiredMode = NOT_REQUIRED)
    private PurchaseOrderVendorStatus status;

    @Schema(
            description = "Case-insensitive substring of the vendor number or display name",
            example = "acme",
            maxLength = 255,
            requiredMode = NOT_REQUIRED)
    @Size(max = 255)
    private String q;
}
