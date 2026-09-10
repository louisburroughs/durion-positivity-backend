package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.TenantMeResponse;
import com.positivity.securityservice.internal.service.TenantQueryService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The caller's own tenant, served from the {@code ext_tenant} replica (ADR-0062 §7): a tenant never
 * reads {@code pos-tenant}, which is platform-only.
 */
@Tag(name = "Tenant API", description = "The calling user's tenant")
@RestController
@RequestMapping("/v1/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantQueryService tenantQueryService;

    @Operation(operationId = "getMyTenant", summary = "Get the Caller's Tenant", description = """
            Returns the tenant the caller's token belongs to (its tid claim) as the security service knows it \
            from the tenant registry's published projection: id, slug, display name and lifecycle status.
            Use this tool to show the tenant name in the header or to check the tenant is ACTIVE; do not use \
            it to look up another tenant, which only platform staff can do through the pos-tenant registry.
            Preconditions: an authenticated caller whose tenant has been published to the replica.
            Required inputs: none; the tenant comes from the token.
            Emits a SECURITY_TENANT_ME_GET event.
            Returns 200 with the tenant, 401 without a bound tenant and 404 when the replica does not know it yet.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Tenant found",
            content = @Content(schema = @Schema(implementation = TenantMeResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description = "The replica does not know the caller's tenant yet",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "SECURITY_TENANT_ME_GET", apiVersion = "1")
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/me")
    public ResponseEntity<TenantMeResponse> me() {
        return ResponseEntity.ok(tenantQueryService
                .currentTenant()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "The caller's tenant is not in the ext_tenant replica yet")));
    }
}
