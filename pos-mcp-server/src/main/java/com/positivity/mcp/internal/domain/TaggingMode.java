package com.positivity.mcp.internal.domain;

/**
 * ADR-0068 §6: the rollout mode ({@code mcp.tagging.mode}). Lives beside {@link QuestionTags} so a tag
 * record can say which mode produced it without the domain package depending on configuration.
 */
public enum TaggingMode {
    /** Heuristic tagger only: no provider call, no meters, no tagging log lines. */
    OFF,
    /** Both taggers run; consumers act on the heuristic result; the model's result is recorded. */
    SHADOW,
    /** As shadow, and the tags listed in {@code enforced-tags} act (Wave 2). */
    ENFORCE
}
