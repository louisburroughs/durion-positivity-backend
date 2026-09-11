package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.ImpersonationTokenResponse;
import com.positivity.securityservice.internal.security.SecurityPermissions;
import com.positivity.securityservice.internal.service.PlatformImpersonationService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform support access (ADR-0062 §7, plan WS2b-4): a platform operator mints a 15-minute,
 * no-refresh, read-only impersonation token for a tenant. Reachable from the platform tenant only:
 * a caller bound to any other tenant is refused with 403 {@code PLATFORM_TENANT_REQUIRED}
 * regardless of the authorities it holds.
 */
@Tag(name = "Platform Support API", description = "Platform-operator support access to a tenant (impersonation tokens)")
@RestController
@RequestMapping("/v1/platform/tenants/{tenantId}")
@RequiredArgsConstructor
public class PlatformImpersonationController {

    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private final PlatformImpersonationService impersonationService;

    @Operation(operationId = "mintImpersonationToken", summary = "Mint a Tenant Impersonation Token", description = """
                    Mints a 15-minute, read-only access token that acts inside the named tenant and returns it \
                    once, with its expiry, the tenant id and the tenant slug. The token carries tid = the tenant, \
                    the tenant's own SUPPORT role's permissions as perm_bits, act = {sub, username} of the \
                    calling operator, token_use = impersonation and a synthetic subject \
                    support:<operator>@<tenantSlug>; it has no refresh token and refreshTokenPair refuses it.
                    Use this tool when a platform operator must look at a tenant's data to support it; do not \
                    grant an operator a role in the tenant, and do not use issueInternalToken, which binds the \
                    caller's own tenant.
                    Preconditions: the caller must hold platform:tenant:impersonate and be bound to the platform \
                    tenant; the tenant must be ACTIVE in the ext_tenant replica and hold a SUPPORT role; the \
                    caller must be a user of the platform tenant.
                    Required inputs: tenantId as a path parameter; there is no request body. X-Correlation-Id, \
                    when sent, is recorded on the audit events.
                    Emits a SECURITY_PLATFORM_TENANT_IMPERSONATE event and a PlatformImpersonationTokenIssued \
                    audit event in both the target tenant and the platform tenant (operator, subject, jti, \
                    expiry, correlation id), plus an INFO log line; the token itself is never logged.
                    Returns 201 with the token; 403 with PLATFORM_TENANT_REQUIRED when the caller is bound to a \
                    tenant other than the platform tenant; 404 with TENANT_NOT_FOUND when the replica does not \
                    know the tenant; 409 with TENANT_NOT_IMPERSONABLE when the tenant is not ACTIVE (its status \
                    is in the message) or has no SUPPORT role yet, or when the target is the platform tenant \
                    itself.
                    """)
    @ApiResponse(
            responseCode = "201",
            description = "Token minted; it is shown here and never again",
            content = @Content(schema = @Schema(implementation = ImpersonationTokenResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "PLATFORM_TENANT_REQUIRED: the caller is bound to a tenant other than the platform tenant",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "TENANT_NOT_FOUND: the ext_tenant replica does not know the tenant",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "TENANT_NOT_IMPERSONABLE: the tenant is not ACTIVE, has no SUPPORT role yet, or is the "
                    + "platform tenant",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"platform:tenant:impersonate"})
    @EmitEvent(id = "SECURITY_PLATFORM_TENANT_IMPERSONATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + SecurityPermissions.PLATFORM_TENANT_IMPERSONATE + "')")
    @PostMapping("/impersonation-token")
    public ResponseEntity<ImpersonationTokenResponse> mintImpersonationToken(
            @Parameter(description = "The tenant to act inside") @PathVariable UUID tenantId,
            @Parameter(hidden = true) @RequestHeader(value = CORRELATION_ID_HEADER, required = false)
                    String correlationId) {
        PlatformImpersonationService.IssuedToken issued = impersonationService.issue(tenantId, correlationId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ImpersonationTokenResponse(
                        issued.token(), issued.expiresAt(), issued.tenantId(), issued.tenantSlug()));
    }
}
