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
 *
 * <p>The token's {@code perm_bits} is the tenant's {@code SUPPORT} grants <em>after</em> the
 * read-only ceiling ({@code SupportReadOnlyCeiling}), not the role verbatim — a widened role never
 * yields a write-capable token — and it is revoked wholesale when the operator's account or
 * platform role ends ({@code ImpersonationTokenRevocationService}), not only at its own expiry.
 */
@Tag(name = "Platform Support API", description = "Platform-operator support access to a tenant (impersonation tokens)")
@RestController
@RequestMapping("/v1/platform/tenants/{tenantId}")
@RequiredArgsConstructor
public class PlatformImpersonationController {

    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private final PlatformImpersonationService impersonationService;

    @Operation(operationId = "mintImpersonationToken", summary = "Mint a Tenant Impersonation Token", description = """
                    Mints a 15-minute, read-only access token that acts inside the named tenant and returns it once, with its \
                    expiry, the tenant id and the tenant slug; it carries tid = the tenant, act = {sub, username} of the calling \
                    operator, token_use = impersonation and a synthetic subject support:<operator>@<tenantSlug>, has no refresh \
                    token, and refreshTokenPair refuses it. perm_bits is not the SUPPORT role verbatim: a tenant administrator \
                    can edit that role's grants, so the mint intersects them with a read-only ceiling that admits only view and \
                    read actions plus location:read and never a write, a platform:* permission or the documented exclusions; \
                    dropped grants do not fail the mint, and are logged at WARN and recorded as droppedGrants on both audit \
                    events. The token ends early when the operator does, because disabling or expiring their account, or \
                    revoking the role that carries platform:tenant:impersonate, revokes every token they have minted in every \
                    tenant; SUPPORT itself can never be granted to a user, which every assignment path refuses with 409 \
                    ROLE_NOT_USER_ASSIGNABLE. Use this tool when a platform operator must look at a tenant's data to support it, \
                    rather than granting an operator a role in the tenant or calling issueInternalToken, which binds the \
                    caller's own tenant. Preconditions: the caller must hold platform:tenant:impersonate, be bound to the \
                    platform tenant and be a user of it, and the target must be ACTIVE in the ext_tenant replica with a SUPPORT \
                    role. Required inputs: tenantId as a path parameter and no request body; X-Correlation-Id, when sent, is \
                    recorded on the audit events. Emits a SECURITY_PLATFORM_TENANT_IMPERSONATE event and a \
                    PlatformImpersonationTokenIssued audit event in both the target tenant and the platform tenant (operator, \
                    subject, jti, expiry, correlation id, droppedGrants), plus an INFO log line that never contains the token. \
                    Returns 201 with the token; 403 PLATFORM_TENANT_REQUIRED when the caller is bound to another tenant; 404 \
                    TENANT_NOT_FOUND when the replica does not know the tenant or USER_NOT_FOUND when the operator has no user \
                    row in the platform tenant; 409 TENANT_NOT_IMPERSONABLE when the tenant is not ACTIVE (its status is in the \
                    message), has no SUPPORT role, or is the platform tenant itself.
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
            description = "TENANT_NOT_FOUND: the ext_tenant replica does not know the tenant. "
                    + "USER_NOT_FOUND: the authenticated operator has no user row in the platform tenant",
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
