package com.positivity.location.internal.exception;

import java.util.UUID;

public class LocationNotFoundException extends RuntimeException {
    public LocationNotFoundException(UUID locationId) {
        super("Location not found: " + locationId);
    }
}
