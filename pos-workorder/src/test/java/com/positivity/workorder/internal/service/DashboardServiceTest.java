package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.client.PeopleAvailabilityClient;
import com.positivity.workorder.internal.client.ShopmgrOperationalContextClient;
import com.positivity.workorder.internal.dto.DashboardResponse;
import com.positivity.workorder.internal.dto.PeopleAvailabilityResponse;
import com.positivity.workorder.internal.dto.PeopleAvailabilityResponse.BreakInfo;
import com.positivity.workorder.internal.dto.PeopleAvailabilityResponse.PersonAvailability;
import com.positivity.workorder.internal.dto.PeopleAvailabilityResponse.PtoBlock;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.WorkorderRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for CAP-142 Story #60: Daily Dispatch Board Dashboard service layer.
 *
 * <p>Validates that {@link DashboardServiceImpl} correctly aggregates workorders,
 * mechanics, bays, and conflict entries for the dispatch board view, including
 * double-booked mechanic (AC-4a), PTO overlap (AC-4b), break overlap (AC-6),
 * clock-out mismatch (AC-4d), and bay double-booking detection.
 *
 * <p>All tests are RED: the stub implementation throws
 * {@link UnsupportedOperationException} ("not yet implemented"), so each test
 * will ERROR until the production code is implemented.
 *
 * Issue: CAP-142
 */
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    private static final LocalDate TEST_DATE = LocalDate.of(2026, 3, 1);
    private static final String LOCATION_ID = "LOC-123";
    private static final UUID LOCATION_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private WorkorderRepository workorderRepository;

    @Mock
    private PeopleAvailabilityClient peopleAvailabilityClient;

    @Mock
    @SuppressWarnings("unused")
    private ShopmgrOperationalContextClient shopmgrOperationalContextClient;

    @InjectMocks
    private DashboardServiceImpl dashboardService;

    // -----------------------------------------------------------------------
    // AC-1: getDashboard returns non-null DashboardResponse with lastRefreshed
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-1: getDashboard returns non-null response with non-null lastRefreshed")
    void getDashboard_validInput_returnsNonNullResponse() {
        // Arrange
        // Issue CAP-142: AC-1 — basic happy path, empty workorder list
        when(workorderRepository.findByScheduledDateAndLocationId(any(LocalDate.class), any(UUID.class)))
                .thenReturn(List.of());
        when(peopleAvailabilityClient.fetchAvailability(any(), any()))
                .thenReturn(PeopleAvailabilityResponse.builder()
                        .asOf(Instant.now())
                        .location(LOCATION_ID)
                        .people(List.of())
                        .build());

        // Act — expected to error with UnsupportedOperationException until GREEN
        DashboardResponse response = dashboardService.getDashboard(LOCATION_ID, TEST_DATE);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.getLastRefreshed()).isNotNull();
    }

    // -----------------------------------------------------------------------
    // AC-1 + AC-5: getDashboard populates mechanics from the People client
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-1 / AC-5: mechanics list is populated from PeopleAvailabilityClient")
    void getDashboard_populatesMechanicsFromPeopleClient() {
        // Arrange
        // Issue CAP-142: AC-5 — verify mechanics sourced from people service
        when(workorderRepository.findByScheduledDateAndLocationId(any(), any()))
                .thenReturn(List.of());
        when(peopleAvailabilityClient.fetchAvailability(LOCATION_ID, TEST_DATE))
                .thenReturn(PeopleAvailabilityResponse.builder()
                        .people(List.of(
                                personAvailability("MECH-001", "Alice", "AVAILABLE"),
                                personAvailability("MECH-002", "Bob", "AVAILABLE")))
                        .build());

        // Act
        DashboardResponse response = dashboardService.getDashboard(LOCATION_ID, TEST_DATE);

        // Assert
        assertThat(response.getMechanics()).hasSize(2);
    }

    // -----------------------------------------------------------------------
    // AC-4a: Double-booked mechanic → ConflictEntry BLOCKING / DOUBLE_BOOKED_MECHANIC
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-4a: Two workorders assigned to same mechanic generates DOUBLE_BOOKED_MECHANIC BLOCKING conflict")
    void getDashboard_doubleBookedMechanic_returnsBlockingConflict() {
        // Arrange
        // Issue CAP-142: AC-4a — same mechanic on 2 workorders on the same date
        Workorder wo1 = buildWorkorder(UUID.randomUUID(), "MECH-001", null);
        Workorder wo2 = buildWorkorder(UUID.randomUUID(), "MECH-001", null);
        when(workorderRepository.findByScheduledDateAndLocationId(any(), any()))
                .thenReturn(List.of(wo1, wo2));
        when(peopleAvailabilityClient.fetchAvailability(any(), any()))
                .thenReturn(emptyAvailability());

        // Act
        DashboardResponse response = dashboardService.getDashboard(LOCATION_ID, TEST_DATE);

        // Assert
        assertThat(response.getConflicts())
                .anySatisfy(conflict -> {
                    assertThat(conflict.getConflictType()).isEqualTo("DOUBLE_BOOKED_MECHANIC");
                    assertThat(conflict.getSeverity()).isEqualTo("BLOCKING");
                });
    }

    // -----------------------------------------------------------------------
    // AC-4b: PTO overlap → ConflictEntry BLOCKING / MECHANIC_PTO_OVERLAP
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-4b: Workorder on date where mechanic has PTO generates MECHANIC_PTO_OVERLAP BLOCKING conflict")
    void getDashboard_mechanicPtoOverlap_returnsBlockingConflict() {
        // Arrange
        // Issue CAP-142: AC-4b — mechanic has PTO that covers the workorder's scheduledDate
        Workorder wo = buildWorkorder(UUID.randomUUID(), "MECH-003", null);
        when(workorderRepository.findByScheduledDateAndLocationId(any(), any()))
                .thenReturn(List.of(wo));

        Instant dayStart = TEST_DATE.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant dayEnd = TEST_DATE.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        PersonAvailability mechanicWithPto = PersonAvailability.builder()
                .personId("MECH-003")
                .firstName("Carol")
                .lastName("Smith")
                .currentStatus("PTO")
                .pto(List.of(PtoBlock.builder()
                        .ptoId("PTO-01")
                        .start(dayStart)
                        .end(dayEnd)
                        .ptoType("ANNUAL")
                        .build()))
                .build();
        when(peopleAvailabilityClient.fetchAvailability(any(), any()))
                .thenReturn(PeopleAvailabilityResponse.builder()
                        .people(List.of(mechanicWithPto))
                        .build());

        // Act
        DashboardResponse response = dashboardService.getDashboard(LOCATION_ID, TEST_DATE);

        // Assert
        assertThat(response.getConflicts())
                .anySatisfy(conflict -> {
                    assertThat(conflict.getConflictType()).isEqualTo("MECHANIC_PTO_OVERLAP");
                    assertThat(conflict.getSeverity()).isEqualTo("BLOCKING");
                });
    }

    // -----------------------------------------------------------------------
    // AC-6: Break overlap <15 min → ConflictEntry WARNING, message contains "break"
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-6: Job overlapping mechanic break by <15 min generates WARNING with 'break' in message")
    void getDashboard_breakOverlapUnder15Min_returnsWarning() {
        // Arrange
        // Issue CAP-142: AC-6 — mechanic is on break; overlap < 15 min → WARNING
        Workorder wo = buildWorkorder(UUID.randomUUID(), "MECH-004", null);
        when(workorderRepository.findByScheduledDateAndLocationId(any(), any()))
                .thenReturn(List.of(wo));

        Instant nearFuture = Instant.now().plusSeconds(600); // 10 min expected return → <15 min overlap
        PersonAvailability mechanicOnBreak = PersonAvailability.builder()
                .personId("MECH-004")
                .firstName("Dave")
                .lastName("Jones")
                .currentStatus("ON_BREAK")
                .breakInfo(BreakInfo.builder()
                        .onBreak(true)
                        .expectedReturn(nearFuture)
                        .build())
                .build();
        when(peopleAvailabilityClient.fetchAvailability(any(), any()))
                .thenReturn(PeopleAvailabilityResponse.builder()
                        .people(List.of(mechanicOnBreak))
                        .build());

        // Act
        DashboardResponse response = dashboardService.getDashboard(LOCATION_ID, TEST_DATE);

        // Assert
        assertThat(response.getConflicts())
                .anySatisfy(conflict -> {
                    assertThat(conflict.getSeverity()).isEqualTo("WARNING");
                    assertThat(conflict.getMessage()).containsIgnoringCase("break");
                });
    }

    // -----------------------------------------------------------------------
    // AC-4d: Mechanic clocked in for a different job → BLOCKING / DOUBLE_BOOKED_MECHANIC
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-4d: Mechanic physically on a different job at schedule time generates DOUBLE_BOOKED_MECHANIC BLOCKING conflict")
    void getDashboard_mechanicClockedInForDifferentJob_returnsWarning() {
        // Arrange
        // Issue CAP-142: AC-4d — mechanic is ON_JOB (a different workorder), physically unavailable → BLOCKING
        UUID differentWorkorderId = UUID.randomUUID();
        Workorder wo = buildWorkorder(UUID.randomUUID(), "MECH-005", null);
        when(workorderRepository.findByScheduledDateAndLocationId(any(), any()))
                .thenReturn(List.of(wo));

        PersonAvailability mechanicOnJob = PersonAvailability.builder()
                .personId("MECH-005")
                .firstName("Eve")
                .lastName("Brown")
                .currentStatus("ON_JOB")
                .build();
        when(peopleAvailabilityClient.fetchAvailability(any(), any()))
                .thenReturn(PeopleAvailabilityResponse.builder()
                        .people(List.of(mechanicOnJob))
                        .build());

        // Act
        DashboardResponse response = dashboardService.getDashboard(LOCATION_ID, TEST_DATE);

        // Assert: mechanic is physically unavailable → BLOCKING not WARNING
        assertThat(response.getConflicts())
                .anySatisfy(conflict -> {
                    assertThat(conflict.getConflictType()).isEqualTo("DOUBLE_BOOKED_MECHANIC");
                    assertThat(conflict.getSeverity()).isEqualTo("BLOCKING");
                    assertThat(conflict.getAffectedResourceId()).isEqualTo("MECH-005");
                });
    }

    // -----------------------------------------------------------------------
    // Bay double-booked → ConflictEntry BLOCKING / BAY_DOUBLE_BOOKED
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-4: Two workorders assigned to same bay generates BAY_DOUBLE_BOOKED BLOCKING conflict")
    void getDashboard_bayDoubleBooked_returnsBlockingConflict() {
        // Arrange
        // Issue CAP-142: bay double-booking detection
        UUID sharedBayId = UUID.fromString("00000000-0000-0000-0000-000000000BAB");
        Workorder wo1 = buildWorkorder(UUID.randomUUID(), "MECH-006", sharedBayId);
        Workorder wo2 = buildWorkorder(UUID.randomUUID(), "MECH-007", sharedBayId);
        when(workorderRepository.findByScheduledDateAndLocationId(any(), any()))
                .thenReturn(List.of(wo1, wo2));
        when(peopleAvailabilityClient.fetchAvailability(any(), any()))
                .thenReturn(emptyAvailability());

        // Act
        DashboardResponse response = dashboardService.getDashboard(LOCATION_ID, TEST_DATE);

        // Assert
        assertThat(response.getConflicts())
                .anySatisfy(conflict -> {
                    assertThat(conflict.getConflictType()).isEqualTo("BAY_DOUBLE_BOOKED");
                    assertThat(conflict.getSeverity()).isEqualTo("BLOCKING");
                });
    }

    // -----------------------------------------------------------------------
    // AC-1: lastRefreshed is set to approximately now
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-1: lastRefreshed is non-null and within 1 second of Instant.now()")
    void getDashboard_lastRefreshedIsSetToNow() {
        // Arrange
        // Issue CAP-142: AC-1 — timestamp freshness assertion
        when(workorderRepository.findByScheduledDateAndLocationId(any(), any()))
                .thenReturn(List.of());
        when(peopleAvailabilityClient.fetchAvailability(any(), any()))
                .thenReturn(emptyAvailability());
        Instant before = Instant.now();

        // Act
        DashboardResponse response = dashboardService.getDashboard(LOCATION_ID, TEST_DATE);

        // Assert
        assertThat(response.getLastRefreshed()).isNotNull();
        assertThat(response.getLastRefreshed()).isBetween(before, Instant.now().plusSeconds(1));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a minimal {@link Workorder} test fixture with a single mechanic ID
     * and optional resource (bay) ID.
     *
     * @param id         workorder UUID
     * @param mechanicId mechanic identifier string
     * @param resourceId bay/resource UUID, or {@code null} if not bay-assigned
     * @return constructed Workorder entity
     */
    private Workorder buildWorkorder(UUID id, String mechanicId, UUID resourceId) {
        return Workorder.builder()
                .id(id)
                .locationId(LOCATION_UUID)
                .mechanicIds("[\"" + mechanicId + "\"]")
                .resourceId(resourceId)
                .status(WorkorderStatus.WORK_IN_PROGRESS)
                .build();
    }

    private PersonAvailability personAvailability(String personId, String firstName, String status) {
        return PersonAvailability.builder()
                .personId(personId)
                .firstName(firstName)
                .lastName("")
                .currentStatus(status)
                .build();
    }

    private PeopleAvailabilityResponse emptyAvailability() {
        return PeopleAvailabilityResponse.builder()
                .asOf(Instant.now())
                .location(LOCATION_ID)
                .people(List.of())
                .build();
    }
}
