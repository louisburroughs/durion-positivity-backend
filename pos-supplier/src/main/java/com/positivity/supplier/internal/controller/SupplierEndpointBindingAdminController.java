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

    private static final String BINDING_EXAMPLE = """
            {"capability":"STOCK_INQUIRY","protocolFamily":"EDIWHEEL_A25","version":"A2_5",
             "baseUrl":"https://edi.michelin.example/a25","path":"/stock/inquiry",
             "authConfigName":"ediwheel-basic","enabled":true,"captureLevel":"REDACTED"}
            """;

    private final SupplierProfileAdminService adminService;

    @Operation(operationId = "listEndpointBindings", summary = "List endpoint bindings", description = """
                    Returns the capability endpoint bindings of a vendor profile, ordered by capability, each with
                    its protocol family, version, URL, auth config name and enabled flag.
                    Use this tool to see which capabilities a supplier actually resolves; do not infer availability
                    from the profile itself, because an absent or disabled binding disables just that capability.
                    Preconditions: the vendor profile must exist.
                    Required inputs: vendorProfileId (UUIDv7) path parameter; there is no request body or filtering.
                    Emits a SUPPLIER_BINDING_LIST audit event; no configuration is changed.
                    Returns 404 when the vendor profile does not exist, and 200 with an empty array when the profile
                    has no bindings, which means every capability is unconfigured.
                    """)
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

    @Operation(operationId = "createEndpointBinding", summary = "Create endpoint binding", description = """
                    Binds one supplier capability on a vendor profile to a protocol family, adapter version, URL and
                    auth config, which is what makes that capability resolve at call time.
                    Use this tool when enabling a capability for a supplier; do not use it to change an existing
                    binding, which is updateEndpointBinding, since at most one binding per capability may exist.
                    Preconditions: the profile must exist and be ADMIN-managed, the capability must not already be
                    bound on it, and authConfigName must name an auth config on the same profile.
                    Required inputs: vendorProfileId (UUIDv7) path parameter plus capability, protocolFamily,
                    version, baseUrl, path and authConfigName; version is not validated on write, so a key with no
                    registered codec is accepted and then resolves to CAPABILITY_NOT_CONFIGURED on every call.
                    Emits a SUPPLIER_BINDING_CREATE audit event; captureLevel and the redaction classifications
                    decide what the exchange-audit trail retains for this binding from now on.
                    Returns 404 when the profile does not exist, 409 when the profile is YAML-managed or the
                    capability is already bound, and 400 when the capability, protocol family or auth config name is
                    unknown.
                    """)
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
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Endpoint binding to add to the vendor profile, mapping one capability to a vendor endpoint.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Stock inquiry over EDIWHEEL A2.5",
                                                            value = BINDING_EXAMPLE)))
                    @Valid
                    @NotNull
                    @RequestBody
                    EndpointBindingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminService.createBinding(vendorProfileId, request));
    }

    @Operation(operationId = "updateEndpointBinding", summary = "Update endpoint binding", description = """
                    Replaces every settable field of an endpoint binding, including its capability, adapter version,
                    URL, auth config, schedule, enabled flag and audit capture level.
                    Use this tool to repoint a capability or take it out of service by clearing enabled; do not use
                    it to bind a second capability, which is createEndpointBinding.
                    Preconditions: the profile must exist and be ADMIN-managed, the binding must belong to it, and a
                    changed capability must not already be bound elsewhere on the profile.
                    Required inputs: vendorProfileId and bindingId (UUIDv7) path parameters plus the full body,
                    because every field is replaced; omitting captureLevel resets it to the deployment default rather
                    than leaving the stored value.
                    Emits a SUPPLIER_BINDING_UPDATE audit event; a disabled binding immediately reports the typed
                    CAPABILITY_NOT_CONFIGURED outcome with HTTP 200 rather than failing.
                    Returns 404 when the profile or binding does not exist, 409 when the profile is YAML-managed or
                    the capability is already bound, and 400 when the capability, protocol family or auth config name
                    is unknown.
                    """)
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
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Endpoint binding to store in place of the existing one, mapping one capability to a vendor endpoint.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Stock inquiry over EDIWHEEL A2.5",
                                                            value = BINDING_EXAMPLE)))
                    @Valid
                    @NotNull
                    @RequestBody
                    EndpointBindingRequest request) {
        return ResponseEntity.ok(adminService.updateBinding(vendorProfileId, bindingId, request));
    }

    @Operation(operationId = "deleteEndpointBinding", summary = "Delete endpoint binding", description = """
                    Removes an endpoint binding, which disables that one capability for the vendor profile.
                    Use this tool when a capability is retired for a supplier; to pause it while keeping the
                    configuration, call updateEndpointBinding with enabled cleared instead, since a disabled binding
                    behaves exactly as an absent one.
                    Preconditions: the profile must exist and be ADMIN-managed, and the binding must belong to it.
                    Required inputs: vendorProfileId and bindingId (UUIDv7) path parameters; there is no request
                    body.
                    Emits a SUPPLIER_BINDING_DELETE audit event; the capability then resolves to the typed
                    CAPABILITY_NOT_CONFIGURED outcome, and exchange audit rows already written are retained.
                    Returns 404 when the profile or binding does not exist, and 409 when the profile is YAML-managed.
                    """)
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
