package com.positivity.tenant.internal.security;

/**
 * Permission names this module enforces (ADR-0062 §7, ADR-0025). The {@code platform:*} families
 * exist only in the platform tenant's role template; a tenant administrator never holds them.
 * Constants rather than literals so the compiler checks every use and
 * {@code scripts/generate-permissions.sh --sync} can resolve them.
 */
public final class TenantPermissions {

    /** Register a tenant under an account. */
    public static final String TENANT_CREATE = "platform:tenant:create";

    /** Read tenants. */
    public static final String TENANT_READ = "platform:tenant:read";

    /** Change a tenant's display name or cell. */
    public static final String TENANT_UPDATE = "platform:tenant:update";

    /** Suspend an active tenant. */
    public static final String TENANT_SUSPEND = "platform:tenant:suspend";

    /** Reactivate a suspended tenant. */
    public static final String TENANT_REACTIVATE = "platform:tenant:reactivate";

    /** Decommission a tenant (terminal). */
    public static final String TENANT_DECOMMISSION = "platform:tenant:decommission";

    /** Create an account. */
    public static final String ACCOUNT_CREATE = "platform:account:create";

    /** Read accounts, their contacts and billing profile. */
    public static final String ACCOUNT_READ = "platform:account:read";

    /** Update an account, its contacts and billing profile. */
    public static final String ACCOUNT_UPDATE = "platform:account:update";

    private TenantPermissions() {
        // Utility class
    }
}
