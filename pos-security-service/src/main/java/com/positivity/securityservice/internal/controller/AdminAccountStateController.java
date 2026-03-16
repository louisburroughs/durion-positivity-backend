package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.AccountStateResponse;
import com.positivity.securityservice.service.AdminAccountStateService;
import io.swagger.v3.oas.annotations.Operation;
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

    @Operation(summary = "Unlock a user account")
    @ApiResponse(responseCode = "204", description = "User account unlocked successfully")
    @EmitEvent(id = "SECURITY_USER_UNLOCK", apiVersion = "1")
    @PreAuthorize("hasAuthority('security:user_account_state:manage')")
    @PostMapping("/{id}/unlock")
    public ResponseEntity<Void> unlock(@PathVariable UUID id) {
        adminAccountStateService.unlock(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Enable a user account")
    @ApiResponse(responseCode = "204", description = "User account enabled successfully")
    @EmitEvent(id = "SECURITY_USER_ENABLE", apiVersion = "1")
    @PreAuthorize("hasAuthority('security:user_account_state:manage')")
    @PostMapping("/{id}/enable")
    public ResponseEntity<Void> enable(@PathVariable UUID id) {
        adminAccountStateService.enable(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Disable a user account")
    @ApiResponse(responseCode = "204", description = "User account disabled successfully")
    @EmitEvent(id = "SECURITY_USER_DISABLE", apiVersion = "1")
    @PreAuthorize("hasAuthority('security:user_account_state:manage')")
    @PostMapping("/{id}/disable")
    public ResponseEntity<Void> disable(@PathVariable UUID id) {
        adminAccountStateService.disable(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Expire a user account")
    @ApiResponse(responseCode = "204", description = "User account expired successfully")
    @EmitEvent(id = "SECURITY_USER_EXPIRE_ACCOUNT", apiVersion = "1")
    @PreAuthorize("hasAuthority('security:user_account_state:manage')")
    @PostMapping("/{id}/expire-account")
    public ResponseEntity<Void> expireAccount(@PathVariable UUID id) {
        adminAccountStateService.expireAccount(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Expire user credentials")
    @ApiResponse(responseCode = "204", description = "User credentials expired successfully")
    @EmitEvent(id = "SECURITY_USER_EXPIRE_CREDENTIALS", apiVersion = "1")
    @PreAuthorize("hasAuthority('security:user_account_state:manage')")
    @PostMapping("/{id}/expire-credentials")
    public ResponseEntity<Void> expireCredentials(@PathVariable UUID id) {
        adminAccountStateService.expireCredentials(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get user account state")
    @ApiResponse(responseCode = "200", description = "User account state returned successfully")
    @PreAuthorize("hasAuthority('security:user_account_state:view')")
    @GetMapping("/{id}/account-state")
    public ResponseEntity<AccountStateResponse> getAccountState(@PathVariable UUID id) {
        return ResponseEntity.ok(adminAccountStateService.getAccountState(id));
    }
}
