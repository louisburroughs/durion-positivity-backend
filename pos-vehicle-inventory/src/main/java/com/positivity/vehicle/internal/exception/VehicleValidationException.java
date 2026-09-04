package com.positivity.vehicle.internal.exception;

/**
 * A vehicle-inventory request is malformed on its face: a blank required field, an out-of-range
 * value (a service interval, a model year), an unsupported enumerated value (vehicle type), or a
 * search query too short for its inferred type (issue #1694). The payload's <em>shape</em> is
 * wrong, not the domain state it describes, so this is a 400 per ADR-0017 §1 — distinct from a
 * {@link VehicleVinConflictException}, where the request is well-formed but collides with
 * existing state.
 *
 * <p>Replaces the module's former blanket {@code IllegalArgumentException} handler (which also
 * caught unrelated JDK/Hibernate {@code IllegalArgumentException}s reachable through no explicit
 * throw in this module, echoing internal detail as if it were a validation message). The
 * {@code VALIDATION_ERROR} code is unchanged from that handler so the wire contract does not
 * drift; only the exception type moved.
 */
public class VehicleValidationException extends RuntimeException {

    public VehicleValidationException(String message) {
        super(message);
    }
}
