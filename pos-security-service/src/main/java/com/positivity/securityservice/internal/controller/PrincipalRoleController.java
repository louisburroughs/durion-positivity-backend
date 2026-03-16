package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.ErrorResponse;
import com.positivity.securityservice.service.RolePermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Principal Role Management", description = "Assign roles to principals for RBAC matrix operations")
public class PrincipalRoleController {

    private final RolePermissionService rolePermissionService;

    @EmitEvent(id = "SECURITY_PRINCIPAL_ROLE_ASSIGN", apiVersion = "1")
    @PostMapping("/{principalId}/roles/{roleId}")
    @PreAuthorize("hasAuthority('security:role:assign')")
    @Operation(summary = "Assign a role to a principal", description = "Assigns the specified role to the specified principal identifier for RBAC matrix authorization.")
    @ApiResponse(responseCode = "200", description = "Role assigned to principal successfully")
    @ApiResponse(responseCode = "404", description = "Principal or role not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<Void> assignRoleToPrincipal(
            @Parameter(description = "Principal identifier receiving the role assignment")
            @PathVariable String principalId,
            @Parameter(description = "Role identifier to assign to the principal")
            @PathVariable UUID roleId) {
        rolePermissionService.assignRoleToPrincipal(principalId, roleId);
        return ResponseEntity.ok().build();
    }
}
