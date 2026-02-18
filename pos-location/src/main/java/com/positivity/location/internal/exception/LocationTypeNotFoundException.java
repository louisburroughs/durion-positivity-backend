package com.positivity.location.internal.exception;

import java.util.UUID;

public class LocationTypeNotFoundException extends RuntimeException {
    public LocationTypeNotFoundException(UUID typeId) {
        super("Location type not found: " + typeId);
    }
}
