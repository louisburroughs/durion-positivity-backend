package com.positivity.location.internal.enums;

/**
 * Fixed reason list for a bay or mobile unit going {@code OUT_OF_SERVICE} (DECISION-LOCATION-026
 * rule 4, issue #2264). Fixed rather than free text so downtime can be reported on by reason; a
 * new reason is added here, never typed by a caller.
 *
 * <p>{@link #OTHER} is the only value that also requires {@code outOfServiceNote}; every other
 * value leaves the note optional (still capped at 255 characters).
 */
public enum OutOfServiceReason {
    EQUIPMENT_FAILURE,
    SCHEDULED_MAINTENANCE,
    INSPECTION,
    SAFETY_HOLD,
    FACILITY_ISSUE,
    OTHER
}
