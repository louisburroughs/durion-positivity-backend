package com.positivity.tenant.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.tenant.internal.dto.TenantCreateRequest;
import com.positivity.tenant.internal.dto.TenantResponse;
import com.positivity.tenant.internal.dto.TenantUpdateRequest;
import com.positivity.tenant.internal.enums.TenantStatus;
import com.positivity.tenant.internal.security.TenantPermissions;
import com.positivity.tenant.internal.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform-admin API over the tenant registry (ADR-0062 §7). Every operation needs a
 * {@code platform:tenant:*} authority, which only the platform tenant's role template grants, and
 * the request must be bound to the platform tenant ({@code PlatformTenantGuard}).
 */
@Tag(name = "Platform Tenant API", description = "Register, inspect and move tenants through their lifecycle")
@RestController
@RequestMapping("/v1/platform/tenants")
@RequiredArgsConstructor
public class PlatformTenantController {

    private final TenantService tenantService;

    private static final String TENANT_CREATE_EXAMPLE = """
            {"slug":"acme-tire","displayName":"Acme Tire & Auto",
             "accountId":"01990000-0000-7000-8000-00000000a001","cell":"us-east-1",
             "initialAdminEmail":"owner@acme.example"}
            """;

    private static final String TENANT_UPDATE_EXAMPLE = """
            {"displayName":"Acme Tire & Auto Group","cell":"us-east-2"}
            """;

    @Operation(operationId = "createTenant", summary = "Register a Tenant", description = """
            Registers a tenant under an existing account in status PENDING and publishes tenant.created on \
            tenant.events.v1; pos-security-service provisions the role template and the initial administrator \
            named by initialAdminEmail, then answers tenant.provisioned, which moves the tenant to ACTIVE.
            Use this tool once per customer tenancy, after createAccount when the owning account does not exist; do not use \
            it to change an existing tenant, use updateTenant or the lifecycle operations instead.
            Preconditions: the account exists and the slug is not taken.
            Required inputs: slug, displayName, accountId and initialAdminEmail; cell is optional.
            Emits a TENANT_CREATE event.
            Returns 201 with the tenant, 404 when the account is unknown and 409 when the slug is taken.
            """)
    @ApiResponse(responseCode = "201", description = "Tenant registered")
    @ApiResponse(responseCode = "404", description = "Account not found")
    @ApiResponse(responseCode = "409", description = "Slug already taken")
    @EmitEvent(id = "TENANT_CREATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + TenantPermissions.TENANT_CREATE + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"platform:tenant:create"})
    @PostMapping
    public ResponseEntity<TenantResponse> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Tenant to register: slug, display name, owning account and the initial administrator.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Register Acme",
                                                            value = TENANT_CREATE_EXAMPLE)))
                    @Valid
                    @RequestBody
                    TenantCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tenantService.create(request));
    }

    @Operation(operationId = "listTenants", summary = "List Tenants", description = """
            Lists every tenant in the registry, oldest first, optionally filtered by lifecycle status.
            Use this tool to find a tenant id or slug; do not use it for one tenant's full record, use getTenant instead.
            Preconditions: none beyond the platform:tenant:read authority.
            Required inputs: none; status is an optional filter (PENDING, ACTIVE, SUSPENDED, DECOMMISSIONED).
            Emits a TENANT_LIST event.
            Returns 200 with the unpaginated list.
            """)
    @ApiResponse(responseCode = "200", description = "Tenants listed")
    @EmitEvent(id = "TENANT_LIST", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + TenantPermissions.TENANT_READ + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"platform:tenant:read"})
    @GetMapping
    public ResponseEntity<List<TenantResponse>> list(
            @Parameter(description = "Only tenants in this status") @RequestParam(required = false)
                    TenantStatus status) {
        return ResponseEntity.ok(tenantService.list(status));
    }

    @Operation(operationId = "getTenant", summary = "Get a Tenant", description = """
            Returns one tenant's registry record, including its account, cell and lifecycle timestamps.
            Use this tool when the tenant id is known; do not use it to search by slug or status, use listTenants instead.
            Preconditions: the tenant exists.
            Required inputs: id (UUID) as a path parameter.
            Emits a TENANT_GET event.
            Returns 200 with the tenant and 404 when it does not exist.
            """)
    @ApiResponse(responseCode = "200", description = "Tenant found")
    @ApiResponse(responseCode = "404", description = "Tenant not found")
    @EmitEvent(id = "TENANT_GET", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + TenantPermissions.TENANT_READ + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"platform:tenant:read"})
    @GetMapping("/{id}")
    public ResponseEntity<TenantResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(tenantService.get(id));
    }

    @Operation(operationId = "updateTenant", summary = "Update a Tenant", description = """
            Changes a tenant's display name or cell and publishes tenant.updated; slug, account and status are \
            never changed here.
            Use this tool for descriptive edits; do not use it to change status, use suspendTenant, reactivateTenant or \
            decommissionTenant instead.
            Preconditions: the tenant exists and is not DECOMMISSIONED.
            Required inputs: id (UUID) as a path parameter and a body with displayName and/or cell; a null field \
            leaves the value unchanged.
            Emits a TENANT_UPDATE event.
            Returns 200 with the tenant, 404 when it does not exist and 409 when it is decommissioned.
            """)
    @ApiResponse(responseCode = "200", description = "Tenant updated")
    @ApiResponse(responseCode = "404", description = "Tenant not found")
    @ApiResponse(responseCode = "409", description = "Tenant is decommissioned")
    @EmitEvent(id = "TENANT_UPDATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + TenantPermissions.TENANT_UPDATE + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"platform:tenant:update"})
    @PatchMapping("/{id}")
    public ResponseEntity<TenantResponse> update(
            @PathVariable UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Fields to change; a null field leaves the value unchanged.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Rename and move",
                                                            value = TENANT_UPDATE_EXAMPLE)))
                    @Valid
                    @RequestBody
                    TenantUpdateRequest request) {
        return ResponseEntity.ok(tenantService.update(id, request));
    }

    @Operation(operationId = "suspendTenant", summary = "Suspend a Tenant", description = """
            Moves an ACTIVE tenant to SUSPENDED and publishes tenant.suspended; logins for the tenant are refused \
            until reactivateTenant.
            Use this tool for non-payment or abuse holds; do not use it for a permanent end, use decommissionTenant instead.
            Preconditions: the tenant is ACTIVE.
            Required inputs: id (UUID) as a path parameter; no body.
            Emits a TENANT_SUSPEND event.
            Returns 200 with the tenant, 404 when it does not exist and 409 when it is not ACTIVE.
            """)
    @ApiResponse(responseCode = "200", description = "Tenant suspended")
    @ApiResponse(responseCode = "404", description = "Tenant not found")
    @ApiResponse(responseCode = "409", description = "Tenant is not ACTIVE")
    @EmitEvent(id = "TENANT_SUSPEND", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + TenantPermissions.TENANT_SUSPEND + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"platform:tenant:suspend"})
    @PostMapping("/{id}/suspend")
    public ResponseEntity<TenantResponse> suspend(@PathVariable UUID id) {
        return ResponseEntity.ok(tenantService.suspend(id));
    }

    @Operation(operationId = "reactivateTenant", summary = "Reactivate a Tenant", description = """
            Moves a SUSPENDED tenant back to ACTIVE and publishes tenant.reactivated.
            Use this tool to lift a hold placed by suspendTenant; do not use it on a PENDING tenant, which becomes ACTIVE \
            through tenant.provisioned instead.
            Preconditions: the tenant is SUSPENDED.
            Required inputs: id (UUID) as a path parameter; no body.
            Emits a TENANT_REACTIVATE event.
            Returns 200 with the tenant, 404 when it does not exist and 409 when it is not SUSPENDED.
            """)
    @ApiResponse(responseCode = "200", description = "Tenant reactivated")
    @ApiResponse(responseCode = "404", description = "Tenant not found")
    @ApiResponse(responseCode = "409", description = "Tenant is not SUSPENDED")
    @EmitEvent(id = "TENANT_REACTIVATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + TenantPermissions.TENANT_REACTIVATE + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"platform:tenant:reactivate"})
    @PostMapping("/{id}/reactivate")
    public ResponseEntity<TenantResponse> reactivate(@PathVariable UUID id) {
        return ResponseEntity.ok(tenantService.reactivate(id));
    }

    @Operation(operationId = "decommissionTenant", summary = "Decommission a Tenant", description = """
            Moves a tenant to the terminal DECOMMISSIONED status and publishes tenant.decommissioned; the tenant \
            can never be reactivated or edited again.
            Use this tool when a tenancy ends; do not use it for a reversible hold, use suspendTenant instead.
            Preconditions: the tenant is not already DECOMMISSIONED.
            Required inputs: id (UUID) as a path parameter; no body.
            Emits a TENANT_DECOMMISSION event.
            Returns 200 with the tenant, 404 when it does not exist and 409 when it is already decommissioned.
            """)
    @ApiResponse(responseCode = "200", description = "Tenant decommissioned")
    @ApiResponse(responseCode = "404", description = "Tenant not found")
    @ApiResponse(responseCode = "409", description = "Tenant already decommissioned")
    @EmitEvent(id = "TENANT_DECOMMISSION", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + TenantPermissions.TENANT_DECOMMISSION + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"platform:tenant:decommission"})
    @PostMapping("/{id}/decommission")
    public ResponseEntity<TenantResponse> decommission(@PathVariable UUID id) {
        return ResponseEntity.ok(tenantService.decommission(id));
    }
}
