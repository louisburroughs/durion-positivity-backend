package com.positivity.supplier.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.supplier.internal.security.SupplierPermissions;
import com.positivity.supplier.service.SupplierProfileAdminService;
import com.positivity.supplier.service.model.VendorProfileRequest;
import com.positivity.supplier.service.model.VendorProfileView;
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
 * Vendor profile administration CRUD (ADR-0050 §1/§2/§6, durion-positivity-backend#1222).
 * Profiles created here are {@code ADMIN}-managed; mutations of {@code YAML}-managed profiles
 * are rejected with 409 by the service.
 */
@Tag(
        name = "Supplier Vendor Profiles",
        description = "Admin CRUD over vendor profiles — one row per configured supplier connection")
@RestController
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/supplier/admin/profiles")
public class SupplierProfileAdminController {

    private final SupplierProfileAdminService adminService;

    @Operation(summary = "List vendor profiles", description = "All vendor profiles, ordered by supplierRef")
    @ApiResponse(responseCode = "200", description = "Profiles returned.")
    @PreAuthorize("hasAuthority('" + SupplierPermissions.PROFILE_READ + "')")
    @EmitEvent(id = "SUPPLIER_PROFILE_LIST", apiVersion = "1")
    @GetMapping
    public ResponseEntity<List<VendorProfileView>> listProfiles() {
        return ResponseEntity.ok(adminService.listProfiles());
    }

    @Operation(summary = "Get vendor profile", description = "Retrieve one vendor profile by id")
    @ApiResponse(responseCode = "200", description = "Profile returned.")
    @ApiResponse(responseCode = "404", description = "Profile not found.")
    @PreAuthorize("hasAuthority('" + SupplierPermissions.PROFILE_READ + "')")
    @EmitEvent(id = "SUPPLIER_PROFILE_GET", apiVersion = "1")
    @GetMapping("/{vendorProfileId}")
    public ResponseEntity<VendorProfileView> getProfile(
            @Parameter(description = "Vendor profile UUID", required = true) @PathVariable @NotNull
                    UUID vendorProfileId) {
        return ResponseEntity.ok(adminService.getProfile(vendorProfileId));
    }

    @Operation(
            summary = "Create vendor profile",
            description = "Create an ADMIN-managed vendor profile; supplierRef must be unique")
    @ApiResponse(responseCode = "201", description = "Profile created.")
    @ApiResponse(responseCode = "400", description = "Invalid request.")
    @ApiResponse(responseCode = "409", description = "supplierRef already in use.")
    @PreAuthorize("hasAuthority('" + SupplierPermissions.PROFILE_WRITE + "')")
    @EmitEvent(id = "SUPPLIER_PROFILE_CREATE", apiVersion = "1")
    @PostMapping
    public ResponseEntity<VendorProfileView> createProfile(@Valid @NotNull @RequestBody VendorProfileRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminService.createProfile(request));
    }

    @Operation(
            summary = "Update vendor profile",
            description = "Full update of a vendor profile; rejected for YAML-managed profiles (ADR-0050 §6)")
    @ApiResponse(responseCode = "200", description = "Profile updated.")
    @ApiResponse(responseCode = "400", description = "Invalid request.")
    @ApiResponse(responseCode = "404", description = "Profile not found.")
    @ApiResponse(responseCode = "409", description = "Profile is YAML-managed or supplierRef already in use.")
    @PreAuthorize("hasAuthority('" + SupplierPermissions.PROFILE_WRITE + "')")
    @EmitEvent(id = "SUPPLIER_PROFILE_UPDATE", apiVersion = "1")
    @PutMapping("/{vendorProfileId}")
    public ResponseEntity<VendorProfileView> updateProfile(
            @Parameter(description = "Vendor profile UUID", required = true) @PathVariable @NotNull
                    UUID vendorProfileId,
            @Valid @NotNull @RequestBody VendorProfileRequest request) {
        return ResponseEntity.ok(adminService.updateProfile(vendorProfileId, request));
    }

    @Operation(
            summary = "Delete vendor profile",
            description = "Delete a vendor profile and its child configuration; rejected for YAML-managed profiles")
    @ApiResponse(responseCode = "204", description = "Profile deleted.")
    @ApiResponse(responseCode = "404", description = "Profile not found.")
    @ApiResponse(responseCode = "409", description = "Profile is YAML-managed.")
    @PreAuthorize("hasAuthority('" + SupplierPermissions.PROFILE_WRITE + "')")
    @EmitEvent(id = "SUPPLIER_PROFILE_DELETE", apiVersion = "1")
    @DeleteMapping("/{vendorProfileId}")
    public ResponseEntity<Void> deleteProfile(
            @Parameter(description = "Vendor profile UUID", required = true) @PathVariable @NotNull
                    UUID vendorProfileId) {
        adminService.deleteProfile(vendorProfileId);
        return ResponseEntity.noContent().build();
    }
}
