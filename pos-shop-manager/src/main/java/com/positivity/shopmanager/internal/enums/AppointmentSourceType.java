package com.positivity.shopmanager.internal.enums;

/**
 * Identifies the workexec domain entity that originated an appointment booking
 * request.
 * <p>
 * CAP-249 Story #12: used on {@code AppointmentCreateRequest.sourceType} to
 * route
 * source eligibility validation and downstream event emission.
 */
public enum AppointmentSourceType {

    /** Appointment was created from a customer-approved estimate. */
    ESTIMATE,

    /** Appointment was created from an active work order. */
    WORK_ORDER
}
