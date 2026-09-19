package com.positivity.mcp.internal.domain;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Profile-independent summary of how one chat turn's answer was produced (#2075, decisions 1-4).
 *
 * <p>Written once, on the assistant row of a persisted {@code CHAT}-origin turn, so a rating can
 * join to how the answer was produced without depending on the alpha-only eval trace (24 h
 * retention vs the message's own). {@code answerPath} distinguishes the agent path from the
 * simple-chat fast path, since {@code CONTENT} is ambiguous between the two (decision 2).
 * {@code toolsCalled} keeps call order and duplicates, capped at {@link #MAX_TOOLS_CALLED}.
 */
public record TurnSummary(
        @Nullable String answerPath,
        @Nullable String answerSource,
        @NonNull List<String> toolsCalled,
        int latencyMs) {

    public static final String PATH_AGENT = "AGENT";
    public static final String PATH_SIMPLE_CHAT = "SIMPLE_CHAT";
    public static final int MAX_TOOLS_CALLED = 64;

    public TurnSummary {
        toolsCalled = List.copyOf(
                toolsCalled.size() > MAX_TOOLS_CALLED ? toolsCalled.subList(0, MAX_TOOLS_CALLED) : toolsCalled);
        latencyMs = Math.max(0, latencyMs);
    }

    /** Clamp helper for callers holding a {@code long} millisecond duration. */
    public static int clampLatency(long millis) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, millis));
    }
}
