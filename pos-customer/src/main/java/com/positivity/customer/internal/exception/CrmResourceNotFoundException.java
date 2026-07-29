package com.positivity.customer.internal.exception;

/** A CRM resource addressed by id or natural key does not exist. Maps to {@code 404}. */
public class CrmResourceNotFoundException extends RuntimeException {

    public CrmResourceNotFoundException(String resource, Object identifier) {
        super(resource + " not found: " + identifier);
    }

    public CrmResourceNotFoundException(String message) {
        super(message);
    }
}
