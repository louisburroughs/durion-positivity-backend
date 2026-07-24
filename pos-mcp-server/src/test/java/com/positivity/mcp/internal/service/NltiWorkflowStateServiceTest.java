package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.entity.NltiSession;
import com.positivity.mcp.internal.exception.SessionOwnershipViolationException;
import com.positivity.mcp.internal.repository.NltiSessionRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NltiWorkflowStateServiceTest {

    private static final String SUBJECT = "alice";
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-000000000901");

    @Mock
    private NltiSessionRepository sessionRepository;

    @InjectMocks
    private NltiWorkflowStateService service;

    @Test
    @DisplayName("resolveActiveState returns the subject's most-recent session state")
    void resolveActiveState_returnsMostRecentState() {
        NltiSession session = new NltiSession();
        session.setId(SESSION_ID);
        session.setSubjectId(SUBJECT);
        session.setWorkflowState(WorkflowState.CREATING_PO);
        when(sessionRepository.findFirstBySubjectIdOrderByUpdatedAtDesc(SUBJECT))
                .thenReturn(Optional.of(session));

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
        NltiSession session = new NltiSession();
        session.setId(SESSION_ID);
        session.setSubjectId(SUBJECT);
        session.setWorkflowState(WorkflowState.IDLE);
        when(sessionRepository.findByIdAndSubjectId(SESSION_ID, SUBJECT)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(NltiSession.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkflowState result = service.advance(SESSION_ID, SUBJECT, WorkflowState.RECEIVING_ASN);

        assertThat(result).isEqualTo(WorkflowState.RECEIVING_ASN);
        assertThat(session.getWorkflowState()).isEqualTo(WorkflowState.RECEIVING_ASN);
        verify(sessionRepository).save(session);
    }

    @Test
    @DisplayName("advance rejects a session not owned by the subject (fail-closed)")
    void advance_throwsWhenNotOwned() {
        when(sessionRepository.findByIdAndSubjectId(SESSION_ID, SUBJECT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.advance(SESSION_ID, SUBJECT, WorkflowState.CREATING_PO))
                .isInstanceOf(SessionOwnershipViolationException.class);
        verify(sessionRepository, never()).save(any());
    }
}
