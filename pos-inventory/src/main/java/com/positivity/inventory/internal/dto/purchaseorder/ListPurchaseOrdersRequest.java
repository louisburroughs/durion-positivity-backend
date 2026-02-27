package com.positivity.inventory.internal.dto.purchaseorder;

import com.positivity.inventory.internal.enums.PurchaseOrderStatus;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ListPurchaseOrdersRequest {
    private UUID vendorId;
    private PurchaseOrderStatus status;
    private String currency;
    private UUID locationId;
}
