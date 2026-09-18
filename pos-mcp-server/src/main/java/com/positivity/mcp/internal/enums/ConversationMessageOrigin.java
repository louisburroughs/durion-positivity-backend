package com.positivity.mcp.internal.enums;

/**
 * How a persisted conversation turn was recorded (#2073). {@link #CHAT} rows were written by the
 * {@code POST /mcp/chat} path itself; {@link #CLIENT} rows were appended directly by a caller through
 * {@code POST /v1/mcp/conversations/{id}/messages}, so grading can exclude client-authored turns.
 */
public enum ConversationMessageOrigin {
    CHAT,
    CLIENT
}
