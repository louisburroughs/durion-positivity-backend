package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.PermissionDto;
import com.positivity.securityservice.internal.dto.RoleAssignmentDto;
import com.positivity.securityservice.internal.dto.RoleAssignmentRequest;
import com.positivity.securityservice.internal.dto.RoleCreateRequest;
import com.positivity.securityservice.internal.dto.RoleDefaultPermissionsResponse;
import com.positivity.securityservice.internal.dto.RoleDto;
import com.positivity.securityservice.internal.dto.RolePermissionGrantRequest;
import com.positivity.securityservice.internal.dto.RolePermissionsRequest;
import com.positivity.securityservice.internal.dto.RolePersonasResponse;
import com.positivity.securityservice.internal.dto.RoleUpdateRequest;
import com.positivity.securityservice.internal.security.SecurityPermissions;
import com.positivity.securityservice.internal.service.RoleAuthorityService;
import com.positivity.securityservice.internal.service.RoleManagementService;
import com.positivity.securityservice.internal.service.RolePermissionService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for role management and role assignments.
 * Provides endpoints for creating roles, assigning permissions, and managing
 * user role assignments.
 */
@RestController
@RequestMapping("/v1/roles")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Role Management", description = "Manage roles, permissions, and user assignments")
public class RoleController {
    private final Clock clock;

    private final RoleManagementService roleManagementService;
    private final RolePermissionService rolePermissionService;
    private final RoleAuthorityService roleAuthorityService;

    /**
     * Create a new role
     */
    @EmitEvent(id = "SECURITY_ROLE_CREATE", apiVersion = "1")
    @PostMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:create"})
    @PreAuthorize("hasAuthority('" + SecurityPermissions.ROLE_CREATE + "')")
    @Operation(operationId = "createRole", summary = "Create a New Role", description = """
                    Creates a new role with the given name and optional description; the role starts with no \
                    permissions and no user assignments.
                    Use this tool to define a new role; do not use createRoleAssignment, which links an existing \
                    role to a user, and do not use updateRolePermissions, which changes an existing role's grants.
                    Preconditions: the caller must hold security:role:create and no role with the same name \
                    (compared case-insensitively) may already exist.
                    Required inputs: name, non-blank; description is optional.
                    Emits a SECURITY_ROLE_CREATE event and records the creating actor and timestamp.
                    Returns 400 when name is missing or blank, and 409 with DUPLICATE_ROLE_NAME when the name is \
                    already taken regardless of case.
                    """)
    @ApiResponse(responseCode = "201", description = "Role created")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid role payload",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Role name already exists",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<RoleDto> createRole(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Name, optional description, and optional MCP persona metadata.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "New role", value = """
                                                    {"name":"WARRANTY_CLERK","description":"Warranty claim intake",\
                                                    "personaTitle":"warranty clerk","mcpPersonaRank":45}
                                                    """)))
                    @Valid
                    @RequestBody
                    RoleCreateRequest request) {
        RoleDto role = roleManagementService.createRole(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(role);
    }

    /**
     * Issue #1613: edit a role's description and MCP persona metadata.
     */
    @EmitEvent(id = "SECURITY_ROLE_UPDATE", apiVersion = "1")
    @PutMapping("/{id}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:edit"})
    @PreAuthorize("hasAuthority('" + SecurityPermissions.ROLE_EDIT + "')")
    @Operation(
            operationId = "updateRole",
            summary = "Update a Role's Description and Persona Metadata",
            description = """
                    Replaces a role's description and its MCP persona metadata: the persona title, focus, and tone \
                    slots, the persona rank, and whether the role participates in persona resolution at all.
                    Use this tool to correct or rank a role's assistant persona; do not use updateRolePermissions, \
                    which changes the role's permission grants, and do not use it to rename a role — the name keys \
                    authority resolution and permission grants and is not updatable here.
                    Preconditions: the caller must hold security:role:edit and the role id must exist.
                    Required inputs: role id as a path parameter. Every body field is optional, but an omitted field \
                    clears the stored value rather than leaving it unchanged, which is how a persona slot is returned \
                    to its derived default.
                    Persona slots must describe the role rather than instruct the assistant: single line, within the \
                    length cap, and free of imperative control verbs.
                    Emits a SECURITY_ROLE_UPDATE event and records the modifying actor and timestamp.
                    Returns 400 when a persona slot fails validation and 404 when no role has that id.
                    """)
    @ApiResponse(responseCode = "200", description = "Role updated")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid persona payload",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Role not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<RoleDto> updateRole(
            @PathVariable UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Replacement description and MCP persona metadata.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Persona edit", value = """
                                                    {"description":"Branch operations lead",\
                                                    "personaTitle":"shop manager",\
                                                    "personaFocus":"branch operations and queue control",\
                                                    "personaTone":"decisive and operational","mcpPersonaRank":35}
                                                    """)))
                    @Valid
                    @RequestBody
                    RoleUpdateRequest request) {
        return ResponseEntity.ok(roleManagementService.updateRole(id, request));
    }

    /**
     * Issue #1613: persona projection consumed by pos-mcp-server.
     */
    @GetMapping("/personas")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:view"})
    @PreAuthorize("hasAuthority('" + SecurityPermissions.ROLE_VIEW + "')")
    @Operation(operationId = "getRolePersonas", summary = "Get MCP Persona Metadata for Every Role", description = """
                    Returns one row per role with its MCP persona slots, resolution rank, and eligibility flag, \
                    ordered by rank then name, plus the timestamp the snapshot was assembled.
                    Use this tool to derive assistant personas and their priority order from role data, as \
                    pos-mcp-server does; do not use getAllRoles, which returns the full role graph including every \
                    permission grant and is far more expensive for this purpose.
                    Preconditions: the caller must hold security:role:view.
                    Required inputs: none.
                    No events are emitted and no state changes; this is a read-only projection.
                    Roles that are excluded from persona resolution are included and flagged rather than omitted, so \
                    a consumer can distinguish a role excluded by design from one it has never seen.
                    Returns 200 with every role and never 404: an environment holding no roles yields an empty list \
                    rather than an error, so a consumer cannot mistake "not provisioned yet" for "endpoint missing".
                    """)
    @ApiResponse(responseCode = "200", description = "Role persona snapshot returned")
    public ResponseEntity<RolePersonasResponse> getRolePersonas() {
        return ResponseEntity.ok(roleManagementService.getRolePersonas());
    }

    /**
     * Issue #42: Grant permission to role and emit audit event.
     */
    @EmitEvent(id = "SECURITY_ROLE_PERMISSION_GRANT", apiVersion = "1")
    @PutMapping("/{roleId}/permissions/grant")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:edit"})
    @PreAuthorize("hasAuthority('" + SecurityPermissions.ROLE_EDIT + "')")
    @Operation(operationId = "grantRolePermission", summary = "Grant a Permission to a Role", description = """
                    Grants a single permission key to a role, auto-registering the key in the permission registry \
                    when it is not yet known.
                    Use this tool for additive one-off grants; do not use updateRolePermissions, which replaces the \
                    role's entire permission set, and do not use assignRolePermissionByKey, which requires the \
                    permission to be registered already.
                    Preconditions: the caller must hold security:role:edit and the role must exist; the permission \
                    key does not need to exist beforehand.
                    Required inputs: roleId (UUID) as a path parameter and permission (domain:resource:action) in \
                    the body; permissionKey is accepted as a field alias.
                    Emits a SECURITY_ROLE_PERMISSION_GRANT event and publishes an internal grant audit record, then \
                    returns the updated role.
                    Returns 400 when permission is missing or blank, and 404 when the role does not exist.
                    """)
    @ApiResponse(responseCode = "200", description = "Permission granted to role")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid grant request",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Role or permission not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<RoleDto> grantPermissionToRole(
            @NonNull @PathVariable UUID roleId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The single permission key to grant to the role.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Grant one permission", value = """
                                                                    {"permission":"people:timekeeping:view"}
                                                                    """)))
                    @RequestBody
                    RolePermissionGrantRequest request) {
        if (request == null
                || request.getPermission() == null
                || request.getPermission().isBlank()) {
            throw new IllegalArgumentException("permission is required");
        }
        return ResponseEntity.ok(rolePermissionService.grantPermission(roleId, request.getPermission()));
    }

    /**
     * Issue #42: Revoke permission from role.
     */
    @EmitEvent(id = "SECURITY_ROLE_PERMISSION_REVOKE", apiVersion = "1")
    @DeleteMapping("/{roleId}/permissions/{permissionKey}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:edit"})
    @PreAuthorize("hasAuthority('" + SecurityPermissions.ROLE_EDIT + "')")
    @Operation(operationId = "revokeRolePermission", summary = "Revoke a Permission From a Role", description = """
                    Removes a permission key from a role's grant set.
                    Use this tool to take one permission away from a role; do not use deleteRole, which removes the \
                    role entirely, and do not use revokeRoleAssignment, which ends a user's assignment instead.
                    Preconditions: the caller must hold security:role:edit and the role must exist; revoking a key \
                    the role does not hold is a silent no-op.
                    Required inputs: roleId (UUID) and permissionKey (domain:resource:action) as path parameters; \
                    there is no request body.
                    Emits a SECURITY_ROLE_PERMISSION_REVOKE event.
                    Returns 404 when the role does not exist; an unknown permission key still yields 204.
                    """)
    @ApiResponse(responseCode = "204", description = "Permission revoked from role")
    @ApiResponse(
            responseCode = "404",
            description = "Role or permission not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Void> revokePermissionFromRole(
            @NonNull @PathVariable UUID roleId, @PathVariable String permissionKey) {
        roleManagementService.revokePermissionFromRole(roleId, permissionKey);
        return ResponseEntity.noContent().build();
    }

    /**
     * Story #62: Assign a permission to a role by permission key.
     */
    @EmitEvent(id = "SECURITY_ROLE_PERMISSION_ASSIGN", apiVersion = "1")
    @PutMapping("/{roleId}/permissions/{permissionKey}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:edit"})
    @PreAuthorize("hasAuthority('" + SecurityPermissions.ROLE_EDIT + "')")
    @Operation(
            operationId = "assignRolePermissionByKey",
            summary = "Assign a Registered Permission to a Role",
            description = """
                    Adds an already-registered permission, identified by its key in the path, to a role's grant set.
                    Use this tool when the permission is known to be registered; do not use grantRolePermission, \
                    which auto-registers unknown keys, and do not use updateRolePermissions, which replaces the \
                    whole set.
                    Preconditions: the caller must hold security:role:edit, the role must exist, and the permission \
                    key must already be registered (modules register at startup via registerModulePermissions).
                    Required inputs: roleId (UUID) and permissionKey (domain:resource:action) as path parameters; \
                    there is no request body.
                    Emits a SECURITY_ROLE_PERMISSION_ASSIGN event.
                    Returns 404 when the role does not exist or the permission key has not been registered.
                    """)
    @ApiResponse(responseCode = "204", description = "Permission assigned to role")
    @ApiResponse(
            responseCode = "404",
            description = "Role or permission not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Void> assignPermissionToRole(
            @NonNull @PathVariable UUID roleId, @PathVariable String permissionKey) {
        roleManagementService.assignPermissionToRole(roleId, permissionKey);
        return ResponseEntity.noContent().build();
    }

    /**
     * Update permissions for a role
     */
    @EmitEvent(id = "SECURITY_ROLE_PERMISSIONS_UPDATE", apiVersion = "1")
    @PutMapping("/permissions")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:edit"})
    @PreAuthorize("hasAuthority('" + SecurityPermissions.ROLE_EDIT + "')")
    @Operation(operationId = "updateRolePermissions", summary = "Replace a Role's Permission Set", description = """
                    Replaces a role's entire permission set with the supplied list of permission names.
                    Use this tool for wholesale permission resets; do not use grantRolePermission or \
                    revokeRolePermission, which change one key at a time and preserve the rest.
                    Preconditions: the caller must hold security:role:edit, the role must exist, and every named \
                    permission must already be registered.
                    Required inputs: roleId (UUID) and permissionNames, a set of domain:resource:action strings; an \
                    empty set clears all grants.
                    Emits a SECURITY_ROLE_PERMISSIONS_UPDATE event and returns the updated role with its new \
                    permission set.
                    Returns 404 when the role or any named permission does not exist; nothing is applied on failure.
                    """)
    @ApiResponse(responseCode = "200", description = "Role permissions updated")
    @ApiResponse(
            responseCode = "404",
            description = "Role or permission not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<RoleDto> updateRolePermissions(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The role and the replacement set of permission names it should hold.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Replace permission set", value = """
                                                                    {"roleId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "permissionNames":["people:timekeeping:view","people:timekeeping:edit"]}
                                                                    """)))
                    @RequestBody
                    RolePermissionsRequest request) {
        RoleDto role = roleManagementService.updateRolePermissions(request);
        return ResponseEntity.ok(role);
    }

    /**
     * Create a role assignment for a user
     */
    @EmitEvent(id = "SECURITY_ROLE_ASSIGNMENT_CREATE", apiVersion = "1")
    @PostMapping("/assignments")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:assign"})
    @PreAuthorize("hasAuthority('" + SecurityPermissions.ROLE_ASSIGN + "')")
    @Operation(operationId = "createRoleAssignment", summary = "Create a Scoped Role Assignment", description = """
                    Assigns a role to a user with a scope (GLOBAL or LOCATION) and an optional effective date \
                    window.
                    Use this tool when the assignment needs scope or dates; do not use assignUserRole, the simple \
                    path-parameter variant that always creates a GLOBAL assignment starting now.
                    Preconditions: the caller must hold security:role:assign, the user and role must exist, and no \
                    overlapping assignment may exist for the same role, scope, and (for LOCATION scope) location.
                    Required inputs: userId and roleId (UUIDs); scopeType defaults to GLOBAL, scopeLocationIds is \
                    required for LOCATION scope and forbidden for GLOBAL, and effectiveStartDate defaults to now \
                    with an open-ended effectiveEndDate.
                    Emits a SECURITY_ROLE_ASSIGNMENT_CREATE event.
                    Returns 400 when the scope and location combination is invalid, 404 when the user or role does \
                    not exist, and 409 with ROLE_ASSIGNMENT_CONFLICT when the date window overlaps an existing \
                    assignment.
                    """)
    @ApiResponse(responseCode = "201", description = "Role assignment created")
    @ApiResponse(
            responseCode = "404",
            description = "User or role not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<RoleAssignmentDto> createRoleAssignment(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The user, role, scope, and effective window of the assignment to create.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(name = "Location-scoped assignment", value = """
                                                                    {"userId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "roleId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                                     "scopeType":"LOCATION",
                                                                     "scopeLocationIds":["018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d"],
                                                                     "effectiveStartDate":"2026-01-15T00:00:00",
                                                                     "effectiveEndDate":"2026-12-31T00:00:00"}
                                                                    """)))
                    @RequestBody
                    RoleAssignmentRequest request) {
        RoleAssignmentDto assignment = roleManagementService.createRoleAssignment(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(assignment);
    }

    /**
     * Get effective role assignments for a user
     */
    @GetMapping("/assignments/user/{userId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:view"})
    @PreAuthorize("hasAuthority('" + SecurityPermissions.ROLE_VIEW + "')")
    @Operation(operationId = "listUserRoleAssignments", summary = "List a User's Role Assignments", description = """
                    Returns a user's role assignments with their scope and effective window, limited to currently \
                    effective assignments by default.
                    Use this tool to inspect who holds which roles and in what scope; use getUserPermissions \
                    instead when only the flattened permission set matters.
                    Preconditions: the caller must hold security:role:view and the user must exist.
                    Required inputs: userId (UUID) as a path parameter; includeHistory defaults to false and, when \
                    true, also returns expired and revoked assignments.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when the user does not exist.
                    """)
    @ApiResponse(responseCode = "200", description = "Role assignments returned successfully")
    @ApiResponse(
            responseCode = "404",
            description = "User not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<RoleAssignmentDto>> getUserRoleAssignments(
            @Parameter(description = "User ID", example = "123e4567-e89b-12d3-a456-426614174000") @PathVariable
                    UUID userId,
            @Parameter(description = "Include historical assignments (expired/revoked)", example = "false")
                    @RequestParam(defaultValue = "false")
                    boolean includeHistory) {
        List<RoleAssignmentDto> assignments = roleManagementService.getAssignmentsForUser(userId, includeHistory);
        return ResponseEntity.ok(assignments);
    }

    /**
     * Get all permissions for a user
     */
    @GetMapping("/permissions/user/{userId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:permission:view"})
    // Same guard as the canonical path (#1612). These two operations return the identical payload
    // from the identical service call, so authorising them differently would mean a caller could
    // read their own permissions at one URL and not the other — a divergence that is a trap rather
    // than a policy.
    @PreAuthorize("hasAuthority('" + SecurityPermissions.PERMISSION_VIEW + "') or @userSelfCheck.isSelf(#userId)")
    @Operation(
            operationId = "getUserPermissionsLegacy",
            summary = "Get User Permissions at Legacy Path",
            description = """
                    Returns the union of permissions granted through a user's currently effective role assignments, \
                    served at the legacy /permissions/user/{userId} path.
                    Use this tool only for legacy callers; use getUserPermissions instead, which returns the same \
                    data at the canonical /v1/users/{userId}/permissions path.
                    Preconditions: the user must exist, and the caller must either hold \
                    security:permission:view or be asking about themselves.
                    Required inputs: userId (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 403 when the caller is asking about another user without \
                    security:permission:view, and 404 when the user does not exist.
                    """)
    @ApiResponse(responseCode = "200", description = "User permissions returned successfully")
    @ApiResponse(
            responseCode = "404",
            description = "User not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Set<PermissionDto>> getUserPermissionsByUserId(@PathVariable UUID userId) {
        Set<PermissionDto> permissions = roleManagementService.getUserPermissions(userId);
        return ResponseEntity.ok(permissions);
    }

    /**
     * Get the authority codes a role expands to (#782).
     *
     * <p>Consumed by pos-mcp-server to prebuild per-role agent tool caches with the role's real
     * permission set instead of an AUTHENTICATED-only warm-up.
     *
     * <p>Reads the role's persisted {@code role_permissions} grants — the same resolution the
     * login path uses to build {@code perm_bits} — so the response matches what a token issued
     * for that role actually carries.
     */
    @GetMapping("/{role}/default-permissions")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:view"})
    @PreAuthorize("hasAuthority('" + SecurityPermissions.ROLE_VIEW + "')")
    @Operation(
            operationId = "getRoleDefaultPermissions",
            summary = "Get a Role's Default Authority Expansion",
            description = """
                    Returns the authority codes a role name expands to: the ROLE_ prefixed authority plus every \
                    permission code granted to that role in role_permissions.
                    Use this tool to prebuild per-role permission or tool caches, as pos-mcp-server does; do not \
                    use getUserPermissions, which reads one specific user's scoped role assignments rather than \
                    the authority set a role carries.
                    Preconditions: the caller must hold security:role:view; the role name does not need to exist in \
                    the database.
                    Required inputs: role name as a path parameter, for example SHOP_MGR.
                    No events are emitted and no state changes; this is a read-only lookup of the role's persisted \
                    permission grants.
                    Returns 200 in all cases; an unrecognized role, or a role with no grants, yields only its \
                    ROLE_ authority with no domain codes rather than an error.
                    """)
    @ApiResponse(responseCode = "200", description = "Role default permissions returned")
    public ResponseEntity<RoleDefaultPermissionsResponse> getRoleDefaultPermissions(@PathVariable String role) {
        Set<String> authorities = roleAuthorityService.expandRolesToAuthorities(Set.of(role));
        List<String> permissions = authorities.stream().sorted().toList();
        return ResponseEntity.ok(new RoleDefaultPermissionsResponse(role, permissions));
    }

    /**
     * Check if a user has a specific permission
     */
    @GetMapping("/check-permission")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:permission:view"})
    @PreAuthorize("hasAuthority('" + SecurityPermissions.PERMISSION_VIEW + "')")
    @Operation(
            operationId = "checkUserPermission",
            summary = "Check One User Permission at a Location",
            description = """
                    Checks whether a user holds a specific permission through a currently effective role assignment \
                    whose scope covers the given location.
                    Use this tool for a point authorization probe by user UUID; use getAuthorizationDecision \
                    instead when the caller has a principal identifier from the RBAC matrix rather than a user id.
                    Preconditions: the caller must hold security:permission:view and the user must exist.
                    Required inputs: userId (UUID) and permission (domain:resource:action) as query parameters; \
                    locationId is optional and defaults to GLOBAL.
                    No events are emitted and no state changes; this is a read-only check.
                    Returns 200 with a plain boolean body, and 404 when the user does not exist.
                    """)
    @ApiResponse(responseCode = "200", description = "Permission check completed")
    @ApiResponse(
            responseCode = "404",
            description = "User not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Boolean> checkUserPermission(
            @RequestParam UUID userId,
            @RequestParam String permission,
            @RequestParam(required = false) String locationId) {

        boolean hasPermission =
                roleManagementService.userHasPermission(userId, permission, locationId != null ? locationId : "GLOBAL");
        return ResponseEntity.ok(hasPermission);
    }

    /**
     * Revoke a role assignment
     */
    @EmitEvent(id = "SECURITY_ROLE_ASSIGNMENT_REVOKE", apiVersion = "1")
    @DeleteMapping("/assignments/{assignmentId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:assign"})
    @PreAuthorize("hasAuthority('" + SecurityPermissions.ROLE_ASSIGN + "')")
    @Operation(operationId = "revokeRoleAssignment", summary = "Revoke a Role Assignment by Id", description = """
                    Revokes a role assignment by setting its effective end date, preserving the row for history.
                    Use this tool when the assignment id is known or a past or future end date is needed; do not \
                    use revokeUserRole, which finds the active assignment from userId and roleId and ends it now.
                    Preconditions: the caller must hold security:role:assign and the assignment must exist.
                    Required inputs: assignmentId (UUID) as a path parameter; endDate (ISO date-time) is optional \
                    and defaults to the current time, and the revocation timestamp is recorded automatically.
                    Emits a SECURITY_ROLE_ASSIGNMENT_REVOKE event.
                    Returns 400 when endDate is malformed, and 404 when the assignment does not exist.
                    """)
    @ApiResponse(responseCode = "204", description = "Role assignment revoked")
    @ApiResponse(responseCode = "400", description = "Invalid endDate")
    @ApiResponse(responseCode = "404", description = "Role assignment not found")
    public ResponseEntity<Void> revokeRoleAssignment(
            @Parameter(description = "Role assignment ID", example = "123e4567-e89b-12d3-a456-426614174000")
                    @PathVariable
                    UUID assignmentId,
            @Parameter(
                            description =
                                    "Effective end date and time for revocation. Defaults to current date and time",
                            example = "2026-02-16T10:30:00")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime endDate) {
        LocalDateTime effectiveEndDate = endDate != null ? endDate : LocalDateTime.now(clock);
        roleManagementService.revokeRoleAssignment(assignmentId, effectiveEndDate);
        return ResponseEntity.noContent().build();
    }

    /**
     * Get all roles
     */
    @GetMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:view"})
    @PreAuthorize("hasAuthority('" + SecurityPermissions.ROLE_VIEW + "')")
    @Operation(operationId = "listRoles", summary = "List All Roles in the System", description = """
                    Returns every role in the system with its permission set and audit metadata.
                    Use this tool to enumerate roles; use getRoleById or getRoleByName instead for a single known \
                    role.
                    Preconditions: the caller must hold security:role:view.
                    Required inputs: none; there are no filters or paging parameters.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty list when no roles exist; there are no business error conditions.
                    """)
    @ApiResponse(responseCode = "200", description = "Roles returned successfully")
    public ResponseEntity<List<RoleDto>> getAllRoles() {
        return ResponseEntity.ok(roleManagementService.getAllRoles());
    }

    /**
     * Get role by name.
     */
    @GetMapping("/by-name/{name}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:view"})
    @PreAuthorize("hasAuthority('" + SecurityPermissions.ROLE_VIEW + "')")
    @Operation(operationId = "getRoleByName", summary = "Get a Single Role by Name", description = """
                    Returns a single role looked up by its exact name, including its permission set.
                    Use this tool when only the role name is known; use getRoleById instead when the UUID is \
                    available, and listRoles to browse.
                    Preconditions: the caller must hold security:role:view and a role with that exact \
                    (case-sensitive) name must exist.
                    Required inputs: name as a path parameter.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 with ROLE_NOT_FOUND when no role has the supplied name.
                    """)
    @ApiResponse(responseCode = "200", description = "Role returned successfully")
    @ApiResponse(
            responseCode = "404",
            description = "Role not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<RoleDto> getRoleByName(@PathVariable String name) {
        return ResponseEntity.ok(roleManagementService.getRoleByName(name));
    }

    /**
     * Story #62: Get role by UUID.
     */
    @GetMapping("/{id}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:view"})
    @PreAuthorize("hasAuthority('" + SecurityPermissions.ROLE_VIEW + "')")
    @Operation(operationId = "getRoleById", summary = "Get a Single Role by UUID", description = """
                    Returns a single role looked up by its UUID, including its permission set and audit metadata.
                    Use this tool when the role id is known; use getRoleByName instead when only the name is \
                    available, and listRoles to browse.
                    Preconditions: the caller must hold security:role:view and the role must exist.
                    Required inputs: id (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no role exists for the supplied UUID.
                    """)
    @ApiResponse(responseCode = "200", description = "Role returned successfully")
    @ApiResponse(
            responseCode = "404",
            description = "Role not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<RoleDto> getRoleById(@PathVariable UUID id) {
        return roleManagementService
                .getRoleById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Story #62: Delete a role and cascade-remove all associations.
     */
    @EmitEvent(id = "SECURITY_ROLE_DELETE", apiVersion = "1")
    @DeleteMapping("/{id}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:delete"})
    @PreAuthorize("hasAuthority('" + SecurityPermissions.ROLE_DELETE + "')")
    @Operation(operationId = "deleteRole", summary = "Delete a Role and Its Associations", description = """
                    Deletes a role by UUID, clearing its permission grants and deleting all of its role assignments \
                    in the same transaction.
                    Use this tool to retire a role entirely; do not use revokeRolePermission or \
                    revokeRoleAssignment, which remove a single grant or assignment and keep the role.
                    Preconditions: the caller must hold security:role:delete and the role must exist; users holding \
                    the role lose it immediately.
                    Required inputs: id (UUID) as a path parameter.
                    Emits a SECURITY_ROLE_DELETE event.
                    Returns 404 when the role does not exist.
                    """)
    @ApiResponse(responseCode = "204", description = "Role deleted")
    @ApiResponse(
            responseCode = "404",
            description = "Role not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Void> deleteRole(@PathVariable UUID id) {
        roleManagementService.deleteRole(id);
        return ResponseEntity.noContent().build();
    }
}
