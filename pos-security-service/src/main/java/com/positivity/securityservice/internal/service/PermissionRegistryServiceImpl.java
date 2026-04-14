package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.PermissionDto;
import com.positivity.securityservice.internal.dto.PermissionRegistrationRequest;
import com.positivity.securityservice.internal.dto.PermissionRegistrationResponse;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.enums.PermissionCode;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.service.PermissionRegistryService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for managing the central permission registry.
 * Handles permission registration, validation, and querying.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PermissionRegistryServiceImpl implements PermissionRegistryService {
    private final Clock clock;

    public record ProcessingCounters(int registered, int updated, int skipped) {}

    private final PermissionRepository permissionRepository;

    /**
     * Pattern for validating permission names: domain:resource:action (3-segment)
     * or domain:action (2-segment legacy format).
     * All lowercase, alphanumeric with underscores and hyphens.
     */
    public static final Pattern PERMISSION_PATTERN =
            Pattern.compile("^[a-z][a-z0-9_-]*:[a-z][a-z0-9_-]*(:[a-z][a-z0-9_-]*)?$");

    /**
     * Register or update permissions from a service manifest.
     */
    @Override
    @Transactional
    public PermissionRegistrationResponse registerPermissions(@NonNull PermissionRegistrationRequest request) {
        validateRegistrationRequest(request);

        log.info("Registering permissions for domain: {}, service: {}", request.getDomain(), request.getServiceName());

        List<String> errors = new ArrayList<>();
        ProcessingCounters counters = new ProcessingCounters(0, 0, 0);

        for (PermissionRegistrationRequest.PermissionDefinition permDef : request.getPermissions()) {
            counters = processPermissionDefinition(request, permDef, errors, counters);
        }

        boolean success = errors.isEmpty() || (counters.registered() + counters.updated()) > 0;
        String message = String.format(
                "Processed %d permissions: %d registered, %d updated, %d skipped",
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

    private void validateRegistrationRequest(PermissionRegistrationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        if (request.getServiceName() == null || request.getServiceName().isBlank()) {
            throw new IllegalArgumentException("serviceName is required");
        }
        if (request.getPermissions() == null || request.getPermissions().isEmpty()) {
            throw new IllegalArgumentException("permissions must not be empty");
        }
    }

    private ProcessingCounters processPermissionDefinition(
            PermissionRegistrationRequest request,
            PermissionRegistrationRequest.PermissionDefinition permDef,
            List<String> errors,
            ProcessingCounters counters) {
        try {
            if (!isValidPermissionName(permDef.getName())) {
                return skipInvalidPermission(permDef, errors, counters);
            }

            Optional<Permission> existingOpt = permissionRepository.findByName(permDef.getName());
            if (existingOpt.isPresent()) {
                return updateExistingPermission(request, permDef, existingOpt.get(), counters);
            }

            return registerNewPermission(request, permDef, errors, counters);
        } catch (Exception e) {
            return handlePermissionProcessingError(permDef, errors, counters, e);
        }
    }

    private ProcessingCounters skipInvalidPermission(
            PermissionRegistrationRequest.PermissionDefinition permDef,
            List<String> errors,
            ProcessingCounters counters) {
        errors.add(
                "Invalid permission name format: " + permDef.getName()
                        + " (must match lowercase 'domain:resource:action' or legacy 'domain:action' format; segments may include hyphens)");
        return incrementSkipped(counters);
    }

    private ProcessingCounters updateExistingPermission(
            PermissionRegistrationRequest request,
            PermissionRegistrationRequest.PermissionDefinition permDef,
            Permission existing,
            ProcessingCounters counters) {
        if (!Objects.equals(existing.getDescription(), permDef.getDescription())) {
            existing.setDescription(permDef.getDescription());
            existing.setRegisteredByService(request.getServiceName());
            permissionRepository.save(existing);
            log.debug("Updated permission: {}", permDef.getName());
            return incrementUpdated(counters);
        }

        return incrementSkipped(counters);
    }

    private ProcessingCounters handlePermissionProcessingError(
            PermissionRegistrationRequest.PermissionDefinition permDef,
            List<String> errors,
            ProcessingCounters counters,
            Exception exception) {
        errors.add("Error processing permission " + permDef.getName() + ": " + exception.getMessage());
        log.error("Error processing permission: {}", permDef.getName(), exception);
        return incrementSkipped(counters);
    }

    private ProcessingCounters registerNewPermission(
            PermissionRegistrationRequest request,
            PermissionRegistrationRequest.PermissionDefinition permDef,
            List<String> errors,
            ProcessingCounters counters) {
        // First, handle the case where the permission already exists but may be missing a bitIndex.
        Optional<Permission> existingOpt = permissionRepository.findByName(permDef.getName());
        if (existingOpt.isPresent()) {
            Permission existing = existingOpt.get();
            boolean changed = false;

            if (existing.getBitIndex() == null) {
                PermissionCode.fromCode(existing.getName()).ifPresent(pc -> existing.setBitIndex(pc.bitIndex()));
                changed = existing.getBitIndex() != null;
            }

            if (changed) {
                permissionRepository.save(existing);
                log.debug("Backfilled bitIndex for existing permission: {}", permDef.getName());
                return incrementUpdated(counters);
            } else {
                log.debug("Permission already exists and is up-to-date: {}", permDef.getName());
                return incrementSkipped(counters);
            }
        }

        // No existing permission found: proceed with registering a new one.
        Permission permission = buildPermission(request, permDef);
        try {
            permission.parsePermissionName();
            PermissionCode.fromCode(permission.getName()).ifPresent(pc -> permission.setBitIndex(pc.bitIndex()));
            permissionRepository.save(permission);
            log.debug("Registered new permission: {}", permDef.getName());
            return incrementRegistered(counters);
        } catch (IllegalArgumentException e) {
            errors.add("Failed to parse permission " + permDef.getName() + ": " + e.getMessage());
            return incrementSkipped(counters);
        }
    }

    private ProcessingCounters incrementRegistered(ProcessingCounters counters) {
        return new ProcessingCounters(counters.registered() + 1, counters.updated(), counters.skipped());
    }

    private ProcessingCounters incrementUpdated(ProcessingCounters counters) {
        return new ProcessingCounters(counters.registered(), counters.updated() + 1, counters.skipped());
    }

    private ProcessingCounters incrementSkipped(ProcessingCounters counters) {
        return new ProcessingCounters(counters.registered(), counters.updated(), counters.skipped() + 1);
    }

    private Permission buildPermission(
            PermissionRegistrationRequest request, PermissionRegistrationRequest.PermissionDefinition permDef) {
        Permission permission = new Permission();
        permission.setName(permDef.getName());
        permission.setDescription(permDef.getDescription());
        permission.setRegisteredByService(request.getServiceName());
        permission.setRegisteredAt(Instant.now(clock));
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
    public List<PermissionDto> getPermissionsByDomain(@NonNull String domain) {
        return permissionRepository.findByDomain(domain).stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Get all registered permissions.
     */
    @Override
    public List<PermissionDto> getAllPermissions() {
        return permissionRepository.findAll().stream().map(this::toDto).toList();
    }

    /**
     * Check if a permission exists.
     */
    @Override
    public boolean permissionExists(@NonNull String permissionName) {
        return permissionRepository.existsByName(permissionName);
    }

    /**
     * Get permission by name.
     */
    @Override
    public Optional<PermissionDto> getPermissionByName(@NonNull String name) {
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
