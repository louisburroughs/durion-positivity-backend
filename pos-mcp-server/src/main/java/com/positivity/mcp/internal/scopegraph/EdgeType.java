package com.positivity.mcp.internal.scopegraph;

/**
 * ADR-0069 §2: the closed set of edge types, each with the node types it joins. Adding one is a
 * reviewed code change.
 */
public enum EdgeType {
    /** Term → Entity. */
    DENOTES(NodeType.TERM, NodeType.ENTITY),
    /** IdentifierPattern → Entity. */
    IDENTIFIES(NodeType.IDENTIFIER_PATTERN, NodeType.ENTITY),
    /** Entity → Domain. */
    OWNED_BY(NodeType.ENTITY, NodeType.DOMAIN),
    /** Entity → Entity, labelled ({@code promotes_to}, {@code billed_by}, ...). */
    RELATES_TO(NodeType.ENTITY, NodeType.ENTITY),
    /** Tool → Entity, labelled {@code reads} or {@code writes}. */
    ACTS_ON(NodeType.TOOL, NodeType.ENTITY),
    /** Tool / RagDoc / Screen → Permission. */
    REQUIRES(null, NodeType.PERMISSION),
    /** Tool → WorkflowState. */
    VALID_IN(NodeType.TOOL, NodeType.WORKFLOW_STATE),
    /** Tool → Tool: the source produces an input of the target. */
    PRODUCES_INPUT_FOR(NodeType.TOOL, NodeType.TOOL),
    /** RagDoc → Entity. */
    ABOUT(NodeType.RAG_DOC, NodeType.ENTITY),
    /** Screen → Entity. */
    SHOWS(NodeType.SCREEN, NodeType.ENTITY),
    /** Entity → LifecycleState. */
    HAS_STATE(NodeType.ENTITY, NodeType.LIFECYCLE_STATE),
    /** LifecycleState → LifecycleState; empty until {@code lifecycles.yaml} exists (§3.3, phase 2). */
    TRANSITIONS_TO(NodeType.LIFECYCLE_STATE, NodeType.LIFECYCLE_STATE);

    private final NodeType from;
    private final NodeType to;

    EdgeType(NodeType from, NodeType to) {
        this.from = from;
        this.to = to;
    }

    /** True when an edge of this type may run between the two node types. */
    public boolean joins(NodeType source, NodeType target) {
        if (target != to) {
            return false;
        }
        if (this == REQUIRES) {
            return source == NodeType.TOOL || source == NodeType.RAG_DOC || source == NodeType.SCREEN;
        }
        return source == from;
    }
}
