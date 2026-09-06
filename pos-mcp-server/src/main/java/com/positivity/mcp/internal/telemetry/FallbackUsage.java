package com.positivity.mcp.internal.telemetry;

/**
 * Per-request flag recording that a model call was served by the secondary model (#1691).
 *
 * <p>Set by the failover wrapper on the thread that ran the chat turn and consumed by {@link
 * NltiRequestTelemetryFactory} when it builds the request's telemetry event, so {@code
 * Model.fallbackUsed} reports what actually happened instead of a constant. Thread-local because a
 * blocking chat turn runs on one servlet thread from model call to telemetry emission; the streaming
 * path emits its telemetry from the reactive chain and may report {@code false} for a failover that
 * did happen — the WARN log line is the record there.
 */
public final class FallbackUsage {

    private static final ThreadLocal<Boolean> USED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private FallbackUsage() {}

    /** Records that the current request's model call fell over to the secondary model. */
    public static void mark() {
        USED.set(Boolean.TRUE);
    }

    /** Returns whether a failover was recorded for the current request, and clears the flag. */
    public static boolean consume() {
        boolean used = USED.get();
        USED.remove();
        return used;
    }
}
