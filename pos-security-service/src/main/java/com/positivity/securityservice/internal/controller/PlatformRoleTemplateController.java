package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.RoleTemplateReconcileResponse;
import com.positivity.securityservice.internal.security.SecurityPermissions;
import com.positivity.securityservice.internal.service.RoleTemplateReconciliationService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
 * Platform-operator endpoint that brings a tenant up to the platform role template (ADR-0062 §6,
 * plan WS8). Reachable from the platform tenant only: a caller bound to any other tenant is
 * refused with 403 {@code PLATFORM_TENANT_REQUIRED} regardless of the authorities it holds.
 */
@Tag(
        name = "Platform Role Template API",
        description = "Platform-operator reconciliation of a tenant's roles against the platform role template")
@RestController
@RequestMapping("/v1/platform/tenants/{tenantId}/roles")
@RequiredArgsConstructor
public class PlatformRoleTemplateController {

    private final RoleTemplateReconciliationService reconciliationService;

    @Operation(
            operationId = "reconcileRoleTemplate",
            summary = "Reconcile a Tenant's Roles Against the Platform Role Template",
            description = """
                    Upserts every platform template role into the named tenant: a template role the tenant lacks \
                    is created with the template's description, persona, location scope and grants, exactly as \
                    provisioning creates it; a role the tenant already holds keeps every tenant-local grant and \
                    gains any grant the template has added since (union, never removal), and is marked with its \
                    template key when it carried none. An existing role's description, persona and scope are left \
                    alone. Idempotent: a second run reports empty lists.
                    Use this tool after the template has grown — a platform bulk load of roles.csv into the \
                    platform tenant, or a new grant on a template role — to bring tenants provisioned before the \
                    change up to it; do not use it to provision a tenant (tenant.created does that) and do not use \
                    it to remove grants, which it never does.
                    Preconditions: the caller must hold platform:tenant:provision and be bound to the platform \
                    tenant; the tenant must be known to this service (ext_tenant), or be the platform tenant \
                    itself.
                    Required inputs: tenantId as a path parameter; there is no request body.
                    Emits a SECURITY_PLATFORM_ROLE_TEMPLATE_RECONCILE event; role_permissions rows it adds carry \
                    the actor role-template-reconcile.
                    Returns 200 with the roles created, the grants added and the roles newly marked as template; \
                    403 with PLATFORM_TENANT_REQUIRED when the caller is bound to a tenant other than the platform \
                    tenant; 404 with TENANT_NOT_FOUND when the tenant is unknown.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Reconciled; the lists say what changed (all empty when nothing did)",
            content = @Content(schema = @Schema(implementation = RoleTemplateReconcileResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "PLATFORM_TENANT_REQUIRED: the caller is bound to a tenant other than the platform tenant",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "TENANT_NOT_FOUND: no such tenant",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"platform:tenant:provision"})
    @EmitEvent(id = "SECURITY_PLATFORM_ROLE_TEMPLATE_RECONCILE", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + SecurityPermissions.PLATFORM_TENANT_PROVISION + "')")
    @PostMapping("/reconcile-template")
    public ResponseEntity<RoleTemplateReconcileResponse> reconcileTemplate(
            @Parameter(description = "The tenant to bring up to the template") @PathVariable UUID tenantId) {
        return ResponseEntity.ok(reconciliationService.reconcile(tenantId));
    }
}
