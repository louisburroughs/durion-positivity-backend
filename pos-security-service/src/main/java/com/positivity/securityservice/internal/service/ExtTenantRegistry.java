package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.entity.ExtTenant;
import com.positivity.securityservice.internal.repository.ExtTenantRepository;
import com.positivity.tenancy.TenantRegistry;
import com.positivity.tenancy.replica.TenantProjectionEvent;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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

    @Override
    public List<UUID> activeTenantIds() {
        return extTenantRepository.findByStatusOrderByTenantIdAsc(TenantProjectionEvent.STATUS_ACTIVE).stream()
                .map(ExtTenant::getTenantId)
                .toList();
    }
}
