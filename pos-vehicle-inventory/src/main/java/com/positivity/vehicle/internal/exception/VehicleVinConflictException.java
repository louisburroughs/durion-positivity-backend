package com.positivity.vehicle.internal.exception;

/**
 * A vehicle create request is well-formed but its VIN is already held by another active vehicle
 * registry record. VINs must be globally unique among active vehicles, so this is a stateful
 * collision — a well-formed request refused because of existing state, not because of anything
 * wrong with the request's shape — and maps to a 409 per ADR-0017 §2, which classifies duplicate
 * unique constraints as stateful collisions (issue #1694).
 *
 * <p>Previously this case shared the module's blanket {@code IllegalArgumentException} handler
 * and answered 400 {@code VALIDATION_ERROR}, a status the endpoint's own OpenAPI description
 * called out as deliberate; that was itself the defect the blanket handler's limited vocabulary
 * forced — it could not distinguish a genuine 400 shape error from a 409 collision. The new
 * {@code VEHICLE_VIN_CONFLICT} code reflects the corrected status.
 */
public class VehicleVinConflictException extends RuntimeException {

    public VehicleVinConflictException(String message) {
        super(message);
    }
}
