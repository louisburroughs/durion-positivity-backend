package com.positivity.order.internal.dto.purchaseorder;

import com.positivity.order.internal.enums.PurchaseOrderStatus;
import java.math.BigDecimal;

/**
 * One row of the purchase-order line roll-up: line count, units ordered and units still open,
 * grouped by the parent order's status. A JPQL constructor projection; sums may be null.
 */
public record PurchaseOrderLineRollup(
        PurchaseOrderStatus status, Long lineCount, BigDecimal unitsOrdered, BigDecimal unitsOpen) {}
