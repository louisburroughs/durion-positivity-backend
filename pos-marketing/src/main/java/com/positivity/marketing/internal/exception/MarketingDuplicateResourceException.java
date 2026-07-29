package com.positivity.marketing.internal.exception;

/** A marketing resource with the same natural key already exists. Maps to {@code 409}. */
public class MarketingDuplicateResourceException extends RuntimeException {

    public MarketingDuplicateResourceException(String resource, Object identifier) {
        super(resource + " already exists: " + identifier);
    }
}
