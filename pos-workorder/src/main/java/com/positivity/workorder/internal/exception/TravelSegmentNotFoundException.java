package com.positivity.workorder.internal.exception;

import java.util.UUID;

public class TravelSegmentNotFoundException extends RuntimeException {
    public TravelSegmentNotFoundException(UUID id) {
        super("Travel segment not found: " + id);
    }
}
