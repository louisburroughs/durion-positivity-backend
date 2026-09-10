package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the platform role template (ADR-0062 §6): the platform tenant's roles that carry a
 * {@code template_key}, seeded by {@code R__seed_tenant_template.sql}. The caller binds the
 * platform tenant; the snapshot is detached so it can then be applied under the new tenant's
 * binding by {@link TenantProvisioningService}.
 */
@Service
@RequiredArgsConstructor
public class RoleTemplateService {

    private final RoleRepository roleRepository;

    /**
     * @throws IllegalStateException when the platform tenant is not the bound tenant: the template
     *     is platform data, and any other binding would copy a tenant's own roles instead
     */
    @Transactional(readOnly = true)
    public @NonNull List<RoleTemplateEntry> snapshot() {
        if (!PlatformTenant.isPlatform(TenantContext.require())) {
            throw new IllegalStateException("The role template is read under the platform tenant only");
        }
        return roleRepository.findByTemplateKeyIsNotNullOrderByNameAsc().stream()
                .map(RoleTemplateService::entry)
                .toList();
    }

    private static RoleTemplateEntry entry(Role role) {
        Set<String> permissions =
                role.getPermissions().stream().map(Permission::getName).collect(Collectors.toUnmodifiableSet());
        return new RoleTemplateEntry(
                role.getTemplateKey(),
                role.getName(),
                role.getDescription(),
                role.getPersonaTitle(),
                role.getPersonaFocus(),
                role.getPersonaTone(),
                role.getMcpPersonaRank(),
                role.isMcpPersonaEligible(),
                role.getLocationScope(),
                role.getLocationHierarchy(),
                permissions);
    }
}
