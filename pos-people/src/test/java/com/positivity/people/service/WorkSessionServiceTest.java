package com.positivity.people.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.people.internal.service.WorkSessionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkSessionServiceTest {

    @Mock
    private com.positivity.people.internal.repository.TimeEntryRepository workSessionRepository;

    @Mock
    private com.positivity.people.internal.repository.TimeEntryExceptionRepository breakRepository;

    @InjectMocks
    private WorkSessionServiceImpl workSessionService;

    private String personId;
    private String actor;

    @BeforeEach
    void setUp() {
        personId = "person-1001";
        actor = "manager.user";
    }

    @Test
    void startSession_whenNoActiveSessionExists_createsActiveSession() {
        com.positivity.people.service.WorkSessionDto result = workSessionService.startSession(personId, actor);

        assertThat(result).isNotNull();
        assertThat(result.getSessionId()).isNotNull();
        assertThat(result.getPersonId()).isEqualTo(personId);
        assertThat(result.getStatus()).isEqualTo("ACTIVE");
        assertThat(result.getStartedAt()).isNotNull();
    }

    @Test
    void startSession_whenActiveSessionAlreadyExists_throwsException() {
        workSessionService.startSession(personId, actor);

        assertThatThrownBy(() -> workSessionService.startSession(personId, actor))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active session");
    }

    @Test
    void stopSession_whenActiveSessionExists_endsSession() {
        workSessionService.startSession(personId, actor);

        com.positivity.people.service.WorkSessionDto result = workSessionService.stopSession(personId, actor);

        assertThat(result).isNotNull();
        assertThat(result.getPersonId()).isEqualTo(personId);
        assertThat(result.getStatus()).isEqualTo("ENDED");
        assertThat(result.getEndedAt()).isNotNull();
    }

    @Test
    void stopSession_whenNoActiveSessionExists_throwsWorkSessionNotFoundException() {
        assertThatThrownBy(() -> workSessionService.stopSession(personId, actor))
                .isInstanceOf(com.positivity.people.service.WorkSessionNotFoundException.class);
    }

    @Test
    void startBreak_whenSessionExistsAndNoActiveBreak_createsActiveBreak() {
        com.positivity.people.service.WorkSessionDto session = workSessionService.startSession(personId, actor);

        com.positivity.people.service.BreakDto result = workSessionService.startBreak(session.getSessionId(), actor);

        assertThat(result).isNotNull();
        assertThat(result.getSessionId()).isEqualTo(session.getSessionId());
        assertThat(result.getStartedAt()).isNotNull();
        assertThat(result.getEndedAt()).isNull();
    }

    @Test
    void startBreak_whenSessionDoesNotExist_throwsWorkSessionNotFoundException() {
        assertThatThrownBy(() -> workSessionService.startBreak(999L, actor))
                .isInstanceOf(com.positivity.people.service.WorkSessionNotFoundException.class);
    }

    @Test
    void startBreak_whenBreakAlreadyActive_throwsException() {
        com.positivity.people.service.WorkSessionDto session = workSessionService.startSession(personId, actor);
        workSessionService.startBreak(session.getSessionId(), actor);

        assertThatThrownBy(() -> workSessionService.startBreak(session.getSessionId(), actor))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already active");
    }

    @Test
    void stopBreak_whenActiveBreakExists_endsBreak() {
        com.positivity.people.service.WorkSessionDto session = workSessionService.startSession(personId, actor);
        workSessionService.startBreak(session.getSessionId(), actor);

        com.positivity.people.service.BreakDto result = workSessionService.stopBreak(session.getSessionId(), actor);

        assertThat(result).isNotNull();
        assertThat(result.getSessionId()).isEqualTo(session.getSessionId());
        assertThat(result.getEndedAt()).isNotNull();
    }

    @Test
    void stopBreak_whenNoActiveBreakExists_throwsException() {
        com.positivity.people.service.WorkSessionDto session = workSessionService.startSession(personId, actor);

        assertThatThrownBy(() -> workSessionService.stopBreak(session.getSessionId(), actor))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No active break");
    }
}
