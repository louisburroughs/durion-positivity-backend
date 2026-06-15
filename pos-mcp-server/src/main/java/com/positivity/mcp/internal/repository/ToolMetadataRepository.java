package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.domain.ToolMetadata;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;

public interface ToolMetadataRepository {

    /**
     * Returns enabled tools authorized for {@code workflowState} where the caller holds at
     * least one of {@code permissionCodes} via {@code mcp_tool_permission} (OR semantics —
     * see spec §6). A tool with zero {@code mcp_tool_permission} rows is never returned,
     * regardless of caller (fail-closed). An empty {@code permissionCodes} set
     * short-circuits to an empty result.
     */
    @NonNull
    List<ToolMetadata> findEnabledByPermissionsAndWorkflow(
            @NonNull Set<String> permissionCodes, @NonNull String workflowState);

    /**
     * Returns all role names currently defined in the mcp_role table. Used for role-scoped
     * domain-tool/RAG-scope resolution ({@link
     * com.positivity.mcp.internal.service.MasterAgentRegistryLoader}), not for {@code
     * mcp_tool} candidate gating.
     */
    @NonNull
    List<String> findAllRoleNames();

    /**
     * Returns enabled tools assigned to {@code role} for {@code workflowState} via {@code
     * mcp_tool_role}/{@code mcp_role}. Used for role-scoped domain-tool/RAG-scope resolution
     * ({@link com.positivity.mcp.internal.service.MasterAgentRegistryLoader}), not for {@code
     * mcp_tool} candidate gating.
     */
    @NonNull
    List<ToolMetadata> findEnabledByRoleAndWorkflow(@NonNull String role, @NonNull String workflowState);

    @NonNull
    List<ToolMetadata> findEnabledByWorkflow(@NonNull String workflowState);

    /**
     * Returns up to {@code limit} tools ordered by semantic similarity to the given
     * embedding, restricted to tools authorized for {@code workflowState} where the caller
     * holds at least one of {@code permissionCodes} via {@code mcp_tool_permission}.
     * Gating is performed in SQL — unauthorized tools never enter the ranking window. An
     * empty {@code permissionCodes} set short-circuits to an empty result.
     */
    @NonNull
    List<ToolMetadata> findTopKByEmbeddingForPermissions(
            float @NonNull [] embedding,
            int limit,
            @NonNull Set<String> permissionCodes,
            @NonNull String workflowState);

    @NonNull
    List<ToolMetadata> findTopKByEmbedding(float @NonNull [] embedding, int limit);
}
