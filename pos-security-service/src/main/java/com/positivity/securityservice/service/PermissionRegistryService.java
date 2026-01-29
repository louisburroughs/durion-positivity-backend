package com.positivity.securityservice.service;

import com.positivity.securityservice.internal.dto.PermissionRegistrationRequest;
import com.positivity.securityservice.internal.dto.PermissionRegistrationResponse;
import com.positivity.securityservice.internal.model.Permission;
import com.positivity.securityservice.internal.repository.PermissionRepository;
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
public class PermissionRegistryService {
    
    private final PermissionRepository permissionRepository;
    
    /**
     * Pattern for validating permission names: domain:resource:action
     * All lowercase, alphanumeric with underscores
     */
    private static final Pattern PERMISSION_PATTERN = 
        Pattern.compile("^[a-z][a-z0-9_]*:[a-z][a-z0-9_]*:[a-z][a-z0-9_]*$");

    /**
     * Register or update permissions from a service manifest
     */
    @Transactional
    public PermissionRegistrationResponse registerPermissions(PermissionRegistrationRequest request) {
        log.info("Registering permissions for domain: {}, service: {}", 
                 request.getDomain(), request.getServiceName());
        
        List<String> errors = new ArrayList<>();
        int registered = 0;
        int updated = 0;
        int skipped = 0;
        
        for (PermissionRegistrationRequest.PermissionDefinition permDef : request.getPermissions()) {
            try {
                // Validate permission name format
                if (!isValidPermissionName(permDef.getName())) {
                    errors.add("Invalid permission name format: " + permDef.getName() + 
                              " (must be lowercase domain:resource:action)");
                    skipped++;
                    continue;
                }
                
                // Check if permission already exists
                Optional<Permission> existingOpt = permissionRepository.findByName(permDef.getName());
                
                if (existingOpt.isPresent()) {
                    Permission existing = existingOpt.get();
                    // Update description if changed
                    if (!existing.getDescription().equals(permDef.getDescription())) {
                        existing.setDescription(permDef.getDescription());
                        existing.setRegisteredByService(request.getServiceName());
                        permissionRepository.save(existing);
                        updated++;
                        log.debug("Updated permission: {}", permDef.getName());
                    } else {
                        skipped++;
                    }
                } else {
                    // Create new permission
                    Permission permission = new Permission();
                    permission.setName(permDef.getName());
                    permission.setDescription(permDef.getDescription());
                    permission.setRegisteredByService(request.getServiceName());
                    permission.setRegisteredAt(Instant.now());
                    
                    // Parse and set domain, resource, action
                    try {
                        permission.parsePermissionName();
                        permissionRepository.save(permission);
                        registered++;
                        log.debug("Registered new permission: {}", permDef.getName());
                    } catch (IllegalArgumentException e) {
                        errors.add("Failed to parse permission " + permDef.getName() + ": " + e.getMessage());
                        skipped++;
                    }
                }
            } catch (Exception e) {
                errors.add("Error processing permission " + permDef.getName() + ": " + e.getMessage());
                skipped++;
                log.error("Error processing permission: {}", permDef.getName(), e);
            }
        }
        
        boolean success = errors.isEmpty() || (registered + updated) > 0;
        String message = String.format("Processed %d permissions: %d registered, %d updated, %d skipped",
                                       request.getPermissions().size(), registered, updated, skipped);
        
        return PermissionRegistrationResponse.builder()
            .success(success)
            .message(message)
            .totalPermissions(request.getPermissions().size())
            .registeredPermissions(registered)
            .updatedPermissions(updated)
            .skippedPermissions(skipped)
            .errors(errors)
            .build();
    }

    /**
     * Validate permission name format
     */
    public boolean isValidPermissionName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        return PERMISSION_PATTERN.matcher(name).matches();
    }

    /**
     * Get all permissions for a domain
     */
    public List<Permission> getPermissionsByDomain(String domain) {
        return permissionRepository.findByDomain(domain);
    }

    /**
     * Get all registered permissions
     */
    public List<Permission> getAllPermissions() {
        return permissionRepository.findAll();
    }

    /**
     * Check if a permission exists
     */
    public boolean permissionExists(String permissionName) {
        return permissionRepository.existsByName(permissionName);
    }

    /**
     * Get permission by name
     */
    public Optional<Permission> getPermissionByName(String name) {
        return permissionRepository.findByName(name);
    }
}
