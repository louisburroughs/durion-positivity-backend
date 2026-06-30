package com.positivity.mcp.internal.domain;

/**
 * Model routing tier (Gate 4). The request is served by the cheapest tier that preserves quality and
 * safety; risky work is forced to {@link #T2_COMPLEX}.
 */
public enum ModelTier {
    /** Rule fast-path, no LLM (SimpleChatRule). */
    T0_RULE,
    /** Small classifier model (intent/risk/domain/complexity, strict JSON, temp 0). */
    T1_ROUTER,
    /** Small executor: single-tool lookups. */
    T2_SIMPLE,
    /** Large executor: multi-tool, writes, and correctness-critical domains. */
    T2_COMPLEX
}
