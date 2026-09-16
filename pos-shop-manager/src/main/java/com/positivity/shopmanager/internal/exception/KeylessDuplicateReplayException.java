package com.positivity.shopmanager.internal.exception;

import java.util.UUID;

/**
 * Raised from the {@code 23P01} path of appointment creation when the refused insert turns out to
 * be an exact keyless resubmission of an appointment that committed concurrently (CAP-326, spec
 * D17 item 3): two identical double-submits replay rather than collide.
 *
 * <p>It is an exception only because the booking transaction is already aborted by the constraint
 * violation and cannot answer a normal result; it carries the id, not the body, because the
 * aborted connection cannot be read either. The controller loads the appointment afresh and
 * answers 200, exactly as it does for the idempotency-key replay.
 */
public class KeylessDuplicateReplayException extends RuntimeException {
    private final UUID existingAppointmentId;

    public KeylessDuplicateReplayException(UUID existingAppointmentId) {
        super("Keyless exact resubmission of appointment " + existingAppointmentId + "; replaying");
        this.existingAppointmentId = existingAppointmentId;
    }

    public UUID getExistingAppointmentId() {
        return existingAppointmentId;
    }
}
