package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.ConflictRule;
import com.positivity.shopmanager.internal.entity.ExtBayReplica;
import com.positivity.shopmanager.internal.entity.ExtLocationReplica;
import com.positivity.shopmanager.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.shopmanager.internal.enums.ConflictResourceType;
import com.positivity.shopmanager.internal.enums.ConflictSeverity;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.ConflictRuleRepository;
import com.positivity.shopmanager.internal.repository.ExtBayReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtLocationReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtStaffingAssignmentReplicaRepository;
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
            "FACILITY_NEAR_CAPACITY", ConflictSeverity.SOFT);

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

    private SchedulingConflictEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new SchedulingConflictEvaluator(
                conflictRuleRepository,
                extLocationReplicaRepository,
                new LocationHoursParser(new ObjectMapper()),
                appointmentRepository,
                staffingRepository,
                extBayReplicaRepository);
        lenient().when(conflictRuleRepository.findByCode(anyString())).thenAnswer(invocation -> {
            String code = invocation.getArgument(0);
            ConflictSeverity severity = CATALOG.get(code);
            return severity == null ? Optional.empty() : Optional.of(rule(code, severity, true));
        });
        // The happy shop: open, bay free, one mechanic rostered, ten bays.
        lenient().when(extLocationReplicaRepository.findById(LOCATION)).thenReturn(Optional.of(location(CHICAGO, HOURS, CLOSURES)));
        lenient().when(appointmentRepository.findHeldOverlappingForResource(any(), any(), any(), any())).thenReturn(List.of());
        lenient().when(appointmentRepository.findHeldOverlappingAtLocation(any(), any(), any(), any())).thenReturn(List.of());
        lenient().when(staffingRepository.findByLocationIdAndStatus(LOCATION, "ACTIVE"))
                .thenReturn(List.of(staffing(LocalDate.of(2026, 1, 1), null)));
        lenient().when(extBayReplicaRepository.findActiveByLocationOrdered(LOCATION)).thenReturn(bays(10));
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
        void spanningLocalMidnightIsOutsideOperatingHours() {
            // Tuesday 23:30 → Wednesday 00:30 Chicago.
            Instant tue2330 = Instant.parse("2026-06-17T04:30:00Z");
            assertThat(codes(evaluator.evaluate(attempt(tue2330, tue2330.plusSeconds(3600), null))))
                    .containsExactly("OUTSIDE_OPERATING_HOURS");
        }

        @Test
        void hoursNeverPublishedFireNoHoursRule_andTheRestStillRuns() {
            when(extLocationReplicaRepository.findById(LOCATION)).thenReturn(Optional.of(location(CHICAGO, null, null)));
            Instant sun10 = Instant.parse("2026-06-14T15:00:00Z");

            assertThat(evaluator.evaluate(attempt(sun10, sun10.plusSeconds(3600), null))).isEmpty();
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
                    .thenReturn(List.of(Appointment.builder().appointmentId(UUID.randomUUID()).build()));

            List<DetectedConflict> detected = evaluator.evaluate(attempt(TUE_10, TUE_11, null));

            assertThat(codes(detected)).containsExactly("BAY_DOUBLE_BOOKED");
            assertThat(detected.get(0).resourceId()).isEqualTo(BAY);
            assertThat(detected.get(0).detail()).contains(BAY).contains("10:00").contains("11:00");
        }

        @Test
        void theAppointmentBeingRescheduledDoesNotCountAgainstItself() {
            when(appointmentRepository.findHeldOverlappingForResource(eq(BAY), eq(TUE_10), eq(TUE_11), any()))
                    .thenReturn(List.of(Appointment.builder().appointmentId(SELF).build()));

            assertThat(evaluator.evaluate(attempt(TUE_10, TUE_11, SELF))).isEmpty();
        }

        @Test
        void unassignedOrBlankResourceIsNotABay() {
            assertThat(evaluator.evaluate(new BookingAttempt(LOCATION, "UNASSIGNED", TUE_10, TUE_11, null))).isEmpty();
            assertThat(evaluator.evaluate(new BookingAttempt(LOCATION, null, TUE_10, TUE_11, null))).isEmpty();
            verify(appointmentRepository, never()).findHeldOverlappingForResource(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("MECHANIC")
    class Mechanic {
        @Test
        void nobodyRosteredAtTheLocationIsMechanicUnavailable() {
            when(staffingRepository.findByLocationIdAndStatus(LOCATION, "ACTIVE")).thenReturn(List.of());
            assertThat(codes(evaluator.evaluate(attempt(TUE_10, TUE_11, null)))).containsExactly("MECHANIC_UNAVAILABLE");
        }

        @Test
        void anAssignmentThatEndedBeforeTheDayDoesNotCount() {
            when(staffingRepository.findByLocationIdAndStatus(LOCATION, "ACTIVE"))
                    .thenReturn(List.of(staffing(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 15))));
            assertThat(codes(evaluator.evaluate(attempt(TUE_10, TUE_11, null)))).containsExactly("MECHANIC_UNAVAILABLE");
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
                    .thenReturn(List.of(Appointment.builder().appointmentId(UUID.randomUUID()).build()));

            List<DetectedConflict> detected = evaluator.evaluate(attempt(TUE_10, TUE_11, null));

            assertThat(codes(detected)).containsExactly("FACILITY_NEAR_CAPACITY");
            assertThat(detected.get(0).isHard()).isFalse();
        }

        @Test
        void oneOfTenIsNot() {
            when(appointmentRepository.findHeldOverlappingAtLocation(eq(LOCATION), eq(TUE_10), eq(TUE_11), any()))
                    .thenReturn(List.of(Appointment.builder().appointmentId(UUID.randomUUID()).build()));
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
            when(staffingRepository.findByLocationIdAndStatus(LOCATION, "ACTIVE")).thenReturn(List.of());

            assertThat(evaluator.evaluate(attempt(TUE_10, TUE_11, null))).isEmpty();
        }

        @Test
        void aRuleMissingFromTheSeedIsADeploymentDefect() {
            when(conflictRuleRepository.findByCode("MECHANIC_UNAVAILABLE")).thenReturn(Optional.empty());
            when(staffingRepository.findByLocationIdAndStatus(LOCATION, "ACTIVE")).thenReturn(List.of());

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

    private static BookingAttempt attempt(Instant start, Instant end, UUID exclude) {
        return new BookingAttempt(LOCATION, BAY, start, end, exclude);
    }

    private static List<String> codes(List<DetectedConflict> detected) {
        return detected.stream().map(DetectedConflict::code).toList();
    }

    private static ConflictRule rule(String code, ConflictSeverity severity, boolean active) {
        String template =
                switch (code) {
                    case "FACILITY_CLOSED" -> "The location is closed on {date}{reason}.";
                    case "BAY_DOUBLE_BOOKED" -> "Bay {resource} is already booked for part of {start}–{end}.";
                    default -> code + " {start}–{end}";
                };
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
                .status("ACTIVE")
                .effectiveFrom(from)
                .effectiveTo(to)
                .build();
    }

    private static List<ExtBayReplica> bays(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> ExtBayReplica.builder().bayId(UUID.randomUUID()).locationId(LOCATION).active(true).build())
                .collect(Collectors.toList());
    }
}
