package com.positivity.marketing.internal.exception;

/** A marketing resource addressed by id or natural key does not exist. Maps to {@code 404}. */
public class MarketingResourceNotFoundException extends RuntimeException {

    public MarketingResourceNotFoundException(String resource, Object identifier) {
        super(resource + " not found: " + identifier);
    }
}
