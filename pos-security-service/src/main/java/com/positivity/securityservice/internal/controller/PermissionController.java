package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.CatalogVersionResponse;
import com.positivity.securityservice.internal.dto.PermissionDecodeRequest;
import com.positivity.securityservice.internal.dto.PermissionDecodeResponse;
import com.positivity.securityservice.internal.dto.PermissionDto;
import com.positivity.securityservice.internal.dto.PermissionRegistrationRequest;
import com.positivity.securityservice.internal.dto.PermissionRegistrationResponse;
import com.positivity.securityservice.service.PermissionCatalogVersionService;
import com.positivity.securityservice.service.PermissionRegistryService;
import com.positivity.securityservice.service.PermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for the central permission registry.
 * Provides endpoints for services to register their permissions.
 */
@RestController
@RequestMapping({"/v1/permissions", "/v1/users/permissions"})
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Permission Registry", description = "Central permission registry for all services")
public class PermissionController {

    private final PermissionRegistryService permissionRegistryService;
    private final PermissionService permissionService;
    private final PermissionCatalogVersionService permissionCatalogVersionService;

    /**
     * Returns the current permission catalog version and total permission count.
     * No authentication required — informational endpoint.
     */
    @GetMapping("/catalog-version")
    @PreAuthorize("permitAll()")
    @Operation(
            summary = "Get current permission catalog version",
            description = "Returns the active catalog version and total permission count. No authentication required.")
    public ResponseEntity<CatalogVersionResponse> getCatalogVersion() {
        return ResponseEntity.ok(new CatalogVersionResponse(
                permissionCatalogVersionService.getCatalogVersion(),
                permissionCatalogVersionService.getPermissionCount()));
    }

    /**
     * Decodes a perm_bits claim for diagnostic purposes.
     * Requires security:permission:view authority.
     */
    @EmitEvent(id = "SECURITY_PERMISSION_DECODE_EXECUTE", apiVersion = "1")
    @PostMapping("/decode")
    @Operation(
            summary = "Decode perm_bits for diagnostics",
            description = "Decodes a perm_bits Base64URL BitSet back to permission code strings. For debugging only.")
    @PreAuthorize("hasAuthority('security:permission:view')")
    public ResponseEntity<PermissionDecodeResponse> decodePermissions(
            @RequestBody @Valid @NonNull PermissionDecodeRequest request) {
        if (!StringUtils.hasText(request.permBits())) {
            throw new IllegalArgumentException("perm_bits must be provided");
        }
        if (request.permVer() <= 0) {
            throw new IllegalArgumentException("perm_ver must be greater than 0");
        }
        return ResponseEntity.ok(new PermissionDecodeResponse(
                permissionCatalogVersionService.decodePermissions(request.permBits(), request.permVer())));
    }

    /**
     * Issue #42: Idempotent bulk permission registration endpoint for user RBAC
     * API.
     */
    @EmitEvent(id = "SECURITY_PERMISSION_REGISTER", apiVersion = "1")
    @PostMapping("/registerPermissions")
    @Operation(
            summary = "Register permissions (RBAC contract endpoint)",
            description =
                    "Registers or updates permissions using the RBAC contract payload and returns the resulting permission set.")
    @PreAuthorize("hasAuthority('security:permission:register')")
    public ResponseEntity<List<PermissionDto>> registerPermissionsContract(
            @RequestBody @NonNull PermissionRegistrationRequest request) {
        return ResponseEntity.ok(permissionService.registerPermissions(request));
    }

    /**
     * Issue #42: Get a permission by UUID identifier.
     */
    @GetMapping("/{id}")
    @Operation(
            summary = "Get permission by identifier",
            description = "Returns a single registered permission by its UUID identifier.")
    @PreAuthorize("hasAuthority('security:permission:view')")
    public ResponseEntity<PermissionDto> getPermissionById(@PathVariable @NonNull UUID id) {
        return ResponseEntity.ok(permissionService.getPermission(id));
    }

    /**
     * Issue #42: Query permissions by domain.
     */
    @GetMapping(params = "domain")
    @Operation(
            summary = "Query permissions by domain",
            description =
                    "Returns all registered permissions for the requested domain using the domain query parameter.")
    @PreAuthorize("hasAuthority('security:permission:view')")
    public ResponseEntity<List<PermissionDto>> getPermissionsByDomainQuery(@RequestParam @NonNull String domain) {
        return ResponseEntity.ok(permissionService.getByDomain(domain));
    }

    /**
     * Register or update permissions from a service
     */
    @EmitEvent(id = "SECURITY_PERMISSION_REGISTER", apiVersion = "1")
    @PostMapping("/register")
    @Operation(
            summary = "Register permissions from a service",
            description = "Services call this endpoint to register their available permissions")
    @PreAuthorize("hasAuthority('security:permission:register')")
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
    @PreAuthorize("hasAuthority('security:permission:view')")
    @Operation(summary = "Get all registered permissions", description = "Returns all permissions in the registry")
    public ResponseEntity<List<PermissionDto>> getAllPermissions() {
        return ResponseEntity.ok(permissionRegistryService.getAllPermissions());
    }

    /**
     * Get permissions for a specific domain
     */
    @GetMapping("/domain/{domain}")
    @PreAuthorize("hasAuthority('security:permission:view')")
    @Operation(
            summary = "Get permissions by domain",
            description = "Returns all permissions for a specific domain/service")
    public ResponseEntity<List<PermissionDto>> getPermissionsByDomain(@PathVariable String domain) {
        return ResponseEntity.ok(permissionRegistryService.getPermissionsByDomain(domain));
    }

    /**
     * Validate a permission name format
     */
    @GetMapping("/validate/{permissionName}")
    @Operation(
            summary = "Validate permission name format",
            description = "Checks if a permission name follows the domain:resource:action format")
    @PreAuthorize("hasAuthority('security:permission:view')")
    public ResponseEntity<Boolean> validatePermissionName(@PathVariable String permissionName) {
        boolean isValid = permissionRegistryService.isValidPermissionName(permissionName);
        return ResponseEntity.ok(isValid);
    }

    /**
     * Check if a permission exists
     */
    @GetMapping("/exists/{permissionName}")
    @Operation(summary = "Check if permission exists", description = "Returns true if the permission is registered")
    @PreAuthorize("hasAuthority('security:permission:view')")
    public ResponseEntity<Boolean> permissionExists(@PathVariable String permissionName) {
        boolean exists = permissionRegistryService.permissionExists(permissionName);
        return ResponseEntity.ok(exists);
    }
}
