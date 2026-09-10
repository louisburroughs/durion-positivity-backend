package com.positivity.tenancy;

import java.util.Optional;
import java.util.UUID;

/**
 * The tenant that infrastructure binds for the work in progress: the thread's {@link
 * TenantContext}, or the transitional default of {@link TenancyProperties} when nothing is bound.
 *
 * <p>The connection binding and the Hibernate resolver read this rather than {@link TenantContext}
 * directly so the transitional default applies uniformly. Once the default is unset (plan WS2b) the
 * two are the same thing and every unbound path fails closed.
 */
public class TenantResolver {

    private final TenancyProperties properties;

    public TenantResolver(TenancyProperties properties) {
        this.properties = properties;
    }

    /** Bound tenant, else the configured default, else empty. */
    public Optional<UUID> resolve() {
        return TenantContext.current().or(properties::getDefaultTenantId);
    }

    /**
     * Like {@link #resolve()} but throws when neither is available.
     *
     * @throws TenantContextMissingException when no tenant can be resolved
     */
    public UUID require() {
        return resolve().orElseThrow(TenantContextMissingException::new);
    }

    /** True while the transitional default is configured. */
    public boolean hasDefault() {
        return properties.getDefaultTenantId().isPresent();
    }
}
