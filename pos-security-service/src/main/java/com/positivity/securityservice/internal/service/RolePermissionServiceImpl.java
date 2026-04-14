package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.PermissionDto;
import com.positivity.securityservice.internal.dto.RoleDto;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.PrincipalRole;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.event.RolePermissionGrantAuditEvent;
import com.positivity.securityservice.internal.exception.RoleNotFoundException;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.internal.repository.PrincipalRoleRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.service.RolePermissionService;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for role-permission and principal-role mutation operations.
 *
 * Issue: #42
 */
@Service
@RequiredArgsConstructor
public class RolePermissionServiceImpl implements RolePermissionService {
    private final Clock clock;

    private static final String ROLE_NOT_FOUND_PREFIX = "Role not found: ";

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final PrincipalRoleRepository principalRoleRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    @Transactional
    public RoleDto createRole(@NonNull String roleName, String description) {
        if (roleRepository.existsByName(roleName)) {
            throw new IllegalArgumentException("Role already exists: " + roleName);
        }
        Role role = new Role();
        role.setName(roleName);
        role.setDescription(description);
        role.setCreatedBy(getCurrentActor());
        return toRoleDto(roleRepository.save(role));
    }

    @Override
    @Transactional
    public RoleDto grantPermission(@NonNull UUID roleId, @NonNull String permissionKey) {
        Role role = roleRepository
                .findById(roleId)
                .orElseThrow(() -> new RoleNotFoundException(ROLE_NOT_FOUND_PREFIX + roleId));

        Permission permission = permissionRepository.findByName(permissionKey).orElseGet(() -> {
            Permission created = new Permission();
            created.setName(permissionKey);
            created.setDescription("Auto-registered during role grant");
            created.setRegisteredByService("pos-security-service");
            created.setDeprecated(false);
            created.parsePermissionName();
            return permissionRepository.save(created);
        });

        role.getPermissions().add(permission);
        Role savedRole = roleRepository.save(role);

        applicationEventPublisher.publishEvent(
                new RolePermissionGrantAuditEvent(this, getCurrentActor(), roleId, permissionKey, Instant.now(clock)));

        return toRoleDto(savedRole);
    }

    @Override
    @Transactional
    public RoleDto revokePermission(@NonNull UUID roleId, @NonNull String permissionKey) {
        Role role = roleRepository
                .findById(roleId)
                .orElseThrow(() -> new RoleNotFoundException(ROLE_NOT_FOUND_PREFIX + roleId));
        role.getPermissions().removeIf(permission -> permissionKey.equals(permission.getName()));
        return toRoleDto(roleRepository.save(role));
    }

    @Override
    @Transactional
    public void assignRoleToPrincipal(@NonNull String principalId, @NonNull UUID roleId) {
        if (principalRoleRepository.existsByPrincipalIdAndRole_Id(principalId, roleId)) {
            return;
        }

        Role role = roleRepository
                .findById(roleId)
                .orElseThrow(() -> new RoleNotFoundException(ROLE_NOT_FOUND_PREFIX + roleId));

        PrincipalRole principalRole = new PrincipalRole();
        principalRole.setPrincipalId(principalId);
        principalRole.setRole(role);
        principalRoleRepository.save(principalRole);
    }

    private String getCurrentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && authentication.getName() != null) {
            return authentication.getName();
        }
        return "system";
    }

    private RoleDto toRoleDto(Role role) {
        return RoleDto.builder()
                .id(role.getId())
                .name(role.getName())
                .description(role.getDescription())
                .permissions(role.getPermissions().stream()
                        .map(this::toPermissionDto)
                        .collect(Collectors.toSet()))
                .createdAt(role.getCreatedAt())
                .createdBy(role.getCreatedBy())
                .lastModifiedAt(role.getLastModifiedAt())
                .lastModifiedBy(role.getLastModifiedBy())
                .build();
    }

    private PermissionDto toPermissionDto(Permission permission) {
        return PermissionDto.builder()
                .id(permission.getId())
                .name(permission.getName())
                .domain(permission.getDomain())
                .description(permission.getDescription())
                .deprecated(permission.isDeprecated())
                .build();
    }
}
