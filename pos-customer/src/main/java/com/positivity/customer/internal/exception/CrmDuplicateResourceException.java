package com.positivity.customer.internal.exception;

/** A CRM resource with the same natural key already exists. Maps to {@code 409}. */
public class CrmDuplicateResourceException extends RuntimeException {

    public CrmDuplicateResourceException(String resource, Object identifier) {
        super(resource + " already exists: " + identifier);
    }

    public CrmDuplicateResourceException(String message) {
        super(message);
    }
}
