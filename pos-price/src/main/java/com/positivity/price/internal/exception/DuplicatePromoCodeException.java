package com.positivity.price.internal.exception;

public class DuplicatePromoCodeException extends RuntimeException {
    public DuplicatePromoCodeException(String promoCode) {
        super("Promotion code already exists: " + promoCode);
    }
}
