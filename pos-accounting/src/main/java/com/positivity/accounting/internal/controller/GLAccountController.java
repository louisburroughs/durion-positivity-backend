package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.GLAccountBalanceResponse;
import com.positivity.accounting.internal.dto.GLAccountCreateRequest;
import com.positivity.accounting.internal.dto.GLAccountListResponse;
import com.positivity.accounting.internal.dto.GLAccountResponse;
import com.positivity.accounting.internal.dto.GLAccountUpdateRequest;
import com.positivity.accounting.service.GLAccountService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller for GL Account (Chart of Accounts management).
 * Handles CRUD operations, activation/deactivation, and archival of GL
 * accounts.
 */
@RestController
@RequestMapping("/v1/accounting/gl-accounts")
@Tag(name = "GL Accounts", description = "Manage chart of accounts including lifecycle actions.")
@RequiredArgsConstructor
@Validated
public class GLAccountController {

    private static final Logger log = LoggerFactory.getLogger(GLAccountController.class);

    private final GLAccountService glAccountService;

    @GetMapping
    @SecurityRequirement(name = "bearerAuth", scopes = { "accounting:coa:view" })
    @PreAuthorize("hasAuthority('accounting:coa:view')")
    @Operation(summary = "List GL accounts", description = "Retrieve paginated GL accounts filtered by status and sorted by a field.", tags = {
            "GL Accounts" })
    @ApiResponse(responseCode = "200", description = "GL accounts listed")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    @EmitEvent(id = "ACCOUNTING_GL_ACCOUNT_LIST", apiVersion = "1")
    public ResponseEntity<GLAccountListResponse> listGLAccounts(
            @Parameter(description = "Page index (0-based)") @PositiveOrZero @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @Positive @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Sort field") @NotBlank @RequestParam(defaultValue = "accountCode") String sort,
            @Parameter(description = "Filter by account status") @RequestParam(required = false) String status) {
        log.info("List GL accounts");
        GLAccountListResponse response = glAccountService.listGLAccounts(page, size, sort, status);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{glAccountId}")
    @SecurityRequirement(name = "bearerAuth", scopes = { "accounting:coa:view" })
    @PreAuthorize("hasAuthority('accounting:coa:view')")
    @Operation(summary = "Get GL account", description = "Retrieve a GL account by identifier.", tags = {
            "GL Accounts" })
    @ApiResponse(responseCode = "200", description = "GL account returned")
    @ApiResponse(responseCode = "404", description = "GL account not found")
    public ResponseEntity<GLAccountResponse> getGLAccount(
            @Parameter(description = "GL account identifier") @PathVariable UUID glAccountId) {
        log.info("Get GL account");
        GLAccountResponse response = glAccountService.getGLAccount(glAccountId);
        return ResponseEntity.ok(response);
    }

    @PostMapping
    @SecurityRequirement(name = "bearerAuth", scopes = { "accounting:coa:create" })
    @PreAuthorize("hasAuthority('accounting:coa:create')")
    @Operation(summary = "Create GL account", description = "Create a new GL account.", tags = { "GL Accounts" })
    @ApiResponse(responseCode = "201", description = "GL account created")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @EmitEvent(id = "ACCOUNTING_GL_ACCOUNT_CREATE", apiVersion = "1")
    public ResponseEntity<GLAccountResponse> createGLAccount(@Valid @RequestBody GLAccountCreateRequest request) {
        log.info("Create GL account request received");
        GLAccountResponse response = glAccountService.createGLAccount(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{glAccountId}")
    @SecurityRequirement(name = "bearerAuth", scopes = { "accounting:coa:edit" })
    @PreAuthorize("hasAuthority('accounting:coa:edit')")
    @Operation(summary = "Update GL account", description = "Update details for an existing GL account.", tags = {
            "GL Accounts" })
    @ApiResponse(responseCode = "200", description = "GL account updated")
    @ApiResponse(responseCode = "404", description = "GL account not found")
    @EmitEvent(id = "ACCOUNTING_GL_ACCOUNT_UPDATE", apiVersion = "1")
    public ResponseEntity<GLAccountResponse> updateGLAccount(
            @Parameter(description = "GL account identifier") @PathVariable UUID glAccountId,
            @Valid @RequestBody GLAccountUpdateRequest request) {
        log.info("Update GL account");
        GLAccountResponse response = glAccountService.updateGLAccount(glAccountId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{glAccountId}/activate")
    @SecurityRequirement(name = "bearerAuth", scopes = { "accounting:coa:edit" })
    @PreAuthorize("hasAuthority('accounting:coa:edit')")
    @Operation(summary = "Activate GL account", description = "Mark a GL account as active.", tags = { "GL Accounts" })
    @ApiResponse(responseCode = "200", description = "GL account activated")
    @ApiResponse(responseCode = "404", description = "GL account not found")
    @EmitEvent(id = "ACCOUNTING_GL_ACCOUNT_ACTIVATE", apiVersion = "1")
    public ResponseEntity<GLAccountResponse> activateGLAccount(
            @Parameter(description = "GL account identifier") @PathVariable UUID glAccountId,
            @Valid @RequestBody com.positivity.accounting.internal.dto.GLAccountActivateRequest request) {
        log.info("Activate GL account");
        GLAccountResponse response = glAccountService.activateGLAccount(
                glAccountId, request.getEffectiveDate().atStartOfDay());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{glAccountId}/deactivate")
    @SecurityRequirement(name = "bearerAuth", scopes = { "accounting:coa:deactivate" })
    @PreAuthorize("hasAuthority('accounting:coa:deactivate')")
    @Operation(summary = "Deactivate GL account", description = "Mark a GL account as inactive.", tags = {
            "GL Accounts" })
    @ApiResponse(responseCode = "200", description = "GL account deactivated")
    @ApiResponse(responseCode = "404", description = "GL account not found")
    @EmitEvent(id = "ACCOUNTING_GL_ACCOUNT_DEACTIVATE", apiVersion = "1")
    public ResponseEntity<GLAccountResponse> deactivateGLAccount(
            @Parameter(description = "GL account identifier") @PathVariable UUID glAccountId,
            @RequestBody(required = false) Object request) {
        log.info("Deactivate GL account");
        GLAccountResponse response = glAccountService.deactivateGLAccount(glAccountId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{glAccountId}/archive")
    @SecurityRequirement(name = "bearerAuth", scopes = { "accounting:coa:deactivate" })
    @PreAuthorize("hasAuthority('accounting:coa:deactivate')")
    @Operation(summary = "Archive GL account", description = "Archive a GL account and remove it from active use.", tags = {
            "GL Accounts" })
    @ApiResponse(responseCode = "200", description = "GL account archived")
    @ApiResponse(responseCode = "404", description = "GL account not found")
    @EmitEvent(id = "ACCOUNTING_GL_ACCOUNT_ARCHIVE", apiVersion = "1")
    public ResponseEntity<GLAccountResponse> archiveGLAccount(
            @Parameter(description = "GL account identifier") @PathVariable UUID glAccountId,
            @RequestBody(required = false) Object request) {
        log.info("Archive GL account");
        GLAccountResponse response = glAccountService.archiveGLAccount(glAccountId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{glAccountId}/balance")
    @SecurityRequirement(name = "bearerAuth", scopes = { "accounting:coa:view" })
    @PreAuthorize("hasAuthority('accounting:coa:view')")
    @Operation(summary = "Get GL account balance", description = "Retrieve the current balance for a GL account.", tags = {
            "GL Accounts" })
    @ApiResponse(responseCode = "200", description = "Balance returned")
    @ApiResponse(responseCode = "404", description = "GL account not found")
    public ResponseEntity<GLAccountBalanceResponse> getAccountBalance(
            @Parameter(description = "GL account identifier") @PathVariable UUID glAccountId) {
        log.info("Get GL account balance");
        GLAccountBalanceResponse response = glAccountService.getAccountBalance(glAccountId);
        return ResponseEntity.ok(response);
    }
}
