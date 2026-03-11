package com.positivity.workorder.service;

import java.time.ZoneOffset;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.workorder.internal.dto.AddBreakSegmentRequest;
import com.positivity.workorder.internal.dto.BreakSegmentResponse;
import com.positivity.workorder.internal.dto.StartWorkSessionRequest;
import com.positivity.workorder.internal.dto.StopWorkSessionRequest;
import com.positivity.workorder.internal.dto.WorkSessionResponse;
import com.positivity.workorder.internal.entity.BreakSegment;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkSession;
import com.positivity.workorder.internal.enums.BreakType;
import com.positivity.workorder.internal.enums.WorkSessionStatus;
import com.positivity.workorder.internal.exception.BreakSegmentNotFoundException;
import com.positivity.workorder.internal.exception.WorkSessionLockedException;
import com.positivity.workorder.internal.exception.WorkSessionNotFoundException;
import com.positivity.workorder.internal.exception.WorkSessionOverlapException;
import com.positivity.workorder.internal.exception.WorkSessionStateException;
import com.positivity.workorder.internal.repository.BreakSegmentRepository;
import com.positivity.workorder.internal.repository.WorkSessionRepository;
import com.positivity.workorder.internal.service.WorkSessionServiceImpl;

/**
 * Unit tests for {@link WorkSessionServiceImpl}. Covers overlap policy, session
 * state transitions,
 * break management, and net duration computation.
 *
 * <p>
 * Acceptance criteria coverage:
 * </p>
 * <ul>
 * <li>AC1: Start session creates IN_PROGRESS session with UTC startAt</li>
 * <li>AC2: Duplicate start for same mechanic is rejected (overlap
 * conflict)</li>
 * <li>AC3: Overlap allowed when all three conditions met (flag + permission +
 * reason)</li>
 * <li>AC4: Stop session records COMPLETED with UTC endAt and positive
 * duration</li>
 * <li>AC5: Stop when no active session is rejected</li>
 * <li>AC6: Break segment created with breakStartAt</li>
 * <li>AC7: Break segment closed with breakEndAt</li>
 * <li>AC8: Net duration deducts break time from total</li>
 * </ul>
 *
 * Issue: CAP-139 Story #68
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkSessionServiceImplTest {
        private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

        @Spy
        Clock clock = TEST_CLOCK;

        @Mock
        private WorkSessionRepository workSessionRepository;

        @Mock
        private BreakSegmentRepository breakSegmentRepository;

        @Mock
        private ApplicationEventPublisher eventPublisher;

        @InjectMocks
        private WorkSessionServiceImpl workSessionService;

        // ── Fixtures ──────────────────────────────────────────────────────────────

        private static final UUID MECHANIC_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        private static final UUID WORK_ORDER_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
        private static final UUID TASK_ID = UUID.fromString("cccccccc-0000-0000-0000-000000000003");
        private static final UUID LOCATION_ID = UUID.fromString("dddddddd-0000-0000-0000-000000000004");
        private static final UUID SESSION_ID = UUID.fromString("eeeeeeee-0000-0000-0000-000000000005");
        private static final UUID BREAK_ID = UUID.fromString("ffffffff-0000-0000-0000-000000000006");
        private static final UUID SYSTEM_USER_ID = UUID.fromString("11111111-0000-0000-0000-000000000000");

        @BeforeEach
        void setUp() {
                ReflectionTestUtils.setField(workSessionService, "allowOverlappingSessions", false);
                setAuthenticatedUser(List.of(), SYSTEM_USER_ID.toString());
        }

        @AfterEach
        void tearDown() {
                SecurityContextHolder.clearContext();
        }

        private StartWorkSessionRequest validStartRequest() {
                return StartWorkSessionRequest.builder()
                                .mechanicId(MECHANIC_ID)
                                .workOrderId(WORK_ORDER_ID)
                                .workOrderTaskId(TASK_ID)
                                .locationId(LOCATION_ID)
                                .build();
        }

        private WorkSession inProgressSession(Instant startAt) {
                return WorkSession.builder()
                                .workSessionId(SESSION_ID)
                                .mechanicId(MECHANIC_ID)
                                .workOrder(new Workorder(WORK_ORDER_ID))
                                .workOrderTaskId(TASK_ID)
                                .locationId(LOCATION_ID)
                                .startAt(startAt)
                                .status(WorkSessionStatus.IN_PROGRESS)
                                .locked(false)
                                .totalDurationSeconds(0)
                                .build();
        }

        private void setAuthenticatedUser(List<String> authorities, String username) {
                var grantedAuthorities = authorities.stream().map(SimpleGrantedAuthority::new).toList();
                var authentication = new UsernamePasswordAuthenticationToken(username, "N/A", grantedAuthorities);
                authentication.setDetails(Map.of(
                                GatewaySecurityConstants.DETAIL_USERNAME, username,
                                GatewaySecurityConstants.DETAIL_USER_ID, SYSTEM_USER_ID));
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
        }

        // ── AC1: Start session — happy path ───────────────────────────────────────

        @Test
        @DisplayName("AC1: startSession creates IN_PROGRESS session with non-null UTC startAt")
        void startSession_createsNewSession_withInProgressStatusAndUtcStartAt() {
                // Issue CAP-139 Story #68: happy-path start
                when(workSessionRepository.findByMechanicIdAndStatus(MECHANIC_ID, WorkSessionStatus.IN_PROGRESS))
                                .thenReturn(List.of());
                when(workSessionRepository.save(any())).thenAnswer(inv -> {
                        WorkSession ws = inv.getArgument(0);
                        ws.setWorkSessionId(SESSION_ID);
                        return ws;
                });

                WorkSessionResponse response = workSessionService.startSession(validStartRequest());

                assertThat(response.getStatus()).isEqualTo(WorkSessionStatus.IN_PROGRESS);
                assertThat(response.getStartAt()).isNotNull();
                assertThat(response.getWorkSessionId()).isNotNull();
        }

        // ── AC2: Overlap detection ─────────────────────────────────────────────────

        @Test
        @DisplayName("AC2: startSession rejects request when another IN_PROGRESS session exists for same mechanic")
        void startSession_rejectsOverlap_whenAnotherSessionInProgress() {
                // Issue CAP-139 Story #68: overlap conflict
                WorkSession existing = inProgressSession(Instant.now(TEST_CLOCK).minus(30, ChronoUnit.MINUTES));
                when(workSessionRepository.findByMechanicIdAndStatus(MECHANIC_ID, WorkSessionStatus.IN_PROGRESS))
                                .thenReturn(List.of(existing));

                assertThatThrownBy(() -> workSessionService.startSession(validStartRequest()))
                                .isInstanceOf(WorkSessionOverlapException.class);
        }

        // ── AC3: Overlap override ──────────────────────────────────────────────────

        @Test
        @DisplayName("AC3: startSession allows overlap when all three conditions met (flag + permission + reason)")
        void startSession_overlap_allowedWhenAllThreeConditionsMet() {
                // Issue CAP-139 Story #68: overlap override — all three conditions
                ReflectionTestUtils.setField(workSessionService, "allowOverlappingSessions", true);
                setAuthenticatedUser(List.of("timekeeping:overlap_override"), SYSTEM_USER_ID.toString());

                WorkSession existing = inProgressSession(Instant.now(TEST_CLOCK).minus(30, ChronoUnit.MINUTES));
                when(workSessionRepository.findByMechanicIdAndStatus(MECHANIC_ID, WorkSessionStatus.IN_PROGRESS))
                                .thenReturn(List.of(existing));
                when(workSessionRepository.save(any())).thenAnswer(inv -> {
                        WorkSession ws = inv.getArgument(0);
                        ws.setWorkSessionId(SESSION_ID);
                        return ws;
                });

                StartWorkSessionRequest requestWithOverride = StartWorkSessionRequest.builder()
                                .mechanicId(MECHANIC_ID)
                                .workOrderId(WORK_ORDER_ID)
                                .workOrderTaskId(TASK_ID)
                                .locationId(LOCATION_ID)
                                .overlapOverrideReason("Manager approved dual session for warranty recall")
                                .build();

                WorkSessionResponse response = workSessionService.startSession(requestWithOverride);

                assertThat(response.isOverlapOverrideUsed()).isTrue();
        }

        @Test
        @DisplayName("AC3b: startSession rejects overlap when flag is true but permission is missing")
        void startSession_overlap_throwsWhenFlagTrueButNoPermission() {
                // Issue CAP-139 Story #68: overlap rejected — flag=true but no authority
                ReflectionTestUtils.setField(workSessionService, "allowOverlappingSessions", true);
                setAuthenticatedUser(List.of(), SYSTEM_USER_ID.toString());

                WorkSession existing = inProgressSession(Instant.now(TEST_CLOCK).minus(30, ChronoUnit.MINUTES));
                when(workSessionRepository.findByMechanicIdAndStatus(MECHANIC_ID, WorkSessionStatus.IN_PROGRESS))
                                .thenReturn(List.of(existing));

                StartWorkSessionRequest req = StartWorkSessionRequest.builder()
                                .mechanicId(MECHANIC_ID).workOrderId(WORK_ORDER_ID).workOrderTaskId(TASK_ID)
                                .locationId(LOCATION_ID)
                                .overlapOverrideReason("some reason")
                                .build();

                assertThatThrownBy(() -> workSessionService.startSession(req))
                                .isInstanceOf(WorkSessionOverlapException.class);
        }

        @Test
        @DisplayName("AC3c: startSession rejects overlap when flag is false even if permission and reason present")
        void startSession_overlap_throwsWhenFlagFalseEvenWithPermissionAndReason() {
                // Issue CAP-139 Story #68: all 3 conditions must be met; flag=false alone
                // blocks override
                ReflectionTestUtils.setField(workSessionService, "allowOverlappingSessions", false);
                setAuthenticatedUser(List.of("timekeeping:overlap_override"), SYSTEM_USER_ID.toString());

                WorkSession existing = inProgressSession(Instant.now(TEST_CLOCK).minus(30, ChronoUnit.MINUTES));
                when(workSessionRepository.findByMechanicIdAndStatus(MECHANIC_ID, WorkSessionStatus.IN_PROGRESS))
                                .thenReturn(List.of(existing));

                StartWorkSessionRequest req = StartWorkSessionRequest.builder()
                                .mechanicId(MECHANIC_ID).workOrderId(WORK_ORDER_ID).workOrderTaskId(TASK_ID)
                                .locationId(LOCATION_ID)
                                .overlapOverrideReason("some reason")
                                .build();

                assertThatThrownBy(() -> workSessionService.startSession(req))
                                .isInstanceOf(WorkSessionOverlapException.class);
        }

        @Test
        @DisplayName("AC4: stopSession updates session to COMPLETED with non-null endAt and positive duration")
        void stopSession_updatesSessionToCompleted_withUtcEndAt() {
                // Issue CAP-139 Story #68: stop session happy path
                Instant startAt = Instant.now(TEST_CLOCK).minus(45, ChronoUnit.MINUTES);
                WorkSession session = inProgressSession(startAt);
                when(workSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
                when(breakSegmentRepository.findByWorkSession_WorkSessionId(SESSION_ID)).thenReturn(List.of());
                when(workSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

                WorkSessionResponse response = workSessionService.stopSession(SESSION_ID, new StopWorkSessionRequest());

                assertThat(response.getStatus()).isEqualTo(WorkSessionStatus.COMPLETED);
                assertThat(response.getEndAt()).isNotNull();
                assertThat(response.getTotalDurationSeconds()).isGreaterThan(0);
        }

        // ── AC5: Stop with no active session ──────────────────────────────────────

        @Test
        @DisplayName("AC5: stopSession throws exception when no IN_PROGRESS session exists for the given ID")
        void stopSession_throwsException_whenNoActiveSession() {
                // Issue CAP-139 Story #68: stop non-existent/not-in-progress session
                when(workSessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

                assertThatThrownBy(() -> workSessionService.stopSession(SESSION_ID, new StopWorkSessionRequest()))
                                .isInstanceOf(WorkSessionNotFoundException.class);
        }

        // ── AC6: Add break segment ─────────────────────────────────────────────────

        @Test
        @DisplayName("AC6: addBreakSegment creates BreakSegment with non-null breakStartAt")
        void addBreakSegment_createsBreakSegment_onActiveSession() {
                // Issue CAP-139 Story #68: start break
                WorkSession session = inProgressSession(Instant.now(TEST_CLOCK).minus(60, ChronoUnit.MINUTES));
                when(workSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
                when(breakSegmentRepository.findFirstByWorkSession_WorkSessionIdAndBreakEndAtIsNull(SESSION_ID))
                                .thenReturn(Optional.empty());
                when(breakSegmentRepository.save(any())).thenAnswer(inv -> {
                        BreakSegment bs = inv.getArgument(0);
                        bs.setBreakSegmentId(BREAK_ID);
                        return bs;
                });

                AddBreakSegmentRequest request = AddBreakSegmentRequest.builder()
                                .breakType(BreakType.MEAL)
                                .build();

                BreakSegmentResponse response = workSessionService.addBreakSegment(SESSION_ID, request);

                assertThat(response.getBreakStartAt()).isNotNull();
        }

        @Test
        @DisplayName("A-2: addBreakSegment rejects if an open break already exists")
        void addBreakSegment_rejectsWhenOpenBreakAlreadyExists() {
                // Issue CAP-139 Story #68: open-break guard
                WorkSession session = inProgressSession(Instant.now(TEST_CLOCK).minus(60, ChronoUnit.MINUTES));
                when(workSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
                when(breakSegmentRepository.findFirstByWorkSession_WorkSessionIdAndBreakEndAtIsNull(SESSION_ID))
                                .thenReturn(Optional.of(new BreakSegment()));

                AddBreakSegmentRequest request = AddBreakSegmentRequest.builder()
                                .breakType(BreakType.MEAL)
                                .build();

                assertThatThrownBy(() -> workSessionService.addBreakSegment(SESSION_ID, request))
                                .isInstanceOf(WorkSessionStateException.class);
        }

        @Test
        @DisplayName("A-2: addBreakSegment succeeds when no open break exists")
        void addBreakSegment_allowsBreakWhenNoOpenBreakExists() {
                // Issue CAP-139 Story #68: no open-break guard triggered
                WorkSession session = inProgressSession(Instant.now(TEST_CLOCK).minus(60, ChronoUnit.MINUTES));
                when(workSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
                when(breakSegmentRepository.findFirstByWorkSession_WorkSessionIdAndBreakEndAtIsNull(SESSION_ID))
                                .thenReturn(Optional.empty());
                when(breakSegmentRepository.save(any())).thenAnswer(inv -> {
                        BreakSegment bs = inv.getArgument(0);
                        bs.setBreakSegmentId(BREAK_ID);
                        return bs;
                });

                BreakSegmentResponse response = workSessionService.addBreakSegment(
                                SESSION_ID, AddBreakSegmentRequest.builder().breakType(BreakType.MEAL).build());

                assertThat(response).isNotNull();
                assertThat(response.getBreakStartAt()).isNotNull();
        }

        // ── AC7: Stop break segment ────────────────────────────────────────────────

        @Test
        @DisplayName("AC7: stopBreakSegment updates BreakSegment with non-null breakEndAt")
        void stopBreakSegment_updatesBreakSegment_withBreakEndAt() {
                // Issue CAP-139 Story #68: stop break
                WorkSession session = inProgressSession(Instant.now(TEST_CLOCK).minus(60, ChronoUnit.MINUTES));
                BreakSegment openBreak = BreakSegment.builder()
                                .breakSegmentId(BREAK_ID)
                                .workSession(session)
                                .breakStartAt(Instant.now(TEST_CLOCK).minus(10, ChronoUnit.MINUTES))
                                .build();
                when(workSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
                when(breakSegmentRepository.findById(BREAK_ID)).thenReturn(Optional.of(openBreak));
                when(breakSegmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

                BreakSegmentResponse response = workSessionService.stopBreakSegment(SESSION_ID, BREAK_ID);

                assertThat(response.getBreakEndAt()).isNotNull();
        }

        @Test
        @DisplayName("A-3: stopBreakSegment throws WorkSessionLockedException when session is locked")
        void stopBreakSegment_throwsWhenSessionLocked() {
                // Issue CAP-139 Story #68: lock guard on stopBreakSegment
                WorkSession lockedSession = mock(WorkSession.class);
                when(lockedSession.getWorkSessionId()).thenReturn(SESSION_ID);
                when(lockedSession.isLocked()).thenReturn(true);
                when(workSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(lockedSession));

                assertThatThrownBy(() -> workSessionService.stopBreakSegment(SESSION_ID, BREAK_ID))
                                .isInstanceOf(WorkSessionLockedException.class);
        }

        @Test
        @DisplayName("A-5: stopBreakSegment throws BreakSegmentNotFoundException when break does not belong to session")
        void stopBreakSegment_throwsWhenBreakBelongsToDifferentSession() {
                // Issue CAP-139 Story #68: cross-session validation
                WorkSession session = inProgressSession(Instant.now(TEST_CLOCK).minus(60, ChronoUnit.MINUTES));
                UUID otherSessionId = UUID.fromString("99999999-0000-0000-0000-000000000009");
                BreakSegment breakFromOtherSession = mock(BreakSegment.class);
                WorkSession otherSession = mock(WorkSession.class);
                when(otherSession.getWorkSessionId()).thenReturn(otherSessionId);
                when(breakFromOtherSession.getWorkSession()).thenReturn(otherSession);
                when(breakFromOtherSession.getBreakEndAt()).thenReturn(null);
                when(workSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
                when(breakSegmentRepository.findById(BREAK_ID)).thenReturn(Optional.of(breakFromOtherSession));

                assertThatThrownBy(() -> workSessionService.stopBreakSegment(SESSION_ID, BREAK_ID))
                                .isInstanceOf(BreakSegmentNotFoundException.class);
        }

        // ── AC8: Net duration deducts break time ───────────────────────────────────

        @Test
        @DisplayName("AC8: stopSession total duration nets out closed break segments (50 min total − 10 min break = 3000s)")
        void stopSession_netsDurationMinusBreaks() {
                // Issue CAP-139 Story #68: net duration calculation
                Instant now = Instant.now(TEST_CLOCK);
                Instant startAt = now.minus(60, ChronoUnit.MINUTES);
                WorkSession session = inProgressSession(startAt);

                Instant breakStart = now.minus(30, ChronoUnit.MINUTES);
                Instant breakEnd = now.minus(20, ChronoUnit.MINUTES); // 10-min break
                BreakSegment closedBreak = BreakSegment.builder()
                                .breakSegmentId(BREAK_ID)
                                .workSession(session)
                                .breakStartAt(breakStart)
                                .breakEndAt(breakEnd)
                                .build();

                when(workSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
                when(breakSegmentRepository.findByWorkSession_WorkSessionId(SESSION_ID))
                                .thenReturn(List.of(closedBreak));
                when(workSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

                WorkSessionResponse response = workSessionService.stopSession(SESSION_ID, new StopWorkSessionRequest());

                // Total = 60 min − 10 min break = 50 min = 3000 s (allow 5 s clock skew
                // tolerance)
                assertThat(response.getTotalDurationSeconds()).isBetween(2995, 3005);
        }
}
