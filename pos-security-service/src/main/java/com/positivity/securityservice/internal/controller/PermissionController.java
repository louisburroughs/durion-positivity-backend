package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.PermissionDto;
import com.positivity.securityservice.internal.dto.PermissionRegistrationRequest;
import com.positivity.securityservice.internal.dto.PermissionRegistrationResponse;
import com.positivity.securityservice.service.PermissionService;
import com.positivity.securityservice.service.PermissionRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for the central permission registry.
 * Provides endpoints for services to register their permissions.
 */
@RestController
@RequestMapping({ "/v1/permissions", "/v1/users/permissions" })
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Permission Registry", description = "Central permission registry for all services")
public class PermissionController {

    private final PermissionRegistryService permissionRegistryService;
    private final PermissionService permissionService;

    /**
     * Issue #42: Idempotent bulk permission registration endpoint for user RBAC
     * API.
     */
    @EmitEvent(id = "SECURITY_PERMISSION_REGISTER", apiVersion = "1")
    @PostMapping("/registerPermissions")
    @Operation(summary = "Register permissions (RBAC contract endpoint)")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('security:permission:register')")
    public ResponseEntity<List<PermissionDto>> registerPermissionsContract(
            @RequestBody @NonNull PermissionRegistrationRequest request) {
        return ResponseEntity.ok(permissionService.registerPermissions(request));
    }

    /**
     * Issue #42: Get a permission by UUID identifier.
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get permission by identifier")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('security:permission:view')")
    public ResponseEntity<PermissionDto> getPermissionById(@PathVariable @NonNull UUID id) {
        return ResponseEntity.ok(permissionService.getPermission(id));
    }

    /**
     * Issue #42: Query permissions by domain.
     */
    @GetMapping(params = "domain")
    @Operation(summary = "Query permissions by domain")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('security:permission:view')")
    public ResponseEntity<List<PermissionDto>> getPermissionsByDomainQuery(@RequestParam @NonNull String domain) {
        return ResponseEntity.ok(permissionService.getByDomain(domain));
    }

    /**
     * Register or update permissions from a service
     */
    @EmitEvent(id = "SECURITY_PERMISSION_REGISTER", apiVersion = "1")
    @PostMapping("/register")
    @Operation(summary = "Register permissions from a service", description = "Services call this endpoint to register their available permissions")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('security:permission:register')")
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
    @Operation(summary = "Get all registered permissions", description = "Returns all permissions in the registry")
    public ResponseEntity<List<PermissionDto>> getAllPermissions() {
        return ResponseEntity.ok(permissionRegistryService.getAllPermissions());
    }

    /**
     * Get permissions for a specific domain
     */
    @GetMapping("/domain/{domain}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    @Operation(summary = "Get permissions by domain", description = "Returns all permissions for a specific domain/service")
    public ResponseEntity<List<PermissionDto>> getPermissionsByDomain(@PathVariable String domain) {
        return ResponseEntity.ok(permissionRegistryService.getPermissionsByDomain(domain));
    }

    /**
     * Validate a permission name format
     */
    @GetMapping("/validate/{permissionName}")
    @Operation(summary = "Validate permission name format", description = "Checks if a permission name follows the domain:resource:action format")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    public ResponseEntity<Boolean> validatePermissionName(@PathVariable String permissionName) {
        boolean isValid = permissionRegistryService.isValidPermissionName(permissionName);
        return ResponseEntity.ok(isValid);
    }

    /**
     * Check if a permission exists
     */
    @GetMapping("/exists/{permissionName}")
    @Operation(summary = "Check if permission exists", description = "Returns true if the permission is registered")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    public ResponseEntity<Boolean> permissionExists(@PathVariable String permissionName) {
        boolean exists = permissionRegistryService.permissionExists(permissionName);
        return ResponseEntity.ok(exists);
    }
}
