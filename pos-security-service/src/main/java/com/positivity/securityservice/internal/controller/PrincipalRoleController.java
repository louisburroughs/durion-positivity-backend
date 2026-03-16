package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.service.RolePermissionService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Principal-role assignment endpoints for RBAC matrix operations.
 *
 * Issue: #42
 */
@RestController
@RequestMapping("/v1/users/principals")
@RequiredArgsConstructor
public class PrincipalRoleController {

    private final RolePermissionService rolePermissionService;

    @EmitEvent(id = "SECURITY_PRINCIPAL_ROLE_ASSIGN", apiVersion = "1")
    @PostMapping("/{principalId}/roles/{roleId}")
    @PreAuthorize("hasAuthority('security:role:assign')")
    public ResponseEntity<Void> assignRoleToPrincipal(
            @PathVariable String principalId,
            @PathVariable UUID roleId) {
        rolePermissionService.assignRoleToPrincipal(principalId, roleId);
        return ResponseEntity.ok().build();
    }
}
