package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.AccountStateResponse;
import com.positivity.securityservice.internal.security.SecurityPermissions;
import com.positivity.securityservice.internal.service.AdminAccountStateService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin Account-State API", description = "Administrative endpoints for user account state management")
@RestController
@RequestMapping("/v1/users")
@RequiredArgsConstructor
public class AdminAccountStateController {

    private final AdminAccountStateService adminAccountStateService;

    @Operation(operationId = "unlockUserAccount", summary = "Unlock a Locked User Account", description = """
                    Clears the lockout state on a user account, resetting the failed-attempt counter and lock \
                    timestamps so the user can authenticate again.
                    Use this tool after a lockout caused by repeated failed logins; do not use enableUserAccount, \
                    which reverses an administrative disable rather than a lockout.
                    Preconditions: the caller must hold security:user_account_state:manage and the user must exist.
                    Required inputs: id (UUID) as a path parameter; there is no request body.
                    Emits a SECURITY_USER_UNLOCK event; existing tokens are not revoked.
                    Returns 404 with USER_NOT_FOUND when the user does not exist.
                    """)
    @ApiResponse(responseCode = "204", description = "User account unlocked successfully")
    @ApiResponse(
            responseCode = "404",
            description = "User not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:user_account_state:manage"})
    @EmitEvent(id = "SECURITY_USER_UNLOCK", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + SecurityPermissions.USER_ACCOUNT_STATE_MANAGE + "')")
    @PostMapping("/{id}/unlock")
    public ResponseEntity<Void> unlock(@PathVariable UUID id) {
        adminAccountStateService.unlock(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "enableUserAccount", summary = "Enable a Disabled User Account", description = """
                    Marks a user account as enabled so it is again accepted for sign-in and access checks.
                    Use this tool to reverse disableUserAccount; do not use unlockUserAccount, which clears a \
                    failed-login lockout instead.
                    Preconditions: the caller must hold security:user_account_state:manage and the user must exist.
                    Required inputs: id (UUID) as a path parameter; there is no request body.
                    Emits a SECURITY_USER_ENABLE event; no tokens are issued or restored, so the user must sign in \
                    again.
                    Returns 404 with USER_NOT_FOUND when the user does not exist.
                    """)
    @ApiResponse(responseCode = "204", description = "User account enabled successfully")
    @ApiResponse(
            responseCode = "404",
            description = "User not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:user_account_state:manage"})
    @EmitEvent(id = "SECURITY_USER_ENABLE", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + SecurityPermissions.USER_ACCOUNT_STATE_MANAGE + "')")
    @PostMapping("/{id}/enable")
    public ResponseEntity<Void> enable(@PathVariable UUID id) {
        adminAccountStateService.enable(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "disableUserAccount", summary = "Disable a User Account", description = """
                    Marks a user account as disabled, records the disabling actor and time, and immediately revokes \
                    every token issued to the user.
                    Use this tool for a reversible administrative block; do not use deleteUser, which removes the \
                    account, and do not use expireUserAccount, which marks the account expired instead.
                    Preconditions: the caller must hold security:user_account_state:manage and the user must exist.
                    Required inputs: id (UUID) as a path parameter; there is no request body.
                    Emits a SECURITY_USER_DISABLE event and revokes all of the user's access and refresh tokens.
                    Returns 404 with USER_NOT_FOUND when the user does not exist.
                    """)
    @ApiResponse(responseCode = "204", description = "User account disabled successfully")
    @ApiResponse(
            responseCode = "404",
            description = "User not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:user_account_state:manage"})
    @EmitEvent(id = "SECURITY_USER_DISABLE", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + SecurityPermissions.USER_ACCOUNT_STATE_MANAGE + "')")
    @PostMapping("/{id}/disable")
    public ResponseEntity<Void> disable(@PathVariable UUID id) {
        adminAccountStateService.disable(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "expireUserAccount", summary = "Expire a User Account", description = """
                    Marks a user account as expired, stamping the expiry time and immediately revoking every token \
                    issued to the user.
                    Use this tool to end an account's validity, for example at offboarding; do not use \
                    disableUserAccount, which signals a reversible administrative block, or expireUserCredentials, \
                    which only forces a credential reset.
                    Preconditions: the caller must hold security:user_account_state:manage and the user must exist.
                    Required inputs: id (UUID) as a path parameter; there is no request body.
                    Emits a SECURITY_USER_EXPIRE_ACCOUNT event and revokes all of the user's access and refresh \
                    tokens.
                    Returns 404 with USER_NOT_FOUND when the user does not exist.
                    """)
    @ApiResponse(responseCode = "204", description = "User account expired successfully")
    @ApiResponse(
            responseCode = "404",
            description = "User not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:user_account_state:manage"})
    @EmitEvent(id = "SECURITY_USER_EXPIRE_ACCOUNT", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + SecurityPermissions.USER_ACCOUNT_STATE_MANAGE + "')")
    @PostMapping("/{id}/expire-account")
    public ResponseEntity<Void> expireAccount(@PathVariable UUID id) {
        adminAccountStateService.expireAccount(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "expireUserCredentials", summary = "Expire a User's Credentials", description = """
                    Marks a user's credentials as expired, stamping the expiry time and immediately revoking every \
                    token so a credential reset is required before further use.
                    Use this tool to force a password rotation; do not use expireUserAccount, which expires the \
                    whole account rather than just its credentials.
                    Preconditions: the caller must hold security:user_account_state:manage and the user must exist.
                    Required inputs: id (UUID) as a path parameter; there is no request body.
                    Emits a SECURITY_USER_EXPIRE_CREDENTIALS event and revokes all of the user's access and refresh \
                    tokens.
                    Returns 404 with USER_NOT_FOUND when the user does not exist.
                    """)
    @ApiResponse(responseCode = "204", description = "User credentials expired successfully")
    @ApiResponse(
            responseCode = "404",
            description = "User not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:user_account_state:manage"})
    @EmitEvent(id = "SECURITY_USER_EXPIRE_CREDENTIALS", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + SecurityPermissions.USER_ACCOUNT_STATE_MANAGE + "')")
    @PostMapping("/{id}/expire-credentials")
    public ResponseEntity<Void> expireCredentials(@PathVariable UUID id) {
        adminAccountStateService.expireCredentials(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "getUserAccountState", summary = "Get a User's Account-State Flags", description = """
                    Returns the administrative account-state flags for a user: enabled, lock, account-expiry, and \
                    credential-expiry state with their timestamps, disabling actor, and failed-attempt count.
                    Use this tool to diagnose why sign-in fails before choosing among unlockUserAccount, \
                    enableUserAccount, and the expiry endpoints; use getUserById instead for identity and role data.
                    Preconditions: the caller must hold security:user_account_state:view and the user must exist.
                    Required inputs: id (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 with USER_NOT_FOUND when the user does not exist.
                    """)
    @ApiResponse(responseCode = "200", description = "User account state returned successfully")
    @ApiResponse(
            responseCode = "404",
            description = "User not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:user_account_state:view"})
    @PreAuthorize("hasAuthority('" + SecurityPermissions.USER_ACCOUNT_STATE_VIEW + "')")
    @GetMapping("/{id}/account-state")
    public ResponseEntity<AccountStateResponse> getAccountState(@PathVariable UUID id) {
        return ResponseEntity.ok(adminAccountStateService.getAccountState(id));
    }
}
