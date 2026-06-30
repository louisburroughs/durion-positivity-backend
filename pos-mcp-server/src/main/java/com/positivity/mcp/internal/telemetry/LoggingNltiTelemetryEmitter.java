package com.positivity.mcp.internal.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link NltiTelemetryEmitter} that writes one structured JSON line per request to a
 * dedicated logger ({@code nlti.telemetry}), keeping the evaluation/observability stream separate
 * from application logs, audit events, and tool-invocation logs.
 *
 * <p>A Gate 7 dashboard-backed emitter can replace this implementation without changing callers.
 */
@Component
public class LoggingNltiTelemetryEmitter implements NltiTelemetryEmitter {

    private static final Logger TELEMETRY = LoggerFactory.getLogger("nlti.telemetry");
    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingNltiTelemetryEmitter.class);

    private final ObjectMapper objectMapper;

    public LoggingNltiTelemetryEmitter(@NonNull ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void emit(@NonNull NltiRequestTelemetry event) {
        try {
            TELEMETRY.info("{}", objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            // Never fail the request path because telemetry serialization failed.
            LOGGER.warn("MCP telemetry serialization failed correlationId={}", event.correlationId(), e);
        }
    }
}
