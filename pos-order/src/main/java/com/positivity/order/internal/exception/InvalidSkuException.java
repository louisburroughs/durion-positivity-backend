package com.positivity.order.internal.exception;

public class InvalidSkuException extends RuntimeException {

    public InvalidSkuException(String message) {
        super(message);
    }

    public InvalidSkuException(String sku, String detail) {
        super("Product not found for SKU: " + sku + ". " + detail);
    }
}
