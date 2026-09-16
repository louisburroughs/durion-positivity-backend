package com.positivity.shopmanager.internal.enums;

/**
 * The assembly outcome of one date in a {@code GET /v1/schedules/capacity} response (issue #2023).
 *
 * <p>Every date requested is present in the response with exactly one of these statuses — a date is
 * never omitted, because an absent date is indistinguishable from "no capacity" to a client (AC4).
 */
public enum ScheduleCapacityDayStatus {

    /** The location was open this date and {@code bays} carries the roster's occupancy. */
    OK,

    /**
     * The location's weekly hours have no window for this date's day-of-week (or the hours are
     * configured as empty for every day, per DECISION-LOCATION-004). A fact, not a failure: {@code
     * bays} is empty and {@code closureReason} is {@code null}.
     */
    CLOSED,

    /**
     * A dated closure from {@code holidayClosures} covers this date. A fact, not a failure: {@code
     * bays} is empty and {@code closureReason} carries the owner's reason, when one was recorded.
     */
    HOLIDAY,

    /**
     * This date could not be assembled — the location has no replica row yet, its timezone is
     * unknown or blank, its weekly hours were never configured, or this date's own hours entry is
     * malformed. Never reported as {@code CLOSED}: an unknown fact is not the same claim as a
     * confirmed closure (AC11).
     */
    UNAVAILABLE
}
