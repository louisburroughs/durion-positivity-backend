package com.positivity.vehicle.internal.enums;

import org.jspecify.annotations.NonNull;

/**
 * Duty category derived from a vehicle's FHWA GVWR class (CAP-327, spec D13). Never stored.
 *
 * <p>The LIGHT/MEDIUM boundary sits at class 4 (14,001 lb), which is Durion's grouping rather than
 * every FHWA-derived table's: it is exactly where ASE's Medium/Heavy Truck series (T1–T8) begins
 * and its Automobile series (A1–A8) ends, so a class 3 F-350 is light-duty A-series work here and
 * a class 4 delivery truck is not. Keeping it a rule in one place is what lets that line move
 * later as one change.
 */
public enum DutyCategory {
    LIGHT(1, 3),
    MEDIUM(4, 6),
    HEAVY(7, 8);

    private final int lowestClass;
    private final int highestClass;

    DutyCategory(int lowestClass, int highestClass) {
        this.lowestClass = lowestClass;
        this.highestClass = highestClass;
    }

    /** The category a class falls in; {@code gvwrClass} must be 1..8. */
    public static @NonNull DutyCategory fromGvwrClass(int gvwrClass) {
        for (DutyCategory category : values()) {
            if (gvwrClass >= category.lowestClass && gvwrClass <= category.highestClass) {
                return category;
            }
        }
        throw new IllegalArgumentException("gvwrClass must be 1..8, was " + gvwrClass);
    }
}
