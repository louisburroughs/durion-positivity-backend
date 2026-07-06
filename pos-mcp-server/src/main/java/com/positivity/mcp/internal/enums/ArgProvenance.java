package com.positivity.mcp.internal.enums;

/**
 * Provenance of a write-plan argument (Gate 6). Every persisted write-plan argument carries one of
 * these so the preview can disclose inferred defaults and the gate can refuse hidden assumptions.
 */
public enum ArgProvenance {
    /** Taken from the user's message text. */
    USER_TEXT,
    /** Taken from a retrieved RAG document. */
    RETRIEVED_DOC,
    /** Taken from a prior tool result in the same turn. */
    PRIOR_TOOL_RESULT,
    /** Taken from the authenticated caller context (e.g. location, user id). */
    USER_CONTEXT,
    /** Filled by an inferred default — MUST be disclosed in the preview; rejected for HIGH risk. */
    INFERRED_DEFAULT,
    /** Taken from system configuration. */
    SYSTEM_CONFIG
}
