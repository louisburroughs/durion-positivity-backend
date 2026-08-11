package com.positivity.supplier.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.supplier.internal.security.SupplierPermissions;
import com.positivity.supplier.service.SupplierProfileAdminService;
import com.positivity.supplier.service.model.EndpointBindingRequest;
import com.positivity.supplier.service.model.EndpointBindingView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
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
 * Capability endpoint binding administration (ADR-0050 §3, durion-positivity-backend#1222):
 * at most one binding per capability per profile; an absent binding means the capability is
 * disabled. {@code capability} and {@code protocolFamily} are canonical string keys — unknown
 * keys surface as deterministic 400s with typed codes; {@code version} is data, not an enum
 * (ADR-0051 §3).
 */
@Tag(
        name = "Supplier Endpoint Bindings",
        description = "Admin CRUD over capability endpoint bindings — capability → (family, version, URL, auth)")
@RestController
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/supplier/admin/profiles/{vendorProfileId}/bindings")
public class SupplierEndpointBindingAdminController {

    private final SupplierProfileAdminService adminService;

    @Operation(summary = "List endpoint bindings", description = "Bindings of a vendor profile, ordered by capability")
    @ApiResponse(responseCode = "200", description = "Bindings returned.")
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
    @EmitEvent(id = "SUPPLIER_BINDING_LIST", apiVersion = "1")
    @GetMapping
    public ResponseEntity<List<EndpointBindingView>> listBindings(
            @Parameter(
                            description =
                                    "Owning vendor profile identifier (UUIDv7). Must reference an existing vendor profile.",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"))
                    @PathVariable
                    @NotNull
                    UUID vendorProfileId) {
        return ResponseEntity.ok(adminService.listBindings(vendorProfileId));
    }

    @Operation(
            summary = "Create endpoint binding",
            description = "Bind a capability to (protocolFamily, version, baseUrl, path, authConfigName)")
    @ApiResponse(responseCode = "201", description = "Binding created.")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request — unknown capability/protocol-family key, unknown auth config name,"
                    + " or invalid cron schedule.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Profile not found.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Profile is YAML-managed or capability already bound.",
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
    @EmitEvent(id = "SUPPLIER_BINDING_CREATE", apiVersion = "1")
    @PostMapping
    public ResponseEntity<EndpointBindingView> createBinding(
            @Parameter(
                            description =
                                    "Owning vendor profile identifier (UUIDv7). Must reference an existing vendor profile.",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"))
                    @PathVariable
                    @NotNull
                    UUID vendorProfileId,
            @Valid @NotNull @RequestBody EndpointBindingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminService.createBinding(vendorProfileId, request));
    }

    @Operation(summary = "Update endpoint binding", description = "Replace an endpoint binding")
    @ApiResponse(responseCode = "200", description = "Binding updated.")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request — unknown capability/protocol-family key, unknown auth config name,"
                    + " or invalid cron schedule.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Profile or binding not found.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Profile is YAML-managed or capability already bound.",
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
    @EmitEvent(id = "SUPPLIER_BINDING_UPDATE", apiVersion = "1")
    @PutMapping("/{bindingId}")
    public ResponseEntity<EndpointBindingView> updateBinding(
            @Parameter(
                            description =
                                    "Owning vendor profile identifier (UUIDv7). Must reference an existing vendor profile.",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"))
                    @PathVariable
                    @NotNull
                    UUID vendorProfileId,
            @Parameter(
                            description =
                                    "Endpoint binding identifier (UUIDv7). Must belong to the vendor profile in the path.",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a60"))
                    @PathVariable
                    @NotNull
                    UUID bindingId,
            @Valid @NotNull @RequestBody EndpointBindingRequest request) {
        return ResponseEntity.ok(adminService.updateBinding(vendorProfileId, bindingId, request));
    }

    @Operation(
            summary = "Delete endpoint binding",
            description = "Remove a binding, disabling its capability for the profile")
    @ApiResponse(responseCode = "204", description = "Binding deleted.")
    @ApiResponse(
            responseCode = "404",
            description = "Profile or binding not found.",
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
    @EmitEvent(id = "SUPPLIER_BINDING_DELETE", apiVersion = "1")
    @DeleteMapping("/{bindingId}")
    public ResponseEntity<Void> deleteBinding(
            @Parameter(
                            description =
                                    "Owning vendor profile identifier (UUIDv7). Must reference an existing vendor profile.",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"))
                    @PathVariable
                    @NotNull
                    UUID vendorProfileId,
            @Parameter(
                            description =
                                    "Endpoint binding identifier (UUIDv7). Must belong to the vendor profile in the path.",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a60"))
                    @PathVariable
                    @NotNull
                    UUID bindingId) {
        adminService.deleteBinding(vendorProfileId, bindingId);
        return ResponseEntity.noContent().build();
    }
}
