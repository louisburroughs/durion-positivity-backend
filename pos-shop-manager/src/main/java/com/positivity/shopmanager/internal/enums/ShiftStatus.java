package com.positivity.shopmanager.internal.enums;

/**
 * Whether a roster entry's placeholder shift window could be derived (issue #2060).
 *
 * <p>Mirrors the distinction {@link ScheduleCapacityDayStatus} already draws for the capacity read:
 * a confirmed closure is a fact, an unreadable configuration is not, and the two must never be
 * collapsed into a default window.
 */
public enum ShiftStatus {

    /**
     * The window was derived from the location's operating hours for the day; {@code shiftStart},
     * {@code shiftEnd} and {@code shiftMinutes} are all populated.
     */
    DERIVED,

    /**
     * A dated holiday closure covers the day. A known fact, distinct from {@link #UNKNOWN}: the
     * window fields are null because the shop is shut, not because the hours could not be read.
     */
    CLOSED,

    /**
     * No window could be derived: the location replica is missing, carries no usable timezone,
     * has unparsable operating hours, has no entry for the day's weekday, or has an entry whose
     * open time is not before its close time. The window fields are null and no default is
     * substituted.
     */
    UNKNOWN
}
