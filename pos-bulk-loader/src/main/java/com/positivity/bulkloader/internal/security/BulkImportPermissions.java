package com.positivity.bulkloader.internal.security;

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
public final class BulkImportPermissions {
    /** View bulk import jobs, column mappings, and review queue. */
    public static final String STATUS_READ = "bulkImport:status:read";

    /** Submit bulk import jobs, upload files, and trigger processing. */
    public static final String UPLOAD_EXECUTE = "bulkImport:upload:execute";

    private BulkImportPermissions() {
        // Utility class - prevent instantiation
    }
}
