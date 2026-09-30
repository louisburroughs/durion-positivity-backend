package com.positivity.mcp.internal.scopegraph;

import com.positivity.mcp.internal.scopegraph.NodeAttributes.ToolSource;
import com.positivity.mcp.internal.security.PermissionCodes;
import java.util.Collection;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * ADR-0069 §5.3 (spec §2.9): what a caller may see of an expanded scope. Permission semantics differ
 * per source, so there is one predicate per source, each a mirror of the predicate that source
 * already enforces, instead of one uniform {@code REQUIRES} check.
 *
 * <p><strong>This is not the security boundary.</strong> It decides what appears in the {@link
 * ScopeSet}, the trace and the scope card. Every tool a consumer adds to a request is gated again by
 * the existing SQL, and documents still pass {@code PermissionAwareMetadataFilter}.
 *
 * <p>The {@code AUTHENTICATED} sentinel is an ordinary code on the tool side, exactly as in the SQL:
 * a caller qualifies through it because {@code PermissionCodes.extract} puts it in every
 * authenticated caller's code set, not because it is special-cased here.
 */
public final class ScopeCallerFilter {

    /** The workflow state the discovered-operation path is fixed to today ({@code OpenApiToolProvider}). */
    public static final String DISCOVERED_WORKFLOW_STATE = "IDLE";

    private ScopeCallerFilter() {}

    /**
     * Whether the caller may see {@code tool} in a turn whose workflow state is {@code
     * workflowState}. False for a node that is not a tool of the graph.
     */
    public static boolean toolPermitted(
            @NonNull ScopeGraph graph,
            @NonNull NodeId tool,
            @NonNull Set<String> callerPermissionCodes,
            @NonNull String workflowState) {
        NodeAttributes.Tool attributes =
                graph.attributes(tool, NodeAttributes.Tool.class).orElse(null);
        if (attributes == null) {
            return false;
        }
        return attributes.source() == ToolSource.FACADE
                ? facadeToolPermitted(attributes, validStates(graph, tool), callerPermissionCodes, workflowState)
                : discoveredToolPermitted(attributes, validStates(graph, tool), callerPermissionCodes);
    }

    /**
     * Mirrors {@code ToolMetadataRepositoryImpl.findEnabledByPermissionsAndWorkflow}: the caller
     * holds every code of at least one {@code permission_group}, and the tool is {@code VALID_IN} the
     * turn's workflow state. A tool with no permission rows is excluded for every caller, and so is
     * every tool for a caller with no codes.
     */
    public static boolean facadeToolPermitted(
            NodeAttributes.@NonNull Tool tool,
            @NonNull Set<String> validWorkflowStates,
            @NonNull Set<String> callerPermissionCodes,
            @NonNull String workflowState) {
        if (callerPermissionCodes.isEmpty() || !validWorkflowStates.contains(workflowState)) {
            return false;
        }
        return tool.permissionGroups().values().stream()
                .anyMatch(group -> !group.isEmpty() && callerPermissionCodes.containsAll(group));
    }

    /**
     * Mirrors {@code ToolMetadataRepositoryImpl.findDiscoveredCandidatesForPermissions}: the caller
     * holds any one of the operation's codes, and the operation is {@code VALID_IN} {@code IDLE}
     * whatever the turn's own state, because the discovered path fixes it. No rows, no tool.
     *
     * <p>One clause of that SQL has no counterpart here: {@code embedding IS NOT NULL}. The graph
     * holds definitions, not embeddings, so an operation still waiting for its embedding can be in
     * scope while the ranked query cannot return it yet.
     */
    public static boolean discoveredToolPermitted(
            NodeAttributes.@NonNull Tool tool,
            @NonNull Set<String> validWorkflowStates,
            @NonNull Set<String> callerPermissionCodes) {
        if (callerPermissionCodes.isEmpty() || !validWorkflowStates.contains(DISCOVERED_WORKFLOW_STATE)) {
            return false;
        }
        return tool.permissionGroups().values().stream()
                .anyMatch(group -> group.stream().anyMatch(callerPermissionCodes::contains));
    }

    /**
     * The RAG document visibility rule, shared with {@code PermissionAwareMetadataFilter} so the two
     * cannot drift: no required permissions is public; a list containing {@code AUTHENTICATED} is
     * visible to a caller holding that sentinel (and to nobody else, whatever other codes the list
     * carries); otherwise the caller must hold at least one of the codes.
     */
    public static boolean ragDocumentVisible(
            @NonNull Collection<String> requiredPermissions, @NonNull Set<String> callerPermissionCodes) {
        if (requiredPermissions.isEmpty()) {
            return true;
        }
        if (requiredPermissions.contains(PermissionCodes.AUTHENTICATED)) {
            return callerPermissionCodes.contains(PermissionCodes.AUTHENTICATED);
        }
        return requiredPermissions.stream().anyMatch(callerPermissionCodes::contains);
    }

    /** Mirrors {@code ScreenLinkResolverImpl}: no {@code required_perm}, or the caller holds it. */
    public static boolean screenVisible(@Nullable String requiredPerm, @NonNull Set<String> callerPermissionCodes) {
        return requiredPerm == null || callerPermissionCodes.contains(requiredPerm);
    }

    /** Whether the caller may see {@code node}, a Tool, RagDoc or Screen of the graph. */
    static boolean permitted(
            @NonNull ScopeGraph graph,
            @NonNull NodeId node,
            @NonNull Set<String> callerPermissionCodes,
            @NonNull String workflowState) {
        return switch (node.type()) {
            case TOOL -> toolPermitted(graph, node, callerPermissionCodes, workflowState);
            case RAG_DOC ->
                graph.attributes(node, NodeAttributes.RagDoc.class)
                        .map(doc -> ragDocumentVisible(doc.requiredPermissions(), callerPermissionCodes))
                        .orElse(false);
            case SCREEN ->
                graph.attributes(node, NodeAttributes.Screen.class)
                        .map(screen -> screenVisible(screen.requiredPerm(), callerPermissionCodes))
                        .orElse(false);
            default -> true;
        };
    }

    private static Set<String> validStates(ScopeGraph graph, NodeId tool) {
        return graph.outgoing(tool, EdgeType.VALID_IN).stream()
                .map(edge -> edge.to().key())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
