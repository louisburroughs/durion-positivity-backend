package com.positivity.tenancy;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The reserved platform-operator tenant (ADR-0062 §7).
 *
 * <p>{@code pos-tenant} bootstraps it in its first seed migration under this constant id so {@code
 * pos-security-service} can create its platform users and role template before the first event
 * flows. Every row {@code pos-tenant} owns (accounts, contacts, billing profiles, the tenant
 * registry itself) carries this id, so row-level security protects them like any other table; there
 * is no unbound session, no root tenant, and no "see every tenant" mode.
 */
public final class PlatformTenant {

    /** Constant id of the platform tenant; the alpha default tenant is {@code ...0001}. */
    public static final UUID ID = UUID.fromString("01900000-0000-7000-8000-000000000000");

    /** The platform tenant's slug. */
    public static final String SLUG = "platform";

    private PlatformTenant() {}

    /** True when {@code tenantId} is the platform tenant. */
    public static boolean isPlatform(@Nullable UUID tenantId) {
        return ID.equals(tenantId);
    }
}
