package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.TenantMeResponse;
import com.positivity.securityservice.internal.repository.ExtTenantRepository;
import com.positivity.tenancy.TenantContext;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the bound tenant out of the {@code ext_tenant} replica (ADR-0062 §7).
 *
 * <p>Empty when the replica has not yet consumed the tenant's {@code tenant.created} fact; the
 * token still names a real tenant, the projection is simply behind.
 */
@Service
@RequiredArgsConstructor
public class TenantQueryService {

    private final ExtTenantRepository extTenantRepository;

    @Transactional(readOnly = true)
    public Optional<TenantMeResponse> currentTenant() {
        return extTenantRepository
                .findById(TenantContext.require())
                .map(tenant -> new TenantMeResponse(
                        tenant.getTenantId(), tenant.getSlug(), tenant.getDisplayName(), tenant.getStatus()));
    }
}
