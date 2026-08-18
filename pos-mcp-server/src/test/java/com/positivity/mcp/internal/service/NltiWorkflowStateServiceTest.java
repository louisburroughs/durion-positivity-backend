package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.entity.NltiSession;
import com.positivity.mcp.internal.exception.SessionOwnershipViolationException;
import com.positivity.mcp.internal.repository.NltiSessionRepository;
import com.positivity.mcp.internal.telemetry.NltiWorkflowTransitionEmitter;
import com.positivity.mcp.internal.telemetry.NltiWorkflowTransitionTelemetry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NltiWorkflowStateServiceTest {

    private static final String SUBJECT = "alice";
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-000000000901");
    private static final UUID CORRELATION_ID = UUID.fromString("00000000-0000-7000-8000-000000000902");
    // Fixed clock: the telemetry timestamp must be asserted exactly, never read from the wall clock.
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-18T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private NltiSessionRepository sessionRepository;

    @Mock
    private NltiWorkflowTransitionEmitter transitionEmitter;

    private NltiWorkflowStateService service;

    @BeforeEach
    void setUp() {
        service = new NltiWorkflowStateService(sessionRepository, transitionEmitter, FIXED_CLOCK);
    }

    private NltiSession ownedSession(WorkflowState state) {
        NltiSession session = new NltiSession();
        session.setId(SESSION_ID);
        session.setSubjectId(SUBJECT);
        session.setWorkflowState(state);
        return session;
    }

    @Test
    @DisplayName("resolveActiveState returns the subject's most-recent session state")
    void resolveActiveState_returnsMostRecentState() {
        when(sessionRepository.findFirstBySubjectIdOrderByUpdatedAtDesc(SUBJECT))
                .thenReturn(Optional.of(ownedSession(WorkflowState.CREATING_PO)));

        assertThat(service.resolveActiveState(SUBJECT)).contains(WorkflowState.CREATING_PO);
    }

    @Test
    @DisplayName("resolveActiveState is empty when the subject has no session (heuristic fallback)")
    void resolveActiveState_emptyWhenNoSession() {
        when(sessionRepository.findFirstBySubjectIdOrderByUpdatedAtDesc(SUBJECT))
                .thenReturn(Optional.empty());

        assertThat(service.resolveActiveState(SUBJECT)).isEmpty();
    }

    @Test
    @DisplayName("advance sets and saves the new state on an owned session")
    void advance_setsAndSavesState_whenOwned() {
        NltiSession session = ownedSession(WorkflowState.IDLE);
        when(sessionRepository.findByIdAndSubjectId(SESSION_ID, SUBJECT)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(NltiSession.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkflowState result = service.advance(SESSION_ID, SUBJECT, WorkflowState.RECEIVING_ASN, CORRELATION_ID);

        assertThat(result).isEqualTo(WorkflowState.RECEIVING_ASN);
        assertThat(session.getWorkflowState()).isEqualTo(WorkflowState.RECEIVING_ASN);
        verify(sessionRepository).save(session);
    }

    @Test
    @DisplayName("advance emits one nlti.workflow.transition telemetry event carrying from -> to")
    void advance_emitsTransitionTelemetry() {
        NltiSession session = ownedSession(WorkflowState.IDLE);
        when(sessionRepository.findByIdAndSubjectId(SESSION_ID, SUBJECT)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(NltiSession.class))).thenAnswer(inv -> inv.getArgument(0));

        service.advance(SESSION_ID, SUBJECT, WorkflowState.PROCESSING_RETURN, CORRELATION_ID);

        ArgumentCaptor<NltiWorkflowTransitionTelemetry> captor =
                ArgumentCaptor.forClass(NltiWorkflowTransitionTelemetry.class);
        verify(transitionEmitter).emit(captor.capture());
        NltiWorkflowTransitionTelemetry event = captor.getValue();
        assertThat(event.schemaVersion()).isEqualTo(NltiWorkflowTransitionTelemetry.SCHEMA_VERSION);
        assertThat(event.eventType()).isEqualTo(NltiWorkflowTransitionTelemetry.EVENT_TYPE);
        assertThat(event.correlationId()).isEqualTo(CORRELATION_ID.toString());
        assertThat(event.sessionId()).isEqualTo(SESSION_ID.toString());
        assertThat(event.timestamp()).isEqualTo("2026-08-18T12:00:00Z");
        assertThat(event.actor().subjectId()).isEqualTo(SUBJECT);
        assertThat(event.transition().fromState()).isEqualTo("IDLE");
        assertThat(event.transition().toState()).isEqualTo("PROCESSING_RETURN");
        assertThat(event.transition().changed()).isTrue();
    }

    @Test
    @DisplayName("advance to the state already held emits the transition with changed=false")
    void advance_toSameState_emitsUnchangedTransition() {
        NltiSession session = ownedSession(WorkflowState.PROCESSING_RETURN);
        when(sessionRepository.findByIdAndSubjectId(SESSION_ID, SUBJECT)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(NltiSession.class))).thenAnswer(inv -> inv.getArgument(0));

        service.advance(SESSION_ID, SUBJECT, WorkflowState.PROCESSING_RETURN, CORRELATION_ID);

        ArgumentCaptor<NltiWorkflowTransitionTelemetry> captor =
                ArgumentCaptor.forClass(NltiWorkflowTransitionTelemetry.class);
        verify(transitionEmitter).emit(captor.capture());
        assertThat(captor.getValue().transition().changed()).isFalse();
        assertThat(captor.getValue().transition().fromState()).isEqualTo("PROCESSING_RETURN");
        assertThat(captor.getValue().transition().toState()).isEqualTo("PROCESSING_RETURN");
    }

    @Test
    @DisplayName("a telemetry emitter failure never fails the persisted transition")
    void advance_telemetryFailure_doesNotFailRequest() {
        NltiSession session = ownedSession(WorkflowState.IDLE);
        when(sessionRepository.findByIdAndSubjectId(SESSION_ID, SUBJECT)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(NltiSession.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new IllegalStateException("telemetry sink down"))
                .when(transitionEmitter)
                .emit(any(NltiWorkflowTransitionTelemetry.class));

        assertThatCode(() -> service.advance(SESSION_ID, SUBJECT, WorkflowState.CREATING_PO, CORRELATION_ID))
                .doesNotThrowAnyException();
        assertThat(session.getWorkflowState()).isEqualTo(WorkflowState.CREATING_PO);
    }

    @Test
    @DisplayName("advance rejects a session not owned by the subject (fail-closed, no telemetry)")
    void advance_throwsWhenNotOwned() {
        when(sessionRepository.findByIdAndSubjectId(SESSION_ID, SUBJECT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.advance(SESSION_ID, SUBJECT, WorkflowState.CREATING_PO, CORRELATION_ID))
                .isInstanceOf(SessionOwnershipViolationException.class);
        verify(sessionRepository, never()).save(any());
        verifyNoInteractions(transitionEmitter);
    }
}
