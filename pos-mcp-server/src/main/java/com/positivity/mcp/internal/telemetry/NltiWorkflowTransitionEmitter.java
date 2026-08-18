package com.positivity.mcp.internal.telemetry;

import org.jspecify.annotations.NonNull;

/**
 * Emits one {@link NltiWorkflowTransitionTelemetry} event per accepted workflow-state change
 * (Gate 2C).
 *
 * <p>Kept separate from {@link NltiTelemetryEmitter} because the two events have different
 * lifecycles — one per chat request versus one per session state move — even though the default
 * implementations write to the same {@code nlti.telemetry} stream.
 *
 * <p>Implementations must be side-effect-light and must never throw into the request path: a
 * telemetry failure must not fail the state change that has already been persisted.
 */
public interface NltiWorkflowTransitionEmitter {

    void emit(@NonNull NltiWorkflowTransitionTelemetry event);
}
