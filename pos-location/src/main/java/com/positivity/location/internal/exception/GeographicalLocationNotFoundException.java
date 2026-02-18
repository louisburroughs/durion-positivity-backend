package com.positivity.location.internal.exception;

import java.util.UUID;

public class GeographicalLocationNotFoundException extends RuntimeException {
    public GeographicalLocationNotFoundException(UUID geographicalLocationId) {
        super("Geographical location not found: " + geographicalLocationId);
    }
}
