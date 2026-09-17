package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.dto.PeopleAvailabilityResponse;
import com.positivity.people.internal.dto.WorkSessionClockStateResponse;
import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.people.internal.enums.ClockState;
import com.positivity.people.internal.repository.EmployeeLocationAssignmentRepository;
import com.positivity.people.internal.repository.ExtLocationReplicaRepository;
import com.positivity.people.internal.repository.ExtPersonReplicaRepository;
import com.positivity.people.internal.service.WorkSessionAccessPolicy.ClockStateViewer;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Clock state on the availability list (issue #2061): one batched lookup for exactly the rows the
 * caller may see, null clock fields on the rest, and no lookup at all when they may see none.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PeopleAvailabilityServiceImpl.getPeopleAvailability — clock state")
class PeopleAvailabilityClockStateTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-02-16T10:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.now(CLOCK);
    private static final UUID LOCATION = UUID.fromString("018f0000-0000-7000-8000-0000000000a1");
    private static final UUID ADA = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID GRACE = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID ADA_SESSION = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Mock
    private EmployeeLocationAssignmentRepository assignmentRepository;

    @Mock
    private ExtPersonReplicaRepository extPersonReplicaRepository;

    @Mock
    private UserPersonTranslationService userPersonTranslationService;

    @Mock
    private ExtLocationReplicaRepository extLocationReplicaRepository;

    @Mock
    private LocationReferenceService locationReferenceService;

    @Mock
    private WorkSessionService workSessionService;

    @Mock
    private WorkSessionAccessPolicy workSessionAccessPolicy;

    private PeopleAvailabilityServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PeopleAvailabilityServiceImpl(
                assignmentRepository,
                extPersonReplicaRepository,
                userPersonTranslationService,
                CLOCK,
                extLocationReplicaRepository,
                locationReferenceService,
                workSessionService,
                workSessionAccessPolicy);
    }

    private static EmployeeLocationAssignment assignment(UUID personId) {
        return EmployeeLocationAssignment.builder()
                .employee(Employee.builder().personId(personId).build())
                .locationId(LOCATION)
                .role("TECHNICIAN")
                .isPrimary(true)
                .effectiveFrom(TODAY.minusDays(30))
                .build();
    }

    private void rosterOfAdaAndGrace() {
        when(assignmentRepository.findActiveByDateAndOptionalLocation(TODAY, LOCATION))
                .thenReturn(List.of(assignment(ADA), assignment(GRACE)));
        when(extPersonReplicaRepository.findAllById(any())).thenReturn(List.of());
    }

    /** The policy decides per row; here the decision is pinned to a set of visible people. */
    private void visible(UUID... personIds) {
        ClockStateViewer viewer = new ClockStateViewer(null, LocationScope.unscoped());
        when(workSessionAccessPolicy.clockStateViewer()).thenReturn(viewer);
        Set<UUID> visibleIds = Set.of(personIds);
        when(workSessionAccessPolicy.mayViewClockState(any(), any(), any()))
                .thenAnswer(invocation -> visibleIds.contains(invocation.<UUID>getArgument(1)));
    }

    private static WorkSessionClockStateResponse clockedIn(UUID personId) {
        return WorkSessionClockStateResponse.builder()
                .personId(personId)
                .clockState(ClockState.CLOCKED_IN)
                .workSessionId(ADA_SESSION)
                .clockedInAt(Instant.parse("2026-02-16T08:00:00Z"))
                .build();
    }

    private static WorkSessionClockStateResponse clockedOut(UUID personId) {
        return WorkSessionClockStateResponse.builder()
                .personId(personId)
                .clockState(ClockState.CLOCKED_OUT)
                .build();
    }

    @Test
    @DisplayName("AC1/AC3/AC4: a supervisor's page resolves every row's state in one batched call")
    void supervisorSeesEveryRowFromOneBatchedCall() {
        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock.when(SecurityContextHelper::locationScope).thenReturn(LocationScope.unscoped());
            rosterOfAdaAndGrace();
            visible(ADA, GRACE);
            when(workSessionService.resolveClockStates(Set.of(ADA, GRACE)))
                    .thenReturn(Map.of(ADA, clockedIn(ADA), GRACE, clockedOut(GRACE)));

            List<PeopleAvailabilityResponse> rows = service.getPeopleAvailability(LOCATION, null);

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)).satisfies(ada -> {
                assertThat(ada.getPersonId()).isEqualTo(ADA);
                assertThat(ada.getClockState()).isEqualTo(ClockState.CLOCKED_IN);
                assertThat(ada.getWorkSessionId()).isEqualTo(ADA_SESSION);
                assertThat(ada.getClockedInAt()).isEqualTo(Instant.parse("2026-02-16T08:00:00Z"));
                assertThat(ada.getBreakStartedAt()).isNull();
            });
            assertThat(rows.get(1)).satisfies(grace -> {
                assertThat(grace.getClockState()).isEqualTo(ClockState.CLOCKED_OUT);
                assertThat(grace.getWorkSessionId()).isNull();
                assertThat(grace.getClockedInAt()).isNull();
                assertThat(grace.getBreakStartedAt()).isNull();
            });
            verify(workSessionService, Mockito.times(1)).resolveClockStates(any());
        }
    }

    @Test
    @DisplayName("OQ1: without people:timekeeping:view only the caller's own row carries clock state")
    void withoutTimekeepingViewOnlyTheOwnRowIsPopulated() {
        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock.when(SecurityContextHelper::locationScope).thenReturn(LocationScope.unscoped());
            rosterOfAdaAndGrace();
            visible(GRACE);
            when(workSessionService.resolveClockStates(Set.of(GRACE))).thenReturn(Map.of(GRACE, clockedIn(GRACE)));

            List<PeopleAvailabilityResponse> rows = service.getPeopleAvailability(LOCATION, null);

            assertThat(rows.get(0).getPersonId()).isEqualTo(ADA);
            assertThat(rows.get(0).getClockState()).isNull();
            assertThat(rows.get(0).getWorkSessionId()).isNull();
            assertThat(rows.get(1).getPersonId()).isEqualTo(GRACE);
            assertThat(rows.get(1).getClockState()).isEqualTo(ClockState.CLOCKED_IN);
            // Only the visible person was asked for: the invisible row cannot leak through the query.
            verify(workSessionService).resolveClockStates(Set.of(GRACE));
        }
    }

    @Test
    @DisplayName("AC5/AC9: a caller who may see no row gets the list as before, with no session query at all")
    void noVisibleRowMeansNoQuery() {
        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock.when(SecurityContextHelper::locationScope).thenReturn(LocationScope.unscoped());
            rosterOfAdaAndGrace();
            visible();

            List<PeopleAvailabilityResponse> rows = service.getPeopleAvailability(LOCATION, null);

            assertThat(rows).hasSize(2).allSatisfy(row -> {
                assertThat(row.getClockState()).isNull();
                assertThat(row.getWorkSessionId()).isNull();
                assertThat(row.getClockedInAt()).isNull();
                assertThat(row.getBreakStartedAt()).isNull();
            });
            verifyNoInteractions(workSessionService);
        }
    }
}
