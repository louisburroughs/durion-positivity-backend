package com.positivity.shopmanager.internal.enums;

/**
 * Where a roster entry's shift window came from (issue #2060).
 *
 * <p>The field exists so a consumer can tell a placeholder window from a real one without reading
 * documentation. Only {@link #LOCATION_HOURS} is emitted today. {@code PERSON_SCHEDULE} is the name
 * reserved for the per-person shift schedule that #71 will introduce once #271 settles the HR
 * availability contract; it is deliberately not declared until something produces it.
 */
public enum ShiftSource {

    /**
     * PLACEHOLDER: the window is the shop location's operating hours for the day, identical for
     * every mechanic at the location. It is not the person's roster, and it knows nothing of
     * staggered shifts, part-time hours, split shifts, overtime or PTO.
     */
    LOCATION_HOURS
}
