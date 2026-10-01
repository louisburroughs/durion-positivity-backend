package com.positivity.mcp.internal.domain;

/** ADR-0068 §1: which tagger produced a {@link TagAnswer}. */
public enum TagSource {
    /** The Jev-protocol decision model behind {@code POST /v1/systemone}. */
    JEV,
    /** Today's rules, moved behind the seam unchanged (ADR-0068 §2). */
    HEURISTIC
}
