package com.positivity.inventory.internal.exception;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resourceType, String identifier) {
        super(resourceType + " not found: " + identifier);
    }
}
