package com.positivity.mcp.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.mcp.internal.dto.ToolPermissionRequest;
import com.positivity.mcp.internal.dto.ToolPermissionsResponse;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.mcp.internal.service.ToolPermissionAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gate 3 (#785): admin management of the permission codes that gate discovered OpenAPI tools.
 * Discovered ops are fail-closed until a permission is granted here, so this endpoint is the
 * supported alternative to editing {@code mcp_tool_permission} by hand.
 */
@RestController
@RequestMapping("/v1/tools")
@Validated
@Tag(name = "MCP Tool Permissions", description = "Manage the permission codes that gate discovered OpenAPI tools")
class ToolPermissionController {

    private final ToolPermissionAdminService service;

    ToolPermissionController(@NonNull ToolPermissionAdminService service) {
        this.service = service;
    }

    @GetMapping("/{toolName}/permissions")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {McpPermissions.TOOL_VIEW})
    @PreAuthorize("hasAuthority('" + McpPermissions.TOOL_VIEW + "')")
    @Operation(
            operationId = "listToolPermissions",
            summary = "List a Tool's Permission Grants",
            description = """
                    Returns the permission codes currently granted to a discovered OpenAPI tool.
                    Use this tool to inspect why a discovered tool is or is not selectable for a caller; use \
                    grantToolPermission or revokeToolPermission instead to change the grants.
                    Preconditions: the named tool must exist as a discovered (source openapi) tool; discovered \
                    tools are fail-closed and unselectable until at least one grant exists.
                    Required inputs: toolName as a path parameter, matching the tool's registered name exactly.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the grant set (possibly empty), and 404 when no discovered OpenAPI tool has \
                    the given name.
                    """,
            tags = {"MCP Tool Permissions"})
    ResponseEntity<ToolPermissionsResponse> list(@PathVariable @NonNull String toolName) {
        return ResponseEntity.ok(service.listPermissions(toolName));
    }

    @PostMapping("/{toolName}/permissions")
    @EmitEvent(id = "MCP_TOOL_PERMISSION_GRANT", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {McpPermissions.TOOL_MANAGE})
    @PreAuthorize("hasAuthority('" + McpPermissions.TOOL_MANAGE + "')")
    @Operation(
            operationId = "grantToolPermission",
            summary = "Grant a Permission to a Tool",
            description = """
                    Grants a permission code to a discovered OpenAPI tool so callers holding that code can select \
                    the tool during chat.
                    Use this tool to open up a fail-closed discovered operation; do not use \
                    revokeToolPermission, which removes a grant.
                    Preconditions: the named tool must exist as a discovered (source openapi) tool; granting a \
                    code that is already present changes nothing.
                    Required inputs: toolName as a path parameter and permissionCode in the body, either the \
                    literal AUTHENTICATED or a domain:resource:action code such as event-receiver:event_type:view.
                    Emits a MCP_TOOL_PERMISSION_GRANT event; when the grant set actually changes, cached role \
                    agents are invalidated so the tool becomes selectable on the next chat turn.
                    Returns 201 with the tool's full permission set (idempotently for repeated grants), 404 when \
                    no discovered OpenAPI tool has the given name, and 400 when the permission code does not match \
                    the required pattern.
                    """,
            tags = {"MCP Tool Permissions"})
    ResponseEntity<ToolPermissionsResponse> grant(
            @PathVariable @NonNull String toolName,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The permission code the tool should be selectable under.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Domain permission grant",
                                                            value =
                                                                    "{\"permissionCode\":\"event-receiver:event_type:view\"}")))
                    @RequestBody
                    @Validated
                    @NonNull
                    ToolPermissionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.grantPermission(toolName, request.permissionCode()));
    }

    @DeleteMapping("/{toolName}/permissions")
    @EmitEvent(id = "MCP_TOOL_PERMISSION_REVOKE", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {McpPermissions.TOOL_MANAGE})
    @PreAuthorize("hasAuthority('" + McpPermissions.TOOL_MANAGE + "')")
    @Operation(
            operationId = "revokeToolPermission",
            summary = "Revoke a Permission From a Tool",
            description = """
                    Revokes a permission code from a discovered OpenAPI tool, removing it from the set that makes \
                    the tool selectable.
                    Use this tool to withdraw chat access to a discovered operation; do not use \
                    grantToolPermission, which adds a grant.
                    Preconditions: the named tool must exist as a discovered (source openapi) tool; revoking a \
                    code that is not granted changes nothing.
                    Required inputs: toolName as a path parameter and permissionCode as a query parameter; there \
                    is no request body.
                    Emits a MCP_TOOL_PERMISSION_REVOKE event; when the grant set actually changes, cached role \
                    agents are invalidated so the tool stops being selectable.
                    Returns 204 whether or not the code was granted (idempotent), 404 when no discovered OpenAPI \
                    tool has the given name, and 400 when the permission code is blank.
                    """,
            tags = {"MCP Tool Permissions"})
    ResponseEntity<Void> revoke(
            @PathVariable @NonNull String toolName, @RequestParam("permissionCode") @NotBlank @NonNull String code) {
        service.revokePermission(toolName, code);
        return ResponseEntity.noContent().build();
    }
}
