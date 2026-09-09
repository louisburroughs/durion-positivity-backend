package com.positivity.tenancy;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the tenancy binding (ADR-0062). */
@ConfigurationProperties(prefix = "pos.tenancy")
public class TenancyProperties {

    /** Whether to wrap the module's datasource so it binds app.current_tenant per checkout. */
    private boolean enabled = true;

    /**
     * Tenant bound when the thread carries none. Unset means fail closed: scoped tables read as
     * empty and refuse inserts.
     *
     * <p>Transitional. The access token carries no {@code tid} claim until plan WS2b, so nothing
     * can derive a per-request tenant yet; deployments that serve the single alpha tenant set this
     * to {@code 01900000-0000-7000-8000-000000000001}. Remove it when the claim ships.
     */
    private @Nullable UUID fallbackTenantId;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public @Nullable UUID getFallbackTenantId() {
        return fallbackTenantId;
    }

    public void setFallbackTenantId(@Nullable UUID fallbackTenantId) {
        this.fallbackTenantId = fallbackTenantId;
    }
}
