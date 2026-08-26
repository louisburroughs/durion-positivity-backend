package com.positivity.vehiclefitment.internal.security;

/**
 * Permission names this module enforces, as constants rather than string literals at each call
 * site.
 *
 * <h2>Why constants and not literals</h2>
 *
 * A literal is invisible to a reader looking for everywhere a permission is used, and it is one
 * typo away from an authority nobody holds — {@code @PreAuthorize} fails closed, so a misspelling
 * does not break the build or the test suite, it silently locks the endpoint. Naming the permission
 * once means the compiler checks every use of it.
 *
 * <p>The repo-wide permission tooling reads these too:
 * {@code scripts/generate-permissions.sh --sync} resolves constant references when it decides
 * whether a permission is registered in the catalogs, so a permission introduced here is picked up
 * without a manual bit assignment.
 */
public final class VehicleFitmentPermissions {
    /** View vehicle applicability hints. */
    public static final String HINT_VIEW = "vehicle-fitment:hint:view";

    /** Create vehicle applicability hints. */
    public static final String HINT_CREATE = "vehicle-fitment:hint:create";

    /** Update vehicle applicability hints. */
    public static final String HINT_UPDATE = "vehicle-fitment:hint:update";

    /** Delete vehicle applicability hints. */
    public static final String HINT_DELETE = "vehicle-fitment:hint:delete";

    /** View vehicle fitment catalog data (manufacturers, makes, models, vehicle types) and run the
     * read-only vehicle-to-product fitment match. */
    public static final String CATALOG_VIEW = "vehicle-fitment:catalog:view";

    private VehicleFitmentPermissions() {
        // Utility class - prevent instantiation
    }
}
