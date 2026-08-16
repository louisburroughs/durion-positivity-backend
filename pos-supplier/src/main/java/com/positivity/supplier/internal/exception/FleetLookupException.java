package com.positivity.supplier.internal.exception;

import java.io.Serial;

/**
 * A fleet lookup could not be answered (CAP-323 #1229).
 *
 * <p>Distinct from an empty result. "The fleet does not know this plate" is an answer and comes back
 * as an empty {@code Optional}; this means nobody managed to ask. A service advisor deciding whether
 * to start work needs those two to look different on screen.
 */
public class FleetLookupException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public FleetLookupException(String message) {
        super(message);
    }

    public FleetLookupException(String message, Throwable cause) {
        super(message, cause);
    }
}
