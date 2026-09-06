package com.positivity.order.internal.dto.purchaseorder;

import com.positivity.order.internal.enums.PurchaseOrderStatus;

/**
 * One row of the purchase-order header roll-up: how many orders sit in a status and what they are
 * worth. A JPQL constructor projection, so the sums are whatever the database returned — null when
 * a status has orders but every total is null — and the service normalises them.
 */
public record PurchaseOrderStatusRollup(
        PurchaseOrderStatus status, Long orderCount, Long grandTotalMinor, Long openBalanceMinor) {}
