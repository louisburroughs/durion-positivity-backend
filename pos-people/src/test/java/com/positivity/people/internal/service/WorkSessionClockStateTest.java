package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.dto.WorkSessionClockStateResponse;
import com.positivity.people.internal.entity.WorkSession;
import com.positivity.people.internal.entity.WorkSessionBreak;
import com.positivity.people.internal.enums.ClockState;
import com.positivity.people.internal.exception.PersonNotFoundException;
import com.positivity.people.internal.repository.EmployeeLocationAssignmentRepository;
import com.positivity.people.internal.repository.ExtPersonReplicaRepository;
import com.positivity.people.internal.repository.TimeEntryRepository;
import com.positivity.people.internal.repository.WorkSessionBreakRepository;
import com.positivity.people.internal.repository.WorkSessionRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * The derived clock-state reads (issue #2061): CLOCKED_IN / ON_BREAK / CLOCKED_OUT from the open
 * session and break, resolved for a whole roster in two queries, never one per person.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkSessionServiceImpl — clock state reads")
class WorkSessionClockStateTest {

    private static final UUID ADA = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID GRACE = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID LINUS = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID ADA_SESSION = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID GRACE_SESSION = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final Instant ADA_IN = Instant.parse("2026-02-16T08:00:00Z");
    private static final Instant GRACE_IN = Instant.parse("2026-02-16T08:30:00Z");
    private static final Instant GRACE_BREAK = Instant.parse("2026-02-16T12:00:00Z");

    @Mock
    private WorkSessionRepository workSessionRepository;

    @Mock
    private WorkSessionBreakRepository workSessionBreakRepository;

    @Mock
    private ExtPersonReplicaRepository extPersonReplicaRepository;

    @Mock
    private TimeEntryRepository timeEntryRepository;

    @Mock
    private EmployeeLocationAssignmentRepository locationAssignmentRepository;

    @Mock
    private WorkSessionAccessPolicy accessPolicy;

    private WorkSessionService service;

    @BeforeEach
    void setUp() {
        service = new WorkSessionServiceImpl(
                workSessionRepository,
                workSessionBreakRepository,
                extPersonReplicaRepository,
                timeEntryRepository,
                locationAssignmentRepository,
                accessPolicy,
                Clock.systemUTC());
    }

    private static WorkSession openSession(UUID sessionId, UUID personId, Instant startedAt) {
        WorkSession session = new WorkSession();
        session.setSessionId(sessionId);
        session.setPersonId(personId);
        session.setStatus("ACTIVE");
        session.setStartedAt(startedAt);
        return session;
    }

    private static WorkSessionBreak openBreak(WorkSession session, Instant startedAt) {
        WorkSessionBreak openBreak = new WorkSessionBreak();
        openBreak.setSession(session);
        openBreak.setStartedAt(startedAt);
        return openBreak;
    }

    @Test
    @DisplayName("AC1/AC2/AC3: clocked in, on break and clocked out are told apart from the open session and break")
    void derivesEachStateFromTheOpenSessionAndBreak() {
        WorkSession adaSession = openSession(ADA_SESSION, ADA, ADA_IN);
        WorkSession graceSession = openSession(GRACE_SESSION, GRACE, GRACE_IN);
        when(workSessionRepository.findByPersonIdInAndEndedAtIsNullOrderByStartedAtDesc(anyCollection()))
                .thenReturn(List.of(graceSession, adaSession));
        when(workSessionBreakRepository.findBySession_SessionIdInAndEndedAtIsNull(anyCollection()))
                .thenReturn(List.of(openBreak(graceSession, GRACE_BREAK)));

        Map<UUID, WorkSessionClockStateResponse> states = service.resolveClockStates(List.of(ADA, GRACE, LINUS));

        assertThat(states).containsOnlyKeys(ADA, GRACE, LINUS);
        assertThat(states.get(ADA)).satisfies(ada -> {
            assertThat(ada.getClockState()).isEqualTo(ClockState.CLOCKED_IN);
            assertThat(ada.getWorkSessionId()).isEqualTo(ADA_SESSION);
            assertThat(ada.getClockedInAt()).isEqualTo(ADA_IN);
            assertThat(ada.getBreakStartedAt()).isNull();
        });
        assertThat(states.get(GRACE)).satisfies(grace -> {
            assertThat(grace.getClockState()).isEqualTo(ClockState.ON_BREAK);
            assertThat(grace.getWorkSessionId()).isEqualTo(GRACE_SESSION);
            assertThat(grace.getClockedInAt()).isEqualTo(GRACE_IN);
            assertThat(grace.getBreakStartedAt()).isEqualTo(GRACE_BREAK);
        });
        assertThat(states.get(LINUS)).satisfies(linus -> {
            assertThat(linus.getPersonId()).isEqualTo(LINUS);
            assertThat(linus.getClockState()).isEqualTo(ClockState.CLOCKED_OUT);
            assertThat(linus.getWorkSessionId()).isNull();
            assertThat(linus.getClockedInAt()).isNull();
            assertThat(linus.getBreakStartedAt()).isNull();
        });
    }

    @Test
    @DisplayName("AC4/BR7: N people cost one session query and one break query — never a query per person")
    void resolvesARosterInABoundedNumberOfQueries() {
        List<UUID> roster = List.of(ADA, GRACE, LINUS, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        WorkSession adaSession = openSession(ADA_SESSION, ADA, ADA_IN);
        when(workSessionRepository.findByPersonIdInAndEndedAtIsNullOrderByStartedAtDesc(anyCollection()))
                .thenReturn(List.of(adaSession));
        when(workSessionBreakRepository.findBySession_SessionIdInAndEndedAtIsNull(anyCollection()))
                .thenReturn(List.of());

        service.resolveClockStates(roster);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> personIds = ArgumentCaptor.forClass(Collection.class);
        verify(workSessionRepository, times(1))
                .findByPersonIdInAndEndedAtIsNullOrderByStartedAtDesc(personIds.capture());
        assertThat(personIds.getValue()).containsExactlyInAnyOrderElementsOf(roster);
        verify(workSessionRepository, never()).findByPersonIdAndEndedAtIsNull(any());
        verify(workSessionBreakRepository, times(1)).findBySession_SessionIdInAndEndedAtIsNull(List.of(ADA_SESSION));
        verify(workSessionBreakRepository, never()).findBySession_SessionIdAndEndedAtIsNull(any());
    }

    @Test
    @DisplayName("nobody clocked in: the break query is skipped and everyone is CLOCKED_OUT")
    void skipsTheBreakQueryWhenNoSessionIsOpen() {
        when(workSessionRepository.findByPersonIdInAndEndedAtIsNullOrderByStartedAtDesc(anyCollection()))
                .thenReturn(List.of());

        Map<UUID, WorkSessionClockStateResponse> states = service.resolveClockStates(List.of(ADA, GRACE));

        assertThat(states.values())
                .extracting(WorkSessionClockStateResponse::getClockState)
                .containsOnly(ClockState.CLOCKED_OUT);
        verifyNoInteractions(workSessionBreakRepository);
    }

    @Test
    @DisplayName("an empty roster touches no repository")
    void emptyRosterTouchesNothing() {
        assertThat(service.resolveClockStates(List.of())).isEmpty();
        verifyNoInteractions(workSessionRepository, workSessionBreakRepository);
    }

    @Test
    @DisplayName("BR2: two open sessions for one person surface the most recent, not an arbitrary one")
    void reportsTheMostRecentWhenTheDataHoldsTwoOpenSessions() {
        WorkSession older = openSession(ADA_SESSION, ADA, ADA_IN);
        WorkSession newer = openSession(GRACE_SESSION, ADA, ADA_IN.plusSeconds(3600));
        // The repository orders newest first; the first one seen per person wins.
        when(workSessionRepository.findByPersonIdInAndEndedAtIsNullOrderByStartedAtDesc(anyCollection()))
                .thenReturn(List.of(newer, older));
        when(workSessionBreakRepository.findBySession_SessionIdInAndEndedAtIsNull(anyCollection()))
                .thenReturn(List.of());

        Map<UUID, WorkSessionClockStateResponse> states = service.resolveClockStates(List.of(ADA));

        assertThat(states.get(ADA).getWorkSessionId()).isEqualTo(GRACE_SESSION);
        assertThat(states.get(ADA).getClockedInAt()).isEqualTo(ADA_IN.plusSeconds(3600));
    }

    @Test
    @DisplayName("AC6: the single-person read answers CLOCKED_OUT, not an error, when nothing is open")
    void currentStateIsClockedOutRatherThanNotFound() {
        when(extPersonReplicaRepository.existsById(ADA)).thenReturn(true);
        when(workSessionRepository.findByPersonIdInAndEndedAtIsNullOrderByStartedAtDesc(anyCollection()))
                .thenReturn(List.of());

        WorkSessionClockStateResponse state = service.getCurrentClockState(ADA);

        assertThat(state.getPersonId()).isEqualTo(ADA);
        assertThat(state.getClockState()).isEqualTo(ClockState.CLOCKED_OUT);
        verify(accessPolicy).requireMayView(ADA);
    }

    @Test
    @DisplayName("the single-person read defaults to the caller's own linked person")
    void currentStateDefaultsToTheCallersPerson() {
        when(accessPolicy.callerPersonId()).thenReturn(Optional.of(GRACE));
        when(extPersonReplicaRepository.existsById(GRACE)).thenReturn(true);
        WorkSession graceSession = openSession(GRACE_SESSION, GRACE, GRACE_IN);
        when(workSessionRepository.findByPersonIdInAndEndedAtIsNullOrderByStartedAtDesc(anyCollection()))
                .thenReturn(List.of(graceSession));
        when(workSessionBreakRepository.findBySession_SessionIdInAndEndedAtIsNull(anyCollection()))
                .thenReturn(List.of());

        WorkSessionClockStateResponse state = service.getCurrentClockState(null);

        assertThat(state.getPersonId()).isEqualTo(GRACE);
        assertThat(state.getClockState()).isEqualTo(ClockState.CLOCKED_IN);
        verify(accessPolicy).requireMayView(GRACE);
    }

    @Test
    @DisplayName("the single-person read is 404 for an unlinked caller with no personId")
    void currentStateWithoutAPersonIsNotFound() {
        when(accessPolicy.callerPersonId()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCurrentClockState(null)).isInstanceOf(EntityNotFoundException.class);
        verifyNoInteractions(workSessionRepository);
    }

    @Test
    @DisplayName("an unknown person is 404 before the access check runs, so ids cannot be probed")
    void unknownPersonIsNotFoundBeforeTheAccessCheck() {
        when(extPersonReplicaRepository.existsById(ADA)).thenReturn(false);

        assertThatThrownBy(() -> service.getCurrentClockState(ADA)).isInstanceOf(PersonNotFoundException.class);
        verify(accessPolicy, never()).requireMayView(any());
    }

    @Test
    @DisplayName("BR1: a caller the policy refuses gets nothing, and nothing is read")
    void refusedCallerReadsNothing() {
        when(extPersonReplicaRepository.existsById(ADA)).thenReturn(true);
        org.mockito.Mockito.doThrow(new AccessDeniedException("no"))
                .when(accessPolicy)
                .requireMayView(ADA);

        assertThatThrownBy(() -> service.getCurrentClockState(ADA)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(workSessionRepository);
    }
}
