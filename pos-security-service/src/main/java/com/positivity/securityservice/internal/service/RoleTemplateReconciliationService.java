package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.RoleTemplateReconcileResponse;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.exception.PlatformTenantRequiredException;
import com.positivity.securityservice.internal.exception.TenantNotFoundException;
import com.positivity.securityservice.internal.repository.ExtTenantRepository;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code reconcileTemplate(tenant)} (ADR-0062 §6, plan WS8, decided 2026-09-10): brings an
 * existing tenant up to the platform role template. Provisioning copies the template once, on
 * {@code tenant.created}; a role added to the template later (a platform bulk load of {@code
 * roles.csv}, a new grant on a template role) reaches tenants provisioned before it through this
 * service.
 *
 * <p>Per template role: missing in the tenant, it is created exactly as provisioning would have;
 * present, it keeps its tenant-local grants and gains any grant the template carries that it does
 * not (union, never removal), and it gets {@code template_key} when it had none. Description,
 * persona and location scope of an existing role are left alone: they may be tenant edits.
 * Idempotent: a second run changes nothing and reports empty lists.
 *
 * <p>Tenant bindings: the caller holds the platform binding (any other is refused with 403
 * {@code PLATFORM_TENANT_REQUIRED}), the template is read under it, and the target tenant is
 * rebound <em>around</em> the transaction in {@link BoundOperations}, because the Hibernate
 * session fixes its tenant at open time.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleTemplateReconciliationService {

    /** Actor recorded on the rows reconciliation writes. */
    static final String ACTOR = "role-template-reconcile";

    private final RoleTemplateService roleTemplateService;
    private final ExtTenantRepository extTenantRepository;
    private final BoundOperations boundOperations;

    /**
     * @param tenantId the tenant to bring up to the template
     * @throws PlatformTenantRequiredException when the caller is not bound to the platform tenant
     * @throws TenantNotFoundException when the {@code ext_tenant} replica does not hold the tenant
     */
    public @NonNull RoleTemplateReconcileResponse reconcile(@NonNull UUID tenantId) {
        UUID bound = TenantContext.current().orElse(null);
        if (!PlatformTenant.isPlatform(bound)) {
            throw new PlatformTenantRequiredException(bound);
        }
        if (!PlatformTenant.isPlatform(tenantId) && !extTenantRepository.existsById(tenantId)) {
            throw new TenantNotFoundException(tenantId);
        }
        List<RoleTemplateEntry> template = roleTemplateService.snapshot();
        RoleTemplateReconcileResponse outcome =
                TenantContext.callAs(tenantId, () -> boundOperations.apply(tenantId, template));
        log.info(
                "Reconciled the role template into tenant {}: {} role(s) created {}, {} grant(s) added {}, {} role(s)"
                        + " marked as template {}",
                tenantId,
                outcome.rolesCreated().size(),
                outcome.rolesCreated(),
                outcome.grantsAdded().size(),
                outcome.grantsAdded(),
                outcome.templateKeysAssigned().size(),
                outcome.templateKeysAssigned());
        return outcome;
    }

    /**
     * The transactional half, a separate bean so the {@code @Transactional} proxy is honoured when
     * the outer service calls it from inside the tenant rebind.
     */
    @Slf4j
    @Component
    @RequiredArgsConstructor
    public static class BoundOperations {

        private final RoleRepository roleRepository;
        private final PermissionRepository permissionRepository;
        private final Clock clock;

        /** Under the target tenant's binding: every read and write here is that tenant's. */
        @Transactional
        public @NonNull RoleTemplateReconcileResponse apply(
                @NonNull UUID tenantId, @NonNull List<RoleTemplateEntry> template) {
            Instant now = Instant.now(clock);
            List<String> rolesCreated = new ArrayList<>();
            List<RoleTemplateReconcileResponse.GrantAdded> grantsAdded = new ArrayList<>();
            List<String> templateKeysAssigned = new ArrayList<>();

            for (RoleTemplateEntry entry : template) {
                Optional<Role> existing = roleRepository.findByName(entry.name());
                if (existing.isEmpty()) {
                    roleRepository.save(RoleTemplateApplier.fromTemplate(entry, permissionRepository, now, ACTOR));
                    rolesCreated.add(entry.name());
                    continue;
                }
                Role role = existing.get();
                boolean changed = false;
                if (role.getTemplateKey() == null) {
                    role.setTemplateKey(entry.templateKey());
                    templateKeysAssigned.add(role.getName());
                    changed = true;
                }
                Set<Permission> added = RoleTemplateApplier.addMissingGrants(role, entry, permissionRepository);
                if (!added.isEmpty()) {
                    added.forEach(permission -> grantsAdded.add(
                            new RoleTemplateReconcileResponse.GrantAdded(role.getName(), permission.getName())));
                    changed = true;
                }
                if (changed) {
                    role.setLastModifiedAt(now);
                    role.setLastModifiedBy(ACTOR);
                    Role saved = roleRepository.save(role);
                    if (!added.isEmpty()) {
                        roleRepository.recordGrantProvenance(
                                saved.getId(),
                                added.stream().map(Permission::getId).toList(),
                                ACTOR,
                                now);
                    }
                }
            }
            return new RoleTemplateReconcileResponse(tenantId, rolesCreated, grantsAdded, templateKeysAssigned);
        }
    }
}
