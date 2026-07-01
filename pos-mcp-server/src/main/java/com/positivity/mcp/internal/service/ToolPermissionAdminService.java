package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.dto.ToolPermissionsResponse;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

/**
 * Gate 3 (#785): admin management of the {@code mcp_tool_permission} grants that gate discovered
 * ({@code source='openapi'}) tools. Ops are fail-closed until an admin grants a permission code,
 * so this is the supported alternative to editing the table by hand.
 */
@Service
public class ToolPermissionAdminService {

    private final ToolMetadataRepository repository;

    public ToolPermissionAdminService(@NonNull ToolMetadataRepository repository) {
        this.repository = repository;
    }

    public @NonNull ToolPermissionsResponse listPermissions(@NonNull String toolName) {
        UUID toolId = resolve(toolName);
        return new ToolPermissionsResponse(toolName, repository.listToolPermissions(toolId));
    }

    public @NonNull ToolPermissionsResponse grantPermission(@NonNull String toolName, @NonNull String permissionCode) {
        UUID toolId = resolve(toolName);
        repository.addToolPermission(toolId, permissionCode);
        return new ToolPermissionsResponse(toolName, repository.listToolPermissions(toolId));
    }

    public void revokePermission(@NonNull String toolName, @NonNull String permissionCode) {
        UUID toolId = resolve(toolName);
        repository.removeToolPermission(toolId, permissionCode);
    }

    private @NonNull UUID resolve(@NonNull String toolName) {
        return repository
                .findDiscoveredToolIdByName(toolName)
                .orElseThrow(() -> new NoSuchElementException("No discovered OpenAPI tool named: " + toolName));
    }
}
