package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.PermissionDto;
import com.positivity.securityservice.internal.dto.PermissionRegistrationRequest;
import com.positivity.securityservice.internal.dto.PermissionRegistrationResponse;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.service.PermissionRegistryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Service for managing the central permission registry.
 * Handles permission registration, validation, and querying.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PermissionRegistryServiceImpl implements PermissionRegistryService {

    public record ProcessingCounters(int registered, int updated, int skipped) {
    }

    private final PermissionRepository permissionRepository;

    /**
     * Pattern for validating permission names: domain:resource:action
     * All lowercase, alphanumeric with underscores.
     */
    public static final Pattern PERMISSION_PATTERN = Pattern
            .compile("^[a-z][a-z0-9_]*:[a-z][a-z0-9_]*:[a-z][a-z0-9_]*$");

    /**
     * Register or update permissions from a service manifest.
     */
    @Override
    @Transactional
    public PermissionRegistrationResponse registerPermissions(PermissionRegistrationRequest request) {
        log.info("Registering permissions for domain: {}, service: {}",
                request.getDomain(), request.getServiceName());

        List<String> errors = new ArrayList<>();
        ProcessingCounters counters = new ProcessingCounters(0, 0, 0);

        for (PermissionRegistrationRequest.PermissionDefinition permDef : request.getPermissions()) {
            try {
                if (!isValidPermissionName(permDef.getName())) {
                    errors.add("Invalid permission name format: " + permDef.getName() +
                            " (must be lowercase domain:resource:action)");
                    counters = new ProcessingCounters(counters.registered(), counters.updated(),
                            counters.skipped() + 1);
                    continue;
                }

                Optional<Permission> existingOpt = permissionRepository.findByName(permDef.getName());

                if (existingOpt.isPresent()) {
                    Permission existing = existingOpt.get();
                    if (!existing.getDescription().equals(permDef.getDescription())) {
                        existing.setDescription(permDef.getDescription());
                        existing.setRegisteredByService(request.getServiceName());
                        permissionRepository.save(existing);
                        counters = new ProcessingCounters(counters.registered(), counters.updated() + 1,
                                counters.skipped());
                        log.debug("Updated permission: {}", permDef.getName());
                    } else {
                        counters = new ProcessingCounters(counters.registered(), counters.updated(),
                                counters.skipped() + 1);
                    }
                } else {
                    counters = registerNewPermission(request, permDef, errors, counters);
                }
            } catch (Exception e) {
                errors.add("Error processing permission " + permDef.getName() + ": " + e.getMessage());
                counters = new ProcessingCounters(counters.registered(), counters.updated(), counters.skipped() + 1);
                log.error("Error processing permission: {}", permDef.getName(), e);
            }
        }

        boolean success = errors.isEmpty() || (counters.registered() + counters.updated()) > 0;
        String message = String.format("Processed %d permissions: %d registered, %d updated, %d skipped",
                request.getPermissions().size(), counters.registered(), counters.updated(), counters.skipped());

        return PermissionRegistrationResponse.builder()
                .success(success)
                .message(message)
                .totalPermissions(request.getPermissions().size())
                .registeredPermissions(counters.registered())
                .updatedPermissions(counters.updated())
                .skippedPermissions(counters.skipped())
                .errors(errors)
                .build();
    }

    private ProcessingCounters registerNewPermission(
            PermissionRegistrationRequest request,
            PermissionRegistrationRequest.PermissionDefinition permDef,
            List<String> errors,
            ProcessingCounters counters) {
        Permission permission = buildPermission(request, permDef);
        try {
            permission.parsePermissionName();
            permissionRepository.save(permission);
            log.debug("Registered new permission: {}", permDef.getName());
            return new ProcessingCounters(counters.registered() + 1, counters.updated(), counters.skipped());
        } catch (IllegalArgumentException e) {
            errors.add("Failed to parse permission " + permDef.getName() + ": " + e.getMessage());
            return new ProcessingCounters(counters.registered(), counters.updated(), counters.skipped() + 1);
        }
    }

    private Permission buildPermission(
            PermissionRegistrationRequest request,
            PermissionRegistrationRequest.PermissionDefinition permDef) {
        Permission permission = new Permission();
        permission.setName(permDef.getName());
        permission.setDescription(permDef.getDescription());
        permission.setRegisteredByService(request.getServiceName());
        permission.setRegisteredAt(Instant.now());
        return permission;
    }

    /**
     * Validate permission name format.
     */
    @Override
    public boolean isValidPermissionName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        return PERMISSION_PATTERN.matcher(name).matches();
    }

    /**
     * Get all permissions for a domain.
     */
    @Override
    public List<PermissionDto> getPermissionsByDomain(String domain) {
        return permissionRepository.findByDomain(domain).stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Get all registered permissions.
     */
    @Override
    public List<PermissionDto> getAllPermissions() {
        return permissionRepository.findAll().stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Check if a permission exists.
     */
    @Override
    public boolean permissionExists(String permissionName) {
        return permissionRepository.existsByName(permissionName);
    }

    /**
     * Get permission by name.
     */
    @Override
    public Optional<PermissionDto> getPermissionByName(String name) {
        return permissionRepository.findByName(name).map(this::toDto);
    }

    private PermissionDto toDto(Permission permission) {
        return PermissionDto.builder()
                .id(permission.getId())
                .name(permission.getName())
                .domain(permission.getDomain())
                .description(permission.getDescription())
                .deprecated(permission.isDeprecated())
                .build();
    }
}
