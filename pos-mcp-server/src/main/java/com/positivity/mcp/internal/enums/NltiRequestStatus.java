package com.positivity.mcp.internal.enums;

public enum NltiRequestStatus {
    ACCEPTED,
    // Gate 6 write-confirmation lifecycle (conversation state, distinct from WorkflowState):
    PENDING_CONFIRMATION,
    CONFIRMED,
    EXECUTING,
    CANCELLED,
    EXPIRED,
    COMPLETE,
    ERROR
}
