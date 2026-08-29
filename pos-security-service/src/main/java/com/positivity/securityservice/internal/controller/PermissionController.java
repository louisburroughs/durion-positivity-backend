package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.CatalogVersionResponse;
import com.positivity.securityservice.internal.dto.PermissionDecodeRequest;
import com.positivity.securityservice.internal.dto.PermissionDecodeResponse;
import com.positivity.securityservice.internal.dto.PermissionDto;
import com.positivity.securityservice.internal.dto.PermissionRegistrationRequest;
import com.positivity.securityservice.internal.dto.PermissionRegistrationResponse;
import com.positivity.securityservice.internal.security.SecurityPermissions;
import com.positivity.securityservice.internal.service.PermissionCatalogVersionService;
import com.positivity.securityservice.internal.service.PermissionRegistryService;
import com.positivity.securityservice.internal.service.PermissionService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for the central permission registry.
 * Provides endpoints for services to register their permissions.
 */
@RestController
@RequestMapping("/v1/permissions")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Permission Registry", description = "Central permission registry for all services")
public class PermissionController {

    private static final String PERMISSION_MANIFEST_EXAMPLE = """
            {"domain":"people",
             "serviceName":"pos-people-service",
             "version":"1.0",
             "permissions":[
               {"name":"people:timekeeping:view","description":"View timekeeping records"},
               {"name":"people:timekeeping:edit","description":"Edit timekeeping records"}]}
            """;

    private final PermissionRegistryService permissionRegistryService;
    private final PermissionService permissionService;
    private final PermissionCatalogVersionService permissionCatalogVersionService;

    /**
     * Returns the current permission catalog version and total permission count.
     * No authentication required — informational endpoint.
     */
    @GetMapping("/catalog-version")
    @PreAuthorize("permitAll()")
    @Operation(
            operationId = "getPermissionCatalogVersion",
            summary = "Get Current Permission Catalog Version",
            description = """
                    Returns the permission catalog version compiled into this service build and the total number of \
                    permission codes in that catalog.
                    Use this tool to check whether a cached perm_bits decoding is stale before calling \
                    decodePermissionBits; do not use listPermissions, which pages the database registry rather than \
                    the compiled catalog.
                    Preconditions: none; the endpoint is public and requires no authentication.
                    Required inputs: none; there are no parameters and no request body.
                    No events are emitted and no state changes; this is a read-only projection of the compiled \
                    PermissionCode catalog.
                    Returns 200 in all cases; there are no business error conditions.
                    """)
    public ResponseEntity<CatalogVersionResponse> getCatalogVersion() {
        return ResponseEntity.ok(new CatalogVersionResponse(
                permissionCatalogVersionService.getCatalogVersion(),
                permissionCatalogVersionService.getPermissionCount()));
    }

    /**
     * Decodes a perm_bits claim for diagnostic purposes.
     * Requires security:permission:view authority.
     */
    @EmitEvent(id = "SECURITY_PERMISSION_DECODE_EXECUTE", apiVersion = "1")
    @PostMapping("/decode")
    @Operation(
            operationId = "decodePermissionBits",
            summary = "Decode Permission Bits for Diagnostics",
            description = """
                    Decodes a Base64URL perm_bits bitset taken from an access token back into sorted permission \
                    code strings for diagnostics.
                    Use this tool to inspect what a token's perm_bits claim grants; do not use getUserPermissions, \
                    which reads a user's effective permissions from role assignments instead of from a token.
                    Preconditions: the caller must hold security:permission:view, and perm_ver must equal the \
                    catalog version currently compiled into the service (see getPermissionCatalogVersion).
                    Required inputs: perm_bits, the Base64URL-encoded BitSet string, and perm_ver, a positive \
                    integer catalog version.
                    Emits a SECURITY_PERMISSION_DECODE_EXECUTE event; no records are changed.
                    Returns 400 when perm_bits is blank, perm_ver is not positive, or perm_ver does not match the \
                    current catalog version.
                    """)
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:permission:view"})
    @PreAuthorize("hasAuthority('" + SecurityPermissions.PERMISSION_VIEW + "')")
    public ResponseEntity<PermissionDecodeResponse> decodePermissions(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Encoded permission bitset and the catalog version it was encoded with.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Decode request", value = """
                                                                    {"perm_bits":"AQID","perm_ver":7}
                                                                    """)))
                    @RequestBody
                    @Valid
                    @NonNull
                    PermissionDecodeRequest request) {
        if (!StringUtils.hasText(request.permBits())) {
            throw new IllegalArgumentException("perm_bits must be provided");
        }
        if (request.permVer() <= 0) {
            throw new IllegalArgumentException("perm_ver must be greater than 0");
        }
        return ResponseEntity.ok(new PermissionDecodeResponse(
                permissionCatalogVersionService.decodePermissions(request.permBits(), request.permVer())));
    }

    /**
     * Issue #42: Idempotent bulk permission registration endpoint for user RBAC
     * API.
     */
    @EmitEvent(id = "SECURITY_PERMISSION_REGISTER", apiVersion = "1")
    @PostMapping("/registerPermissions")
    @Operation(
            operationId = "registerPermissionsContract",
            summary = "Register Permissions via RBAC Contract",
            description = """
                    Registers or updates permissions from an RBAC contract payload, upserting each named permission \
                    and returning the resulting permission list.
                    Use this tool for strict all-or-nothing permission upserts; do not use \
                    registerModulePermissions, the lenient startup manifest endpoint that skips invalid entries and \
                    reports per-entry counters instead of failing the request.
                    Preconditions: the caller must hold security:permission:register, and every permission name \
                    must match domain:resource:action (or the legacy domain:action form).
                    Required inputs: permissions, a list of name and description definitions; serviceName is \
                    optional here and defaults to pos-security-service as the registering service.
                    Emits a SECURITY_PERMISSION_REGISTER event; existing permissions with the same name are \
                    overwritten in place, including description and registering service.
                    Returns 400 when any permission key fails the format check, rejecting the entire request.
                    """)
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:permission:register"})
    @PreAuthorize("hasAuthority('" + SecurityPermissions.PERMISSION_REGISTER + "')")
    public ResponseEntity<List<PermissionDto>> registerPermissionsContract(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "RBAC contract payload of permission definitions to upsert.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Contract registration",
                                                            value = PERMISSION_MANIFEST_EXAMPLE)))
                    @RequestBody
                    @NonNull
                    PermissionRegistrationRequest request) {
        return ResponseEntity.ok(permissionService.registerPermissions(request));
    }

    /**
     * Issue #42: Get a permission by UUID identifier.
     */
    @GetMapping("/{id}")
    @Operation(operationId = "getPermissionById", summary = "Get Permission by Identifier", description = """
                    Returns a single registered permission by its UUID identifier, including name, domain, \
                    description, and deprecation flag.
                    Use this tool when the permission id is already known; use listPermissions instead to search by \
                    domain or page through the registry.
                    Preconditions: the caller must hold security:permission:view and the permission must exist in \
                    the registry.
                    Required inputs: id (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no permission exists for the supplied id.
                    """)
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:permission:view"})
    @PreAuthorize("hasAuthority('" + SecurityPermissions.PERMISSION_VIEW + "')")
    public ResponseEntity<PermissionDto> getPermissionById(@PathVariable @NonNull UUID id) {
        return ResponseEntity.ok(permissionService.getPermission(id));
    }

    /**
     * Register or update permissions from a service
     */
    @EmitEvent(id = "SECURITY_PERMISSION_REGISTER", apiVersion = "1")
    @PostMapping("/register")
    @Operation(
            operationId = "registerModulePermissions",
            summary = "Register Module Permission Manifest",
            description = """
                    Registers or updates a module's permission manifest in the central registry; this is the \
                    endpoint every pos module's permission-registry initializer calls at startup.
                    Use this tool for idempotent bulk manifest registration that tolerates partial failure; do not \
                    use registerPermissionsContract, which rejects the entire payload on the first invalid key.
                    Preconditions: the caller must hold security:permission:register (module initializers \
                    authenticate with an internal service token).
                    Required inputs: serviceName and a non-empty permissions list of name and description entries; \
                    names must match domain:resource:action, while domain and version are informational.
                    Emits a SECURITY_PERMISSION_REGISTER event; new names are inserted (with a bit index when the \
                    compiled catalog defines one), changed descriptions are updated, and unchanged or invalid \
                    entries are counted as skipped.
                    Returns 400 when serviceName is blank or permissions is empty, and 400 with success=false and a \
                    per-entry errors list when every entry failed; partial failures still return 200 with the \
                    errors listed in the response.
                    """)
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:permission:register"})
    @PreAuthorize("hasAuthority('" + SecurityPermissions.PERMISSION_REGISTER + "')")
    public ResponseEntity<PermissionRegistrationResponse> registerPermissions(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Permission manifest published by one domain service at startup.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Startup manifest",
                                                            value = PERMISSION_MANIFEST_EXAMPLE)))
                    @RequestBody
                    PermissionRegistrationRequest request) {

        log.info("Received permission registration request from service: {}", request.getServiceName());

        PermissionRegistrationResponse response = permissionRegistryService.registerPermissions(request);

        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.badRequest().body(response);
        }
    }

    @GetMapping
    @Operation(operationId = "listPermissions", summary = "List Registered Permissions", description = """
                    Returns a paged list of registered permissions, optionally filtered to a single domain.
                    Use this tool to browse or search the permission registry; use getPermissionById instead when \
                    the UUID is already known, and getRoleDefaultPermissions to see what a role name expands to.
                    Preconditions: the caller must hold security:permission:view.
                    Required inputs: none are mandatory; domain is an optional filter, and page, size, and sort \
                    follow Spring pageable defaults (page 0, size 20).
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty page when nothing matches, and 400 when pagination parameters are \
                    malformed.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Permissions returned",
            content = @Content(schema = @Schema(implementation = Page.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid pagination parameters",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Insufficient authority",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:permission:view"})
    @PreAuthorize("hasAuthority('" + SecurityPermissions.PERMISSION_VIEW + "')")
    public ResponseEntity<Page<PermissionDto>> listPermissions(
            @Parameter(description = "Optional domain filter", required = false, example = "catalog")
                    @RequestParam(required = false)
                    String domain,
            @ParameterObject Pageable pageable) {
        Page<PermissionDto> permissions = permissionService.listPermissions(domain, pageable);
        return ResponseEntity.ok(permissions);
    }

    /**
     * Get permissions for a specific domain
     */
    @GetMapping("/domain/{domain}")
    @PreAuthorize("hasAuthority('" + SecurityPermissions.PERMISSION_VIEW + "')")
    @Operation(operationId = "listPermissionsByDomain", summary = "List Permissions for One Domain", description = """
                    Returns every registered permission for one domain as an unpaged list.
                    Use this tool when a complete domain snapshot is needed; use listPermissions instead when \
                    paging or when no domain filter applies.
                    Preconditions: the caller must hold security:permission:view.
                    Required inputs: domain as a path parameter, matching the first segment of the permission name \
                    (for example catalog in catalog:product:view).
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty list when the domain has no registered permissions; an unknown domain \
                    is not an error.
                    """)
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:permission:view"})
    public ResponseEntity<List<PermissionDto>> getPermissionsByDomain(@PathVariable String domain) {
        return ResponseEntity.ok(permissionRegistryService.getPermissionsByDomain(domain));
    }

    /**
     * Validate a permission name format
     */
    @GetMapping("/validate/{permissionName}")
    @Operation(operationId = "validatePermissionName", summary = "Validate Permission Name Format", description = """
                    Checks whether a permission name string matches the required domain:resource:action format (or \
                    the legacy two-segment domain:action form) without touching the database.
                    Use this tool to pre-validate a name before registration; use checkPermissionExists instead to \
                    test whether the name is actually registered.
                    Preconditions: the caller must hold security:permission:view.
                    Required inputs: permissionName as a path parameter; each segment must start with a letter and \
                    may contain letters, digits, underscores, and hyphens.
                    No events are emitted and no state changes; this is a pure format check.
                    Returns 200 with a plain boolean body; a malformed name yields false, never an error status.
                    """)
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:permission:view"})
    @PreAuthorize("hasAuthority('" + SecurityPermissions.PERMISSION_VIEW + "')")
    public ResponseEntity<Boolean> validatePermissionName(@PathVariable String permissionName) {
        boolean isValid = permissionRegistryService.isValidPermissionName(permissionName);
        return ResponseEntity.ok(isValid);
    }

    /**
     * Check if a permission exists
     */
    @GetMapping("/exists/{permissionName}")
    @Operation(
            operationId = "checkPermissionExists",
            summary = "Check Whether a Permission Is Registered",
            description = """
                    Checks whether a permission with the given name is registered in the central registry.
                    Use this tool to test registration state; use validatePermissionName instead for a pure format \
                    check that ignores the database.
                    Preconditions: the caller must hold security:permission:view.
                    Required inputs: permissionName as a path parameter in domain:resource:action format.
                    No events are emitted and no state changes; this is a read-only existence check.
                    Returns 200 with a plain boolean body; an unregistered name yields false, never an error status.
                    """)
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:permission:view"})
    @PreAuthorize("hasAuthority('" + SecurityPermissions.PERMISSION_VIEW + "')")
    public ResponseEntity<Boolean> permissionExists(@PathVariable String permissionName) {
        boolean exists = permissionRegistryService.permissionExists(permissionName);
        return ResponseEntity.ok(exists);
    }
}
