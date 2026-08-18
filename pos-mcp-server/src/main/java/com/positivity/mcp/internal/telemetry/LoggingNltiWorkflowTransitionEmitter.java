package com.positivity.mcp.internal.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link NltiWorkflowTransitionEmitter} that writes one structured JSON line per accepted
 * workflow-state change to the same dedicated logger ({@code nlti.telemetry}) as
 * {@link LoggingNltiTelemetryEmitter}, so a single harvest of that stream carries both the
 * per-request events and the transitions between them.
 *
 * <p>A Gate 7 dashboard-backed emitter can replace this implementation without changing callers.
 */
@Component
public class LoggingNltiWorkflowTransitionEmitter implements NltiWorkflowTransitionEmitter {

    private static final Logger TELEMETRY = LoggerFactory.getLogger("nlti.telemetry");
    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingNltiWorkflowTransitionEmitter.class);

    private final ObjectMapper objectMapper;

    public LoggingNltiWorkflowTransitionEmitter(@NonNull ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void emit(@NonNull NltiWorkflowTransitionTelemetry event) {
        try {
            TELEMETRY.info("{}", objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            // Never fail the request path because telemetry serialization failed: the transition is
            // already persisted, so losing its telemetry line must not surface as a request failure.
            LOGGER.warn(
                    "MCP workflow transition telemetry serialization failed correlationId={}",
                    event.correlationId(),
                    e);
        }
    }
}
