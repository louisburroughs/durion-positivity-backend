package com.positivity.inventory.internal.exception;

import java.util.UUID;

/**
 * Thrown when a PICK or ISSUE movement would result in negative on-hand stock.
 *
 * Issue: CAP-215 Story #37
 */
public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(String productSku, UUID locationId) {
        super("Insufficient stock for product " + productSku + " at location " + locationId);
    }
}
