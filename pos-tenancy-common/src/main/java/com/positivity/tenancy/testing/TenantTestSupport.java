package com.positivity.tenancy.testing;

import com.positivity.tenancy.TenantContext;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Shared test helpers (plan R-B7): two fixed tenant ids and {@code asTenant} wrappers, so every
 * module's isolation test reads the same way. Lives in the main jar on purpose: test-jar plumbing
 * is not worth a three-method class.
 */
public final class TenantTestSupport {

    /** The alpha default tenant, which every seed row belongs to. */
    public static final UUID TENANT_A = UUID.fromString("01900000-0000-7000-8000-000000000001");

    /** A second tenant that owns nothing seeded. */
    public static final UUID TENANT_B = UUID.fromString("01900000-0000-7000-8000-000000000002");

    private TenantTestSupport() {}

    public static void asTenant(UUID tenantId, Runnable work) {
        TenantContext.runAs(tenantId, work);
    }

    public static <T> T asTenant(UUID tenantId, Callable<T> work) {
        return TenantContext.callAs(tenantId, work);
    }
}
