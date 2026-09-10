package com.positivity.tenancy.hibernate;

import com.positivity.tenancy.TenantResolver;
import java.util.Map;
import java.util.UUID;
import org.hibernate.cfg.MultiTenancySettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;

/**
 * Feeds the bound tenant to Hibernate's {@code @TenantId} discriminator (ADR-0062 §3, layer 4).
 *
 * <p>Registered through {@link HibernatePropertiesCustomizer} because {@code spring-boot-hibernate}
 * does not auto-register a resolver bean (verified against 4.1.1). With it in place Hibernate stamps
 * {@code tenant_id} on persist, appends {@code tenant_id = ?} to derived and JPQL queries including
 * load-by-key, and rejects an update whose row belongs to another tenant.
 *
 * <p>{@link #isRoot} is never {@code true}: it is a Hibernate-level bypass, and row-level security
 * would still hide the rows, so the two layers would disagree. An ArchUnit rule pins that.
 */
public class TenantContextIdentifierResolver
        implements CurrentTenantIdentifierResolver<UUID>, HibernatePropertiesCustomizer {

    private final TenantResolver tenantResolver;

    public TenantContextIdentifierResolver(TenantResolver tenantResolver) {
        this.tenantResolver = tenantResolver;
    }

    /** The resolved tenant, or {@code null} so an unbound session inserts nothing (the column is NOT NULL). */
    @Override
    public @Nullable UUID resolveCurrentTenantIdentifier() {
        return tenantResolver.resolve().orElse(null);
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }

    @Override
    public boolean isRoot(UUID tenantId) {
        return false;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
    }
}
