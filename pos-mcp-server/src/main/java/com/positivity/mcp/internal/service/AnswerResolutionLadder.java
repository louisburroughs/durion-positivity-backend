package com.positivity.mcp.internal.service;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Owns the response for a turn that produced no direct answer (the model's {@code content} was
 * blank). Instead of surfacing the raw thinking channel, it falls through the answer resolution
 * ladder: a deep link to the right screen (rung 3), else an honest hand-off (rung 4).
 *
 * <p>Rungs 1–2 (answer tools, prerequisite composition) run earlier, inside the model's tool loop;
 * this entry point is the graceful-degradation tail invoked only when that loop yielded nothing.
 */
public interface AnswerResolutionLadder {

    /** Resolve a fallback response for {@code userMessage}. Never returns null. */
    @NonNull
    LadderResult resolveFallback(@NonNull String userMessage);

    /** The chosen fallback: user-facing text, which rung produced it, and any deep-link URL. */
    record LadderResult(
            @NonNull String text,
            @NonNull Rung rung,
            @Nullable String screenUrl) {}

    enum Rung {
        DEEP_LINK,
        HANDOFF
    }
}
