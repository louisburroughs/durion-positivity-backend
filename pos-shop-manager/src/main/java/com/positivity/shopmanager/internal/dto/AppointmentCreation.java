package com.positivity.shopmanager.internal.dto;

import org.jspecify.annotations.NonNull;

/**
 * What {@code createAppointment} produced: a new appointment, or a replay of one that already
 * existed — through the {@code Idempotency-Key} or, since CAP-326 (spec D17 item 3), through an
 * exact keyless resubmission. The controller answers 201 for the former and 200 for the latter.
 */
public record AppointmentCreation(@NonNull AppointmentResponse appointment, boolean replayed) {

    public static AppointmentCreation created(@NonNull AppointmentResponse appointment) {
        return new AppointmentCreation(appointment, false);
    }

    public static AppointmentCreation replayed(@NonNull AppointmentResponse appointment) {
        return new AppointmentCreation(appointment, true);
    }
}
