package com.positivity.securityservice.internal.security;

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
public final class SecurityPermissions {
    /** Create audit events and pricing snapshots. */
    public static final String AUDIT_CREATE = "security:audit:create";

    /** Export audit. */
    public static final String AUDIT_EXPORT = "security:audit:export";

    /** View audit events and pricing snapshots. */
    public static final String AUDIT_VIEW = "security:audit:view";

    /** Evaluate authorization decisions for principals. */
    public static final String AUTHORIZATION_DECIDE = "security:authorization:decide";

    /** Register new permissions. */
    public static final String PERMISSION_REGISTER = "security:permission:register";

    /** View registered permissions. */
    public static final String PERMISSION_VIEW = "security:permission:view";

    /** Assign roles to users. */
    public static final String ROLE_ASSIGN = "security:role:assign";

    /** Create new roles. */
    public static final String ROLE_CREATE = "security:role:create";

    /** Delete roles. */
    public static final String ROLE_DELETE = "security:role:delete";

    /** Edit existing roles. */
    public static final String ROLE_EDIT = "security:role:edit";

    /** View roles and their permissions. */
    public static final String ROLE_VIEW = "security:role:view";

    /** Issue internal JWT access tokens for trusted callers. */
    public static final String TOKEN_ISSUE_INTERNAL = "security:token:issue_internal";

    /** Create new users. */
    public static final String USER_CREATE = "security:user:create";

    /** Delete users. */
    public static final String USER_DELETE = "security:user:delete";

    /** Edit user information. */
    public static final String USER_EDIT = "security:user:edit";

    /** View user information. */
    public static final String USER_VIEW = "security:user:view";

    /** Manage user account state such as lock, disable, or expiration. */
    public static final String USER_ACCOUNT_STATE_MANAGE = "security:user_account_state:manage";

    /** View user account state and lockout metadata. */
    public static final String USER_ACCOUNT_STATE_VIEW = "security:user_account_state:view";

    private SecurityPermissions() {
        // Utility class - prevent instantiation
    }
}
