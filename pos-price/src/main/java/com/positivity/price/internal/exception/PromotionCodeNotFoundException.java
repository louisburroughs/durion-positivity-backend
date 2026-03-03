package com.positivity.price.internal.exception;

public class PromotionCodeNotFoundException extends RuntimeException {

    public PromotionCodeNotFoundException(String code) {
        super("Promotion code not found: " + code);
    }
}
