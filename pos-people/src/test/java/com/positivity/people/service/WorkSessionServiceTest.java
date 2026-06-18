package com.positivity.people.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.dto.BreakDto;
import com.positivity.people.internal.dto.WorkSessionDto;
import com.positivity.people.internal.dto.WorkSessionSubmitRequest;
import com.positivity.people.internal.entity.Person;
import com.positivity.people.internal.entity.WorkSession;
import com.positivity.people.internal.entity.WorkSessionBreak;
import com.positivity.people.internal.exception.WorkSessionNotFoundException;
import com.positivity.people.internal.repository.PersonRepository;
import com.positivity.people.internal.repository.WorkSessionBreakRepository;
import com.positivity.people.internal.repository.WorkSessionRepository;
import com.positivity.people.internal.service.WorkSessionServiceImpl;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
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

    @Mock
    private PersonRepository personRepository;

    private WorkSessionService service;

    private UUID personId;

    @BeforeEach
    void setUp() {
        service = new WorkSessionServiceImpl(
                workSessionRepository, workSessionBreakRepository, personRepository, Clock.systemUTC());
        personId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    }

    @Test
    void startSession_whenNoActiveSessionExists_createsActiveSession() {
        when(workSessionRepository.findByPerson_IdAndEndedAtIsNull(personId)).thenReturn(Optional.empty());
        when(personRepository.getReferenceById(personId))
                .thenReturn(Person.builder().id(personId).build());
        when(workSessionRepository.save(any(WorkSession.class))).thenAnswer(invocation -> {
            WorkSession session = invocation.getArgument(0);
            session.setSessionId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
            return session;
        });

        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock
                    .when(() -> SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .thenReturn("manager.user");

            WorkSessionDto result = service.startSession(personId);

            assertThat(result).isNotNull();
            assertThat(result.getSessionId()).isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
            assertThat(result.getPersonId()).isEqualTo(personId);
            assertThat(result.getStatus()).isEqualTo("ACTIVE");
            assertThat(result.getStartedAt()).isNotNull();
            assertThat(result.getEndedAt()).isNull();
        }
    }

    @Test
    void startSession_whenActiveSessionAlreadyExists_throwsException() {
        WorkSession existing = new WorkSession();
        existing.setSessionId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        when(workSessionRepository.findByPerson_IdAndEndedAtIsNull(personId)).thenReturn(Optional.of(existing));

        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock
                    .when(() -> SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .thenReturn("manager.user");

            assertThatThrownBy(() -> service.startSession(personId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("active session");
        }
    }

    @Test
    void stopSession_whenActiveSessionExists_endsSession() {
        WorkSession active = new WorkSession();
        active.setSessionId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        active.setPerson(Person.builder().id(personId).build());
        active.setStatus("ACTIVE");
        active.setStartedAt(Instant.parse("2026-01-01T08:00:00Z"));

        when(workSessionRepository.findByPerson_IdAndEndedAtIsNull(personId)).thenReturn(Optional.of(active));
        when(workSessionRepository.save(any(WorkSession.class))).thenAnswer(i -> i.getArgument(0));
        when(workSessionBreakRepository.findBySession_SessionIdAndEndedAtIsNull(
                        UUID.fromString("11111111-1111-1111-1111-111111111111")))
                .thenReturn(Optional.empty());

        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock
                    .when(() -> SecurityContextHelper.getCurrentUsernameOrDefault("system"))
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
        when(workSessionRepository.findByPerson_IdAndEndedAtIsNull(personId)).thenReturn(Optional.empty());

        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock
                    .when(() -> SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .thenReturn("manager.user");

            assertThatThrownBy(() -> service.stopSession(personId)).isInstanceOf(WorkSessionNotFoundException.class);
        }
    }

    @Test
    void startBreak_whenSessionExistsAndNoActiveBreak_createsActiveBreak() {
        UUID sessionId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        WorkSession session = new WorkSession();
        session.setSessionId(sessionId);
        session.setPerson(Person.builder().id(personId).build());

        when(workSessionRepository.findBySessionIdAndEndedAtIsNull(sessionId)).thenReturn(Optional.of(session));
        when(workSessionBreakRepository.findBySession_SessionIdAndEndedAtIsNull(sessionId))
                .thenReturn(Optional.empty());
        when(workSessionBreakRepository.save(any(WorkSessionBreak.class))).thenAnswer(invocation -> {
            WorkSessionBreak workBreak = invocation.getArgument(0);
            workBreak.setBreakId(UUID.fromString("10000000-0000-0000-0000-000000000000"));
            return workBreak;
        });

        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock
                    .when(() -> SecurityContextHelper.getCurrentUsernameOrDefault("system"))
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
        UUID missingId = UUID.fromString("99999999-9999-9999-9999-999999999999");
        when(workSessionRepository.findBySessionIdAndEndedAtIsNull(missingId)).thenReturn(Optional.empty());

        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock
                    .when(() -> SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .thenReturn("manager.user");

            assertThatThrownBy(() -> service.startBreak(missingId)).isInstanceOf(WorkSessionNotFoundException.class);
        }
    }

    @Test
    void startBreak_whenBreakAlreadyActive_throwsException() {
        UUID sessionId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        WorkSession session = new WorkSession();
        session.setSessionId(sessionId);
        WorkSessionBreak activeBreak = new WorkSessionBreak();
        activeBreak.setSession(session);

        when(workSessionRepository.findBySessionIdAndEndedAtIsNull(sessionId)).thenReturn(Optional.of(session));
        when(workSessionBreakRepository.findBySession_SessionIdAndEndedAtIsNull(sessionId))
                .thenReturn(Optional.of(activeBreak));

        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock
                    .when(() -> SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .thenReturn("manager.user");

            assertThatThrownBy(() -> service.startBreak(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already active");
        }
    }

    @Test
    void stopBreak_whenActiveBreakExists_endsBreak() {
        UUID sessionId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        WorkSession sessionForBreak = new WorkSession();
        sessionForBreak.setSessionId(sessionId);
        WorkSessionBreak activeBreak = new WorkSessionBreak();
        activeBreak.setSession(sessionForBreak);
        activeBreak.setStartedAt(Instant.parse("2026-01-01T10:00:00Z"));

        when(workSessionBreakRepository.findBySession_SessionIdAndEndedAtIsNull(sessionId))
                .thenReturn(Optional.of(activeBreak));
        when(workSessionBreakRepository.save(any(WorkSessionBreak.class))).thenAnswer(i -> i.getArgument(0));

        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock
                    .when(() -> SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .thenReturn("manager.user");

            BreakDto result = service.stopBreak(sessionId);

            assertThat(result).isNotNull();
            assertThat(result.getSessionId()).isEqualTo(sessionId);
            assertThat(result.getEndedAt()).isNotNull();
        }
    }

    @Test
    void stopBreak_whenNoActiveBreakExists_throwsException() {
        UUID sessionId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(workSessionBreakRepository.findBySession_SessionIdAndEndedAtIsNull(sessionId))
                .thenReturn(Optional.empty());

        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock
                    .when(() -> SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .thenReturn("manager.user");

            assertThatThrownBy(() -> service.stopBreak(sessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No active break");
        }
    }

    @Test
    void stopSession_whenActiveBreakExists_alsoEndsBreak() {
        WorkSession active = new WorkSession();
        active.setSessionId(UUID.fromString("55555555-5555-5555-5555-555555555555"));
        active.setPerson(Person.builder().id(personId).build());
        active.setStatus("ACTIVE");
        active.setStartedAt(Instant.parse("2026-01-01T08:00:00Z"));

        WorkSessionBreak activeBreak = new WorkSessionBreak();
        activeBreak.setBreakId(UUID.fromString("12121212-1212-1212-1212-121212121212"));
        activeBreak.setSession(active);
        activeBreak.setStartedAt(Instant.parse("2026-01-01T09:00:00Z"));

        when(workSessionRepository.findByPerson_IdAndEndedAtIsNull(personId)).thenReturn(Optional.of(active));
        when(workSessionRepository.save(any(WorkSession.class))).thenAnswer(i -> i.getArgument(0));
        when(workSessionBreakRepository.findBySession_SessionIdAndEndedAtIsNull(
                        UUID.fromString("55555555-5555-5555-5555-555555555555")))
                .thenReturn(Optional.of(activeBreak));
        when(workSessionBreakRepository.save(any(WorkSessionBreak.class))).thenAnswer(i -> i.getArgument(0));

        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock
                    .when(() -> SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .thenReturn("manager.user");

            WorkSessionDto result = service.stopSession(personId);

            assertThat(result.getStatus()).isEqualTo("ENDED");
            verify(workSessionBreakRepository).save(any(WorkSessionBreak.class));
            assertThat(activeBreak.getEndedAt()).isNotNull();
        }
    }

    @Test
    void submitSession_whenSessionEnded_transitionsToSubmittedAndPersistsTotals() {
        UUID sessionId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        WorkSession ended = new WorkSession();
        ended.setSessionId(sessionId);
        ended.setPerson(Person.builder().id(personId).build());
        ended.setStatus("ENDED");
        ended.setStartedAt(Instant.parse("2026-01-01T08:00:00Z"));
        ended.setEndedAt(Instant.parse("2026-01-01T16:00:00Z"));

        WorkSessionSubmitRequest request = new WorkSessionSubmitRequest();
        request.setBillableMinutes(450);
        request.setBreakMinutes(30);
        request.setSubmittedAt(Instant.parse("2026-01-01T16:05:00Z"));

        when(workSessionRepository.findById(sessionId)).thenReturn(Optional.of(ended));
        when(workSessionRepository.save(any(WorkSession.class))).thenAnswer(i -> i.getArgument(0));

        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock
                    .when(() -> SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .thenReturn("worker.user");

            WorkSessionDto result = service.submitSession(sessionId, request);

            assertThat(result.getStatus()).isEqualTo("SUBMITTED");
            assertThat(result.getBillableMinutes()).isEqualTo(450);
            assertThat(result.getBreakMinutes()).isEqualTo(30);
            assertThat(result.getSubmittedAt()).isEqualTo(Instant.parse("2026-01-01T16:05:00Z"));
            verify(workSessionRepository).save(any(WorkSession.class));
        }
    }

    @Test
    void submitSession_whenSessionMissing_throwsWorkSessionNotFoundException() {
        UUID missingId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        WorkSessionSubmitRequest request = new WorkSessionSubmitRequest();
        request.setBillableMinutes(10);
        request.setBreakMinutes(0);
        request.setSubmittedAt(Instant.parse("2026-01-01T16:05:00Z"));
        when(workSessionRepository.findById(missingId)).thenReturn(Optional.empty());

        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock
                    .when(() -> SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .thenReturn("worker.user");

            assertThatThrownBy(() -> service.submitSession(missingId, request))
                    .isInstanceOf(WorkSessionNotFoundException.class);
        }
    }

    @Test
    void submitSession_whenSessionNotEnded_throwsIllegalState() {
        UUID sessionId = UUID.fromString("55555555-5555-5555-5555-555555555556");
        WorkSession active = new WorkSession();
        active.setSessionId(sessionId);
        active.setPerson(Person.builder().id(personId).build());
        active.setStatus("ACTIVE");

        WorkSessionSubmitRequest request = new WorkSessionSubmitRequest();
        request.setBillableMinutes(10);
        request.setBreakMinutes(0);
        request.setSubmittedAt(Instant.parse("2026-01-01T16:05:00Z"));
        when(workSessionRepository.findById(sessionId)).thenReturn(Optional.of(active));

        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock
                    .when(() -> SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .thenReturn("worker.user");

            assertThatThrownBy(() -> service.submitSession(sessionId, request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ENDED");
        }
    }
}
