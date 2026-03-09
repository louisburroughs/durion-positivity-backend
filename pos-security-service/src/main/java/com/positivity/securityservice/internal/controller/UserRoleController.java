package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.PermissionDto;
import com.positivity.securityservice.service.RoleManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Set;
import java.util.UUID;

/**
 * REST controller for user-role assignment endpoints.
 * Manages assigning/revoking roles to/from users and querying effective permissions.
 * Separated from RoleController to avoid path ambiguity with UserController at /v1/users.
 */
@RestController
@RequestMapping("/v1/users")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "User Role Management", description = "Assign and revoke roles for users")
public class UserRoleController {

    private final RoleManagementService roleManagementService;

    /**
     * Story #62 (CAP-141): Assign a role to a user.
     */
    @EmitEvent(id = "SECURITY_USER_ROLE_ASSIGN", apiVersion = "1")
    @PutMapping("/{userId}/roles/{roleId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Assign a role to a user")
    public ResponseEntity<Void> assignRoleToUser(
            @PathVariable UUID userId,
            @PathVariable UUID roleId) {
        roleManagementService.assignRoleToUser(userId, roleId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Story #62 (CAP-141): Revoke a role from a user.
     */
    @EmitEvent(id = "SECURITY_USER_ROLE_REVOKE", apiVersion = "1")
    @DeleteMapping("/{userId}/roles/{roleId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Revoke a role from a user")
    public ResponseEntity<Void> revokeRoleFromUser(
            @PathVariable UUID userId,
            @PathVariable UUID roleId) {
        roleManagementService.revokeRoleFromUser(userId, roleId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Story #62 (CAP-141): Get all effective permissions for a user.
     */
    @GetMapping("/{userId}/permissions")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    @Operation(summary = "Get user permissions", description = "Returns all effective permissions for a user")
    public ResponseEntity<Set<PermissionDto>> getUserPermissions(@PathVariable UUID userId) {
        return ResponseEntity.ok(roleManagementService.getUserPermissions(userId));
    }
}
