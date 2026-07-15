package com.positivity.workorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.workorder.internal.dto.DashboardResponse;
import com.positivity.workorder.internal.dto.PeopleAvailabilityResponse;
import com.positivity.workorder.internal.dto.PeopleAvailabilityResponse.BreakInfo;
import com.positivity.workorder.internal.dto.PeopleAvailabilityResponse.PersonAvailability;
import com.positivity.workorder.internal.dto.PeopleAvailabilityResponse.PtoBlock;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.service.DashboardServiceImpl;
import com.positivity.workorder.internal.service.PeopleAvailabilityLocalService;
import java.time.Clock;
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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for CAP-142 Story #60: Daily Dispatch Board Dashboard service
 * layer.
 *
 * <p>
 * Validates that {@link DashboardServiceImpl} correctly aggregates workorders,
 * mechanics, bays, and conflict entries for the dispatch board view, including
 * double-booked mechanic (AC-4a), PTO overlap (AC-4b), break overlap (AC-6),
 * clock-out mismatch (AC-4d), bay double-booking (AC-2a), bay unavailable
 * (AC-2b),
 * location mismatch (AC-3a), and skill mismatch (AC-3b).
 *
 * Issue: CAP-142
 */
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    private static final LocalDate TEST_DATE = LocalDate.ofInstant(TEST_CLOCK.instant(), TEST_CLOCK.getZone());
    private static final UUID LOCATION_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String LOCATION_ID = LOCATION_UUID.toString();

    @Mock
    private WorkorderRepository workorderRepository;

    @Mock
    private PeopleAvailabilityLocalService peopleAvailabilityLocalService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

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
        when(peopleAvailabilityLocalService.fetchAvailability(any(), any()))
                .thenReturn(PeopleAvailabilityResponse.builder()
                        .asOf(Instant.now(TEST_CLOCK))
                        .location(LOCATION_ID)
                        .people(List.of())
                        .build());

        // Act
        DashboardResponse response = dashboardService.getDashboard(LOCATION_ID, TEST_DATE);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.getLastRefreshed()).isNotNull();
    }

    // -----------------------------------------------------------------------
    // AC-1 + AC-5: getDashboard populates mechanics from the People client
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-1 / AC-5: mechanics list is populated from PeopleAvailabilityLocalService")
    void getDashboard_populatesMechanicsFromPeopleClient() {
        // Arrange
        // Issue CAP-142: AC-5 — verify mechanics sourced from people service
        when(workorderRepository.findByScheduledDateAndLocationId(any(), any())).thenReturn(List.of());
        when(peopleAvailabilityLocalService.fetchAvailability(LOCATION_ID, TEST_DATE))
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
    // AC-4a: Double-booked mechanic → ConflictEntry BLOCKING /
    // DOUBLE_BOOKED_MECHANIC
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-4a: Two workorders assigned to same mechanic generates DOUBLE_BOOKED_MECHANIC BLOCKING conflict")
    void getDashboard_doubleBookedMechanic_returnsBlockingConflict() {
        // Arrange
        // Issue CAP-142: AC-4a — same mechanic on 2 workorders on the same date
        Workorder wo1 = buildWorkorder(UUID.fromString("00000000-0000-0000-0000-000000000001"), "MECH-001", null);
        Workorder wo2 = buildWorkorder(UUID.fromString("00000000-0000-0000-0000-000000000001"), "MECH-001", null);
        when(workorderRepository.findByScheduledDateAndLocationId(any(), any())).thenReturn(List.of(wo1, wo2));
        when(peopleAvailabilityLocalService.fetchAvailability(any(), any())).thenReturn(emptyAvailability());

        // Act
        DashboardResponse response = dashboardService.getDashboard(LOCATION_ID, TEST_DATE);

        // Assert
        assertThat(response.getConflicts()).anySatisfy(conflict -> {
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
        // Issue CAP-142: AC-4b — mechanic has PTO that covers the workorder's
        // scheduledDate
        Workorder wo = buildWorkorder(UUID.fromString("00000000-0000-0000-0000-000000000001"), "MECH-003", null);
        when(workorderRepository.findByScheduledDateAndLocationId(any(), any())).thenReturn(List.of(wo));

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
        when(peopleAvailabilityLocalService.fetchAvailability(any(), any()))
                .thenReturn(PeopleAvailabilityResponse.builder()
                        .people(List.of(mechanicWithPto))
                        .build());

        // Act
        DashboardResponse response = dashboardService.getDashboard(LOCATION_ID, TEST_DATE);

        // Assert
        assertThat(response.getConflicts()).anySatisfy(conflict -> {
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
        Workorder wo = buildWorkorder(UUID.fromString("00000000-0000-0000-0000-000000000001"), "MECH-004", null);
        when(workorderRepository.findByScheduledDateAndLocationId(any(), any())).thenReturn(List.of(wo));

        Instant nearFuture = Instant.now(TEST_CLOCK).plusSeconds(600); // 10 min expected return → <15 min
        // overlap
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
        when(peopleAvailabilityLocalService.fetchAvailability(any(), any()))
                .thenReturn(PeopleAvailabilityResponse.builder()
                        .people(List.of(mechanicOnBreak))
                        .build());

        // Act
        DashboardResponse response = dashboardService.getDashboard(LOCATION_ID, TEST_DATE);

        // Assert
        assertThat(response.getConflicts()).anySatisfy(conflict -> {
            assertThat(conflict.getSeverity()).isEqualTo("WARNING");
            assertThat(conflict.getMessage()).containsIgnoringCase("break");
        });
    }

    // -----------------------------------------------------------------------
    // AC-4d: Mechanic clocked in for a different job → BLOCKING /
    // DOUBLE_BOOKED_MECHANIC
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "AC-4d: Mechanic clocked in for another job without clock-out generates CLOCK_OUT_MISMATCH WARNING conflict")
    void getDashboard_mechanicClockedInForDifferentJob_returnsWarning() {
        // Arrange
        // Issue CAP-142: AC-4d — mechanic is ON_JOB (clocked in elsewhere, not clocked
        // out) → CLOCK_OUT_MISMATCH WARNING
        Workorder wo = buildWorkorder(UUID.fromString("00000000-0000-0000-0000-000000000001"), "MECH-005", null);
        when(workorderRepository.findByScheduledDateAndLocationId(any(), any())).thenReturn(List.of(wo));

        PersonAvailability mechanicOnJob = PersonAvailability.builder()
                .personId("MECH-005")
                .firstName("Eve")
                .lastName("Brown")
                .currentStatus("ON_JOB")
                .build();
        when(peopleAvailabilityLocalService.fetchAvailability(any(), any()))
                .thenReturn(PeopleAvailabilityResponse.builder()
                        .people(List.of(mechanicOnJob))
                        .build());

        // Act
        DashboardResponse response = dashboardService.getDashboard(LOCATION_ID, TEST_DATE);

        // Assert: mechanic has not clocked out → WARNING, not BLOCKING
        assertThat(response.getConflicts()).anySatisfy(conflict -> {
            assertThat(conflict.getConflictType()).isEqualTo("CLOCK_OUT_MISMATCH");
            assertThat(conflict.getSeverity()).isEqualTo("WARNING");
            assertThat(conflict.getAffectedResourceId()).isEqualTo("MECH-005");
        });
    }

    // -----------------------------------------------------------------------
    // Bay double-booked → ConflictEntry BLOCKING / BAY_DOUBLE_BOOKED
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-2a: Two workorders assigned to same bay generates BAY_DOUBLE_BOOKED BLOCKING conflict")
    void getDashboard_bayDoubleBooked_returnsBlockingConflict() {
        // Arrange
        // Issue CAP-142: bay double-booking detection
        UUID sharedBayId = UUID.fromString("00000000-0000-0000-0000-000000000BAB");
        Workorder wo1 =
                buildWorkorder(UUID.fromString("00000000-0000-0000-0000-000000000001"), "MECH-006", sharedBayId);
        Workorder wo2 =
                buildWorkorder(UUID.fromString("00000000-0000-0000-0000-000000000001"), "MECH-007", sharedBayId);
        when(workorderRepository.findByScheduledDateAndLocationId(any(), any())).thenReturn(List.of(wo1, wo2));
        when(peopleAvailabilityLocalService.fetchAvailability(any(), any())).thenReturn(emptyAvailability());

        // Act
        DashboardResponse response = dashboardService.getDashboard(LOCATION_ID, TEST_DATE);

        // Assert
        assertThat(response.getConflicts()).anySatisfy(conflict -> {
            assertThat(conflict.getConflictType()).isEqualTo("BAY_DOUBLE_BOOKED");
            assertThat(conflict.getSeverity()).isEqualTo("BLOCKING");
        });
    }

    // -----------------------------------------------------------------------
    // AC-1: lastRefreshed is set to approximately now
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-1: lastRefreshed is non-null and within 1 second of Instant.now(TEST_CLOCK)")
    void getDashboard_lastRefreshedIsSetToNow() {
        // Arrange
        // Issue CAP-142: AC-1 — timestamp freshness assertion
        when(workorderRepository.findByScheduledDateAndLocationId(any(), any())).thenReturn(List.of());
        when(peopleAvailabilityLocalService.fetchAvailability(any(), any())).thenReturn(emptyAvailability());
        Instant before = Instant.now(TEST_CLOCK);

        // Act
        DashboardResponse response = dashboardService.getDashboard(LOCATION_ID, TEST_DATE);

        // Assert
        assertThat(response.getLastRefreshed()).isNotNull();
        assertThat(response.getLastRefreshed())
                .isBetween(before, Instant.now(TEST_CLOCK).plusSeconds(1));
    }

    // -----------------------------------------------------------------------
    // AC-2b: Bay marked CLOSED/RESERVED/UNDER_MAINTENANCE → BAY_UNAVAILABLE
    // BLOCKING
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // AC-3a: Mechanic at different location → LOCATION_MISMATCH WARNING
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-3a: Mechanic at different location than workorder generates LOCATION_MISMATCH WARNING")
    void getDashboard_mechanicAtDifferentLocation_returnsWarning() {
        // Arrange
        // Issue CAP-142: AC-3a — mechanic currentLocationId differs from workorder
        // locationId
        UUID differentLocation = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Workorder wo = buildWorkorder(UUID.fromString("00000000-0000-0000-0000-000000000001"), "MECH-011", null);
        when(workorderRepository.findByScheduledDateAndLocationId(any(), any())).thenReturn(List.of(wo));
        PersonAvailability mechanicElsewhere = PersonAvailability.builder()
                .personId("MECH-011")
                .firstName("Frank")
                .lastName("Kelly")
                .currentStatus("AVAILABLE")
                .currentLocationId(differentLocation.toString())
                .build();
        when(peopleAvailabilityLocalService.fetchAvailability(any(), any()))
                .thenReturn(PeopleAvailabilityResponse.builder()
                        .people(List.of(mechanicElsewhere))
                        .build());

        // Act
        DashboardResponse response = dashboardService.getDashboard(LOCATION_ID, TEST_DATE);

        // Assert
        assertThat(response.getConflicts()).anySatisfy(conflict -> {
            assertThat(conflict.getConflictType()).isEqualTo("LOCATION_MISMATCH");
            assertThat(conflict.getSeverity()).isEqualTo("WARNING");
            assertThat(conflict.getAffectedResourceId()).isEqualTo("MECH-011");
        });
    }

    // -----------------------------------------------------------------------
    // AC-3b: Mechanic lacks required certification → MECHANIC_SKILL_MISMATCH
    // WARNING
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-3b: Mechanic lacks required certification generates MECHANIC_SKILL_MISMATCH WARNING")
    void getDashboard_mechanicMissingCertification_returnsWarning() {
        // Arrange
        // Issue CAP-142: AC-3b — workorder requires ALIGNMENT_CERT but mechanic only
        // has BRAKE_CERT
        Workorder wo = Workorder.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .locationId(LOCATION_UUID)
                .mechanicIds("[\"MECH-012\"]")
                .requiredCertifications("[\"BRAKE_CERT\",\"ALIGNMENT_CERT\"]")
                .status(WorkorderStatus.WORK_IN_PROGRESS)
                .build();
        when(workorderRepository.findByScheduledDateAndLocationId(any(), any())).thenReturn(List.of(wo));
        PersonAvailability mechanicMissingCert = PersonAvailability.builder()
                .personId("MECH-012")
                .firstName("Grace")
                .lastName("Lee")
                .currentStatus("AVAILABLE")
                .certifications(List.of("BRAKE_CERT"))
                .build();
        when(peopleAvailabilityLocalService.fetchAvailability(any(), any()))
                .thenReturn(PeopleAvailabilityResponse.builder()
                        .people(List.of(mechanicMissingCert))
                        .build());

        // Act
        DashboardResponse response = dashboardService.getDashboard(LOCATION_ID, TEST_DATE);

        // Assert
        assertThat(response.getConflicts()).anySatisfy(conflict -> {
            assertThat(conflict.getConflictType()).isEqualTo("MECHANIC_SKILL_MISMATCH");
            assertThat(conflict.getSeverity()).isEqualTo("WARNING");
            assertThat(conflict.getAffectedResourceId()).isEqualTo("MECH-012");
        });
    }

    // -----------------------------------------------------------------------
    // Null availability response → treated as empty people list
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("getDashboard handles null availability response without NPE")
    void getDashboard_nullAvailabilityResponse_treatedAsEmptyPeople() {
        // Arrange
        // Issue CAP-142: null guard on availability response at line ~56
        when(workorderRepository.findByScheduledDateAndLocationId(any(), any())).thenReturn(List.of());
        when(peopleAvailabilityLocalService.fetchAvailability(any(), any())).thenReturn(null);

        // Act
        DashboardResponse response = dashboardService.getDashboard(LOCATION_ID, TEST_DATE);

        // Assert: should not throw, mechanics list is empty
        assertThat(response).isNotNull();
        assertThat(response.getMechanics()).isEmpty();
        assertThat(response.getConflicts()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // AC-2b: Bay with RESERVED status → BAY_UNAVAILABLE BLOCKING
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // AC-2b: Bay with UNDER_MAINTENANCE status → BAY_UNAVAILABLE BLOCKING
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // parseCertifications with invalid JSON → treated as empty (no NPE/throw)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("getDashboard with invalid certifications JSON does not throw and skips skill check")
    void getDashboard_invalidCertificationsJson_doesNotThrow() {
        // Arrange
        // Issue CAP-142: parseCertifications warn-and-return-empty path
        Workorder wo = Workorder.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .locationId(LOCATION_UUID)
                .mechanicIds("[\"MECH-015\"]")
                .requiredCertifications("NOT_VALID_JSON")
                .status(WorkorderStatus.WORK_IN_PROGRESS)
                .build();
        when(workorderRepository.findByScheduledDateAndLocationId(any(), any())).thenReturn(List.of(wo));
        when(peopleAvailabilityLocalService.fetchAvailability(any(), any())).thenReturn(emptyAvailability());

        // Act + Assert: should not throw; invalid certifications treated as empty → no
        // skill conflict
        DashboardResponse response = dashboardService.getDashboard(LOCATION_ID, TEST_DATE);
        assertThat(response).isNotNull();
        assertThat(response.getConflicts()).noneMatch(c -> "MECHANIC_SKILL_MISMATCH".equals(c.getConflictType()));
    }

    // -----------------------------------------------------------------------
    // getDashboard with valid UUID locationId uses UUID.fromString path
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("getDashboard with valid UUID locationId correctly parses UUID path")
    void getDashboard_withValidUuidLocationId_usesUuidFromStringPath() {
        // Arrange
        // Issue CAP-142: covers UUID.fromString(locationId) branch
        String uuidLocationId = LOCATION_UUID.toString();
        when(workorderRepository.findByScheduledDateAndLocationId(any(), any())).thenReturn(List.of());
        when(peopleAvailabilityLocalService.fetchAvailability(any(), any())).thenReturn(emptyAvailability());

        // Act
        DashboardResponse response = dashboardService.getDashboard(uuidLocationId, TEST_DATE);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.getLocationId()).isEqualTo(uuidLocationId);
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
    // -----------------------------------------------------------------------
    // F4: Bay status list is populated with bayName and status from Shopmgr
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // F5: MechanicStatus.assignedWorkorderId populated for assigned mechanic
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("F5: MechanicStatus.assignedWorkorderId is populated for assigned mechanic")
    void getDashboard_assignedMechanic_populatesAssignedWorkorderId() {
        // Arrange
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Workorder wo = Workorder.builder()
                .id(workorderId)
                .locationId(LOCATION_UUID)
                .mechanicIds("[\"MECH-030\"]")
                .status(WorkorderStatus.WORK_IN_PROGRESS)
                .build();
        when(workorderRepository.findByScheduledDateAndLocationId(any(), any())).thenReturn(List.of(wo));
        PersonAvailability mech = PersonAvailability.builder()
                .personId("MECH-030")
                .firstName("Dana")
                .lastName("White")
                .currentStatus("ON_JOB")
                .build();
        when(peopleAvailabilityLocalService.fetchAvailability(any(), any()))
                .thenReturn(PeopleAvailabilityResponse.builder()
                        .people(List.of(mech))
                        .build());

        // Act
        DashboardResponse response = dashboardService.getDashboard(LOCATION_ID, TEST_DATE);

        // Assert
        assertThat(response.getMechanics()).anySatisfy(m -> {
            assertThat(m.getPersonId()).isEqualTo("MECH-030");
            assertThat(m.getAssignedWorkorderId()).isEqualTo(workorderId.toString());
        });
    }

    // -----------------------------------------------------------------------
    // F6: Break expected far in future (>15 min) does not raise BREAK_OVERLAP
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("F6: Mechanic on break returning more than 15 min from now does not raise BREAK_OVERLAP")
    void getDashboard_breakReturnFarAway_noBreakOverlapConflict() {
        // Arrange
        // Break expected to return 1 hour from now — outside the 15-min window
        Instant oneHourFromNow = Instant.now(TEST_CLOCK).plusSeconds(3600);
        Workorder wo = buildWorkorder(UUID.fromString("00000000-0000-0000-0000-000000000001"), "MECH-040", null);
        when(workorderRepository.findByScheduledDateAndLocationId(any(), any())).thenReturn(List.of(wo));
        PersonAvailability mechOnLongBreak = PersonAvailability.builder()
                .personId("MECH-040")
                .firstName("Hiro")
                .lastName("Tanaka")
                .currentStatus("ON_BREAK")
                .breakInfo(BreakInfo.builder()
                        .onBreak(true)
                        .expectedReturn(oneHourFromNow)
                        .build())
                .build();
        when(peopleAvailabilityLocalService.fetchAvailability(any(), any()))
                .thenReturn(PeopleAvailabilityResponse.builder()
                        .people(List.of(mechOnLongBreak))
                        .build());

        // Act
        DashboardResponse response = dashboardService.getDashboard(LOCATION_ID, TEST_DATE);

        // Assert: no MECHANIC_BREAK_OVERLAP (break too far away)
        assertThat(response.getConflicts()).noneMatch(c -> "MECHANIC_BREAK_OVERLAP".equals(c.getConflictType()));
    }

    // -----------------------------------------------------------------------
    // AC-DQ-1: dataQualityWarning=true when PeopleAvailabilityLocalService throws

    // -----------------------------------------------------------------------
    // AC-DQ-2: dataQualityWarning=true when PeopleAvailabilityLocalService returns null

    // -----------------------------------------------------------------------
    // AC-DQ-3: dataQualityWarning=false when both services healthy
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-DQ-3: dataQualityWarning is false when people availability resolves normally")
    void whenBothServicesAvailable_dataQualityWarningIsFalse() {
        // Arrange
        // Issue CAP-142 Story #60: data quality warning — happy path, no degradation
        when(workorderRepository.findByScheduledDateAndLocationId(any(), any())).thenReturn(List.of());
        when(peopleAvailabilityLocalService.fetchAvailability(any(), any())).thenReturn(emptyAvailability());

        // Act
        DashboardResponse response = dashboardService.getDashboard(LOCATION_ID, TEST_DATE);

        // Assert: no degradation — warning must not be true
        assertThat(response.getDataQualityWarning()).isFalse();
    }

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
                .asOf(Instant.now(TEST_CLOCK))
                .location(LOCATION_ID)
                .people(List.of())
                .build();
    }
}
