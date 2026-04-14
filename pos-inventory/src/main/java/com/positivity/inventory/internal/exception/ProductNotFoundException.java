package com.positivity.inventory.internal.exception;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(String productSku) {
        super("Product not found: " + productSku);
    }
}
