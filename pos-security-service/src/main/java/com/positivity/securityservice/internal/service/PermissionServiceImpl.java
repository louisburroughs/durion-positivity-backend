package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.PermissionDto;
import com.positivity.securityservice.internal.dto.PermissionRegistrationRequest;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.service.PermissionService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of permission registration/query API.
 *
 * Issue: #42
 */
@Service
@RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionService {

    private static final Pattern PERMISSION_PATTERN = Pattern.compile("^\\w+:\\w+:\\w+$");

    private final PermissionRepository permissionRepository;

    @Override
    @Transactional
    public List<PermissionDto> registerPermissions(@NonNull PermissionRegistrationRequest request) {
        List<PermissionDto> upsertedPermissions = new ArrayList<>();
        if (request.getPermissions() == null) {
            return upsertedPermissions;
        }

        for (PermissionRegistrationRequest.PermissionDefinition definition : request.getPermissions()) {
            String permissionKey = definition.getName();
            if (!isValidPermissionKey(permissionKey)) {
                throw new IllegalArgumentException("Permission key must match domain:resource:action");
            }

            Permission permission = permissionRepository.findByName(permissionKey).orElseGet(Permission::new);
            permission.setName(permissionKey);
            permission.setDescription(definition.getDescription());
            permission.setRegisteredByService(
                    request.getServiceName() != null ? request.getServiceName() : "pos-security-service");
            permission.setDeprecated(false);
            permission.parsePermissionName();
            Permission saved = permissionRepository.save(permission);
            upsertedPermissions.add(toDto(saved));
        }

        return upsertedPermissions;
    }

    @Override
    @Transactional(readOnly = true)
    public PermissionDto getPermission(@NonNull UUID id) {
        Permission permission = permissionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Permission not found: " + id));
        return toDto(permission);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PermissionDto> getByDomain(@NonNull String domain) {
        return permissionRepository.findByDomain(domain).stream().map(this::toDto).toList();
    }

    private boolean isValidPermissionKey(String permissionKey) {
        return permissionKey != null && PERMISSION_PATTERN.matcher(permissionKey).matches();
    }

    private PermissionDto toDto(Permission permission) {
        return PermissionDto.builder()
                .id(permission.getName())
                .domain(permission.getDomain())
                .description(permission.getDescription())
                .deprecated(permission.isDeprecated())
                .build();
    }
}
