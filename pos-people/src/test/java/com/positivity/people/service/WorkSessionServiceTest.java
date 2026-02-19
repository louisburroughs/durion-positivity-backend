package com.positivity.people.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.dto.BreakDto;
import com.positivity.people.internal.dto.WorkSessionDto;
import com.positivity.people.internal.entity.WorkSession;
import com.positivity.people.internal.entity.WorkSessionBreak;
import com.positivity.people.internal.exception.WorkSessionNotFoundException;
import com.positivity.people.internal.repository.WorkSessionBreakRepository;
import com.positivity.people.internal.repository.WorkSessionRepository;
import com.positivity.people.internal.service.WorkSessionServiceImpl;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkSessionServiceTest {

    @Mock
    private WorkSessionRepository workSessionRepository;

    @Mock
    private WorkSessionBreakRepository workSessionBreakRepository;

    private WorkSessionService service;
    private String personId;

    @BeforeEach
    void setUp() {
        service = new WorkSessionServiceImpl(workSessionRepository, workSessionBreakRepository);
        personId = "person-1001";
    }

    @Test
    void startSession_whenNoActiveSessionExists_createsActiveSession() {
        when(workSessionRepository.findByPersonIdAndEndedAtIsNull(personId)).thenReturn(Optional.empty());
        when(workSessionRepository.save(any(WorkSession.class))).thenAnswer(invocation -> {
            WorkSession session = invocation.getArgument(0);
            session.setSessionId(1L);
            return session;
        });

        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock.when(() -> SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .thenReturn("manager.user");

            WorkSessionDto result = service.startSession(personId);

            assertThat(result).isNotNull();
            assertThat(result.getSessionId()).isEqualTo(1L);
            assertThat(result.getPersonId()).isEqualTo(personId);
            assertThat(result.getStatus()).isEqualTo("ACTIVE");
            assertThat(result.getStartedAt()).isNotNull();
            assertThat(result.getEndedAt()).isNull();
        }
    }

    @Test
    void startSession_whenActiveSessionAlreadyExists_throwsException() {
        WorkSession existing = new WorkSession();
        existing.setSessionId(10L);
        when(workSessionRepository.findByPersonIdAndEndedAtIsNull(personId)).thenReturn(Optional.of(existing));

        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock.when(() -> SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .thenReturn("manager.user");

            assertThatThrownBy(() -> service.startSession(personId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("active session");
        }
    }

    @Test
    void stopSession_whenActiveSessionExists_endsSession() {
        WorkSession active = new WorkSession();
        active.setSessionId(1L);
        active.setPersonId(personId);
        active.setStatus("ACTIVE");
        active.setStartedAt(Instant.parse("2026-01-01T08:00:00Z"));

        when(workSessionRepository.findByPersonIdAndEndedAtIsNull(personId)).thenReturn(Optional.of(active));
        when(workSessionRepository.save(any(WorkSession.class))).thenAnswer(i -> i.getArgument(0));
        when(workSessionBreakRepository.findBySessionIdAndEndedAtIsNull(1L)).thenReturn(Optional.empty());

        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock.when(() -> SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .thenReturn("manager.user");

            WorkSessionDto result = service.stopSession(personId);

            assertThat(result).isNotNull();
            assertThat(result.getPersonId()).isEqualTo(personId);
            assertThat(result.getStatus()).isEqualTo("ENDED");
            assertThat(result.getEndedAt()).isNotNull();
        }
    }

    @Test
    void stopSession_whenNoActiveSessionExists_throwsWorkSessionNotFoundException() {
        when(workSessionRepository.findByPersonIdAndEndedAtIsNull(personId)).thenReturn(Optional.empty());

        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock.when(() -> SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .thenReturn("manager.user");

            assertThatThrownBy(() -> service.stopSession(personId))
                    .isInstanceOf(WorkSessionNotFoundException.class);
        }
    }

    @Test
    void startBreak_whenSessionExistsAndNoActiveBreak_createsActiveBreak() {
        Long sessionId = 1L;
        WorkSession session = new WorkSession();
        session.setSessionId(sessionId);
        session.setPersonId(personId);

        when(workSessionRepository.findBySessionIdAndEndedAtIsNull(sessionId)).thenReturn(Optional.of(session));
        when(workSessionBreakRepository.findBySessionIdAndEndedAtIsNull(sessionId)).thenReturn(Optional.empty());
        when(workSessionBreakRepository.save(any(WorkSessionBreak.class))).thenAnswer(invocation -> {
            WorkSessionBreak workBreak = invocation.getArgument(0);
            workBreak.setBreakId(100L);
            return workBreak;
        });

        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock.when(() -> SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .thenReturn("manager.user");

            BreakDto result = service.startBreak(sessionId);

            assertThat(result).isNotNull();
            assertThat(result.getSessionId()).isEqualTo(sessionId);
            assertThat(result.getStartedAt()).isNotNull();
            assertThat(result.getEndedAt()).isNull();
        }
    }

    @Test
    void startBreak_whenSessionDoesNotExist_throwsWorkSessionNotFoundException() {
        when(workSessionRepository.findBySessionIdAndEndedAtIsNull(999L)).thenReturn(Optional.empty());

        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock.when(() -> SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .thenReturn("manager.user");

            assertThatThrownBy(() -> service.startBreak(999L))
                    .isInstanceOf(WorkSessionNotFoundException.class);
        }
    }

    @Test
    void startBreak_whenBreakAlreadyActive_throwsException() {
        Long sessionId = 1L;
        WorkSession session = new WorkSession();
        session.setSessionId(sessionId);
        WorkSessionBreak activeBreak = new WorkSessionBreak();
        activeBreak.setSessionId(sessionId);

        when(workSessionRepository.findBySessionIdAndEndedAtIsNull(sessionId)).thenReturn(Optional.of(session));
        when(workSessionBreakRepository.findBySessionIdAndEndedAtIsNull(sessionId)).thenReturn(Optional.of(activeBreak));

        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock.when(() -> SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .thenReturn("manager.user");

            assertThatThrownBy(() -> service.startBreak(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already active");
        }
    }

    @Test
    void stopBreak_whenActiveBreakExists_endsBreak() {
        Long sessionId = 1L;
        WorkSessionBreak activeBreak = new WorkSessionBreak();
        activeBreak.setSessionId(sessionId);
        activeBreak.setStartedAt(Instant.parse("2026-01-01T10:00:00Z"));

        when(workSessionBreakRepository.findBySessionIdAndEndedAtIsNull(sessionId)).thenReturn(Optional.of(activeBreak));
        when(workSessionBreakRepository.save(any(WorkSessionBreak.class))).thenAnswer(i -> i.getArgument(0));

        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock.when(() -> SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .thenReturn("manager.user");

            BreakDto result = service.stopBreak(sessionId);

            assertThat(result).isNotNull();
            assertThat(result.getSessionId()).isEqualTo(sessionId);
            assertThat(result.getEndedAt()).isNotNull();
        }
    }

    @Test
    void stopBreak_whenNoActiveBreakExists_throwsException() {
        Long sessionId = 1L;
        when(workSessionBreakRepository.findBySessionIdAndEndedAtIsNull(sessionId)).thenReturn(Optional.empty());

        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock.when(() -> SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .thenReturn("manager.user");

            assertThatThrownBy(() -> service.stopBreak(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No active break");
        }
    }

    @Test
    void stopSession_whenActiveBreakExists_alsoEndsBreak() {
        WorkSession active = new WorkSession();
        active.setSessionId(5L);
        active.setPersonId(personId);
        active.setStatus("ACTIVE");
        active.setStartedAt(Instant.parse("2026-01-01T08:00:00Z"));

        WorkSessionBreak activeBreak = new WorkSessionBreak();
        activeBreak.setBreakId(12L);
        activeBreak.setSessionId(5L);
        activeBreak.setStartedAt(Instant.parse("2026-01-01T09:00:00Z"));

        when(workSessionRepository.findByPersonIdAndEndedAtIsNull(personId)).thenReturn(Optional.of(active));
        when(workSessionRepository.save(any(WorkSession.class))).thenAnswer(i -> i.getArgument(0));
        when(workSessionBreakRepository.findBySessionIdAndEndedAtIsNull(5L)).thenReturn(Optional.of(activeBreak));
        when(workSessionBreakRepository.save(any(WorkSessionBreak.class))).thenAnswer(i -> i.getArgument(0));

        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock.when(() -> SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .thenReturn("manager.user");

            WorkSessionDto result = service.stopSession(personId);

            assertThat(result.getStatus()).isEqualTo("ENDED");
            verify(workSessionBreakRepository).save(any(WorkSessionBreak.class));
            assertThat(activeBreak.getEndedAt()).isNotNull();
        }
    }
}
