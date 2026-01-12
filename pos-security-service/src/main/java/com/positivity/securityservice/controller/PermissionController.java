package com.positivity.securityservice.controller;

import com.positivity.securityservice.dto.PermissionRegistrationRequest;
import com.positivity.securityservice.dto.PermissionRegistrationResponse;
import com.positivity.securityservice.model.Permission;
import com.positivity.securityservice.service.PermissionRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for the central permission registry.
 * Provides endpoints for services to register their permissions.
 */
@RestController
@RequestMapping("/api/permissions")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Permission Registry", description = "Central permission registry for all services")
public class PermissionController {
    
    private final PermissionRegistryService permissionRegistryService;

    /**
     * Register or update permissions from a service
     */
    @PostMapping("/register")
    @Operation(summary = "Register permissions from a service",
               description = "Services call this endpoint to register their available permissions")
    public ResponseEntity<PermissionRegistrationResponse> registerPermissions(
            @RequestBody PermissionRegistrationRequest request) {
        
        log.info("Received permission registration request from service: {}", request.getServiceName());
        
        PermissionRegistrationResponse response = permissionRegistryService.registerPermissions(request);
        
        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Get all registered permissions
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get all registered permissions",
               description = "Returns all permissions in the registry")
    public ResponseEntity<List<Permission>> getAllPermissions() {
        return ResponseEntity.ok(permissionRegistryService.getAllPermissions());
    }

    /**
     * Get permissions for a specific domain
     */
    @GetMapping("/domain/{domain}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    @Operation(summary = "Get permissions by domain",
               description = "Returns all permissions for a specific domain/service")
    public ResponseEntity<List<Permission>> getPermissionsByDomain(@PathVariable String domain) {
        return ResponseEntity.ok(permissionRegistryService.getPermissionsByDomain(domain));
    }

    /**
     * Validate a permission name format
     */
    @GetMapping("/validate/{permissionName}")
    @Operation(summary = "Validate permission name format",
               description = "Checks if a permission name follows the domain:resource:action format")
    public ResponseEntity<Boolean> validatePermissionName(@PathVariable String permissionName) {
        boolean isValid = permissionRegistryService.isValidPermissionName(permissionName);
        return ResponseEntity.ok(isValid);
    }

    /**
     * Check if a permission exists
     */
    @GetMapping("/exists/{permissionName}")
    @Operation(summary = "Check if permission exists",
               description = "Returns true if the permission is registered")
    public ResponseEntity<Boolean> permissionExists(@PathVariable String permissionName) {
        boolean exists = permissionRegistryService.permissionExists(permissionName);
        return ResponseEntity.ok(exists);
    }
}
