package com.positivity.order.internal.exception;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A drawer opened at a location other than the one its register's configured float is held at (#2573,
 * Order ruling: no register moves during an open session; ADR-0017): answered 422 {@code
 * REGISTER_FLOAT_LOCATION_MISMATCH} with the terminal, the requested location and — only when the caller's
 * scope covers it — the float's location. No session is opened.
 */
public class RegisterFloatLocationMismatchException extends RuntimeException {

    private final transient String terminalId;
    private final transient UUID requestedLocationId;
    private final transient UUID floatLocationId;

    /**
     * @param terminalId the register
     * @param requestedLocationId the location the session would open at (in the caller's scope), or null
     * @param floatLocationId the float's location, or null when the caller's scope does not cover it
     */
    public RegisterFloatLocationMismatchException(
            @NonNull String terminalId, @Nullable UUID requestedLocationId, @Nullable UUID floatLocationId) {
        super("Register " + terminalId + " has its configured float at another location; open the drawer where"
                + " the float is held, or have accounting move the register first");
        this.terminalId = terminalId;
        this.requestedLocationId = requestedLocationId;
        this.floatLocationId = floatLocationId;
    }

    public @NonNull String terminalId() {
        return terminalId;
    }

    public @Nullable UUID requestedLocationId() {
        return requestedLocationId;
    }

    /** Null when the caller may not see it — never echo an out-of-scope location. */
    public @Nullable UUID floatLocationId() {
        return floatLocationId;
    }
}
