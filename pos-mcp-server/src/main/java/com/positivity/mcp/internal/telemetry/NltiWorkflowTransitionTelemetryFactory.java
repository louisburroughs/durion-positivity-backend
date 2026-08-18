package com.positivity.mcp.internal.telemetry;

import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.telemetry.NltiWorkflowTransitionTelemetry.Actor;
import com.positivity.mcp.internal.telemetry.NltiWorkflowTransitionTelemetry.Transition;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Pure builder for {@link NltiWorkflowTransitionTelemetry} events (Gate 2C emission).
 *
 * <p>Deterministic by design, matching {@link NltiRequestTelemetryFactory}: {@code correlationId} and
 * {@code timestamp} are supplied by the caller (never read from the clock, MDC, or a random source
 * here) so the mapping is fully unit-testable.
 */
public final class NltiWorkflowTransitionTelemetryFactory {

    private NltiWorkflowTransitionTelemetryFactory() {}

    /**
     * Builds the transition event for an accepted workflow-state write.
     *
     * @param correlationId request correlation id (the {@code X-Correlation-Id} of the call that
     *     performed the transition)
     * @param timestamp ISO-8601 instant the transition was persisted
     * @param sessionId the NLTI session whose state moved
     * @param subjectId the authenticated subject that owns the session
     * @param fromState state held before the write
     * @param toState state held after the write
     */
    public static @NonNull NltiWorkflowTransitionTelemetry forTransition(
            @NonNull String correlationId,
            @NonNull String timestamp,
            @NonNull UUID sessionId,
            @NonNull String subjectId,
            @NonNull WorkflowState fromState,
            @NonNull WorkflowState toState) {
        return new NltiWorkflowTransitionTelemetry(
                NltiWorkflowTransitionTelemetry.SCHEMA_VERSION,
                NltiWorkflowTransitionTelemetry.EVENT_TYPE,
                correlationId,
                sessionId.toString(),
                timestamp,
                new Actor(subjectId),
                new Transition(fromState.name(), toState.name(), fromState != toState));
    }
}
