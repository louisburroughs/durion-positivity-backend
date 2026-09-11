package com.positivity.mcp.tenancy;

import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.testing.TenantTestSupport;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Binds {@link TenantTestSupport#TENANT_A} to the test thread for every test and clears it after
 * (ADR-0062 plan WS6). The session and chat paths call {@code TenantContext.require()}, the way a
 * request arrives bound by {@code TenantContextFilter}; a unit test that drives them directly binds
 * the tenant the same way with {@code @ExtendWith(BoundTenant.class)}.
 */
public final class BoundTenant implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        TenantContext.bind(TenantTestSupport.TENANT_A);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        TenantContext.clear();
    }
}
