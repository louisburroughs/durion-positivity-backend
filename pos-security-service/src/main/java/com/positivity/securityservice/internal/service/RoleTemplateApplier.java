package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;

/**
 * How a {@link RoleTemplateEntry} becomes rows of a tenant (ADR-0062 §6), shared by provisioning
 * ({@link TenantProvisioningService}, a fresh tenant) and reconciliation ({@link
 * RoleTemplateReconciliationService}, an existing one) so the two cannot drift: a role missing
 * from the tenant is created from the entry, and a role the tenant already holds gains the grants
 * the template carries that it does not. The permission catalog is global (ADR-0062 §6), so a
 * grant naming a permission the catalog does not know is a template ahead of the registered
 * catalog, not a tenant problem: it is skipped with a WARN.
 */
@Slf4j
final class RoleTemplateApplier {

    private RoleTemplateApplier() {}

    /** A new role of the bound tenant carrying everything the template entry says, grants included. */
    static @NonNull Role fromTemplate(
            @NonNull RoleTemplateEntry entry,
            @NonNull PermissionRepository permissionRepository,
            @NonNull Instant now,
            @NonNull String actor) {
        Role role = new Role();
        role.setName(entry.name());
        role.setDescription(entry.description());
        role.setTemplateKey(entry.templateKey());
        role.setPersonaTitle(entry.personaTitle());
        role.setPersonaFocus(entry.personaFocus());
        role.setPersonaTone(entry.personaTone());
        role.setMcpPersonaRank(entry.mcpPersonaRank());
        role.setMcpPersonaEligible(entry.mcpPersonaEligible());
        role.setLocationScope(entry.locationScope());
        role.setLocationHierarchy(entry.locationHierarchy());
        role.setCreatedAt(now);
        role.setCreatedBy(actor);
        addMissingGrants(role, entry, permissionRepository);
        return role;
    }

    /**
     * Adds to {@code role} every grant of {@code entry} it does not already hold (union, never
     * removal: a tenant-local grant stays).
     *
     * @return the permissions added, in template order; empty when the role already held them all
     */
    static @NonNull Set<Permission> addMissingGrants(
            @NonNull Role role, @NonNull RoleTemplateEntry entry, @NonNull PermissionRepository permissionRepository) {
        Set<String> held =
                role.getPermissions().stream().map(Permission::getName).collect(Collectors.toSet());
        Set<Permission> added = new LinkedHashSet<>();
        for (String permissionName : entry.permissionNames().stream().sorted().toList()) {
            if (held.contains(permissionName)) {
                continue;
            }
            permissionRepository
                    .findByName(permissionName)
                    .ifPresentOrElse(
                            permission -> {
                                role.getPermissions().add(permission);
                                added.add(permission);
                            },
                            () -> log.warn(
                                    "Template role {} grants unknown permission {}; skipped",
                                    entry.name(),
                                    permissionName));
        }
        return added;
    }
}
