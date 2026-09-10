package com.positivity.tenancy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pos.tenancy.*} configuration.
 *
 * <p>{@link #getDefaultTenantId()} is the transitional single-tenant binding of ADR-0062 §9: while
 * the gateway does not yet inject {@code X-Tenant-Id} (plan WS2b) and producers do not yet stamp the
 * Kafka header, every unbound path (request, record, scheduler, connection) falls back to it. Unset
 * it and the runtime is strict: an unbound request is a 401, an unbound record is rejected, and an
 * unbound connection carries no tenant so RLS hides every scoped row.
 */
@ConfigurationProperties(prefix = "pos.tenancy")
public class TenancyProperties {

    private @Nullable UUID defaultTenantId;

    /**
     * Refuse unbound work when no tenant resolves: a request without {@code X-Tenant-Id} is a 401
     * and a record without the tenant header is rejected. Only observable once {@link
     * #getDefaultTenantId()} is unset; kept as a switch so a module can be stepped to strict mode
     * deliberately rather than by removing the default.
     */
    private boolean enforce = true;

    /** Tenants a {@link TenantIterator} visits until the {@code ext_tenant} replica exists (plan WS2a). */
    private List<UUID> tenants = new ArrayList<>();

    private final Datasource datasource = new Datasource();

    public Optional<UUID> getDefaultTenantId() {
        return Optional.ofNullable(defaultTenantId);
    }

    public void setDefaultTenantId(@Nullable UUID defaultTenantId) {
        this.defaultTenantId = defaultTenantId;
    }

    public boolean isEnforce() {
        return enforce;
    }

    public void setEnforce(boolean enforce) {
        this.enforce = enforce;
    }

    public List<UUID> getTenants() {
        return tenants;
    }

    public void setTenants(List<UUID> tenants) {
        this.tenants = tenants;
    }

    public Datasource getDatasource() {
        return datasource;
    }

    /** {@code pos.tenancy.datasource.*}. */
    public static class Datasource {

        /** Wrap the module's {@code DataSource} so every checkout binds {@code app.current_tenant}. */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
