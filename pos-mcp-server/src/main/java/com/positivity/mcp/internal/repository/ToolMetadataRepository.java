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
     *
     * <p>Since V40 (#1606) the grant lands in its own {@code permission_group} (group = code), the
     * OR-equivalent shape: holding that one code satisfies the group. Multi-code AND-groups are
     * seed-derived per {@code @Tool} method and are not expressible through this single-code API.
     */
    boolean addToolPermission(@NonNull UUID toolId, @NonNull String permissionCode);

    /**
     * Gate 3 (#785): resolves a discovered ({@code source='openapi'}) tool id by its unique name.
     * Empty when no such openapi tool exists. Facade rows are never returned.
     */
    @NonNull
    Optional<UUID> findDiscoveredToolIdByName(@NonNull String name);

    /**
     * #1422: resolves any tool id by its unique name regardless of source — facade rows (PascalCase
     * class names) and discovered openapi rows alike — so per-execution invocation logging can
     * attribute a {@code tool_id}. Empty when no tool has the given name.
     */
    @NonNull
    Optional<UUID> findToolIdByName(@NonNull String name);

    /** Gate 3 (#785): the permission codes currently granted to a tool, ascending. */
    @NonNull
    List<String> listToolPermissions(@NonNull UUID toolId);

    /**
     * Gate 6 (#1193): resolves a discovered ({@code source='openapi'}) operation's execution
     * coordinates by its unique name, for confirmed write-plan execution. Empty when no such
     * openapi tool exists — facade rows are never returned.
     */
    @NonNull
    Optional<DiscoveredOperation> findDiscoveredOperationByName(@NonNull String name);

    /**
     * Gate 3 (#785): revokes a permission code from a tool. Idempotent (no-op if absent).
     * Returns {@code true} when a row was deleted, {@code false} when the grant was already absent.
     *
     * <p><strong>Singleton groups only (V40 / #1606).</strong> This deletes by
     * {@code (tool_id, permission_code)} and is therefore group-blind: on a tool whose codes are
     * spread across per-method groups it removes the code from <em>every</em> group. That can
     * <em>widen</em> the gate rather than narrow it — revoking {@code location:read} from
     * {@code TaxFacadeTool} would leave {@code calculateTax} needing only {@code tax:calculate}
     * instead of both, admitting callers the two-code group excluded.
     *
     * <p>Safe today because the only caller ({@code ToolPermissionAdminService}) resolves tools via
     * {@code findDiscoveredToolIdByName}, i.e. {@code source='openapi'} operations, whose rows are
     * all singleton groups. Do not call it for a facade tool without first making it group-aware.
     */
    boolean removeToolPermission(@NonNull UUID toolId, @NonNull String permissionCode);

    /**
     * Returns enabled facade tools ({@code source <> 'openapi'}) authorized for
     * {@code workflowState} that the caller's {@code permissionCodes} satisfy under AND-group
     * semantics (V40, #1606): every {@code mcp_tool_permission} row belongs to a
     * {@code permission_group} — one group per {@code @Tool} method, holding the codes that
     * method's required downstream calls need — and a tool qualifies iff the caller holds
     * <em>all</em> codes of <em>at least one</em> group. A method that requires no codes
     * contributes no group, so it never widens the gate; a composition's group holds only its
     * {@code .require()}d legs, because optional legs degrade individually.
     *
     * <p>This replaces the former flat OR over the union of a tool's codes, under which a
     * composition's least-privileged leg admitted the whole tool. Discovered
     * ({@code source='openapi'}) operations keep OR semantics — every one of their rows is its
     * own singleton group, for which AND-within-group and OR coincide.
     *
     * <p>A tool with zero {@code mcp_tool_permission} rows is never returned, regardless of
     * caller (fail-closed): the qualifying predicate is an {@code EXISTS} over that tool's
     * groups, and {@code EXISTS} over no rows is false. An empty {@code permissionCodes} set
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
     * Returns up to {@code limit} facade tools ordered by semantic similarity to the given
     * embedding, restricted to tools authorized for {@code workflowState} that
     * {@code permissionCodes} satisfies under the same AND-group semantics as
     * {@link #findEnabledByPermissionsAndWorkflow} (V40, #1606). Gating is performed in SQL —
     * unauthorized tools never enter the ranking window. An empty {@code permissionCodes} set
     * short-circuits to an empty result.
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
