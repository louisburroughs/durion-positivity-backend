package com.positivity.mcp.internal.controller;

import com.positivity.mcp.internal.dto.ToolPermissionRequest;
import com.positivity.mcp.internal.dto.ToolPermissionsResponse;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.mcp.internal.service.ToolPermissionAdminService;
import io.swagger.v3.oas.annotations.Operation;
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
    @PreAuthorize("hasAuthority('mcp:tool:view')")
    @Operation(
            operationId = "listToolPermissions",
            summary = "List a tool's permission grants",
            description = "Return the permission codes currently granted to a discovered OpenAPI tool.",
            tags = {"MCP Tool Permissions"})
    ResponseEntity<ToolPermissionsResponse> list(@PathVariable @NonNull String toolName) {
        return ResponseEntity.ok(service.listPermissions(toolName));
    }

    @PostMapping("/{toolName}/permissions")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {McpPermissions.TOOL_MANAGE})
    @PreAuthorize("hasAuthority('mcp:tool:manage')")
    @Operation(
            operationId = "grantToolPermission",
            summary = "Grant a permission to a tool",
            description = "Grant a permission code to a discovered OpenAPI tool so callers holding it can select "
                    + "the tool. Idempotent. Returns the tool's full permission set.",
            tags = {"MCP Tool Permissions"})
    ResponseEntity<ToolPermissionsResponse> grant(
            @PathVariable @NonNull String toolName, @RequestBody @Validated @NonNull ToolPermissionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.grantPermission(toolName, request.permissionCode()));
    }

    @DeleteMapping("/{toolName}/permissions")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {McpPermissions.TOOL_MANAGE})
    @PreAuthorize("hasAuthority('mcp:tool:manage')")
    @Operation(
            operationId = "revokeToolPermission",
            summary = "Revoke a permission from a tool",
            description = "Revoke a permission code from a discovered OpenAPI tool. Idempotent.",
            tags = {"MCP Tool Permissions"})
    ResponseEntity<Void> revoke(
            @PathVariable @NonNull String toolName, @RequestParam("permissionCode") @NotBlank @NonNull String code) {
        service.revokePermission(toolName, code);
        return ResponseEntity.noContent().build();
    }
}
