package com.positivity.inventory.internal.dto.purchaseorder;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to approve a purchase order, optionally capturing approval notes")
public class ApprovePurchaseOrderRequest {

    @Schema(
            description = "Free-text notes recorded by the approver explaining the approval decision",
            example = "Approved within budget for Q1 restock",
            requiredMode = NOT_REQUIRED)
    private String approvalNotes;
}
