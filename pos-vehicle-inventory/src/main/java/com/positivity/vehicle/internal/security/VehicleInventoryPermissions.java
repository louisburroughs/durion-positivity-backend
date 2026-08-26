package com.positivity.vehicle.internal.security;

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
public final class VehicleInventoryPermissions {
    /** View vehicle registry records. */
    public static final String REGISTRY_VIEW = "vehicle-inventory:registry:view";

    /** Create vehicle registry records. */
    public static final String REGISTRY_CREATE = "vehicle-inventory:registry:create";

    /** Update vehicle registry records. */
    public static final String REGISTRY_UPDATE = "vehicle-inventory:registry:update";

    /** Delete (deactivate) vehicle registry records. */
    public static final String REGISTRY_DELETE = "vehicle-inventory:registry:delete";

    /** Search vehicle inventory records. */
    public static final String SEARCH_VIEW = "vehicle-inventory:search:view";

    /** Manage (read, upsert, merge, delete) vehicle care preferences. */
    public static final String PREFERENCES_MANAGE = "vehicle-inventory:preferences:manage";

    private VehicleInventoryPermissions() {
        // Utility class - prevent instantiation
    }
}
