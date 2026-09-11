package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.ActivationTokenResponse;
import com.positivity.securityservice.internal.security.SecurityPermissions;
import com.positivity.securityservice.internal.service.AdministratorActivationService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform-operator endpoints for a provisioned tenant's first administrator (ADR-0062 §7, plan
 * WS2b-3). Reachable from the platform tenant only: a caller bound to any other tenant is refused
 * with 403 {@code PLATFORM_TENANT_REQUIRED} regardless of the authorities it holds.
 */
@Tag(
        name = "Platform Administrator API",
        description = "Platform-operator activation of a tenant's first administrator")
@RestController
@RequestMapping("/v1/platform/tenants/{tenantId}/administrators/{userId}")
@RequiredArgsConstructor
public class PlatformAdministratorController {

    private final AdministratorActivationService activationService;

    @Operation(
            operationId = "mintAdministratorActivationToken",
            summary = "Mint a First-Administrator Activation Token",
            description = """
                    Mints a one-time activation token for the named user of the named tenant and returns it once, \
                    together with its expiry. Any earlier token still open for that user is closed. The operator \
                    hands the token to the administrator out of band; the administrator exchanges it, \
                    unauthenticated, at activateAccount (POST /v1/auth/activate) for the account's first password.
                    Use this tool after tenant provisioning (tenant.created → tenant.provisioned) leaves the \
                    first ADMIN unable to sign in, or whenever that administrator has lost an unexchanged token; \
                    do not use updateUser to set a tenant's first password from the platform tenant.
                    Preconditions: the caller must hold platform:tenant:provision and be bound to the platform \
                    tenant; the user must exist in the named tenant and still be awaiting activation (credentials \
                    expired by provisioning, never signed in).
                    Required inputs: tenantId and userId as path parameters; there is no request body.
                    Emits a SECURITY_PLATFORM_ADMINISTRATOR_ACTIVATION_TOKEN_MINT event and an audit event on the \
                    user; only the token's SHA-256 is stored, so a lost token is replaced by minting again.
                    Returns 201 with the token and expiresAt (72 hours); 403 with PLATFORM_TENANT_REQUIRED when the \
                    caller is bound to a tenant other than the platform tenant; 404 with USER_NOT_FOUND when the \
                    user is not in that tenant; 409 with USER_NOT_AWAITING_ACTIVATION when the user has already \
                    been activated or signed in, so a live account's password is never overwritten.
                    """)
    @ApiResponse(
            responseCode = "201",
            description = "Token minted; it is shown here and never again",
            content = @Content(schema = @Schema(implementation = ActivationTokenResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "PLATFORM_TENANT_REQUIRED: the caller is bound to a tenant other than the platform tenant",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "USER_NOT_FOUND: no such user in that tenant",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "USER_NOT_AWAITING_ACTIVATION: the user has been activated or signed in already, or is "
                    + "not the credential-expired account provisioning created; a live account keeps its password",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"platform:tenant:provision"})
    @EmitEvent(id = "SECURITY_PLATFORM_ADMINISTRATOR_ACTIVATION_TOKEN_MINT", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + SecurityPermissions.PLATFORM_TENANT_PROVISION + "')")
    @PostMapping("/activation-token")
    public ResponseEntity<ActivationTokenResponse> mintActivationToken(
            @Parameter(description = "The provisioned tenant") @PathVariable UUID tenantId,
            @Parameter(description = "The administrator, a user of that tenant") @PathVariable UUID userId) {
        AdministratorActivationService.IssuedToken issued = activationService.mint(tenantId, userId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ActivationTokenResponse(issued.token(), issued.expiresAt()));
    }
}
