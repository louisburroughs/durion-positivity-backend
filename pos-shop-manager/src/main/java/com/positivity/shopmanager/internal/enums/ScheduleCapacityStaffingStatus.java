package com.positivity.shopmanager.internal.enums;

/**
 * Whether a {@code GET /v1/schedules/capacity} response could say who was rostered (issue #2527).
 *
 * <p>Response-level, because the source is: the staffing-assignment replica either holds data for
 * the location or it does not. Without this a client could not tell "nobody rostered" (an empty
 * {@code technicians} list on an {@code AVAILABLE} response) from "rostering unknown" (AC4).
 */
public enum ScheduleCapacityStaffingStatus {

    /**
     * The staffing replica holds at least one ACTIVE assignment at the location, so every {@code OK}
     * day's {@code technicians} list is the real roster for that date — empty when nobody in a
     * technician role is rostered then.
     */
    AVAILABLE,

    /**
     * The staffing replica holds no ACTIVE assignment at the location at all (not yet replicated, or
     * never staffed). Every day's {@code technicians} list is empty, and that means unknown, never
     * "nobody on duty".
     */
    UNAVAILABLE
}
