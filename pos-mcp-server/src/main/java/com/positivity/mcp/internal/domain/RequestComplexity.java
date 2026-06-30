package com.positivity.mcp.internal.domain;

/** Estimated request complexity used for tier routing (Gate 4). */
public enum RequestComplexity {
    /** A single lookup / one expected tool call. */
    SINGLE_LOOKUP,
    /** Multiple expected tool calls or cross-domain synthesis. */
    MULTI_DOMAIN
}
