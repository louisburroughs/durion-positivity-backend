package com.positivity.shopmanager.internal.enums;

/**
 * The closed vocabularies of {@code GET /v1/schedules/openings} (#2022, spec D10.2/D11), kept
 * together because they are read together: what an opening checked, how a skill stands on it, and
 * why a search came back empty or under-staffed.
 */
public final class OpeningSearchEnums {

    private OpeningSearchEnums() {}

    /** Why the openings list is empty. Set only when it is; skill never empties the list (D10.2). */
    public enum NoOpeningReason {
        /** No active bay at the location can perform every requested service for this vehicle. */
        NO_ELIGIBLE_BAY_AT_LOCATION,
        /** Eligible bays exist, but none has a free, unbroken, buffered window in the horizon. */
        ALL_ELIGIBLE_BAYS_BOOKED
    }

    /** How the opening's technician stands against the work's skill requirement (D10.1). */
    public enum SkillFulfillment {
        /** The named technician holds every required skill on the opening's local date. */
        CERTIFIED,
        /** The named technician lacks at least one required skill; booking is allowed, assignment waits. */
        AWAITING
    }

    /** What an opening actually evaluated — never implies a check that did not run (D11). */
    public enum OpeningConstraint {
        /** The window lies inside the location's published operating hours; closures skipped. */
        HOURS,
        /** The bay is eligible for every requested service and the vehicle's duty class. */
        BAY,
        /** The bay is free for the whole duration, unbroken, in that one bay. */
        DURATION,
        /** The location's check-in and cleanup buffers are reserved around the window. */
        BUFFER,
        /** A technician is rostered that day (day grain) and not booked in the window (minute grain). */
        ROSTER,
        /** Required skills were resolved and checked against the technician's credentials. */
        SKILL
    }

    /** Response-level staffing condition, invariant across the whole search (D10.2). */
    public enum StaffingAdvisoryCode {
        /** Nobody rostered here in the horizon holds a required skill. Openings are still returned. */
        NO_COMPETENT_MECHANIC_ROSTERED,
        /** No technician at all is rostered here in the horizon (#2035 answer 5) — no opening can name one. */
        MECHANIC_UNAVAILABLE
    }

    /** The remedy an advisory points at. {@code NOT_IN_TENANT} is deliberately absent (D10.2). */
    public enum AbsenceScope {
        /** Someone at this location qualifies, just not on any day in the searched horizon. */
        NOT_ROSTERED_THIS_DAY,
        /** Nobody staffed at this location qualifies, on any day. */
        NOT_AT_THIS_LOCATION
    }
}
