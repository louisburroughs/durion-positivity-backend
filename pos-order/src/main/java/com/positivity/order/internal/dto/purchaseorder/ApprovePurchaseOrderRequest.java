package com.positivity.order.internal.dto.purchaseorder;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
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

    @Schema(
            description = "Optional: the versionNumber of the order the approver read. When given, the approval is"
                    + " applied only if the order is still at that version, so a person approves the content they"
                    + " saw; otherwise 409 PURCHASE_ORDER_VERSION_CONFLICT and nothing changes (#2681). Absent"
                    + " approves the current version",
            example = "2",
            minimum = "1",
            requiredMode = NOT_REQUIRED)
    @Min(1)
    private Integer expectedVersionNumber;
}
