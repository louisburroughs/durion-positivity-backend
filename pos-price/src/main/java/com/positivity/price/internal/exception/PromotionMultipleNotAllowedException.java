package com.positivity.price.internal.exception;

public class PromotionMultipleNotAllowedException extends RuntimeException {

    public PromotionMultipleNotAllowedException() {
        super("Only one promotion may be applied per estimate");
    }
}
