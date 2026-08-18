package com.positivity.mcp.internal.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.domain.WorkflowState;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NltiWorkflowTransitionTelemetryFactoryTest {

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-000000000901");

    @Test
    void forTransition_populatesSchemaCorrelationSessionActorAndTransition() {
        NltiWorkflowTransitionTelemetry event = NltiWorkflowTransitionTelemetryFactory.forTransition(
                "corr-1",
                "2026-08-18T12:00:00Z",
                SESSION_ID,
                "alice",
                WorkflowState.IDLE,
                WorkflowState.PROCESSING_RETURN);

        assertThat(event.schemaVersion()).isEqualTo(NltiWorkflowTransitionTelemetry.SCHEMA_VERSION);
        assertThat(event.eventType()).isEqualTo(NltiWorkflowTransitionTelemetry.EVENT_TYPE);
        assertThat(event.eventType()).isEqualTo("nlti.workflow.transition");
        assertThat(event.correlationId()).isEqualTo("corr-1");
        assertThat(event.sessionId()).isEqualTo(SESSION_ID.toString());
        assertThat(event.timestamp()).isEqualTo("2026-08-18T12:00:00Z");
        assertThat(event.actor().subjectId()).isEqualTo("alice");
        assertThat(event.transition().fromState()).isEqualTo("IDLE");
        assertThat(event.transition().toState()).isEqualTo("PROCESSING_RETURN");
        assertThat(event.transition().changed()).isTrue();
    }

    @Test
    void forTransition_sameFromAndToState_marksTransitionUnchanged() {
        NltiWorkflowTransitionTelemetry event = NltiWorkflowTransitionTelemetryFactory.forTransition(
                "corr-2",
                "2026-08-18T12:00:01Z",
                SESSION_ID,
                "bob",
                WorkflowState.CREATING_PO,
                WorkflowState.CREATING_PO);

        assertThat(event.transition().changed()).isFalse();
        assertThat(event.transition().fromState()).isEqualTo("CREATING_PO");
        assertThat(event.transition().toState()).isEqualTo("CREATING_PO");
    }

    @Test
    void forTransition_isDeterministic_forTheSameInputs() {
        NltiWorkflowTransitionTelemetry first = NltiWorkflowTransitionTelemetryFactory.forTransition(
                "corr-3",
                "2026-08-18T12:00:02Z",
                SESSION_ID,
                "alice",
                WorkflowState.RECEIVING_ASN,
                WorkflowState.INVENTORY_RECON);
        NltiWorkflowTransitionTelemetry second = NltiWorkflowTransitionTelemetryFactory.forTransition(
                "corr-3",
                "2026-08-18T12:00:02Z",
                SESSION_ID,
                "alice",
                WorkflowState.RECEIVING_ASN,
                WorkflowState.INVENTORY_RECON);

        assertThat(first).isEqualTo(second);
    }
}
