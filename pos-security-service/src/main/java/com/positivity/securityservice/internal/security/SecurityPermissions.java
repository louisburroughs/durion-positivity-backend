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

    /**
     * Register, replace or remove the tenant's audit export webhook destination, the endpoint WEBHOOK
     * audit exports are sent to outside the shop (#2497). Granted to ADMIN only: registering one
     * decides where the tenant's audit data may go and issues its signing secret.
     */
    public static final String AUDIT_EXPORT_WEBHOOK_MANAGE = "security:audit_export:webhook_manage";

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

    /**
     * Mint a first-administrator activation token for a provisioned tenant (ADR-0062 §7, WS2b-3).
     * A {@code platform:*} permission: held by {@code PLATFORM_ADMIN} in the platform tenant only
     * ({@code R__seed_tenant_template.sql}), never by a tenant role.
     */
    public static final String PLATFORM_TENANT_PROVISION = "platform:tenant:provision";

    /**
     * Mint a 15-minute, no-refresh {@code SUPPORT} impersonation token for a tenant (ADR-0062 §7,
     * WS2b-4). Like {@link #PLATFORM_TENANT_PROVISION} a {@code platform:*} permission: held by
     * {@code PLATFORM_ADMIN} in the platform tenant only, never by a tenant role.
     */
    public static final String PLATFORM_TENANT_IMPERSONATE = "platform:tenant:impersonate";

    private SecurityPermissions() {
        // Utility class - prevent instantiation
    }
}
