package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.dto.ToolPermissionsResponse;
import com.positivity.mcp.internal.event.AgentCacheInvalidationEvent;
import com.positivity.mcp.internal.repository.ToolMetadataRepository;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gate 3 (#785): admin management of the {@code mcp_tool_permission} grants that gate discovered
 * ({@code source='openapi'}) tools. Ops are fail-closed until an admin grants a permission code,
 * so this is the supported alternative to editing the table by hand.
 */
@Service
public class ToolPermissionAdminService {

    private final ToolMetadataRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    public ToolPermissionAdminService(
            @NonNull ToolMetadataRepository repository, @NonNull ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    public @NonNull ToolPermissionsResponse listPermissions(@NonNull String toolName) {
        UUID toolId = resolve(toolName);
        return new ToolPermissionsResponse(toolName, repository.listToolPermissions(toolId));
    }

    @Transactional
    public @NonNull ToolPermissionsResponse grantPermission(@NonNull String toolName, @NonNull String permissionCode) {
        UUID toolId = resolve(toolName);
        // Idempotent SQL: only flush agent caches when the mapping set actually changed, so a
        // repeated grant does not trigger an unnecessary full rebuild.
        if (repository.addToolPermission(toolId, permissionCode)) {
            eventPublisher.publishEvent(AgentCacheInvalidationEvent.toolPermissionChanged(toolName));
        }
        return new ToolPermissionsResponse(toolName, repository.listToolPermissions(toolId));
    }

    @Transactional
    public void revokePermission(@NonNull String toolName, @NonNull String permissionCode) {
        UUID toolId = resolve(toolName);
        if (repository.removeToolPermission(toolId, permissionCode)) {
            eventPublisher.publishEvent(AgentCacheInvalidationEvent.toolPermissionChanged(toolName));
        }
    }

    private @NonNull UUID resolve(@NonNull String toolName) {
        return repository
                .findDiscoveredToolIdByName(toolName)
                .orElseThrow(() -> new NoSuchElementException("No discovered OpenAPI tool named: " + toolName));
    }
}
