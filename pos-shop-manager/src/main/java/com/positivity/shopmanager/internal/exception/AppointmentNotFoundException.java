package com.positivity.shopmanager.internal.exception;

import java.util.UUID;

public class AppointmentNotFoundException extends RuntimeException {

    public AppointmentNotFoundException(UUID appointmentId) {
        super("Appointment not found: " + appointmentId);
    }
}
