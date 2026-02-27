package com.positivity.inventory.internal.dto.purchaseorder;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApprovePurchaseOrderRequest {
    private String approvalNotes;
}
