package com.positivity.mcp.internal.domain;

/**
 * Operational workflow state that gates which tool sets are active (Gate 2C).
 *
 * <p>This is the session-owned workflow context (persisted on {@code NltiSession}); it is
 * <strong>distinct</strong> from the conversation lifecycle state (READY / NEEDS_CLARIFICATION /
 * PENDING_CONFIRMATION / …) which tracks a single request's progress. Workflow state selects the
 * {@code mcp_workflow_state} row used by permission-gated tool selection.
 */
public enum WorkflowState {
    IDLE,
    CREATING_PO,
    RECEIVING_ASN,
    INVENTORY_RECON,
    PROCESSING_RETURN;

    /** Default state for a new session or a session-less request. */
    public static final WorkflowState DEFAULT = IDLE;
}
