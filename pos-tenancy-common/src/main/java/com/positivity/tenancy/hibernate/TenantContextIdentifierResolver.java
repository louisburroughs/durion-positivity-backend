package com.positivity.tenancy.hibernate;

import com.positivity.tenancy.TenantResolver;
import java.util.Map;
import java.util.UUID;
import org.hibernate.cfg.MultiTenancySettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
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
 *
 * <p>An unbound session resolves to {@link #NO_TENANT}, the nil UUID, rather than {@code null}: once
 * any entity carries {@code @TenantId}, Hibernate refuses to open a session with no tenant at all
 * ("SessionFactory configured for multi-tenancy, but no tenant identifier specified"), which would
 * stop Spring Data deriving its queries at boot and stop every {@code @PlatformScoped} job reading a
 * global table through JPA. The nil tenant is fail-closed on both layers: the Hibernate filter
 * matches no scoped row, a persist stamps a tenant that no row-level-security policy's {@code WITH
 * CHECK} accepts, and the pool has RESET {@code app.current_tenant} so Postgres hides every scoped
 * row anyway. {@link com.positivity.tenancy.TenantResolver#resolve()} still answers empty for an
 * unbound thread; the sentinel exists for Hibernate alone.
 */
public class TenantContextIdentifierResolver
        implements CurrentTenantIdentifierResolver<UUID>, HibernatePropertiesCustomizer {

    private final TenantResolver tenantResolver;

    public TenantContextIdentifierResolver(TenantResolver tenantResolver) {
        this.tenantResolver = tenantResolver;
    }

    /** The tenant an unbound session runs as: the nil UUID, which matches no row and which every policy refuses. */
    public static final UUID NO_TENANT = new UUID(0L, 0L);

    /** The resolved tenant, or {@link #NO_TENANT} for an unbound session. */
    @Override
    public UUID resolveCurrentTenantIdentifier() {
        return tenantResolver.resolve().orElse(NO_TENANT);
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
