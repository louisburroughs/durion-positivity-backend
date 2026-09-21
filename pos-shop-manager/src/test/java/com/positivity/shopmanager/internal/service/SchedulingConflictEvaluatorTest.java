package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.shopmanager.SchedulingWorldFixture;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.ConflictRule;
import com.positivity.shopmanager.internal.entity.ExtBayReplica;
import com.positivity.shopmanager.internal.entity.ExtCatalogServiceReplica;
import com.positivity.shopmanager.internal.entity.ExtCatalogServiceSkillReplica;
import com.positivity.shopmanager.internal.entity.ExtLocationReplica;
import com.positivity.shopmanager.internal.entity.ExtPersonCredentialReplica;
import com.positivity.shopmanager.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.shopmanager.internal.entity.ExtVehicleReplica;
import com.positivity.shopmanager.internal.enums.ConflictResourceType;
import com.positivity.shopmanager.internal.enums.ConflictSeverity;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.ConflictRuleRepository;
import com.positivity.shopmanager.internal.repository.ExtBayReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtCatalogServiceReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtCatalogServiceSkillReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtLocationReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtPersonCredentialReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtStaffingAssignmentReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtVehicleReplicaRepository;
import com.positivity.shopmanager.internal.service.SchedulingConflictEvaluator.BookingAttempt;
import com.positivity.shopmanager.internal.service.SchedulingConflictEvaluator.DetectedConflict;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * DECISION-SHOPMGMT-002's rules against one booking attempt (CAP-326, spec D18.1). Chicago shop,
 * weekdays 08:00–17:00, Saturday until 13:00, closed Sunday and on 2026-07-03; the attempt is a
 * Tuesday 10:00–11:00 unless a test says otherwise.
 */
@ExtendWith(MockitoExtension.class)
class SchedulingConflictEvaluatorTest {

    private static final UUID LOCATION = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID SELF = UUID.fromString("00000000-0000-0000-0000-000000000055");
    private static final String BAY = "bay-1";
    private static final String CHICAGO = "America/Chicago";
    private static final String HOURS = """
            [{"dayOfWeek":"MONDAY","openTime":"08:00:00","closeTime":"17:00:00"},
             {"dayOfWeek":"TUESDAY","openTime":"08:00:00","closeTime":"17:00:00"},
             {"dayOfWeek":"WEDNESDAY","openTime":"08:00:00","closeTime":"17:00:00"},
             {"dayOfWeek":"THURSDAY","openTime":"08:00:00","closeTime":"17:00:00"},
             {"dayOfWeek":"FRIDAY","openTime":"08:00:00","closeTime":"17:00:00"},
             {"dayOfWeek":"SATURDAY","openTime":"08:00:00","closeTime":"13:00:00"}]""";
    private static final String CLOSURES = """
            [{"date":"2026-07-03","reason":"Independence Day (observed)"}]""";
    // Tuesday 2026-06-16, 10:00–11:00 Chicago (CDT = UTC-5).
    private static final Instant TUE_10 = Instant.parse("2026-06-16T15:00:00Z");
    private static final Instant TUE_11 = Instant.parse("2026-06-16T16:00:00Z");

    private static final Map<String, ConflictSeverity> CATALOG = Map.of(
            "FACILITY_CLOSED", ConflictSeverity.HARD,
            "OUTSIDE_OPERATING_HOURS", ConflictSeverity.HARD,
            "BAY_DOUBLE_BOOKED", ConflictSeverity.HARD,
            "MECHANIC_UNAVAILABLE", ConflictSeverity.HARD,
            "FACILITY_NEAR_CAPACITY", ConflictSeverity.SOFT,
            "NO_COMPETENT_MECHANIC_ROSTERED", ConflictSeverity.SOFT,
            "COMPETENT_MECHANIC_UNAVAILABLE", ConflictSeverity.SOFT);

    @Mock
    private ConflictRuleRepository conflictRuleRepository;

    @Mock
    private ExtLocationReplicaRepository extLocationReplicaRepository;

    @Mock
    private AppointmentRepository appointmentRepository;

    @Mock
    private ExtStaffingAssignmentReplicaRepository staffingRepository;

    @Mock
    private ExtBayReplicaRepository extBayReplicaRepository;

    @Mock
    private ExtCatalogServiceReplicaRepository catalogServiceRepository;

    @Mock
    private ExtCatalogServiceSkillReplicaRepository catalogServiceSkillRepository;

    @Mock
    private ExtPersonCredentialReplicaRepository credentialRepository;

    @Mock
    private ExtVehicleReplicaRepository vehicleRepository;

    private SchedulingConflictEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new SchedulingConflictEvaluator(
                conflictRuleRepository,
                extLocationReplicaRepository,
                new LocationHoursParser(new ObjectMapper()),
                appointmentRepository,
                staffingRepository,
                extBayReplicaRepository,
                new SkillRequirementResolver(
                        catalogServiceRepository,
                        catalogServiceSkillRepository,
                        credentialRepository,
                        vehicleRepository));
        lenient().when(conflictRuleRepository.findByCode(anyString())).thenAnswer(invocation -> {
            String code = invocation.getArgument(0);
            ConflictSeverity severity = CATALOG.get(code);
            return severity == null ? Optional.empty() : Optional.of(rule(code, severity, true));
        });
        // The happy shop: open, bay free, one mechanic rostered, ten bays.
        lenient()
                .when(extLocationReplicaRepository.findById(LOCATION))
                .thenReturn(Optional.of(location(CHICAGO, HOURS, CLOSURES)));
        lenient()
                .when(appointmentRepository.findHeldOverlappingForResource(any(), any(), any(), any()))
                .thenReturn(List.of());
        lenient()
                .when(appointmentRepository.findHeldOverlappingAtLocation(any(), any(), any(), any()))
                .thenReturn(List.of());
        lenient()
                .when(staffingRepository.findByLocationIdAndStatus(LOCATION, "ACTIVE"))
                .thenReturn(List.of(staffing(LocalDate.of(2026, 1, 1), null)));
        lenient()
                .when(extBayReplicaRepository.findActiveByLocationOrdered(LOCATION))
                .thenReturn(bays(10));
    }

    @Test
    @DisplayName("an open day, a free bay, a rostered mechanic and room to spare: nothing fires")
    void happyPathFiresNothing() {
        assertThat(evaluator.evaluate(attempt(TUE_10, TUE_11, null))).isEmpty();
    }

    @Nested
    @DisplayName("HOURS")
    class Hours {
        @Test
        void closedWeekdayIsFacilityClosed_andNothingElseIsAsked() {
            // Sunday 2026-06-14 10:00 Chicago.
            Instant sun10 = Instant.parse("2026-06-14T15:00:00Z");
            List<DetectedConflict> detected = evaluator.evaluate(attempt(sun10, sun10.plusSeconds(3600), null));

            assertThat(codes(detected)).containsExactly("FACILITY_CLOSED");
            assertThat(detected.get(0).isHard()).isTrue();
            assertThat(detected.get(0).detail()).contains("2026-06-14");
            verify(appointmentRepository, never()).findHeldOverlappingForResource(any(), any(), any(), any());
        }

        @Test
        void datedClosureIsFacilityClosedWithTheReason() {
            // Friday 2026-07-03 10:00 Chicago — a weekday the calendar closes.
            Instant fri10 = Instant.parse("2026-07-03T15:00:00Z");
            List<DetectedConflict> detected = evaluator.evaluate(attempt(fri10, fri10.plusSeconds(3600), null));

            assertThat(codes(detected)).containsExactly("FACILITY_CLOSED");
            assertThat(detected.get(0).detail()).contains("Independence Day");
        }

        @Test
        void startingBeforeOpeningIsOutsideOperatingHours() {
            Instant tue0730 = Instant.parse("2026-06-16T12:30:00Z");
            assertThat(codes(evaluator.evaluate(attempt(tue0730, tue0730.plusSeconds(3600), null))))
                    .containsExactly("OUTSIDE_OPERATING_HOURS");
        }

        @Test
        void endingAfterClosingIsOutsideOperatingHours_inFacilityLocalTime() {
            // Saturday 2026-06-20 12:30–13:30 Chicago: closes at 13:00.
            Instant sat1230 = Instant.parse("2026-06-20T17:30:00Z");
            List<DetectedConflict> detected = evaluator.evaluate(attempt(sat1230, sat1230.plusSeconds(3600), null));
            assertThat(codes(detected)).containsExactly("OUTSIDE_OPERATING_HOURS");
            assertThat(detected.get(0).detail()).contains("12:30").contains("13:30");
        }

        @Test
        @DisplayName("#2139: the hours refusal names the zone the window was converted into")
        void outsideOperatingHoursNamesTheFacilityZone() {
            // 09:00Z on Friday 2026-06-19 is 04:00 Chicago (CDT), four hours before the 08:00 open:
            // the shape of #2139, where a caller who published hours without a zone read the
            // converted time as a platform error because nothing said which zone it was in.
            Instant fri09Utc = Instant.parse("2026-06-19T09:00:00Z");
            List<DetectedConflict> detected = evaluator.evaluate(attempt(fri09Utc, fri09Utc.plusSeconds(3600), null));

            assertThat(codes(detected)).containsExactly("OUTSIDE_OPERATING_HOURS");
            assertThat(detected.get(0).detail())
                    .isEqualTo("04:00–05:00 America/Chicago falls outside the"
                            + " location's operating hours for that day.");
        }

        @Test
        @DisplayName("#2139: with no resolvable zone the window is UTC and {zone} says so, not \"Z\"")
        void unresolvableZoneLabelsTheWindowUtc() {
            // The fallback half of the contract render() documents. A location with no resolvable
            // zone cannot fire the HOURS rules at all, so the label is pinned on the next rule that
            // quotes the window: 15:00–16:00 is the UTC reading of the attempt, and the label has to
            // say which zone that is. ZoneOffset.UTC.getId() is "Z" — neither the IANA identifier
            // the refusal advertises nor a string a caller reads as a zone at all.
            when(extLocationReplicaRepository.findById(LOCATION))
                    .thenReturn(Optional.of(location("Mars/Olympus", HOURS, CLOSURES)));
            when(appointmentRepository.findHeldOverlappingForResource(eq(BAY), eq(TUE_10), eq(TUE_11), any()))
                    .thenReturn(List.of(Appointment.builder()
                            .appointmentId(UUID.randomUUID())
                            .build()));

            List<DetectedConflict> detected = evaluator.evaluate(attempt(TUE_10, TUE_11, null));

            assertThat(codes(detected)).containsExactly("BAY_DOUBLE_BOOKED");
            assertThat(detected.get(0).detail()).isEqualTo("Bay bay-1 is already booked for part of 15:00–16:00 UTC.");
        }

        @Test
        void spanningLocalMidnightIsOutsideOperatingHours() {
            // Tuesday 23:30 → Wednesday 00:30 Chicago.
            Instant tue2330 = Instant.parse("2026-06-17T04:30:00Z");
            assertThat(codes(evaluator.evaluate(attempt(tue2330, tue2330.plusSeconds(3600), null))))
                    .containsExactly("OUTSIDE_OPERATING_HOURS");
        }

        @Test
        void hoursNeverPublishedFireNoHoursRule_andTheRestStillRuns() {
            when(extLocationReplicaRepository.findById(LOCATION))
                    .thenReturn(Optional.of(location(CHICAGO, null, null)));
            Instant sun10 = Instant.parse("2026-06-14T15:00:00Z");

            assertThat(evaluator.evaluate(attempt(sun10, sun10.plusSeconds(3600), null)))
                    .isEmpty();
            verify(appointmentRepository).findHeldOverlappingForResource(any(), any(), any(), any());
        }

        @Test
        void noReplicaRowFiresNoHoursRule() {
            when(extLocationReplicaRepository.findById(LOCATION)).thenReturn(Optional.empty());
            assertThat(evaluator.evaluate(attempt(TUE_10, TUE_11, null))).isEmpty();
        }

        @Test
        void unparsableHoursAreUnknown_notClosed() {
            when(extLocationReplicaRepository.findById(LOCATION))
                    .thenReturn(Optional.of(location(CHICAGO, "[{\"dayOfWeek\":\"Someday\"}]", null)));
            assertThat(evaluator.evaluate(attempt(TUE_10, TUE_11, null))).isEmpty();
        }
    }

    @Nested
    @DisplayName("BAY")
    class Bay {
        @Test
        void aHeldOverlapOnTheSameResourceIsBayDoubleBooked() {
            when(appointmentRepository.findHeldOverlappingForResource(eq(BAY), eq(TUE_10), eq(TUE_11), any()))
                    .thenReturn(List.of(Appointment.builder()
                            .appointmentId(UUID.randomUUID())
                            .build()));

            List<DetectedConflict> detected = evaluator.evaluate(attempt(TUE_10, TUE_11, null));

            assertThat(codes(detected)).containsExactly("BAY_DOUBLE_BOOKED");
            assertThat(detected.get(0).resourceId()).isEqualTo(BAY);
            assertThat(detected.get(0).detail()).contains(BAY).contains("10:00").contains("11:00");
        }

        @Test
        void theAppointmentBeingRescheduledDoesNotCountAgainstItself() {
            when(appointmentRepository.findHeldOverlappingForResource(eq(BAY), eq(TUE_10), eq(TUE_11), any()))
                    .thenReturn(
                            List.of(Appointment.builder().appointmentId(SELF).build()));

            assertThat(evaluator.evaluate(attempt(TUE_10, TUE_11, SELF))).isEmpty();
        }

        @Test
        void unassignedOrBlankResourceIsNotABay() {
            assertThat(evaluator.evaluate(new BookingAttempt(LOCATION, "UNASSIGNED", TUE_10, TUE_11, null)))
                    .isEmpty();
            assertThat(evaluator.evaluate(new BookingAttempt(LOCATION, null, TUE_10, TUE_11, null)))
                    .isEmpty();
            verify(appointmentRepository, never()).findHeldOverlappingForResource(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("MECHANIC")
    class Mechanic {
        @Test
        void nobodyRosteredAtTheLocationIsMechanicUnavailable() {
            when(staffingRepository.findByLocationIdAndStatus(LOCATION, "ACTIVE"))
                    .thenReturn(List.of());
            assertThat(codes(evaluator.evaluate(attempt(TUE_10, TUE_11, null))))
                    .containsExactly("MECHANIC_UNAVAILABLE");
        }

        @Test
        void anAssignmentThatEndedBeforeTheDayDoesNotCount() {
            when(staffingRepository.findByLocationIdAndStatus(LOCATION, "ACTIVE"))
                    .thenReturn(List.of(staffing(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 15))));
            assertThat(codes(evaluator.evaluate(attempt(TUE_10, TUE_11, null))))
                    .containsExactly("MECHANIC_UNAVAILABLE");
        }

        @Test
        @DisplayName("#2140: an assignment beginning after the date judged does not cover it")
        void anAssignmentThatBeginsAfterTheDayDoesNotCount() {
            // The back-dated-clock shape: the assignment was written at wall time and starts ten
            // months after the instant being judged. Effective dates are read as written, so the
            // date genuinely has nobody assigned on it — what the refusal must not do is call that
            // "nobody is present".
            when(staffingRepository.findByLocationIdAndStatus(LOCATION, "ACTIVE"))
                    .thenReturn(List.of(staffing(LocalDate.of(2027, 4, 1), null)));
            List<DetectedConflict> detected = evaluator.evaluate(attempt(TUE_10, TUE_11, null));

            assertThat(codes(detected)).containsExactly("MECHANIC_UNAVAILABLE");
            assertThat(detected.get(0).detail())
                    .isEqualTo("No technician staffing assignment covers 10:00–11:00 America/Chicago at this"
                            + " location (1 ACTIVE technician staffing assignment exists at this location, none"
                            + " effective on 2026-06-16).");
        }

        @Test
        @DisplayName("#2140: the assignment count reads as a count, singular or plural")
        void theAssignmentCountIsGrammatical() {
            when(staffingRepository.findByLocationIdAndStatus(LOCATION, "ACTIVE"))
                    .thenReturn(List.of(
                            staffing(LocalDate.of(2027, 4, 1), null), staffing(LocalDate.of(2027, 5, 1), null)));

            assertThat(evaluator.evaluate(attempt(TUE_10, TUE_11, null)).get(0).detail())
                    .contains("2 ACTIVE technician staffing assignments exist at this location");
        }

        @Test
        @DisplayName("#2140: a location with no assignment at all says that, not that a date is uncovered")
        void anUnstaffedLocationSaysItHasNoAssignment() {
            when(staffingRepository.findByLocationIdAndStatus(LOCATION, "ACTIVE"))
                    .thenReturn(List.of());
            List<DetectedConflict> detected = evaluator.evaluate(attempt(TUE_10, TUE_11, null));

            assertThat(detected.get(0).detail())
                    .isEqualTo("No technician staffing assignment covers 10:00–11:00 America/Chicago at this"
                            + " location (no ACTIVE technician staffing assignment exists at this location).");
        }

        @Test
        void anOpenEndedAssignmentCovers() {
            when(staffingRepository.findByLocationIdAndStatus(LOCATION, "ACTIVE"))
                    .thenReturn(List.of(staffing(LocalDate.of(2026, 6, 16), null)));
            assertThat(evaluator.evaluate(attempt(TUE_10, TUE_11, null))).isEmpty();
        }
    }

    @Nested
    @DisplayName("CAPACITY")
    class Capacity {
        @Test
        void fillingTheLastOfTwoBaysIsNearCapacity_soft() {
            when(extBayReplicaRepository.findActiveByLocationOrdered(LOCATION)).thenReturn(bays(2));
            when(appointmentRepository.findHeldOverlappingAtLocation(eq(LOCATION), eq(TUE_10), eq(TUE_11), any()))
                    .thenReturn(List.of(Appointment.builder()
                            .appointmentId(UUID.randomUUID())
                            .build()));

            List<DetectedConflict> detected = evaluator.evaluate(attempt(TUE_10, TUE_11, null));

            assertThat(codes(detected)).containsExactly("FACILITY_NEAR_CAPACITY");
            assertThat(detected.get(0).isHard()).isFalse();
        }

        @Test
        void oneOfTenIsNot() {
            when(appointmentRepository.findHeldOverlappingAtLocation(eq(LOCATION), eq(TUE_10), eq(TUE_11), any()))
                    .thenReturn(List.of(Appointment.builder()
                            .appointmentId(UUID.randomUUID())
                            .build()));
            assertThat(evaluator.evaluate(attempt(TUE_10, TUE_11, null))).isEmpty();
        }

        @Test
        void noBaysReplicatedMeansNoCapacityJudgement() {
            when(extBayReplicaRepository.findActiveByLocationOrdered(LOCATION)).thenReturn(List.of());
            assertThat(evaluator.evaluate(attempt(TUE_10, TUE_11, null))).isEmpty();
        }
    }

    @Nested
    @DisplayName("catalog")
    class Catalog {
        @Test
        void aSwitchedOffRuleDoesNotFire() {
            when(conflictRuleRepository.findByCode("MECHANIC_UNAVAILABLE"))
                    .thenReturn(Optional.of(rule("MECHANIC_UNAVAILABLE", ConflictSeverity.HARD, false)));
            when(staffingRepository.findByLocationIdAndStatus(LOCATION, "ACTIVE"))
                    .thenReturn(List.of());

            assertThat(evaluator.evaluate(attempt(TUE_10, TUE_11, null))).isEmpty();
        }

        @Test
        void aRuleMissingFromTheSeedIsADeploymentDefect() {
            when(conflictRuleRepository.findByCode("MECHANIC_UNAVAILABLE")).thenReturn(Optional.empty());
            when(staffingRepository.findByLocationIdAndStatus(LOCATION, "ACTIVE"))
                    .thenReturn(List.of());

            assertThatThrownBy(() -> evaluator.evaluate(attempt(TUE_10, TUE_11, null)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MECHANIC_UNAVAILABLE");
        }

        @Test
        void bayDoubleBookedForARefusedInsertRendersTheWindowLocally() {
            DetectedConflict overlap = evaluator.bayDoubleBooked(attempt(TUE_10, TUE_11, null));
            assertThat(overlap.code()).isEqualTo("BAY_DOUBLE_BOOKED");
            assertThat(overlap.detail()).contains(BAY).contains("10:00").contains("11:00");
        }
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SKILL (CAP-329)")
    class Skill {
        private static final UUID BRAKE_JOB = UUID.fromString("0196cf6f-c8dd-7ee0-93e7-f48a5698a535");
        private static final UUID DOT_INSPECTION = UUID.fromString("0196cf6f-c8dd-7ee0-93e7-f48a5698a540");
        private static final UUID UNCONFIGURED = UUID.fromString("0196cf6f-c8dd-7ee0-93e7-f48a5698a541");
        private static final UUID VEHICLE = UUID.fromString("0196cf6f-c8dd-7ee0-93e7-f48a5698a600");
        private static final UUID TECH = UUID.fromString("01960011-0000-7000-8000-0000000000e1");

        @BeforeEach
        void catalog() {
            // BRAKE-PAD-REPLACE-FRONT: A-series brakes on classes 1-3, T-series on 4-8 (one SKU, class-
            // conditional competence). DOT-ANNUAL-INSPECTION: DOT-INSPECTOR for ANY class.
            lenient()
                    .when(catalogServiceRepository.findAllByServiceIdIn(any()))
                    .thenAnswer(inv -> ((java.util.Collection<UUID>) inv.getArgument(0))
                            .stream()
                                    .filter(id -> !UNCONFIGURED.equals(id))
                                    .map(id -> ExtCatalogServiceReplica.builder()
                                            .serviceId(id)
                                            .active(true)
                                            .requirementsConfiguredAt(Instant.parse("2026-06-01T00:00:00Z"))
                                            .build())
                                    .toList());
            lenient()
                    .when(catalogServiceSkillRepository.findAllByServiceIdIn(any()))
                    .thenAnswer(inv -> {
                        java.util.Collection<UUID> ids = inv.getArgument(0);
                        List<ExtCatalogServiceSkillReplica> rows = new java.util.ArrayList<>();
                        if (ids.contains(BRAKE_JOB)) {
                            rows.add(requirement(BRAKE_JOB, "BRAKES-LIGHT", 1, 3));
                            rows.add(requirement(BRAKE_JOB, "BRAKES-MEDIUM_HEAVY", 4, 8));
                        }
                        if (ids.contains(DOT_INSPECTION)) {
                            rows.add(requirement(DOT_INSPECTION, "DOT-INSPECTOR", null, null));
                        }
                        return rows;
                    });
            // One technician rostered, holding T-series brakes (issued as ASE T4-BRAKES) through 2027.
            lenient()
                    .when(staffingRepository.findByLocationIdAndStatus(LOCATION, "ACTIVE"))
                    .thenReturn(List.of(staffingFor(TECH)));
            lenient()
                    .when(credentialRepository.findByPersonIdInOrderByIssuedOnDesc(any()))
                    .thenReturn(List.of(
                            credential(TECH, "BRAKES-MEDIUM_HEAVY", "T4-BRAKES", LocalDate.of(2027, 1, 1), "ACTIVE")));
            lenient().when(vehicleRepository.findById(VEHICLE)).thenReturn(Optional.of(vehicle(6)));
        }

        private BookingAttempt booking(UUID service, UUID vehicle) {
            return new BookingAttempt(LOCATION, BAY, TUE_10, TUE_11, null, List.of(service), vehicle);
        }

        @Test
        @DisplayName("a heavy vehicle needs T-series brakes; the rostered holder is free: nothing fires")
        void heavyVehicleWithCompetentFreeMechanicIsClean() {
            assertThat(evaluator.evaluate(booking(BRAKE_JOB, VEHICLE))).isEmpty();
        }

        @Test
        @DisplayName("the same unforked service on a light vehicle resolves to A-series, which nobody here holds")
        void lightVehicleResolvesToDifferentSkill_andItsAbsenceIsSoft() {
            when(vehicleRepository.findById(VEHICLE)).thenReturn(Optional.of(vehicle(2)));

            List<DetectedConflict> detected = evaluator.evaluate(booking(BRAKE_JOB, VEHICLE));

            assertThat(codes(detected)).containsExactly("NO_COMPETENT_MECHANIC_ROSTERED");
            assertThat(detected.get(0).isHard()).isFalse();
            assertThat(detected.get(0).detail()).contains("BRAKES-LIGHT").doesNotContain("MEDIUM_HEAVY");
        }

        @Test
        @DisplayName("class 3 is light duty under Durion's grouping and resolves to A-series (the boundary case)")
        void classThreeResolvesToLightDuty() {
            when(vehicleRepository.findById(VEHICLE)).thenReturn(Optional.of(vehicle(3)));

            assertThat(evaluator.evaluate(booking(BRAKE_JOB, VEHICLE)).get(0).detail())
                    .contains("BRAKES-LIGHT");
        }

        @Test
        @DisplayName(
                "without a vehicle only ANY-class requirements apply, and the detail says the class is undetermined")
        void noVehicleAppliesOnlyAnyClassRequirements() {
            // Brake job: both requirements are class-ranged, so nothing applies without a class.
            assertThat(evaluator.evaluate(booking(BRAKE_JOB, null))).isEmpty();
            // DOT inspection: ANY-class requirement, nobody holds it.
            List<DetectedConflict> detected = evaluator.evaluate(booking(DOT_INSPECTION, null));
            assertThat(codes(detected)).containsExactly("NO_COMPETENT_MECHANIC_ROSTERED");
            assertThat(detected.get(0).detail()).contains("DOT-INSPECTOR").contains("not determined");
        }

        @Test
        @DisplayName("a competent mechanic who is busy in the window is contention, not absence")
        void busyCompetentMechanicIsContention() {
            when(appointmentRepository.findHeldOverlappingForResource(
                            eq(TECH.toString()), eq(TUE_10), eq(TUE_11), any()))
                    .thenReturn(List.of(Appointment.builder()
                            .appointmentId(UUID.randomUUID())
                            .resourceType("TECHNICIAN")
                            .resourceId(TECH.toString())
                            .build()));

            List<DetectedConflict> detected = evaluator.evaluate(booking(BRAKE_JOB, VEHICLE));

            assertThat(codes(detected)).containsExactly("COMPETENT_MECHANIC_UNAVAILABLE");
            assertThat(detected.get(0).detail()).contains("BRAKES-MEDIUM_HEAVY");
        }

        @Test
        @DisplayName("the mechanic's own slot does not count against a reschedule")
        void rescheduleExcludesItsOwnAppointment() {
            UUID self = UUID.randomUUID();
            when(appointmentRepository.findHeldOverlappingForResource(eq(TECH.toString()), any(), any(), any()))
                    .thenReturn(List.of(Appointment.builder()
                            .appointmentId(self)
                            .resourceType("TECHNICIAN")
                            .resourceId(TECH.toString())
                            .build()));

            assertThat(evaluator.evaluate(
                            new BookingAttempt(LOCATION, BAY, TUE_10, TUE_11, self, List.of(BRAKE_JOB), VEHICLE)))
                    .isEmpty();
        }

        @Test
        @DisplayName("an expired credential does not satisfy a requirement, judged on the facility-local date")
        void expiredCredentialDoesNotCount() {
            // Expired 2026-06-15; the booking is Tuesday 2026-06-16 in Chicago.
            when(credentialRepository.findByPersonIdInOrderByIssuedOnDesc(any()))
                    .thenReturn(List.of(
                            credential(TECH, "BRAKES-MEDIUM_HEAVY", "T4-BRAKES", LocalDate.of(2026, 6, 15), "ACTIVE")));

            assertThat(codes(evaluator.evaluate(booking(BRAKE_JOB, VEHICLE))))
                    .containsExactly("NO_COMPETENT_MECHANIC_ROSTERED");
        }

        @Test
        @DisplayName("a credential expiring on the booking's local date is still held that day")
        void credentialExpiringTodayStillCounts() {
            when(credentialRepository.findByPersonIdInOrderByIssuedOnDesc(any()))
                    .thenReturn(List.of(
                            credential(TECH, "BRAKES-MEDIUM_HEAVY", "T4-BRAKES", LocalDate.of(2026, 6, 16), "ACTIVE")));

            assertThat(evaluator.evaluate(booking(BRAKE_JOB, VEHICLE))).isEmpty();
        }

        @Test
        @DisplayName("a revoked credential never counts, whatever its dates")
        void revokedCredentialDoesNotCount() {
            when(credentialRepository.findByPersonIdInOrderByIssuedOnDesc(any()))
                    .thenReturn(List.of(credential(TECH, "BRAKES-MEDIUM_HEAVY", "T4-BRAKES", null, "REVOKED")));

            assertThat(codes(evaluator.evaluate(booking(BRAKE_JOB, VEHICLE))))
                    .containsExactly("NO_COMPETENT_MECHANIC_ROSTERED");
        }

        @Test
        @DisplayName(
                "the issuer's own code satisfies a requirement written against the Durion code, case-insensitively")
        void sourceCodeMatches() {
            // doReturn: when(mock.call(any())) would invoke the class-level thenAnswer with a null argument.
            doReturn(List.of(requirement(BRAKE_JOB, "t4-brakes ", 4, 8)))
                    .when(catalogServiceSkillRepository)
                    .findAllByServiceIdIn(any());

            assertThat(evaluator.evaluate(booking(BRAKE_JOB, VEHICLE))).isEmpty();
        }

        @Test
        @DisplayName("a service whose requirements were never configured fires no competence rule")
        void unconfiguredServiceFiresNothing() {
            assertThat(evaluator.evaluate(booking(UNCONFIGURED, VEHICLE))).isEmpty();
        }

        @Test
        @DisplayName("zero technicians is MECHANIC_UNAVAILABLE and never a competence failure (#2035 answer 5)")
        void zeroTechniciansIsNotACompetenceFailure() {
            when(staffingRepository.findByLocationIdAndStatus(LOCATION, "ACTIVE"))
                    .thenReturn(List.of());

            List<DetectedConflict> detected = evaluator.evaluate(booking(DOT_INSPECTION, VEHICLE));

            assertThat(codes(detected)).containsExactly("MECHANIC_UNAVAILABLE");
            verify(credentialRepository, never()).findByPersonIdInOrderByIssuedOnDesc(any());
        }

        @Test
        @DisplayName("an attempt naming no services asks the catalog nothing")
        void noServicesAskNothing() {
            assertThat(evaluator.evaluate(attempt(TUE_10, TUE_11, null))).isEmpty();
            verify(catalogServiceRepository, never()).findAllByServiceIdIn(any());
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

        private static ExtStaffingAssignmentReplica staffingFor(UUID personId) {
            return ExtStaffingAssignmentReplica.builder()
                    .assignmentId(UUID.randomUUID())
                    .locationId(LOCATION)
                    .personId(personId)
                    .role("TECHNICIAN")
                    .status("ACTIVE")
                    .effectiveFrom(LocalDate.of(2026, 1, 1))
                    .build();
        }

        private static ExtPersonCredentialReplica credential(
                UUID personId, String skillCode, String sourceCode, LocalDate expiresOn, String status) {
            return ExtPersonCredentialReplica.builder()
                    .credentialId(UUID.randomUUID())
                    .personId(personId)
                    .skillId(UUID.nameUUIDFromBytes(skillCode.getBytes()))
                    .skillCode(skillCode)
                    .competenceCode("BRAKES")
                    .minGvwrClass(4)
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

        private static ExtVehicleReplica vehicle(int gvwrClass) {
            return ExtVehicleReplica.builder()
                    .vehicleId(VEHICLE)
                    .gvwrClass(gvwrClass)
                    .build();
        }
    }

    private static BookingAttempt attempt(Instant start, Instant end, UUID exclude) {
        return new BookingAttempt(LOCATION, BAY, start, end, exclude);
    }

    private static List<String> codes(List<DetectedConflict> detected) {
        return detected.stream().map(DetectedConflict::code).toList();
    }

    /**
     * The rule as production seeds it, template included: a refusal a caller reads is the template
     * rendered, so a test with its own paraphrase of it cannot defend what the message says
     * (#2139, #2140). Severity and the active flag stay parameters — some tests need a rule switched
     * off, which is configuration rather than wording.
     */
    private static ConflictRule rule(String code, ConflictSeverity severity, boolean active) {
        String template = SchedulingWorldFixture.messageTemplate(code);
        return ConflictRule.builder()
                .id(UUID.nameUUIDFromBytes(code.getBytes()))
                .code(code)
                .severity(severity)
                .resourceType(ConflictResourceType.BAY)
                .messageTemplate(template)
                .active(active)
                .build();
    }

    private static ExtLocationReplica location(String timezone, String hours, String closures) {
        return ExtLocationReplica.builder()
                .locationId(LOCATION)
                .timezone(timezone)
                .operatingHours(hours)
                .holidayClosures(closures)
                .active(true)
                .build();
    }

    private static ExtStaffingAssignmentReplica staffing(LocalDate from, LocalDate to) {
        return ExtStaffingAssignmentReplica.builder()
                .assignmentId(UUID.randomUUID())
                .locationId(LOCATION)
                .personId(UUID.randomUUID())
                .role("TECHNICIAN")
                .status("ACTIVE")
                .effectiveFrom(from)
                .effectiveTo(to)
                .build();
    }

    private static List<ExtBayReplica> bays(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> ExtBayReplica.builder()
                        .bayId(UUID.randomUUID())
                        .locationId(LOCATION)
                        .active(true)
                        .build())
                .collect(Collectors.toList());
    }
}
