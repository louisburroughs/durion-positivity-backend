package com.positivity.tenancy;

import java.util.UUID;

/**
 * Fixed tenant identifiers from ADR-0062 and {@code docs/TENANCY_SCHEMA.md}.
 *
 * <p>These are not configuration. The values are baked into the flattened baselines' seed rows and
 * into the transitional bindings, so they must not drift.
 */
public final class TenancyConstants {

    /**
     * The one tenant the alpha cell serves until {@code pos-tenant} (plan WS2a) provisions tenants.
     * Every seed row written by the flattened baselines carries it.
     */
    public static final UUID ALPHA_DEFAULT_TENANT_ID = UUID.fromString("01900000-0000-7000-8000-000000000001");

    /**
     * The reserved platform-operator tenant of ADR-0062 §7. Created by the {@code pos-tenant}
     * bootstrap migration; nothing uses it yet.
     */
    public static final UUID PLATFORM_TENANT_ID = UUID.fromString("01900000-0000-7000-8000-000000000000");

    /** The Postgres session setting every {@code tenant_isolation} policy reads. */
    public static final String TENANT_SETTING = "app.current_tenant";

    private TenancyConstants() {}
}
