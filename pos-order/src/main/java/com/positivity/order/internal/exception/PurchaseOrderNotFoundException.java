package com.positivity.order.internal.exception;

import java.util.UUID;

/** Thrown when a purchase order id does not resolve to an order (CAP-320 #1334). */
public class PurchaseOrderNotFoundException extends RuntimeException {

    public PurchaseOrderNotFoundException(UUID purchaseOrderId) {
        super("Purchase order not found: " + purchaseOrderId);
    }
}
