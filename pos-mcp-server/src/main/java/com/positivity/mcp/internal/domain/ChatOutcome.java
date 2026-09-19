package com.positivity.mcp.internal.domain;

import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * One chat turn's answer plus the {@link TurnSummary} of how it was produced (#2075).
 *
 * <p>Returned by {@link com.positivity.mcp.internal.config.AgentOrchestrationService#chatTurn}
 * so a persisted turn can carry the summary on its assistant row without changing the shape of
 * the existing {@code chat(...)} methods every other caller still uses.
 */
public record ChatOutcome(@NonNull String text, @NonNull TurnSummary summary) {

    /** Test/fallback convenience: text with an unreported summary (null path/source, no tools, 0 ms). */
    public static @NonNull ChatOutcome of(@NonNull String text) {
        return new ChatOutcome(text, new TurnSummary(null, null, List.of(), 0));
    }
}
