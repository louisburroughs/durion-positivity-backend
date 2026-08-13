package com.positivity.supplier.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.supplier.internal.security.SupplierPermissions;
import com.positivity.supplier.service.SupplierProfileAdminService;
import com.positivity.supplier.service.model.VendorProfileRequest;
import com.positivity.supplier.service.model.VendorProfileView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
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

    private static final String PROFILE_EXAMPLE = """
            {"supplierRef":"michelin-eu","displayName":"Michelin Europe","enabled":true,"sandbox":false,
             "connectTimeoutMillis":5000,"readTimeoutMillis":30000,"maxRetries":2,"retryBackoff":"EXPONENTIAL"}
            """;

    private final SupplierProfileAdminService adminService;

    @Operation(operationId = "listVendorProfiles", summary = "List vendor profiles", description = """
                    Returns every configured vendor profile, ordered by supplierRef, with its enabled, sandbox and
                    source-of-truth state.
                    Use this tool to discover a vendorProfileId before working with accounts, auth config or
                    bindings; use getVendorProfile instead when the id is already known.
                    Preconditions: none; the list is unfiltered and includes both ADMIN-managed and YAML-managed
                    profiles.
                    Required inputs: none, and there is no request body, paging or filtering.
                    Emits a SUPPLIER_PROFILE_LIST audit event; no configuration is changed.
                    Returns 200 with an empty array when nothing is configured, so an empty result is not an error
                    condition.
                    """)
    @ApiResponse(responseCode = "200", description = "Profiles returned.")
    @ApiResponse(
            responseCode = "401",
            description = "Authentication is missing or the bearer token is invalid."
                    + " The response has NO body: the gateway rejects unauthenticated calls with a bodiless"
                    + " status, so clients must not attempt to parse an error envelope here.",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = "Authenticated caller lacks the required supplier permission.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.PROFILE_READ + "')")
    @EmitEvent(id = "SUPPLIER_PROFILE_LIST", apiVersion = "1")
    @GetMapping
    public ResponseEntity<List<VendorProfileView>> listProfiles() {
        return ResponseEntity.ok(adminService.listProfiles());
    }

    @Operation(operationId = "getVendorProfile", summary = "Get vendor profile", description = """
                    Returns one vendor profile with its timeouts, retry settings, sandbox overlay and whether it is
                    ADMIN-managed or YAML-managed.
                    Use this tool when the vendorProfileId is already known; use listVendorProfiles instead to search
                    by supplierRef.
                    Preconditions: the profile must exist.
                    Required inputs: vendorProfileId (UUIDv7) path parameter; there is no request body.
                    Emits a SUPPLIER_PROFILE_GET audit event; no configuration is changed.
                    Returns 404 when no profile exists for the supplied id.
                    """)
    @ApiResponse(responseCode = "200", description = "Profile returned.")
    @ApiResponse(
            responseCode = "404",
            description = "Profile not found.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Authentication is missing or the bearer token is invalid."
                    + " The response has NO body: the gateway rejects unauthenticated calls with a bodiless"
                    + " status, so clients must not attempt to parse an error envelope here.",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = "Authenticated caller lacks the required supplier permission.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.PROFILE_READ + "')")
    @EmitEvent(id = "SUPPLIER_PROFILE_GET", apiVersion = "1")
    @GetMapping("/{vendorProfileId}")
    public ResponseEntity<VendorProfileView> getProfile(
            @Parameter(
                            description =
                                    "Vendor profile identifier (UUIDv7). Must reference an existing vendor profile.",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"))
                    @PathVariable
                    @NotNull
                    UUID vendorProfileId) {
        return ResponseEntity.ok(adminService.getProfile(vendorProfileId));
    }

    @Operation(operationId = "createVendorProfile", summary = "Create vendor profile", description = """
                    Creates an ADMIN-managed vendor profile, the row that carries one supplier connection and owns
                    its accounts, auth config and endpoint bindings.
                    Use this tool when onboarding a new supplier connection; do not use it to change an existing
                    profile, which is updateVendorProfile, and note that YAML-managed profiles cannot be created
                    here at all.
                    Preconditions: supplierRef must not already be in use by another profile.
                    Required inputs: supplierRef and displayName, both non-blank, plus the enabled and sandbox flags;
                    timeouts, maxRetries, retryBackoff and sandboxBaseUrlOverride are optional and fall back to the
                    deployment defaults when omitted.
                    Emits a SUPPLIER_PROFILE_CREATE audit event; the profile is created with no bindings, so it
                    resolves every capability to a not-configured outcome until bindings are added.
                    Returns 409 when supplierRef is already in use, and 400 when supplierRef or displayName are blank
                    or a timeout value is not greater than zero.
                    """)
    @ApiResponse(responseCode = "201", description = "Profile created.")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "supplierRef already in use.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Authentication is missing or the bearer token is invalid."
                    + " The response has NO body: the gateway rejects unauthenticated calls with a bodiless"
                    + " status, so clients must not attempt to parse an error envelope here.",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = "Authenticated caller lacks the required supplier permission.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.PROFILE_WRITE + "')")
    @EmitEvent(id = "SUPPLIER_PROFILE_CREATE", apiVersion = "1")
    @PostMapping
    public ResponseEntity<VendorProfileView> createProfile(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Vendor profile to create; the configuration source is always ADMIN.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Enabled production profile",
                                                            value = PROFILE_EXAMPLE)))
                    @Valid
                    @NotNull
                    @RequestBody
                    VendorProfileRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminService.createProfile(request));
    }

    @Operation(operationId = "updateVendorProfile", summary = "Update vendor profile", description = """
                    Replaces every settable field of a vendor profile, including its alias, display name, enabled and
                    sandbox flags and default timeouts.
                    Use this tool to change connection defaults or take a supplier out of service by clearing
                    enabled; do not use it on YAML-managed profiles, whose source of truth is the deployment
                    configuration instead.
                    Preconditions: the profile must exist, must be ADMIN-managed, and the supplierRef in the body
                    must not belong to a different profile.
                    Required inputs: vendorProfileId (UUIDv7) path parameter plus the full body, because every field
                    is replaced; omitting an optional field resets it to the deployment default rather than leaving
                    the stored value.
                    Emits a SUPPLIER_PROFILE_UPDATE audit event; disabling a profile immediately makes its bindings
                    resolve to a typed not-configured outcome.
                    Returns 404 when the profile does not exist, 409 when it is YAML-managed or the supplierRef is
                    taken, and 400 when a required field is blank or a timeout is not greater than zero.
                    """)
    @ApiResponse(responseCode = "200", description = "Profile updated.")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Profile not found.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Profile is YAML-managed or supplierRef already in use.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Authentication is missing or the bearer token is invalid."
                    + " The response has NO body: the gateway rejects unauthenticated calls with a bodiless"
                    + " status, so clients must not attempt to parse an error envelope here.",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = "Authenticated caller lacks the required supplier permission.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.PROFILE_WRITE + "')")
    @EmitEvent(id = "SUPPLIER_PROFILE_UPDATE", apiVersion = "1")
    @PutMapping("/{vendorProfileId}")
    public ResponseEntity<VendorProfileView> updateProfile(
            @Parameter(
                            description =
                                    "Vendor profile identifier (UUIDv7). Must reference an existing vendor profile.",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"))
                    @PathVariable
                    @NotNull
                    UUID vendorProfileId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Replacement values for every settable field of the vendor profile.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Enabled production profile",
                                                            value = PROFILE_EXAMPLE)))
                    @Valid
                    @NotNull
                    @RequestBody
                    VendorProfileRequest request) {
        return ResponseEntity.ok(adminService.updateProfile(vendorProfileId, request));
    }

    @Operation(operationId = "deleteVendorProfile", summary = "Delete vendor profile", description = """
                    Deletes a vendor profile together with its accounts, auth config and endpoint bindings.
                    Use this tool only when a supplier connection is being retired permanently; to stop traffic while
                    keeping the configuration, call updateVendorProfile with enabled cleared instead.
                    Preconditions: the profile must exist and must be ADMIN-managed, because YAML-managed profiles
                    are owned by the deployment configuration.
                    Required inputs: vendorProfileId (UUIDv7) path parameter; there is no request body and no
                    confirmation flag.
                    Emits a SUPPLIER_PROFILE_DELETE audit event and cascades to the profile's child configuration;
                    exchange audit records already written are retained.
                    Returns 404 when the profile does not exist and 409 when it is YAML-managed.
                    """)
    @ApiResponse(responseCode = "204", description = "Profile deleted.")
    @ApiResponse(
            responseCode = "404",
            description = "Profile not found.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Profile is YAML-managed.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Authentication is missing or the bearer token is invalid."
                    + " The response has NO body: the gateway rejects unauthenticated calls with a bodiless"
                    + " status, so clients must not attempt to parse an error envelope here.",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = "Authenticated caller lacks the required supplier permission.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.PROFILE_WRITE + "')")
    @EmitEvent(id = "SUPPLIER_PROFILE_DELETE", apiVersion = "1")
    @DeleteMapping("/{vendorProfileId}")
    public ResponseEntity<Void> deleteProfile(
            @Parameter(
                            description =
                                    "Vendor profile identifier (UUIDv7). Must reference an existing vendor profile.",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"))
                    @PathVariable
                    @NotNull
                    UUID vendorProfileId) {
        adminService.deleteProfile(vendorProfileId);
        return ResponseEntity.noContent().build();
    }
}
