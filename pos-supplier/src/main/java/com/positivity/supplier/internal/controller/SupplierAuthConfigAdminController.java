package com.positivity.supplier.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.supplier.internal.security.SupplierPermissions;
import com.positivity.supplier.service.SupplierProfileAdminService;
import com.positivity.supplier.service.model.AuthConfigRequest;
import com.positivity.supplier.service.model.AuthConfigView;
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
@RequestMapping("/v1/supplier/admin/profiles/{vendorProfileId}/auth-configs")
public class SupplierAuthConfigAdminController {

    private static final String AUTH_CONFIG_EXAMPLE = """
            {"name":"ediwheel-basic","type":"BASIC_PLUS_APIKEY","usernameRef":"env:MICHELIN_EDI_USER",
             "passwordRef":"env:MICHELIN_EDI_PASSWORD","apiKeyRef":"env:MICHELIN_EDI_APIKEY","apiKeyHeader":"apikey"}
            """;

    private final SupplierProfileAdminService adminService;

    @Operation(operationId = "listAuthConfigs", summary = "List auth configs", description = """
                    Returns the auth configs of a vendor profile, ordered by name, showing the credential scheme and
                    which reference fields are populated.
                    Use this tool to find the auth config name an endpoint binding should reference; do not use it to
                    read credentials, because secret references are write-only and never appear in a response.
                    Preconditions: the vendor profile must exist.
                    Required inputs: vendorProfileId (UUIDv7) path parameter; there is no request body or filtering.
                    Emits a SUPPLIER_AUTHCONFIG_LIST audit event; no configuration is changed.
                    Returns 404 when the vendor profile does not exist, and 200 with an empty array when the profile
                    has no auth configs.
                    """)
    @ApiResponse(responseCode = "200", description = "Auth configs returned.")
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
    @EmitEvent(id = "SUPPLIER_AUTHCONFIG_LIST", apiVersion = "1")
    @GetMapping
    public ResponseEntity<List<AuthConfigView>> listAuthConfigs(
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
        return ResponseEntity.ok(adminService.listAuthConfigs(vendorProfileId));
    }

    @Operation(operationId = "createAuthConfig", summary = "Create auth config", description = """
                    Adds a named auth config to a vendor profile, describing one credential scheme as a set of secret
                    references resolved at call time.
                    Use this tool when a supplier connection needs credentials; do not put plaintext credentials in
                    any field, because every secret field takes a scheme-prefixed reference such as env:VAR_NAME and
                    plaintext is rejected.
                    Preconditions: the profile must exist and be ADMIN-managed, and the name must not already be used
                    by another auth config on the same profile.
                    Required inputs: vendorProfileId (UUIDv7) path parameter plus name and type in the body; the type
                    fixes which reference fields are required and which must be absent, so BASIC_PLUS_APIKEY needs
                    usernameRef, passwordRef and apiKeyRef while OAUTH2_CLIENT_CREDENTIALS needs tokenUrlRef,
                    clientIdRef and clientSecretRef.
                    Emits a SUPPLIER_AUTHCONFIG_CREATE audit event; the response omits every reference value.
                    Returns 404 when the profile does not exist, 409 when the profile is YAML-managed or the name is
                    taken, and 400 when the reference set does not match the declared type or a reference is
                    malformed.
                    """)
    @ApiResponse(responseCode = "201", description = "Auth config created (without credential reference values).")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request or incomplete/malformed secret references.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Profile not found.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Profile is YAML-managed or auth config name already in use.",
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
    @EmitEvent(id = "SUPPLIER_AUTHCONFIG_CREATE", apiVersion = "1")
    @PostMapping
    public ResponseEntity<AuthConfigView> createAuthConfig(
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
                                    "Auth config to add to the vendor profile. Every secret field is a reference, never a plaintext"
                                            + " credential.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Basic auth plus API key",
                                                            value = AUTH_CONFIG_EXAMPLE)))
                    @Valid
                    @NotNull
                    @RequestBody
                    AuthConfigRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminService.createAuthConfig(vendorProfileId, request));
    }

    @Operation(operationId = "updateAuthConfig", summary = "Update auth config", description = """
                    Replaces the name, credential scheme and secret references of an existing auth config.
                    Use this tool to rotate which references a connection resolves or to switch credential scheme; do
                    not use it to rename a config that endpoint bindings still reference, because bindings resolve it
                    by name and the rename is rejected.
                    Preconditions: the profile must exist and be ADMIN-managed, the auth config must belong to it,
                    and a new name must be free and unreferenced.
                    Required inputs: vendorProfileId and authConfigId (UUIDv7) path parameters plus the full body,
                    because every field is replaced; omitting a reference field clears it, which fails validation
                    when the declared type requires it.
                    Emits a SUPPLIER_AUTHCONFIG_UPDATE audit event; the new references take effect on the next
                    outbound call, and the response omits every reference value.
                    Returns 404 when the profile or config does not exist, 409 when the profile is YAML-managed or
                    the name is in use or still referenced, and 400 when the reference set does not match the
                    declared type.
                    """)
    @ApiResponse(responseCode = "200", description = "Auth config updated (without credential reference values).")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request or incomplete/malformed secret references.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Profile or auth config not found.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Profile is YAML-managed, name in use, or config referenced.",
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
    @EmitEvent(id = "SUPPLIER_AUTHCONFIG_UPDATE", apiVersion = "1")
    @PutMapping("/{authConfigId}")
    public ResponseEntity<AuthConfigView> updateAuthConfig(
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
                                    "Auth config identifier (UUIDv7). Must belong to the vendor profile in the path.",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a61"))
                    @PathVariable
                    @NotNull
                    UUID authConfigId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Auth config to store in place of the existing one. Every secret field is a reference, never a plaintext"
                                            + " credential.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Basic auth plus API key",
                                                            value = AUTH_CONFIG_EXAMPLE)))
                    @Valid
                    @NotNull
                    @RequestBody
                    AuthConfigRequest request) {
        return ResponseEntity.ok(adminService.updateAuthConfig(vendorProfileId, authConfigId, request));
    }

    @Operation(operationId = "deleteAuthConfig", summary = "Delete auth config", description = """
                    Removes an auth config from a vendor profile.
                    Use this tool when a credential scheme is retired; do not call it to rotate credentials, where
                    updateAuthConfig repoints the references instead, and repoint or delete the endpoint bindings
                    that name it first, because deletion is rejected while any binding still references it.
                    Preconditions: the profile must exist and be ADMIN-managed, the config must belong to it, and no
                    endpoint binding may reference it.
                    Required inputs: vendorProfileId and authConfigId (UUIDv7) path parameters; there is no request
                    body.
                    Emits a SUPPLIER_AUTHCONFIG_DELETE audit event; the referenced secrets themselves are untouched,
                    since only the reference is stored here.
                    Returns 404 when the profile or config does not exist, and 409 when the profile is YAML-managed
                    or a binding still references the config.
                    """)
    @ApiResponse(responseCode = "204", description = "Auth config deleted.")
    @ApiResponse(
            responseCode = "404",
            description = "Profile or auth config not found.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Profile is YAML-managed or config still referenced.",
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
    @EmitEvent(id = "SUPPLIER_AUTHCONFIG_DELETE", apiVersion = "1")
    @DeleteMapping("/{authConfigId}")
    public ResponseEntity<Void> deleteAuthConfig(
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
                                    "Auth config identifier (UUIDv7). Must belong to the vendor profile in the path.",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a61"))
                    @PathVariable
                    @NotNull
                    UUID authConfigId) {
        adminService.deleteAuthConfig(vendorProfileId, authConfigId);
        return ResponseEntity.noContent().build();
    }
}
