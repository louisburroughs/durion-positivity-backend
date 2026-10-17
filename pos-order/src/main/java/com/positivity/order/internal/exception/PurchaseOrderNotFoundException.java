package com.positivity.order.internal.exception;

import java.util.UUID;

/** Thrown when a purchase order id does not resolve to an order (CAP-320 #1334). */
public class PurchaseOrderNotFoundException extends RuntimeException {

    public PurchaseOrderNotFoundException(UUID purchaseOrderId) {
        super("Purchase order not found: " + purchaseOrderId);
    }

    /** A line the order does not hold (#2635). */
    public PurchaseOrderNotFoundException(UUID purchaseOrderId, UUID lineId) {
        super("Purchase order " + purchaseOrderId + " has no line " + lineId);
    }
}
