package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.service.RoleAuthorityService;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves role authorities from persisted {@code role_permissions} grants.
 *
 * <p>This is the single authorization model for the platform. Authorities travel to
 * downstream services as {@code perm_bits} in the JWT (encoded by {@code JwtServiceImpl},
 * decoded by pos-api-gateway into {@code X-Authorities}) where they satisfy
 * {@code @PreAuthorize("hasAuthority('crm:party:view')")}-style checks. Every one of those
 * authorities originates here, and therefore from the database:
 *
 * <pre>
 *   users -&gt; user_roles -&gt; roles -&gt; role_permissions -&gt; permissions
 * </pre>
 *
 * <p>Grants are provisioned by the {@code R__seed_role_permissions.sql} baseline and by the
 * role-permission admin API ({@code PUT /v1/roles/{roleId}/permissions/...}). Earlier
 * revisions expanded roles through a compiled switch statement in this class; that map is
 * retired, so adding a capability to a role is a data change and never a code change.
 */
@Service
@RequiredArgsConstructor
public class RoleAuthorityServiceImpl implements RoleAuthorityService {

    private final RoleRepository roleRepository;

    @Override
    @Transactional(readOnly = true)
    public Set<String> expandRolesToAuthorities(Set<String> roles) {
        Set<String> authorities = new HashSet<>();
        if (roles == null || roles.isEmpty()) {
            return authorities;
        }

        Set<String> roleNames = new HashSet<>();
        for (String role : roles) {
            if (role == null || role.isBlank()) {
                continue;
            }
            String normalized = normalizeRole(role);
            roleNames.add(normalized);
            authorities.add(ROLE_PREFIX + normalized);
        }

        if (roleNames.isEmpty()) {
            return authorities;
        }

        // Unknown roles and roles without grants simply contribute nothing here, which is
        // what makes an ungranted user fail closed rather than inherit a default bundle.
        authorities.addAll(roleRepository.findPermissionNamesByRoleNames(roleNames));
        return authorities;
    }

    private String normalizeRole(String role) {
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith(ROLE_PREFIX) ? normalized.substring(ROLE_PREFIX.length()) : normalized;
    }
}
