package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.domain.DiscoveredOperation;
import com.positivity.mcp.internal.domain.ToolMetadata;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface ToolMetadataRepository {

    /**
     * Gate 3: permission-gated, embedding-ranked candidates restricted to OpenAPI-discovered
     * operations ({@code source = 'openapi'}), with their execution coordinates. Same fail-closed
     * permission ∩ workflow gating as {@link #findTopKByEmbeddingForPermissions}; empty
     * {@code permissionCodes} short-circuits to an empty result.
     */
    @NonNull
    List<DiscoveredOperation> findDiscoveredCandidatesForPermissions(
            float @NonNull [] embedding,
            int limit,
            @NonNull Set<String> permissionCodes,
            @NonNull String workflowState);

    /**
     * Gate 3 (G3.1): upserts a discovered OpenAPI operation as a {@code source='openapi'}
     * {@code mcp_tool} row (execution coordinates), keyed by tool name. Returns the row id so the
     * caller can link workflow states and permissions. The embedding is left null for
     * {@code ToolEmbeddingInitializer} to backfill; {@code ON CONFLICT} preserves an existing one.
     * Facade rows are untouched.
     */
    @NonNull
    UUID upsertDiscoveredOperation(@NonNull DiscoveredOperation operation, @NonNull String domain);

    /**
     * #1121: reconciles the discovered-tool set to the current run. Deletes every
     * {@code source='openapi'} {@code mcp_tool} row whose name is NOT in {@code keptNames} (and its FK
     * children), removing orphans left behind by a spec change or discovery-mode switch — discovery is
     * otherwise upsert-only and never prunes. Scoped strictly to {@code source='openapi'} so
     * hand-seeded/admin tools are never touched. Returns the number of orphan rows deleted.
     *
     * <p><strong>Safety:</strong> an empty {@code keptNames} is a no-op (returns 0) — the caller must
     * only prune after a successful, non-empty discovery run so a transient empty/failed fetch can
     * never wipe the catalog.
     */
    int pruneDiscoveredOperationsExcept(@NonNull Collection<String> keptNames);

    /** Gate 3 (G3.1): maps a tool to a workflow state (by name) so it is selectable there. Idempotent. */
    void linkToolToWorkflow(@NonNull UUID toolId, @NonNull String workflowState);

    /**
     * Gate 3 (G3.1): grants a tool a required permission code (fail-closed gating input). Idempotent.
     * Returns {@code true} when a row was inserted, {@code false} when the grant already existed.
     */
    boolean addToolPermission(@NonNull UUID toolId, @NonNull String permissionCode);

    /**
     * Gate 3 (#785): resolves a discovered ({@code source='openapi'}) tool id by its unique name.
     * Empty when no such openapi tool exists. Facade rows are never returned.
     */
    @NonNull
    Optional<UUID> findDiscoveredToolIdByName(@NonNull String name);

    /** Gate 3 (#785): the permission codes currently granted to a tool, ascending. */
    @NonNull
    List<String> listToolPermissions(@NonNull UUID toolId);

    /**
     * Gate 3 (#785): revokes a permission code from a tool. Idempotent (no-op if absent).
     * Returns {@code true} when a row was deleted, {@code false} when the grant was already absent.
     */
    boolean removeToolPermission(@NonNull UUID toolId, @NonNull String permissionCode);

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

    // Gate 2B / #780: findAllRoleNames() and findEnabledByRoleAndWorkflow() removed — the legacy
    // mcp_role / mcp_tool_role role-gating path is retired in favour of permission gating.

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
