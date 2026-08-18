package com.positivity.mcp.internal.telemetry;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Structured workflow-transition telemetry event ({@code nlti.workflow.transition} v1).
 *
 * <p>Gate 2C requires workflow transitions to be observable in the telemetry stream. The per-request
 * event ({@link NltiRequestTelemetry}) only reports the workflow state a request was <em>served
 * under</em> ({@code routing.workflowState}); it cannot show when, by whom, or from which state a
 * session moved. This event records the transition itself, once per accepted state change, on the
 * same dedicated {@code nlti.telemetry} logger stream.
 *
 * <p>It is intentionally distinct from the {@code NLTI_SESSION_WORKFLOW_STATE_SET} audit event
 * (compliance ledger, {@code nlti_audit_event}) — that stream is not queryable alongside the
 * evaluation/observability telemetry the gate dashboards read.
 *
 * <p>Privacy: like {@link NltiRequestTelemetry}, this event carries ids and enums only. The actor is
 * identified by the authenticated subject id (the same key persisted as
 * {@code nlti_audit_event.actor_subject_id}); prompts, customer PII, and permission codes must NOT
 * be placed in this event.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NltiWorkflowTransitionTelemetry(
        int schemaVersion,
        String eventType,
        String correlationId,
        String sessionId,
        String timestamp,
        Actor actor,
        Transition transition) {

    public static final int SCHEMA_VERSION = 1;
    public static final String EVENT_TYPE = "nlti.workflow.transition";

    /** The authenticated subject that owns the session and requested the transition. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Actor(String subjectId) {}

    /**
     * The transition itself. {@code changed} is false when a session was re-set to the state it was
     * already in, so a no-op re-assert is distinguishable from a real state move without the consumer
     * having to compare the two enum names.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Transition(String fromState, String toState, boolean changed) {}
}
