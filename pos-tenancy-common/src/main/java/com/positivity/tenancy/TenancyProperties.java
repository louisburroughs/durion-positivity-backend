package com.positivity.tenancy;

import java.time.Duration;
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

    /**
     * Tenants a {@link TenantIterator} visits in {@link Registry.Mode#STATIC} mode, and the snapshot
     * a {@link RemoteTenantRegistry} starts from before its first successful fetch.
     */
    private List<UUID> tenants = new ArrayList<>();

    /**
     * Request path prefixes the {@code TenantContextFilter} never refuses for lack of a tenant, on
     * top of the infrastructure paths it always exempts (actuator, OpenAPI). A module that binds the
     * tenant itself on some paths lists them here: pos-security-service resolves the tenant for
     * {@code /v1/auth/**} from the login slug or the refresh token's {@code tid} claim (plan WS2b).
     * A tenant header on such a path is still bound when present.
     */
    private List<String> unenforcedPaths = new ArrayList<>();

    private final Datasource datasource = new Datasource();

    private final Registry registry = new Registry();

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

    public List<String> getUnenforcedPaths() {
        return unenforcedPaths;
    }

    public void setUnenforcedPaths(List<String> unenforcedPaths) {
        // Null is "nothing exempt": the filter stays fail-closed rather than failing on the list.
        this.unenforcedPaths = unenforcedPaths == null ? new ArrayList<>() : unenforcedPaths;
    }

    public Datasource getDatasource() {
        return datasource;
    }

    public Registry getRegistry() {
        return registry;
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

    /**
     * {@code pos.tenancy.registry.*}: where {@link TenantRegistry} gets the active tenants from
     * (plan WS4-2, decided 2026-09-10).
     */
    public static class Registry {

        /** Source of the tenant list. */
        public enum Mode {
            /** {@link StaticTenantRegistry}: {@code pos.tenancy.tenants}, else the default tenant. */
            STATIC,
            /**
             * {@link RemoteTenantRegistry}: a cached, shared-secret lookup against {@code pos-tenant}'s
             * internal list endpoint, starting from the static list.
             */
            REMOTE
        }

        private Mode mode = Mode.STATIC;

        /**
         * Full URL of {@code pos-tenant}'s internal list endpoint, e.g. {@code
         * http://tenant/internal/v1/tenants}. A service-name host is resolved by the module's
         * {@code @LoadBalanced RestClient.Builder} when it declares one; otherwise the URL is used as
         * is.
         */
        private String url = "http://tenant/internal/v1/tenants";

        /** Shared secret sent as {@code X-Tenant-Registry-Secret}; {@code pos.tenant.registry.api-secret} on the server. */
        private String secret = "";

        /** The snapshot is refreshed at most this often, lazily on read. */
        private Duration refresh = Duration.ofSeconds(60);

        private Duration connectTimeout = Duration.ofSeconds(2);

        private Duration readTimeout = Duration.ofSeconds(5);

        public Mode getMode() {
            return mode;
        }

        public void setMode(Mode mode) {
            this.mode = mode;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(@Nullable String secret) {
            this.secret = secret == null ? "" : secret;
        }

        public Duration getRefresh() {
            return refresh;
        }

        public void setRefresh(Duration refresh) {
            this.refresh = refresh;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }
}
