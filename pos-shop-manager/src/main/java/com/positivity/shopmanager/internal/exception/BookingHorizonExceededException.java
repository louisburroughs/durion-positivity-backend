package com.positivity.shopmanager.internal.exception;

/**
 * A create or reschedule whose start falls beyond the configured booking horizon
 * (DECISION-SHOPMGMT-019, issue #2100).
 *
 * <p>Mapped to {@code 422 Unprocessable Content}, like {@link ScheduleCapacityRangeExceededException}
 * and {@link OpeningSearchPolicyException} and for the same reason: the request is syntactically
 * valid — the instants parse and the start precedes the end — but it violates a documented policy
 * limit, and DECISION-SHOPMGMT-011 puts policy failures at 422 and syntactic ones at 400.
 */
public class BookingHorizonExceededException extends RuntimeException {

    public static final String CODE = "BOOKING_HORIZON_EXCEEDED";

    public BookingHorizonExceededException(int maxAdvanceDays, long requestedDaysAhead) {
        super("Appointment starts " + requestedDaysAhead + " day(s) from now, beyond the booking horizon of "
                + maxAdvanceDays + " day(s); book within " + maxAdvanceDays + " day(s) of today.");
    }
}
