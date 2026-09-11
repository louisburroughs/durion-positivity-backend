package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.entity.ExtTenant;
import com.positivity.securityservice.internal.repository.ExtTenantRepository;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantRegistry;
import com.positivity.tenancy.replica.TenantProjectionEvent;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * The module's {@link TenantRegistry}, backed by the {@code ext_tenant} replica (ADR-0062 §8):
 * per-tenant scheduled work visits every {@code ACTIVE} tenant the registry owner has published.
 * Replaces the transitional {@code pos.tenancy.tenants} list for this module.
 */
@Component
@RequiredArgsConstructor
public class ExtTenantRegistry implements TenantRegistry {

    private final ExtTenantRepository extTenantRepository;

    /**
     * {@inheritDoc}
     *
     * <p>Filters {@link PlatformTenant#ID} out of the replica ({@link
     * TenantRegistry#activeTenantIds()} forbids it), the same way {@code StaticTenantRegistry}
     * filters it out of its configured list and {@code RemoteTenantRegistry} out of every fetched
     * one. The replica seeds the platform tenant as {@code ACTIVE} on purpose — slug resolution and
     * the platform bootstrap read that row directly, so it stays in the table — but it is
     * control-plane data owned by {@code pos-tenant} (ADR-0062 §7), and no {@code TenantIterator}
     * sweep in this module may run a tenant job under it; {@code @PlatformScoped} exists precisely
     * so platform work is swept separately.
     */
    @Override
    public @NonNull List<UUID> activeTenantIds() {
        return extTenantRepository.findByStatusOrderByTenantIdAsc(TenantProjectionEvent.STATUS_ACTIVE).stream()
                .map(ExtTenant::getTenantId)
                .filter(tenantId -> !PlatformTenant.ID.equals(tenantId))
                .toList();
    }
}
