package com.positivity.location.internal.security;

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
public final class LocationPermissions {
    /** Create or update bay definitions and assignments. */
    public static final String BAY_MANAGE = "location:bay:manage";

    /** Read bay definitions and availability. */
    public static final String BAY_READ = "location:bay:read";

    /** Create or update mobile unit definitions. */
    public static final String MOBILE_UNIT_MANAGE = "location:mobile-unit:manage";

    /** Read mobile unit definitions and eligibility. */
    public static final String MOBILE_UNIT_READ = "location:mobile-unit:read";

    /** Read location records and hierarchy data. */
    public static final String READ = "location:read";

    /** Create or update service area definitions. */
    public static final String SERVICE_AREA_MANAGE = "location:service-area:manage";

    /** Read service area definitions. */
    public static final String SERVICE_AREA_READ = "location:service-area:read";

    /** Create or update travel buffer policy definitions. */
    public static final String TRAVEL_BUFFER_POLICY_MANAGE = "location:travel-buffer-policy:manage";

    /** Read travel buffer policy definitions. */
    public static final String TRAVEL_BUFFER_POLICY_READ = "location:travel-buffer-policy:read";

    /** Create or update location records and defaults. */
    public static final String WRITE = "location:write";

    private LocationPermissions() {
        // Utility class - prevent instantiation
    }
}
