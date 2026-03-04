package com.positivity.price.internal.exception;

/** Thrown when a PromotionOffer cannot be found. Issue: #97 */
public class PromotionOfferNotFoundException extends RuntimeException {
    public PromotionOfferNotFoundException(java.util.UUID id) {
        super("Promotion offer not found: " + id);
    }

    public PromotionOfferNotFoundException(String promoCode) {
        super("Promotion offer not found for code: " + promoCode);
    }
}
