package com.positivity.shopmanager.internal.security;

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
public final class ShopPermissions {
    /** Assign work to bays. */
    public static final String BAY_ASSIGN = "shop:bay:assign";

    /** Edit shop schedules. */
    public static final String SCHEDULE_EDIT = "shop:schedule:edit";

    /** View shop schedules. */
    public static final String SCHEDULE_VIEW = "shop:schedule:view";

    /** View technician identity details. */
    public static final String TECHNICIAN_VIEW = "shop:technician:view";

    // ── Permissions owned by other domains ──────────────────────────────────────────────
    //
    // Declared here so this module's call sites are constants like every other, but the names
    // belong elsewhere. Their definition, bit assignment and description live with their owner —
    // this is a reference, not a claim of ownership.

    /** Owned by the appointments domain. */
    public static final String APPOINTMENTS_CANCEL = "appointments:cancel";

    /** Owned by the appointments domain. */
    public static final String APPOINTMENTS_CREATE = "appointments:create";

    /** Owned by the appointments domain. */
    public static final String APPOINTMENTS_RESCHEDULE = "appointments:reschedule";

    /** Owned by the appointments domain. */
    public static final String APPOINTMENTS_VIEW = "appointments:view";

    private ShopPermissions() {
        // Utility class - prevent instantiation
    }
}
