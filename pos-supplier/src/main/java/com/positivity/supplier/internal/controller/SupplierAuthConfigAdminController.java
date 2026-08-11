package com.positivity.supplier.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.supplier.internal.security.SupplierPermissions;
import com.positivity.supplier.service.SupplierProfileAdminService;
import com.positivity.supplier.service.model.AuthConfigRequest;
import com.positivity.supplier.service.model.AuthConfigView;
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
 * Vendor auth config administration (ADR-0050 §4, durion-positivity-backend#1222). Request
 * secret fields are <em>references</em> ({@code env:} / secret-store keys) and are write-only:
 * {@link AuthConfigView} carries no reference fields at all, so credential references never
 * serialize into responses.
 */
@Tag(
        name = "Supplier Auth Configs",
        description = "Admin CRUD over vendor auth configs — secret references only, never plaintext credentials")
@RestController
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/supplier/admin/profiles/{vendorProfileId}/auth-configs")
public class SupplierAuthConfigAdminController {

    private final SupplierProfileAdminService adminService;

    @Operation(summary = "List auth configs", description = "Auth configs of a vendor profile, ordered by name")
    @ApiResponse(responseCode = "200", description = "Auth configs returned.")
    @ApiResponse(responseCode = "404", description = "Profile not found.")
    @PreAuthorize("hasAuthority('" + SupplierPermissions.PROFILE_READ + "')")
    @EmitEvent(id = "SUPPLIER_AUTHCONFIG_LIST", apiVersion = "1")
    @GetMapping
    public ResponseEntity<List<AuthConfigView>> listAuthConfigs(
            @Parameter(description = "Owning vendor profile UUID", required = true) @PathVariable @NotNull
                    UUID vendorProfileId) {
        return ResponseEntity.ok(adminService.listAuthConfigs(vendorProfileId));
    }

    @Operation(
            summary = "Create auth config",
            description = "Add an auth config to a vendor profile; secret fields are references, never plaintext")
    @ApiResponse(responseCode = "201", description = "Auth config created (without credential reference values).")
    @ApiResponse(responseCode = "400", description = "Invalid request or incomplete/malformed secret references.")
    @ApiResponse(responseCode = "404", description = "Profile not found.")
    @ApiResponse(responseCode = "409", description = "Profile is YAML-managed or auth config name already in use.")
    @PreAuthorize("hasAuthority('" + SupplierPermissions.PROFILE_WRITE + "')")
    @EmitEvent(id = "SUPPLIER_AUTHCONFIG_CREATE", apiVersion = "1")
    @PostMapping
    public ResponseEntity<AuthConfigView> createAuthConfig(
            @Parameter(description = "Owning vendor profile UUID", required = true) @PathVariable @NotNull
                    UUID vendorProfileId,
            @Valid @NotNull @RequestBody AuthConfigRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminService.createAuthConfig(vendorProfileId, request));
    }

    @Operation(
            summary = "Update auth config",
            description = "Replace an auth config; renaming while endpoint bindings reference it is rejected")
    @ApiResponse(responseCode = "200", description = "Auth config updated (without credential reference values).")
    @ApiResponse(responseCode = "400", description = "Invalid request or incomplete/malformed secret references.")
    @ApiResponse(responseCode = "404", description = "Profile or auth config not found.")
    @ApiResponse(responseCode = "409", description = "Profile is YAML-managed, name in use, or config referenced.")
    @PreAuthorize("hasAuthority('" + SupplierPermissions.PROFILE_WRITE + "')")
    @EmitEvent(id = "SUPPLIER_AUTHCONFIG_UPDATE", apiVersion = "1")
    @PutMapping("/{authConfigId}")
    public ResponseEntity<AuthConfigView> updateAuthConfig(
            @Parameter(description = "Owning vendor profile UUID", required = true) @PathVariable @NotNull
                    UUID vendorProfileId,
            @Parameter(description = "Auth config UUID", required = true) @PathVariable @NotNull UUID authConfigId,
            @Valid @NotNull @RequestBody AuthConfigRequest request) {
        return ResponseEntity.ok(adminService.updateAuthConfig(vendorProfileId, authConfigId, request));
    }

    @Operation(
            summary = "Delete auth config",
            description = "Remove an auth config; rejected while endpoint bindings still reference it")
    @ApiResponse(responseCode = "204", description = "Auth config deleted.")
    @ApiResponse(responseCode = "404", description = "Profile or auth config not found.")
    @ApiResponse(responseCode = "409", description = "Profile is YAML-managed or config still referenced.")
    @PreAuthorize("hasAuthority('" + SupplierPermissions.PROFILE_WRITE + "')")
    @EmitEvent(id = "SUPPLIER_AUTHCONFIG_DELETE", apiVersion = "1")
    @DeleteMapping("/{authConfigId}")
    public ResponseEntity<Void> deleteAuthConfig(
            @Parameter(description = "Owning vendor profile UUID", required = true) @PathVariable @NotNull
                    UUID vendorProfileId,
            @Parameter(description = "Auth config UUID", required = true) @PathVariable @NotNull UUID authConfigId) {
        adminService.deleteAuthConfig(vendorProfileId, authConfigId);
        return ResponseEntity.noContent().build();
    }
}
