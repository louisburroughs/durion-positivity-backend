package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.security.SecurityPermissions;
import com.positivity.securityservice.internal.service.RolePermissionService;
import com.positivity.shared.error.ApiError;
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
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"security:role:assign"})
@RequestMapping("/v1/users/principals")
@RequiredArgsConstructor
@Tag(name = "Principal Role Management", description = "Assign roles to principals for RBAC matrix operations")
public class PrincipalRoleController {

    private final RolePermissionService rolePermissionService;

    @EmitEvent(id = "SECURITY_PRINCIPAL_ROLE_ASSIGN", apiVersion = "1")
    @PostMapping("/{principalId}/roles/{roleId}")
    @PreAuthorize("hasAuthority('" + SecurityPermissions.ROLE_ASSIGN + "')")
    @Operation(operationId = "assignPrincipalRole", summary = "Assign a Role to a Principal", description = """
                    Links a free-form principal identifier to a role in the RBAC principal matrix consulted by \
                    getAuthorizationDecision.
                    Use this tool for matrix principals that are not user UUIDs; do not use assignUserRole, which \
                    creates a role assignment for a real user account.
                    Preconditions: the caller must hold security:role:assign and the role must exist; the principal \
                    is not validated against any store, and an existing identical link makes the call a no-op.
                    Required inputs: principalId (arbitrary string) and roleId (UUID) as path parameters; there is \
                    no request body.
                    Emits a SECURITY_PRINCIPAL_ROLE_ASSIGN event.
                    Returns 404 when the role does not exist; repeat assignments still return 200.
                    """)
    @ApiResponse(responseCode = "200", description = "Role assigned to principal successfully")
    @ApiResponse(
            responseCode = "404",
            description = "Principal or role not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Void> assignRoleToPrincipal(
            @Parameter(description = "Principal identifier receiving the role assignment") @PathVariable
                    String principalId,
            @Parameter(description = "Role identifier to assign to the principal") @PathVariable UUID roleId) {
        rolePermissionService.assignRoleToPrincipal(principalId, roleId);
        return ResponseEntity.ok().build();
    }
}
