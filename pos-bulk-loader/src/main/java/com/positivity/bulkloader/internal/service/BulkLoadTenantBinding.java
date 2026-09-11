package com.positivity.bulkloader.internal.service;

import com.positivity.bulkloader.internal.enums.DomainType;
import com.positivity.bulkloader.internal.exception.BulkLoadTenantException;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantRegistry;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
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
 * {@code roles.csv} / {@code role-permissions.csv}, {@link #PLATFORM_TENANT_DOMAIN_TYPES}). A bound
 * caller may only load into its own tenant — the platform tenant's operator included, who therefore
 * loads platform data and nothing else. A request that names no tenant is refused unless the
 * transitional default tenant ({@code pos.tenancy.default-tenant-id}, ADR-0062 §9) is configured, in
 * which case the default is used and a WARN says so: the job will fail loudly once the default is
 * retired rather than silently landing somewhere.
 *
 * <h2>Why the platform tenant is restricted to specific domain types</h2>
 *
 * <p>{@code scripts/seed-alpha.py} refuses to target the platform tenant for any pack but the two
 * security role packs, but a client-side allow-list enforces nothing on its own: a direct API caller
 * bound to the platform tenant could otherwise create a job of any {@link DomainType} there, and
 * {@code BulkLoadJobServiceImpl} would relay that platform binding into a sibling service that has
 * no idea the tenant is special, writing control-plane data as though it were an ordinary tenant's.
 * This class is what makes the documented restriction (ADR-0062 §7; {@code
 * docs/OPERATIONS_RUNBOOK.md} "Bulk loading into a tenant") true at the server, not only in the
 * driver script (Copilot review of PR #1955, Finding 5).
 *
 * <h2>Why a platform caller may not load into a tenant</h2>
 *
 * <p>A job is tenant-scoped data like everything else the loader writes, and it is owned by the
 * operator who created it. A platform operator who created a job in tenant B could not then use
 * it: upload, process and status run under the request's own binding and operator id, so the
 * platform token — bound to the platform tenant — would not see the job at all (404), and a token
 * of B would see it but fail the ownership check under a different operator id (403). The job
 * would be a row nobody can continue. Refusing the target up front says so once, where the
 * operator can read it, instead of three calls later. Loading into a tenant on that tenant's
 * behalf needs an impersonation path — a credential that is genuinely bound to B — which is a
 * separate decision and not part of WS8; when one lands, this is the single place that reopens.
 *
 * <p>The resolved tenant is what {@code BulkLoadJobServiceImpl} binds ({@code TenantContext.runAs})
 * around the job's create and around the whole batch run, so every row the loader writes and every
 * call it makes to a sibling service lands in that tenant.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BulkLoadTenantBinding {

    /**
     * The only {@link DomainType}s a job may target when it resolves to the platform tenant: the
     * role-template packs {@code security/roles.csv} and {@code security/role-permissions.csv}
     * ({@code docs/OPERATIONS_RUNBOOK.md} "Bulk loading into a tenant"). Every other domain writes
     * data the platform tenant does not own.
     */
    private static final Set<DomainType> PLATFORM_TENANT_DOMAIN_TYPES =
            EnumSet.of(DomainType.SECURITY_ROLE, DomainType.SECURITY_ROLE_PERMISSION);

    private final TenantRegistry tenantRegistry;
    private final TenancyProperties tenancyProperties;

    /**
     * @param requested the request's {@code tenantId}, or null when the request named none
     * @param domainType the job's domain type, checked against {@link #PLATFORM_TENANT_DOMAIN_TYPES}
     *     when the target resolves to the platform tenant
     * @return the tenant to bind the job to
     * @throws BulkLoadTenantException when no tenant can be resolved, the tenant is not one the
     *     module knows as active, the caller may not load into it, or the target is the platform
     *     tenant and {@code domainType} is not one of its packs
     */
    public @NonNull UUID resolveTarget(@Nullable UUID requested, @NonNull DomainType domainType) {
        Optional<UUID> bound = TenantContext.current();
        UUID target = requested != null ? requested : transitionalDefault();
        if (!isLoadable(target)) {
            throw new BulkLoadTenantException(
                    BulkLoadTenantException.TENANT_UNKNOWN,
                    HttpStatus.BAD_REQUEST,
                    "Tenant " + target + " is not an active tenant of this cell; a bulk load must target an active"
                            + " tenant or the platform tenant");
        }
        // Every bound caller, the platform operator included: see the class comment. An unbound
        // caller is the transitional case only (no tid on the token yet, ADR-0062 section 9) and
        // still names its target explicitly.
        if (bound.isPresent() && !bound.get().equals(target)) {
            throw new BulkLoadTenantException(
                    BulkLoadTenantException.TENANT_FORBIDDEN,
                    HttpStatus.FORBIDDEN,
                    "A caller bound to tenant " + bound.get() + " cannot load into tenant " + target
                            + "; a bulk load job runs under the creating caller's own binding and operator, so a job"
                            + " created in another tenant could not be uploaded, processed or polled by anyone");
        }
        if (PlatformTenant.isPlatform(target) && !PLATFORM_TENANT_DOMAIN_TYPES.contains(domainType)) {
            throw new BulkLoadTenantException(
                    BulkLoadTenantException.TENANT_DOMAIN_FORBIDDEN,
                    HttpStatus.FORBIDDEN,
                    "The platform tenant only accepts " + PLATFORM_TENANT_DOMAIN_TYPES + " jobs; " + domainType
                            + " writes data the platform tenant does not own. Target the caller's own tenant"
                            + " instead.");
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
