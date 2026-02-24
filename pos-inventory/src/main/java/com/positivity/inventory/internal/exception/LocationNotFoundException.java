package com.positivity.inventory.internal.exception;

public class LocationNotFoundException extends RuntimeException {
    public LocationNotFoundException(String locationId) {
        super("Location not found: " + locationId);
    }
}