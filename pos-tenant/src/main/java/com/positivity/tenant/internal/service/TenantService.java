package com.positivity.tenant.internal.service;

import com.positivity.tenant.internal.dto.TenantCreateRequest;
import com.positivity.tenant.internal.dto.TenantResponse;
import com.positivity.tenant.internal.dto.TenantUpdateRequest;
import com.positivity.tenant.internal.enums.TenantStatus;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** The tenant registry and its status machine (ADR-0062 §7). */
public interface TenantService {

    /** Register a tenant as {@code PENDING} and publish {@code tenant.created}. */
    @NonNull
    TenantResponse create(@NonNull TenantCreateRequest request);

    @NonNull
    TenantResponse get(@NonNull UUID tenantId);

    /** Every tenant, optionally filtered by status, oldest first. */
    @NonNull
    List<TenantResponse> list(@Nullable TenantStatus status);

    /** Change display name or cell; publishes {@code tenant.updated}. */
    @NonNull
    TenantResponse update(@NonNull UUID tenantId, @NonNull TenantUpdateRequest request);

    /** {@code ACTIVE} to {@code SUSPENDED}; publishes {@code tenant.suspended}. */
    @NonNull
    TenantResponse suspend(@NonNull UUID tenantId);

    /** {@code SUSPENDED} to {@code ACTIVE}; publishes {@code tenant.reactivated}. */
    @NonNull
    TenantResponse reactivate(@NonNull UUID tenantId);

    /** Any state to the terminal {@code DECOMMISSIONED}; publishes {@code tenant.decommissioned}. */
    @NonNull
    TenantResponse decommission(@NonNull UUID tenantId);

    /**
     * Handle {@code tenant.provisioned} from pos-security-service: {@code PENDING} to {@code
     * ACTIVE}, publishing {@code tenant.updated}. Idempotent: any other current status is left
     * untouched, and an unknown tenant id is logged and ignored.
     */
    void markProvisioned(@NonNull UUID tenantId);
}
