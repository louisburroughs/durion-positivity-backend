package com.positivity.supplier.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.supplier.internal.security.SupplierPermissions;
import com.positivity.supplier.service.SupplierProfileAdminService;
import com.positivity.supplier.service.model.CommercialAccountRequest;
import com.positivity.supplier.service.model.CommercialAccountView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Commercial account administration (ADR-0050 §5, durion-positivity-backend#1222): one
 * billing account per profile, one delivery account per (profile, pos-location). Account
 * numbers are ordinary profile data, distinct from credentials.
 */
@Tag(
        name = "Supplier Commercial Accounts",
        description = "Admin CRUD over vendor commercial accounts — billing and per-location delivery numbers")
@RestController
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/supplier/admin/profiles/{vendorProfileId}/accounts")
public class SupplierAccountAdminController {

    private final SupplierProfileAdminService adminService;

    @Operation(summary = "List commercial accounts", description = "Accounts of a vendor profile, billing first")
    @ApiResponse(responseCode = "200", description = "Accounts returned.")
    @ApiResponse(responseCode = "404", description = "Profile not found.")
    @PreAuthorize("hasAuthority('" + SupplierPermissions.PROFILE_READ + "')")
    @EmitEvent(id = "SUPPLIER_ACCOUNT_LIST", apiVersion = "1")
    @GetMapping
    public ResponseEntity<List<CommercialAccountView>> listAccounts(
            @Parameter(description = "Owning vendor profile UUID", required = true) @PathVariable @NotNull
                    UUID vendorProfileId) {
        return ResponseEntity.ok(adminService.listAccounts(vendorProfileId));
    }

    @Operation(
            summary = "Create commercial account",
            description = "Add a billing or delivery account; delivery accounts carry the pos-location mapping")
    @ApiResponse(responseCode = "201", description = "Account created.")
    @ApiResponse(responseCode = "400", description = "Invalid request.")
    @ApiResponse(responseCode = "404", description = "Profile not found.")
    @ApiResponse(responseCode = "409", description = "Profile is YAML-managed or account slot already taken.")
    @PreAuthorize("hasAuthority('" + SupplierPermissions.PROFILE_WRITE + "')")
    @EmitEvent(id = "SUPPLIER_ACCOUNT_CREATE", apiVersion = "1")
    @PostMapping
    public ResponseEntity<CommercialAccountView> createAccount(
            @Parameter(description = "Owning vendor profile UUID", required = true) @PathVariable @NotNull
                    UUID vendorProfileId,
            @Valid @NotNull @RequestBody CommercialAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminService.createAccount(vendorProfileId, request));
    }

    @Operation(summary = "Update commercial account", description = "Replace a commercial account")
    @ApiResponse(responseCode = "200", description = "Account updated.")
    @ApiResponse(responseCode = "400", description = "Invalid request.")
    @ApiResponse(responseCode = "404", description = "Profile or account not found.")
    @ApiResponse(responseCode = "409", description = "Profile is YAML-managed or account slot already taken.")
    @PreAuthorize("hasAuthority('" + SupplierPermissions.PROFILE_WRITE + "')")
    @EmitEvent(id = "SUPPLIER_ACCOUNT_UPDATE", apiVersion = "1")
    @PutMapping("/{accountId}")
    public ResponseEntity<CommercialAccountView> updateAccount(
            @Parameter(description = "Owning vendor profile UUID", required = true) @PathVariable @NotNull
                    UUID vendorProfileId,
            @Parameter(description = "Account UUID", required = true) @PathVariable @NotNull UUID accountId,
            @Valid @NotNull @RequestBody CommercialAccountRequest request) {
        return ResponseEntity.ok(adminService.updateAccount(vendorProfileId, accountId, request));
    }

    @Operation(summary = "Delete commercial account", description = "Remove a commercial account")
    @ApiResponse(responseCode = "204", description = "Account deleted.")
    @ApiResponse(responseCode = "404", description = "Profile or account not found.")
    @ApiResponse(responseCode = "409", description = "Profile is YAML-managed.")
    @PreAuthorize("hasAuthority('" + SupplierPermissions.PROFILE_WRITE + "')")
    @EmitEvent(id = "SUPPLIER_ACCOUNT_DELETE", apiVersion = "1")
    @DeleteMapping("/{accountId}")
    public ResponseEntity<Void> deleteAccount(
            @Parameter(description = "Owning vendor profile UUID", required = true) @PathVariable @NotNull
                    UUID vendorProfileId,
            @Parameter(description = "Account UUID", required = true) @PathVariable @NotNull UUID accountId) {
        adminService.deleteAccount(vendorProfileId, accountId);
        return ResponseEntity.noContent().build();
    }
}
