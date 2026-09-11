package com.positivity.bulkloader.internal.service;

import com.positivity.bulkloader.internal.exception.BulkLoadTenantException;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantRegistry;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Decides which tenant a bulk-load job belongs to (ADR-0062, plan WS8: a target tenant per job).
 *
 * <p>The target is the request's {@code tenantId}. It must be a tenant the module's {@link
 * TenantRegistry} lists as active, or the platform tenant (platform data: the role template's
 * {@code roles.csv}). A caller bound to a tenant may only load into that tenant; a caller bound
 * to the platform tenant may load into any of them. A request that names no tenant is refused
 * unless the transitional default tenant ({@code pos.tenancy.default-tenant-id}, ADR-0062 §9) is
 * configured, in which case the default is used and a WARN says so: the job will fail loudly
 * once the default is retired rather than silently landing somewhere.
 *
 * <p>The resolved tenant is what {@code BulkLoadJobServiceImpl} binds ({@code TenantContext.runAs})
 * around the job's create and around the whole batch run, so every row the loader writes and every
 * call it makes to a sibling service lands in that tenant.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BulkLoadTenantBinding {

    private final TenantRegistry tenantRegistry;
    private final TenancyProperties tenancyProperties;

    /**
     * @param requested the request's {@code tenantId}, or null when the request named none
     * @return the tenant to bind the job to
     * @throws BulkLoadTenantException when no tenant can be resolved, the tenant is not one the
     *     module knows as active, or the caller may not load into it
     */
    public @NonNull UUID resolveTarget(@Nullable UUID requested) {
        Optional<UUID> bound = TenantContext.current();
        UUID target = requested != null ? requested : transitionalDefault();
        if (!isLoadable(target)) {
            throw new BulkLoadTenantException(
                    BulkLoadTenantException.TENANT_UNKNOWN,
                    HttpStatus.BAD_REQUEST,
                    "Tenant " + target + " is not an active tenant of this cell; a bulk load must target an active"
                            + " tenant or the platform tenant");
        }
        if (bound.isPresent()
                && !PlatformTenant.isPlatform(bound.get())
                && !bound.get().equals(target)) {
            throw new BulkLoadTenantException(
                    BulkLoadTenantException.TENANT_FORBIDDEN,
                    HttpStatus.FORBIDDEN,
                    "A caller bound to tenant " + bound.get() + " cannot load into tenant " + target
                            + "; only a platform-tenant caller loads into another tenant");
        }
        return target;
    }

    private UUID transitionalDefault() {
        UUID fallback = tenancyProperties
                .getDefaultTenantId()
                .orElseThrow(() -> new BulkLoadTenantException(
                        BulkLoadTenantException.TENANT_REQUIRED,
                        HttpStatus.BAD_REQUEST,
                        "A bulk load job must name its target tenant (tenantId)"));
        log.warn(
                "Bulk load job created without a tenantId; using the transitional default tenant {} (ADR-0062"
                        + " section 9). Name the tenant explicitly: this fallback goes away with the default.",
                fallback);
        return fallback;
    }

    private boolean isLoadable(UUID tenantId) {
        return PlatformTenant.isPlatform(tenantId)
                || tenantRegistry.activeTenantIds().contains(tenantId);
    }
}
