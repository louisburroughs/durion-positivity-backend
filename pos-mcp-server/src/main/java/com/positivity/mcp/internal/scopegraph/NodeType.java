package com.positivity.mcp.internal.scopegraph;

/**
 * ADR-0069 §2: the closed set of node types. Adding one is a reviewed code change, never
 * configuration.
 */
public enum NodeType {
    /** A tool-catalog {@code domain} value or a RAG scope. */
    DOMAIN,
    /** A curated entity key from the lexicon. */
    ENTITY,
    /** A normalized phrase in one language ({@code <lang>:<phrase>}). */
    TERM,
    /** A lexicon identifier regex, by its key. */
    IDENTIFIER_PATTERN,
    /** {@code mcp_tool.name}. */
    TOOL,
    /** A permission code; its semantics differ per source (§5.3). */
    PERMISSION,
    /** A {@code mcp_workflow_state} value. */
    WORKFLOW_STATE,
    /** {@code <entity>.<enum value>} from the entity's status enum. */
    LIFECYCLE_STATE,
    /** A {@code mcp.rag.preload.docs} {@code document_id}. */
    RAG_DOC,
    /** A {@code mcp_screen_registry.screen_key}. */
    SCREEN
}
