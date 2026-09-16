package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.shopmanager.internal.dto.OpeningSearchQuery;
import com.positivity.shopmanager.internal.dto.OpeningSearchResponse;
import com.positivity.shopmanager.internal.dto.OpeningSearchResponse.Opening;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.ExtBayReplica;
import com.positivity.shopmanager.internal.entity.ExtCatalogServiceReplica;
import com.positivity.shopmanager.internal.entity.ExtCatalogServiceSkillReplica;
import com.positivity.shopmanager.internal.entity.ExtLocationReplica;
import com.positivity.shopmanager.internal.entity.ExtPersonCredentialReplica;
import com.positivity.shopmanager.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.shopmanager.internal.entity.ExtVehicleReplica;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.enums.OpeningSearchEnums.AbsenceScope;
import com.positivity.shopmanager.internal.enums.OpeningSearchEnums.NoOpeningReason;
import com.positivity.shopmanager.internal.enums.OpeningSearchEnums.OpeningConstraint;
import com.positivity.shopmanager.internal.enums.OpeningSearchEnums.SkillFulfillment;
import com.positivity.shopmanager.internal.enums.OpeningSearchEnums.StaffingAdvisoryCode;
import com.positivity.shopmanager.internal.exception.LocationNotFoundException;
import com.positivity.shopmanager.internal.exception.OpeningSearchPolicyException;
import com.positivity.shopmanager.internal.exception.ResourceNotFoundException;
import com.positivity.shopmanager.internal.exception.ShopManagerValidationException;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.ExtBayReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtCatalogServiceReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtCatalogServiceSkillReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtLocationReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtPersonCredentialReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtStaffingAssignmentReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtVehicleReplicaRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * #2022 AC13 against a Charlotte shop (America/New_York, EDT in June): open Mon–Fri 08:00–17:00
 * local (12:00–21:00Z), 15-minute check-in and cleanup buffers, two general bays and one
 * alignment rack, the Friday 2026-06-19 a holiday.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OpeningSearchServiceImpl (#2022)")
class OpeningSearchServiceImplTest {

    private static final UUID LOCATION = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID BAY_1 = UUID.fromString("01960005-0000-7000-8000-0000000000b1");
    private static final UUID BAY_2 = UUID.fromString("01960005-0000-7000-8000-0000000000b2");
    private static final UUID RACK = UUID.fromString("01960005-0000-7000-8000-0000000000b3");
    private static final UUID BRAKE_JOB = UUID.fromString("0196cf6f-c8dd-7ee0-93e7-f48a5698a535");
    private static final UUID ALIGNMENT = UUID.fromString("0196cf6f-c8dd-7ee0-93e7-f48a5698a536");
    private static final UUID TECH_A = UUID.fromString("01960011-0000-7000-8000-0000000000e1");
    private static final UUID TECH_B = UUID.fromString("01960011-0000-7000-8000-0000000000e2");
    private static final UUID VEHICLE = UUID.fromString("0196cf6f-c8dd-7ee0-93e7-f48a5698a600");

    /** Tuesday 2026-06-16, 09:00 local = 13:00Z. */
    private static final Instant TUE_0900 = Instant.parse("2026-06-16T13:00:00Z");

    private static final String HOURS = """
            [{"dayOfWeek":"MONDAY","openTime":"08:00","closeTime":"17:00"},
             {"dayOfWeek":"TUESDAY","openTime":"08:00","closeTime":"17:00"},
             {"dayOfWeek":"WEDNESDAY","openTime":"08:00","closeTime":"17:00"},
             {"dayOfWeek":"THURSDAY","openTime":"08:00","closeTime":"17:00"},
             {"dayOfWeek":"FRIDAY","openTime":"08:00","closeTime":"17:00"}]""";
    private static final String CLOSURES = "[{\"date\":\"2026-06-19\",\"reason\":\"Juneteenth\"}]";

    @Mock
    private ExtLocationReplicaRepository locationRepository;

    @Mock
    private ExtBayReplicaRepository bayRepository;

    @Mock
    private ExtCatalogServiceReplicaRepository catalogServiceRepository;

    @Mock
    private ExtCatalogServiceSkillReplicaRepository catalogServiceSkillRepository;

    @Mock
    private ExtStaffingAssignmentReplicaRepository staffingRepository;

    @Mock
    private AppointmentRepository appointmentRepository;

    @Mock
    private ExtPersonCredentialReplicaRepository credentialRepository;

    @Mock
    private ExtVehicleReplicaRepository vehicleRepository;

    private OpeningSearchServiceImpl service;

    private final List<Appointment> held = new ArrayList<>();
    private final List<ExtStaffingAssignmentReplica> staffing = new ArrayList<>();
    private final List<ExtPersonCredentialReplica> credentials = new ArrayList<>();
    private final List<ExtCatalogServiceSkillReplica> requirements = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new OpeningSearchServiceImpl(
                Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC),
                locationRepository,
                bayRepository,
                catalogServiceRepository,
                staffingRepository,
                appointmentRepository,
                new LocationHoursParser(new ObjectMapper()),
                new SkillRequirementResolver(
                        catalogServiceRepository,
                        catalogServiceSkillRepository,
                        credentialRepository,
                        vehicleRepository));
        lenient()
                .when(locationRepository.findById(LOCATION))
                .thenReturn(Optional.of(location("America/New_York", HOURS, CLOSURES, 15, 15)));
        lenient()
                .when(bayRepository.findActiveByLocationOrdered(LOCATION))
                .thenReturn(List.of(
                        bay(BAY_1, "Bay 1", List.of(), null),
                        bay(BAY_2, "Bay 2", List.of(), 3),
                        bay(RACK, "Rack", List.of("WHEEL-ALIGNMENT-4-WHEEL"), null)));
        lenient()
                .when(catalogServiceRepository.findAllByServiceIdIn(any()))
                .thenAnswer(inv -> ((java.util.Collection<UUID>) inv.getArgument(0))
                        .stream()
                                .map(id -> BRAKE_JOB.equals(id)
                                        ? catalogService(BRAKE_JOB, "BRAKE-PAD-REPLACE-FRONT", true)
                                        : ALIGNMENT.equals(id)
                                                ? catalogService(ALIGNMENT, "WHEEL-ALIGNMENT-4-WHEEL", true)
                                                : null)
                                .filter(java.util.Objects::nonNull)
                                .toList());
        lenient()
                .when(catalogServiceSkillRepository.findAllByServiceIdIn(any()))
                .thenAnswer(inv -> {
                    java.util.Collection<UUID> ids = inv.getArgument(0);
                    return requirements.stream()
                            .filter(r -> ids.contains(r.getServiceId()))
                            .toList();
                });
        lenient()
                .when(staffingRepository.findByLocationIdAndStatus(LOCATION, "ACTIVE"))
                .thenReturn(staffing);
        lenient()
                .when(credentialRepository.findByPersonIdInOrderByIssuedOnDesc(any()))
                .thenReturn(credentials);
        lenient()
                .when(appointmentRepository.findHeldOverlappingAtLocation(eq(LOCATION), any(), any(), any()))
                .thenReturn(held);
        lenient()
                .when(vehicleRepository.findById(VEHICLE))
                .thenReturn(Optional.of(ExtVehicleReplica.builder()
                        .vehicleId(VEHICLE)
                        .gvwrClass(2)
                        .build()));

        // Default roster: two technicians all year, A holding T-series brakes, nobody holding alignment.
        staffing.add(technician(TECH_A, LocalDate.of(2026, 1, 1), null));
        staffing.add(technician(TECH_B, LocalDate.of(2026, 1, 1), null));
        credentials.add(credential(TECH_A, "BRAKES-LIGHT", "A5-BRAKES", LocalDate.of(2027, 1, 1), "ACTIVE"));
        requirements.add(requirement(BRAKE_JOB, "BRAKES-LIGHT", 1, 3));
        requirements.add(requirement(ALIGNMENT, "A4-SUSPENSION", null, null));
    }

    private OpeningSearchQuery query(UUID service, int minutes, Instant earliest) {
        return new OpeningSearchQuery(LOCATION, List.of(service), minutes, earliest, VEHICLE, null, 30, 10);
    }

    // ── AC1–AC4: one call, bay ∩ duration ∩ continuity, real minutes, bookable ─────────────────

    @Test
    @DisplayName(
            "AC1/AC4: an empty day yields the first buffered start in each eligible bay, earliest first, naming bay and technician")
    void emptyDayYieldsBufferedStartPerBay() {
        OpeningSearchResponse response = service.search(query(BRAKE_JOB, 90, TUE_0900));

        assertThat(response.getNoOpeningReason()).isNull();
        assertThat(response.getStaffingAdvisory()).isNull();
        assertThat(response.getTimezone()).isEqualTo("America/New_York");
        // D14: general work may go to every bay but a wash bay — the rack too, ranked after the general bays.
        assertThat(response.getBayEligibility().getEligibleBays()).isEqualTo(3);
        assertThat(response.getBayEligibility().getExcludedByCapability()).isEqualTo(0);
        Opening first = response.getOpenings().getFirst();
        // Day opens 08:00; the 15-minute check-in buffer is honoured against earliestStart 09:00 → 09:00 exactly.
        assertThat(first.getStartAt()).isEqualTo(TUE_0900);
        assertThat(first.getEndAt()).isEqualTo(TUE_0900.plusSeconds(90 * 60));
        assertThat(first.getBayId()).isEqualTo(BAY_1);
        assertThat(first.getBayName()).isEqualTo("Bay 1");
        assertThat(first.getTechnicianId()).isEqualTo(TECH_A);
        assertThat(first.getTechnicianRosterGrain()).isEqualTo("DAY");
        assertThat(first.getSkillFulfillment()).isEqualTo(SkillFulfillment.CERTIFIED);
        assertThat(first.getUnmetSkillCodes()).isEmpty();
        assertThat(first.getConstraintsEvaluated())
                .containsExactly(
                        OpeningConstraint.HOURS,
                        OpeningConstraint.BAY,
                        OpeningConstraint.DURATION,
                        OpeningConstraint.BUFFER,
                        OpeningConstraint.ROSTER,
                        OpeningConstraint.SKILL);
        assertThat(response.getOpenings()).extracting(Opening::getStartAt).isSorted();
        assertThat(response.getOpenings()).hasSize(10); // limit
    }

    @Test
    @DisplayName("AC3: a genuine 13:00–14:30 gap is offered for a 90-minute job; whole-hour rounding would lose it")
    void exactFitGapIsOffered() {
        // Bay 1 busy 08:00–12:45 and 14:45–17:00 local: free 12:45–14:45; buffers leave exactly 13:00–14:30.
        held.add(bay(BAY_1, "2026-06-16T12:00:00Z", "2026-06-16T16:45:00Z"));
        held.add(bay(BAY_1, "2026-06-16T18:45:00Z", "2026-06-16T21:00:00Z"));
        // Bay 2 fully booked Tuesday so it does not compete.
        held.add(bay(BAY_2, "2026-06-16T12:00:00Z", "2026-06-16T21:00:00Z"));
        held.add(bay(RACK, "2026-06-16T12:00:00Z", "2026-06-16T21:00:00Z"));

        OpeningSearchResponse response = service.search(
                new OpeningSearchQuery(LOCATION, List.of(BRAKE_JOB), 90, TUE_0900, VEHICLE, null, 1, 10));

        assertThat(response.getOpenings()).singleElement().satisfies(opening -> {
            assertThat(opening.getStartAt()).isEqualTo(Instant.parse("2026-06-16T17:00:00Z"));
            assertThat(opening.getEndAt()).isEqualTo(Instant.parse("2026-06-16T18:30:00Z"));
            assertThat(opening.getBayId()).isEqualTo(BAY_1);
        });
    }

    @Test
    @DisplayName("AC7: a window that fits only by ignoring a buffer is not an opening")
    void oneMinuteTooShortForTheBuffersIsNotAnOpening() {
        // Free 12:45–14:44 local: 119 minutes; 90 + 15 + 15 = 120 needed.
        held.add(bay(BAY_1, "2026-06-16T12:00:00Z", "2026-06-16T16:45:00Z"));
        held.add(bay(BAY_1, "2026-06-16T18:44:00Z", "2026-06-16T21:00:00Z"));
        held.add(bay(BAY_2, "2026-06-16T12:00:00Z", "2026-06-16T21:00:00Z"));
        held.add(bay(RACK, "2026-06-16T12:00:00Z", "2026-06-16T21:00:00Z"));

        OpeningSearchResponse response = service.search(
                new OpeningSearchQuery(LOCATION, List.of(BRAKE_JOB), 90, TUE_0900, VEHICLE, null, 1, 10));

        assertThat(response.getOpenings()).isEmpty();
        assertThat(response.getNoOpeningReason()).isEqualTo(NoOpeningReason.ALL_ELIGIBLE_BAYS_BOOKED);
    }

    @Test
    @DisplayName("AC2: a window broken by a short job in the middle of one bay is not an opening in that bay")
    void windowBrokenByShortJobIsNotContinuous() {
        // Bay 1: 09:00–10:00 free, 10:00–10:15 booked, 10:15–17:00 free. A 90-minute job cannot use 09:00.
        held.add(bay(BAY_1, "2026-06-16T14:00:00Z", "2026-06-16T14:15:00Z"));
        held.add(bay(BAY_2, "2026-06-16T12:00:00Z", "2026-06-16T21:00:00Z"));
        held.add(bay(RACK, "2026-06-16T12:00:00Z", "2026-06-16T21:00:00Z"));

        OpeningSearchResponse response = service.search(
                new OpeningSearchQuery(LOCATION, List.of(BRAKE_JOB), 90, TUE_0900, VEHICLE, null, 1, 10));

        // The 60-minute gap before the short job cannot host 90 + buffers; the first fit is after it (+ check-in).
        assertThat(response.getOpenings()).singleElement().satisfies(opening -> {
            assertThat(opening.getStartAt()).isEqualTo(Instant.parse("2026-06-16T14:30:00Z"));
            assertThat(opening.getBayId()).isEqualTo(BAY_1);
        });
    }

    @Test
    @DisplayName("AC2: free in bay A then bay B is not an opening — the whole duration is in one bay")
    void splitAcrossBaysIsNotAnOpening() {
        // Bay 1 free 09:00–10:00 only; Bay 2 free 10:00–11:00 only (both otherwise booked).
        held.add(bay(BAY_1, "2026-06-16T14:00:00Z", "2026-06-16T21:00:00Z"));
        held.add(bay(BAY_1, "2026-06-16T12:00:00Z", "2026-06-16T13:00:00Z"));
        held.add(bay(BAY_2, "2026-06-16T12:00:00Z", "2026-06-16T14:00:00Z"));
        held.add(bay(BAY_2, "2026-06-16T15:00:00Z", "2026-06-16T21:00:00Z"));
        held.add(bay(RACK, "2026-06-16T12:00:00Z", "2026-06-16T21:00:00Z"));

        OpeningSearchResponse response = service.search(
                new OpeningSearchQuery(LOCATION, List.of(BRAKE_JOB), 90, TUE_0900, VEHICLE, null, 1, 10));

        assertThat(response.getOpenings()).isEmpty();
        assertThat(response.getNoOpeningReason()).isEqualTo(NoOpeningReason.ALL_ELIGIBLE_BAYS_BOOKED);
    }

    // ── AC5: crossing days, skipping closures ───────────────────────────────────────────────────

    @Test
    @DisplayName("AC5: nothing today rolls into tomorrow's first opening, not an empty result")
    void rollsIntoNextOpenDay() {
        held.add(bay(BAY_1, "2026-06-16T12:00:00Z", "2026-06-16T21:00:00Z"));
        held.add(bay(BAY_2, "2026-06-16T12:00:00Z", "2026-06-16T21:00:00Z"));
        held.add(bay(RACK, "2026-06-16T12:00:00Z", "2026-06-16T21:00:00Z"));

        OpeningSearchResponse response =
                service.search(new OpeningSearchQuery(LOCATION, List.of(BRAKE_JOB), 90, TUE_0900, VEHICLE, null, 2, 1));

        // Wednesday 08:00 local + 15-minute check-in = 08:15 local = 12:15Z.
        assertThat(response.getOpenings()).singleElement().satisfies(opening -> {
            assertThat(opening.getStartAt()).isEqualTo(Instant.parse("2026-06-17T12:15:00Z"));
            assertThat(opening.getLocalDate()).isEqualTo("2026-06-17");
        });
    }

    @Test
    @DisplayName("AC5: a holiday and the weekend are skipped, never reported as full")
    void skipsHolidayAndClosedDays() {
        // Thursday 2026-06-18 09:00 local; Friday is Juneteenth, Sat/Sun have no hours → Monday 22nd.
        Instant thu = Instant.parse("2026-06-18T13:00:00Z");
        held.add(bay(BAY_1, "2026-06-18T12:00:00Z", "2026-06-18T21:00:00Z"));
        held.add(bay(BAY_2, "2026-06-18T12:00:00Z", "2026-06-18T21:00:00Z"));
        held.add(bay(RACK, "2026-06-18T12:00:00Z", "2026-06-18T21:00:00Z"));

        OpeningSearchResponse response =
                service.search(new OpeningSearchQuery(LOCATION, List.of(BRAKE_JOB), 90, thu, VEHICLE, null, 7, 1));

        assertThat(response.getOpenings())
                .singleElement()
                .satisfies(opening -> assertThat(opening.getLocalDate()).isEqualTo("2026-06-22"));
        assertThat(response.getNoOpeningReason()).isNull();
    }

    // ── AC6: two kinds of "no opening" ──────────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "AC6: no bay at the location can do the work → NO_ELIGIBLE_BAY_AT_LOCATION, before any appointment is read")
    void noEligibleBay() {
        // D14: a wash bay never absorbs general mechanical work, and it is the only bay here.
        when(bayRepository.findActiveByLocationOrdered(LOCATION))
                .thenReturn(List.of(ExtBayReplica.builder()
                        .bayId(RACK)
                        .locationId(LOCATION)
                        .name("Wash")
                        .bayType("WASH_DETAIL")
                        .active(true)
                        .serviceCapabilityCodes(List.of())
                        .build()));

        OpeningSearchResponse response = service.search(query(BRAKE_JOB, 60, TUE_0900));

        assertThat(response.getOpenings()).isEmpty();
        assertThat(response.getNoOpeningReason()).isEqualTo(NoOpeningReason.NO_ELIGIBLE_BAY_AT_LOCATION);
        assertThat(response.getBayEligibility().getExcludedByCapability()).isEqualTo(1);
        verify(appointmentRepository, never()).findHeldOverlappingAtLocation(any(), any(), any(), any());
    }

    @Test
    @DisplayName("D14: a specialty bay takes general work, offered after the general bays at the same start")
    void specialtyBayTakesGeneralWorkRankedLast() {
        requirements.clear();
        OpeningSearchResponse response = service.search(
                new OpeningSearchQuery(LOCATION, List.of(BRAKE_JOB), 60, TUE_0900, VEHICLE, null, 1, 10));

        // Tuesday, three openings at 09:00: Bay 1, Bay 2, then the rack.
        assertThat(response.getOpenings()).extracting(Opening::getBayId).containsExactly(BAY_1, BAY_2, RACK);
    }

    @Test
    @DisplayName("spec §8: a duty-class miss is counted apart from a capability miss")
    void dutyClassMissIsDistinct() {
        when(vehicleRepository.findById(VEHICLE))
                .thenReturn(Optional.of(ExtVehicleReplica.builder()
                        .vehicleId(VEHICLE)
                        .gvwrClass(6)
                        .build()));
        requirements.clear(); // keep the skill axis out of this case

        OpeningSearchResponse response = service.search(query(BRAKE_JOB, 60, TUE_0900));

        // Bay 2's ceiling is class 3; Bay 1 and the rack are unconstrained (the rack takes general work, D14).
        assertThat(response.getBayEligibility().getEligibleBays()).isEqualTo(2);
        assertThat(response.getBayEligibility().getExcludedByCapability()).isEqualTo(0);
        assertThat(response.getBayEligibility().getExcludedByDutyClass()).isEqualTo(1);
        assertThat(response.getOpenings()).extracting(Opening::getBayId).doesNotContain(BAY_2);
    }

    @Test
    @DisplayName(
            "CAP-325 D14: a specialty operation is done only in the bay that claims it, matched case-insensitively")
    void specialtyOperationOnlyInClaimingBay() {
        when(bayRepository.findActiveByLocationOrdered(LOCATION))
                .thenReturn(List.of(
                        bay(BAY_1, "Bay 1", List.of(), null),
                        bay(RACK, "Rack", List.of(" wheel-alignment-4-wheel "), null)));
        credentials.add(credential(TECH_B, "A4-SUSPENSION", "A4", LocalDate.of(2027, 1, 1), "ACTIVE"));

        OpeningSearchResponse response =
                service.search(new OpeningSearchQuery(LOCATION, List.of(ALIGNMENT), 60, TUE_0900, null, null, 1, 10));

        assertThat(response.getOpenings()).singleElement().satisfies(opening -> {
            assertThat(opening.getBayId()).isEqualTo(RACK);
            assertThat(opening.getTechnicianId()).isEqualTo(TECH_B);
            assertThat(opening.getSkillFulfillment()).isEqualTo(SkillFulfillment.CERTIFIED);
        });
        assertThat(response.getVehicleGvwrClass()).isNull();
    }

    // ── AC8 / D10.2: staffing advisory alongside a non-empty list ───────────────────────────────

    @Test
    @DisplayName(
            "AC8: WHEEL-ALIGNMENT at a shop with a rack but no alignment technician → openings AND a NOT_AT_THIS_LOCATION advisory")
    void rosteredAbsenceIsAResponseLevelAdvisory() {
        OpeningSearchResponse response =
                service.search(new OpeningSearchQuery(LOCATION, List.of(ALIGNMENT), 60, TUE_0900, null, null, 1, 10));

        assertThat(response.getOpenings()).isNotEmpty();
        assertThat(response.getNoOpeningReason()).isNull();
        assertThat(response.getStaffingAdvisory()).isNotNull().satisfies(advisory -> {
            assertThat(advisory.getCode()).isEqualTo(StaffingAdvisoryCode.NO_COMPETENT_MECHANIC_ROSTERED);
            assertThat(advisory.getMissingSkillCodes()).containsExactly("A4-SUSPENSION");
            assertThat(advisory.getAbsenceScope()).isEqualTo(AbsenceScope.NOT_AT_THIS_LOCATION);
        });
        // The openings themselves are AWAITING and say what is unmet — assignable to a non-certified technician.
        assertThat(response.getOpenings()).allSatisfy(opening -> {
            assertThat(opening.getSkillFulfillment()).isEqualTo(SkillFulfillment.AWAITING);
            assertThat(opening.getUnmetSkillCodes()).containsExactly("A4-SUSPENSION");
        });
    }

    @Test
    @DisplayName("D10.2: a holder who works here but is not rostered in the horizon → NOT_ROSTERED_THIS_DAY")
    void holderNotRosteredInHorizon() {
        // TECH_B holds alignment but their posting ends before the search; TECH_A carries the days.
        staffing.clear();
        staffing.add(technician(TECH_A, LocalDate.of(2026, 1, 1), null));
        staffing.add(technician(TECH_B, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1)));
        credentials.add(credential(TECH_B, "A4-SUSPENSION", "A4", LocalDate.of(2027, 1, 1), "ACTIVE"));

        OpeningSearchResponse response =
                service.search(new OpeningSearchQuery(LOCATION, List.of(ALIGNMENT), 60, TUE_0900, null, null, 1, 10));

        assertThat(response.getOpenings()).isNotEmpty();
        assertThat(response.getStaffingAdvisory().getAbsenceScope()).isEqualTo(AbsenceScope.NOT_ROSTERED_THIS_DAY);
    }

    @Test
    @DisplayName(
            "spec §8: a competent technician rostered but busy is contention — AWAITING on the opening, no advisory")
    void contentionIsPerOpeningNotAdvisory() {
        // TECH_A (the only brake holder) is on another job 09:00–17:00 Tuesday; TECH_B is free.
        held.add(technician(TECH_A, "2026-06-16T13:00:00Z", "2026-06-16T21:00:00Z"));

        OpeningSearchResponse response = service.search(
                new OpeningSearchQuery(LOCATION, List.of(BRAKE_JOB), 60, TUE_0900, VEHICLE, null, 1, 10));

        assertThat(response.getStaffingAdvisory()).isNull();
        assertThat(response.getOpenings()).isNotEmpty().allSatisfy(opening -> {
            assertThat(opening.getTechnicianId()).isEqualTo(TECH_B);
            assertThat(opening.getSkillFulfillment()).isEqualTo(SkillFulfillment.AWAITING);
            assertThat(opening.getUnmetSkillCodes()).containsExactly("BRAKES-LIGHT");
        });
    }

    @Test
    @DisplayName(
            "a busy technician's appointment end is a candidate start: the CERTIFIED opening after it ranks above nothing")
    void technicianBusyIntervalEndIsACandidateStart() {
        staffing.clear();
        staffing.add(technician(TECH_A, LocalDate.of(2026, 1, 1), null)); // the only technician
        held.add(technician(TECH_A, "2026-06-16T13:00:00Z", "2026-06-16T14:20:00Z"));

        OpeningSearchResponse response = service.search(
                new OpeningSearchQuery(LOCATION, List.of(BRAKE_JOB), 60, TUE_0900, VEHICLE, null, 1, 10));

        assertThat(response.getOpenings()).isNotEmpty().allSatisfy(opening -> {
            assertThat(opening.getStartAt()).isEqualTo(Instant.parse("2026-06-16T14:20:00Z"));
            assertThat(opening.getTechnicianId()).isEqualTo(TECH_A);
        });
    }

    @Test
    @DisplayName("CAP-328 expiry: a credential lapsed before the opening's local date does not certify it")
    void expiredCredentialDoesNotCertify() {
        credentials.clear();
        credentials.add(credential(TECH_A, "BRAKES-LIGHT", "A5-BRAKES", LocalDate.of(2026, 6, 15), "ACTIVE"));

        OpeningSearchResponse response = service.search(
                new OpeningSearchQuery(LOCATION, List.of(BRAKE_JOB), 60, TUE_0900, VEHICLE, null, 1, 10));

        assertThat(response.getOpenings())
                .allSatisfy(opening -> assertThat(opening.getSkillFulfillment()).isEqualTo(SkillFulfillment.AWAITING));
        assertThat(response.getStaffingAdvisory().getCode())
                .isEqualTo(StaffingAdvisoryCode.NO_COMPETENT_MECHANIC_ROSTERED);
    }

    @Test
    @DisplayName("AC13: zero technicians is MECHANIC_UNAVAILABLE, never a competence rule, and no opening can be named")
    void zeroTechniciansIsMechanicUnavailable() {
        staffing.clear();

        OpeningSearchResponse response = service.search(query(BRAKE_JOB, 60, TUE_0900));

        assertThat(response.getOpenings()).isEmpty();
        assertThat(response.getNoOpeningReason()).isNull();
        assertThat(response.getStaffingAdvisory()).satisfies(advisory -> {
            assertThat(advisory.getCode()).isEqualTo(StaffingAdvisoryCode.MECHANIC_UNAVAILABLE);
            assertThat(advisory.getMissingSkillCodes()).isEmpty();
            assertThat(advisory.getAbsenceScope()).isEqualTo(AbsenceScope.NOT_AT_THIS_LOCATION);
        });
        verify(credentialRepository, never()).findByPersonIdInOrderByIssuedOnDesc(any());
    }

    @Test
    @DisplayName("a service advisor on the roster is not a technician: the search does not name them")
    void nonTechnicianRolesAreNotNamed() {
        staffing.clear();
        staffing.add(ExtStaffingAssignmentReplica.builder()
                .assignmentId(UUID.randomUUID())
                .locationId(LOCATION)
                .personId(TECH_B)
                .role("SERVICE_ADVISOR")
                .status("ACTIVE")
                .effectiveFrom(LocalDate.of(2026, 1, 1))
                .build());

        OpeningSearchResponse response = service.search(query(BRAKE_JOB, 60, TUE_0900));

        assertThat(response.getOpenings()).isEmpty();
        assertThat(response.getStaffingAdvisory().getCode()).isEqualTo(StaffingAdvisoryCode.MECHANIC_UNAVAILABLE);
    }

    @Test
    @DisplayName("a service whose requirements were never configured evaluates no SKILL constraint and says so")
    void unconfiguredServiceOmitsSkillConstraint() {
        UUID unconfigured = UUID.fromString("0196cf6f-c8dd-7ee0-93e7-f48a5698a599");
        org.mockito.Mockito.doReturn(List.of(catalogService(unconfigured, "OIL-CHANGE", false)))
                .when(catalogServiceRepository)
                .findAllByServiceIdIn(any());

        OpeningSearchResponse response = service.search(
                new OpeningSearchQuery(LOCATION, List.of(unconfigured), 30, TUE_0900, VEHICLE, null, 1, 10));

        assertThat(response.getRequiredSkillCodes()).isEmpty();
        assertThat(response.getStaffingAdvisory()).isNull();
        assertThat(response.getOpenings()).isNotEmpty().allSatisfy(opening -> {
            assertThat(opening.getConstraintsEvaluated()).doesNotContain(OpeningConstraint.SKILL);
            assertThat(opening.getSkillFulfillment()).isEqualTo(SkillFulfillment.CERTIFIED);
        });
    }

    @Test
    @DisplayName("technicianId restricts the search to openings that technician can take")
    void preferredTechnicianFilters() {
        OpeningSearchResponse response = service.search(
                new OpeningSearchQuery(LOCATION, List.of(BRAKE_JOB), 60, TUE_0900, VEHICLE, TECH_B, 1, 10));

        assertThat(response.getOpenings())
                .isNotEmpty()
                .allSatisfy(opening -> assertThat(opening.getTechnicianId()).isEqualTo(TECH_B));
    }

    // ── AC11: bounds ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("bounds (AC11)")
    class Bounds {
        @Test
        void malformedValuesAre400() {
            assertThatThrownBy(() -> service.search(
                            new OpeningSearchQuery(LOCATION, List.of(), 60, TUE_0900, null, null, 30, 10)))
                    .isInstanceOf(ShopManagerValidationException.class);
            assertThatThrownBy(() -> service.search(
                            new OpeningSearchQuery(LOCATION, List.of(BRAKE_JOB), 0, TUE_0900, null, null, 30, 10)))
                    .isInstanceOf(ShopManagerValidationException.class);
            assertThatThrownBy(() -> service.search(
                            new OpeningSearchQuery(LOCATION, List.of(BRAKE_JOB), 1441, TUE_0900, null, null, 30, 10)))
                    .isInstanceOf(ShopManagerValidationException.class);
            assertThatThrownBy(() -> service.search(
                            new OpeningSearchQuery(LOCATION, List.of(BRAKE_JOB), 60, TUE_0900, null, null, 0, 10)))
                    .isInstanceOf(ShopManagerValidationException.class);
            assertThatThrownBy(() -> service.search(
                            new OpeningSearchQuery(LOCATION, List.of(BRAKE_JOB), 60, TUE_0900, null, null, 30, 0)))
                    .isInstanceOf(ShopManagerValidationException.class);
        }

        @Test
        void policyLimitsAre422WithTheirCode() {
            assertThatThrownBy(() -> service.search(
                            new OpeningSearchQuery(LOCATION, List.of(BRAKE_JOB), 60, TUE_0900, null, null, 31, 10)))
                    .isInstanceOfSatisfying(
                            OpeningSearchPolicyException.class,
                            e -> assertThat(e.getCode()).isEqualTo("OPENING_HORIZON_EXCEEDED"));
            assertThatThrownBy(() -> service.search(
                            new OpeningSearchQuery(LOCATION, List.of(BRAKE_JOB), 60, TUE_0900, null, null, 30, 51)))
                    .isInstanceOfSatisfying(
                            OpeningSearchPolicyException.class,
                            e -> assertThat(e.getCode()).isEqualTo("OPENING_LIMIT_EXCEEDED"));
            List<UUID> eleven =
                    java.util.stream.Stream.generate(UUID::randomUUID).limit(11).toList();
            assertThatThrownBy(() ->
                            service.search(new OpeningSearchQuery(LOCATION, eleven, 60, TUE_0900, null, null, 30, 10)))
                    .isInstanceOfSatisfying(
                            OpeningSearchPolicyException.class,
                            e -> assertThat(e.getCode()).isEqualTo("OPENING_TOO_MANY_SERVICES"));
        }

        @Test
        void unknownLocationIs404_andUnpublishedHoursAre422() {
            when(locationRepository.findById(LOCATION)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.search(query(BRAKE_JOB, 60, TUE_0900)))
                    .isInstanceOf(LocationNotFoundException.class);

            when(locationRepository.findById(LOCATION))
                    .thenReturn(Optional.of(location("America/New_York", null, null, 0, 0)));
            assertThatThrownBy(() -> service.search(query(BRAKE_JOB, 60, TUE_0900)))
                    .isInstanceOfSatisfying(
                            OpeningSearchPolicyException.class,
                            e -> assertThat(e.getCode()).isEqualTo("LOCATION_HOURS_UNKNOWN"));
        }

        @Test
        void unknownServiceIs404() {
            UUID unknown = UUID.randomUUID();
            assertThatThrownBy(() -> service.search(new OpeningSearchQuery(
                            LOCATION, List.of(BRAKE_JOB, unknown), 60, TUE_0900, null, null, 30, 10)))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(unknown.toString());
        }

        @Test
        void oneAppointmentReadSpansTheWholeHorizon() {
            service.search(new OpeningSearchQuery(LOCATION, List.of(BRAKE_JOB), 60, TUE_0900, VEHICLE, null, 30, 50));

            // Tuesday 00:00 local (04:00Z) through 30 days later, one call, held statuses only.
            verify(appointmentRepository)
                    .findHeldOverlappingAtLocation(
                            eq(LOCATION),
                            eq(Instant.parse("2026-06-16T04:00:00Z")),
                            eq(Instant.parse("2026-07-16T04:00:00Z")),
                            eq(AppointmentStatus.holdingAResource()));
        }
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────

    private static ExtLocationReplica location(String tz, String hours, String closures, int checkIn, int cleanup) {
        return ExtLocationReplica.builder()
                .locationId(LOCATION)
                .timezone(tz)
                .operatingHours(hours)
                .holidayClosures(closures)
                .checkInBufferMinutes(checkIn)
                .cleanupBufferMinutes(cleanup)
                .active(true)
                .build();
    }

    private static ExtBayReplica bay(UUID id, String name, List<String> codes, Integer maxDutyClass) {
        return ExtBayReplica.builder()
                .bayId(id)
                .locationId(LOCATION)
                .name(name)
                .bayType(codes.isEmpty() ? "GENERAL_SERVICE" : "ALIGNMENT")
                .active(true)
                .serviceCapabilityCodes(codes)
                .maxDutyClass(maxDutyClass)
                .build();
    }

    private static ExtCatalogServiceReplica catalogService(UUID id, String operation, boolean configured) {
        return ExtCatalogServiceReplica.builder()
                .serviceId(id)
                .name(operation)
                .operationCode(operation)
                .active(true)
                .requirementsConfiguredAt(configured ? Instant.parse("2026-06-01T00:00:00Z") : null)
                .build();
    }

    private static ExtCatalogServiceSkillReplica requirement(UUID service, String code, Integer min, Integer max) {
        return ExtCatalogServiceSkillReplica.builder()
                .id(UUID.randomUUID())
                .serviceId(service)
                .skillId(UUID.nameUUIDFromBytes(code.getBytes()))
                .skillCode(code)
                .minGvwrClass(min)
                .maxGvwrClass(max)
                .build();
    }

    private static ExtStaffingAssignmentReplica technician(UUID person, LocalDate from, LocalDate to) {
        return ExtStaffingAssignmentReplica.builder()
                .assignmentId(UUID.randomUUID())
                .locationId(LOCATION)
                .personId(person)
                .role("TECHNICIAN")
                .status("ACTIVE")
                .effectiveFrom(from)
                .effectiveTo(to)
                .build();
    }

    private static ExtPersonCredentialReplica credential(
            UUID person, String skillCode, String sourceCode, LocalDate expiresOn, String status) {
        return ExtPersonCredentialReplica.builder()
                .credentialId(UUID.randomUUID())
                .personId(person)
                .skillId(UUID.nameUUIDFromBytes(skillCode.getBytes()))
                .skillCode(skillCode)
                .competenceCode(skillCode)
                .minGvwrClass(1)
                .maxGvwrClass(8)
                .issuer("ASE")
                .sourceCode("ASE")
                .sourceCredentialCode(sourceCode)
                .issuedOn(LocalDate.of(2021, 1, 1))
                .expiresOn(expiresOn)
                .status(status)
                .aggregateVersion(1)
                .updatedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }

    private static Appointment bay(UUID bayId, String start, String end) {
        return heldAppointment(bayId.toString(), start, end);
    }

    private static Appointment technician(UUID personId, String start, String end) {
        return heldAppointment(personId.toString(), start, end);
    }

    private static Appointment heldAppointment(String resourceId, String start, String end) {
        return Appointment.builder()
                .appointmentId(UUID.randomUUID())
                .locationId(LOCATION)
                .resourceId(resourceId)
                .status(AppointmentStatus.SCHEDULED)
                .startAt(Instant.parse(start))
                .endAt(Instant.parse(end))
                .build();
    }
}
