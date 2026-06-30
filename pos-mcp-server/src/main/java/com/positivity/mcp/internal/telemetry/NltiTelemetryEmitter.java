package com.positivity.mcp.internal.telemetry;

import org.jspecify.annotations.NonNull;

/**
 * Emits one {@link NltiRequestTelemetry} event per chat request (blocking and streaming alike).
 *
 * <p>Implementations must be side-effect-light and must never throw into the request path:
 * a telemetry failure must not fail the user request.
 */
public interface NltiTelemetryEmitter {

    void emit(@NonNull NltiRequestTelemetry event);
}
