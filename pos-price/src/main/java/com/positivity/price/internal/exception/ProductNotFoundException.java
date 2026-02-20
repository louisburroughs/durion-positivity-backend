package com.positivity.price.internal.exception;

import java.util.UUID;

/**
 * Thrown when no active pricing baseline exists for a product.
 *
 * Issue: #51
 */
public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(UUID productId) {
        super("Product not found: " + productId);
    }
}
