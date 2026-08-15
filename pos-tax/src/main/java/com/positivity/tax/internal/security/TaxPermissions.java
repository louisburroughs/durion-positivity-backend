package com.positivity.tax.internal.security;

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
public final class TaxPermissions {
    /** Calculate tax for line items based on destination and jurisdiction rules. */
    public static final String CALCULATE = "tax:calculate";

    /** Commit or void provider tax documents in the tax document lifecycle (invoice finalize/revert). */
    public static final String COMMIT = "tax:commit";

    /** Create and update tax exemption certificates in the pos-tax exemption registry. */
    public static final String EXEMPTION_MANAGE = "tax:exemption:manage";

    /** View tax exemption certificates in the pos-tax exemption registry. */
    public static final String EXEMPTION_VIEW = "tax:exemption:view";

    /** View current tax service mode (test or production). */
    public static final String MODE_VIEW = "tax:mode:view";

    private TaxPermissions() {
        // Utility class - prevent instantiation
    }
}
