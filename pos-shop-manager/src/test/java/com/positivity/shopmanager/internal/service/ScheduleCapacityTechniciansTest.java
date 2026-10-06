package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.shopmanager.internal.dto.ScheduleCapacityResponse;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.Assignment;
import com.positivity.shopmanager.internal.entity.AssignmentMechanic;
import com.positivity.shopmanager.internal.entity.ExtBayReplica;
import com.positivity.shopmanager.internal.entity.ExtLocationReplica;
import com.positivity.shopmanager.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.shopmanager.internal.entity.Mechanic;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.enums.AssignmentStatusEnum;
import com.positivity.shopmanager.internal.enums.MechanicRoleEnum;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import com.positivity.shopmanager.internal.enums.ScheduleCapacityDayStatus;
import com.positivity.shopmanager.internal.enums.ScheduleCapacityStaffingStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * The technician section of {@code GET /v1/schedules/capacity} (issue #2527): per-day duty and
 * assignment load, slotted like the bays. H2-backed like {@link ScheduleCapacityServiceTest}, since
 * the statement budget is part of the contract.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(ScheduleCapacityTechniciansTest.FixedClockConfig.class)
@DisplayName("ScheduleCapacityTechniciansTest")
class ScheduleCapacityTechniciansTest {

    static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    private static final UUID CUSTOMER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VEHICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 6);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 10, 10);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 11);

    /** Weekdays 08:00-17:00 (nine slots), Saturday a short 08:00-13:30 (six slots), Sunday closed. */
    private static final String HOURS = """
            [{"dayOfWeek":"MONDAY","openTime":"08:00:00","closeTime":"17:00:00"},
             {"dayOfWeek":"TUESDAY","openTime":"08:00:00","closeTime":"17:00:00"},
             {"dayOfWeek":"WEDNESDAY","openTime":"08:00:00","closeTime":"17:00:00"},
             {"dayOfWeek":"THURSDAY","openTime":"08:00:00","closeTime":"17:00:00"},
             {"dayOfWeek":"FRIDAY","openTime":"08:00:00","closeTime":"17:00:00"},
             {"dayOfWeek":"SATURDAY","openTime":"08:00:00","closeTime":"13:30:00"}]""";

    @Autowired
    private ScheduleCapacityService scheduleCapacityService;

    @Autowired
    private EntityManager em;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    @DisplayName("#2527 AC1 - every OK day of a 42-day range lists every rostered technician, idle ones included")
    void everyOkDayListsEveryRosteredTechnician() {
        UUID locationId = persistLocation();
        persistBay(locationId);
        UUID busy = UUIDv7Generator.generate();
        UUID idle = UUIDv7Generator.generate();
        UUID startsTuesday = UUIDv7Generator.generate();
        UUID serviceWriter = UUIDv7Generator.generate();
        rosterTechnician(busy, locationId, null, null);
        rosterTechnician(idle, locationId, null, null);
        rosterTechnician(startsTuesday, locationId, TUESDAY, null);
        roster(serviceWriter, locationId, "SERVICE_ADVISOR", null, null);
        assign(busy, persistAppointment(locationId, null, at(MONDAY, 10), at(MONDAY, 12), AppointmentStatus.SCHEDULED));
        flushAndClear();

        ScheduleCapacityResponse response =
                scheduleCapacityService.getCapacity(locationId, MONDAY, MONDAY.plusDays(41));

        assertThat(response.getStaffingStatus()).isEqualTo(ScheduleCapacityStaffingStatus.AVAILABLE);
        assertThat(response.getDays()).hasSize(42);
        for (ScheduleCapacityResponse.DayCapacityView day : response.getDays()) {
            if (day.getStatus() != ScheduleCapacityDayStatus.OK) {
                assertThat(day.getTechnicians())
                        .as("%s is not OK", day.getDate())
                        .isEmpty();
                continue;
            }
            List<UUID> expected = day.getDate().equals(MONDAY) ? sorted(busy, idle) : sorted(busy, idle, startsTuesday);
            assertThat(day.getTechnicians())
                    .as("technicians on %s", day.getDate())
                    .extracting(ScheduleCapacityResponse.TechnicianCapacityView::getMechanicPersonId)
                    .containsExactlyElementsOf(expected);
        }
        ScheduleCapacityResponse.TechnicianCapacityView idleMonday = technician(response, MONDAY, idle);
        assertThat(idleMonday.getAssignedMinutes()).isZero();
        assertThat(idleMonday.getAssigned()).containsOnly(0);
        assertThat(idleMonday.getOnDuty()).containsOnly(1);
    }

    @Test
    @DisplayName("#2527 AC2 - onDuty and assigned have as many slots as the bays' occupancy, short day included")
    void slotArraysAlignWithBayOccupancy() {
        UUID locationId = persistLocation();
        persistBay(locationId);
        UUID person = UUIDv7Generator.generate();
        rosterTechnician(person, locationId, null, null);
        flushAndClear();

        ScheduleCapacityResponse response = scheduleCapacityService.getCapacity(locationId, MONDAY, SUNDAY);

        for (ScheduleCapacityResponse.DayCapacityView day : response.getDays()) {
            if (day.getStatus() != ScheduleCapacityDayStatus.OK) {
                continue;
            }
            int slots = day.getBays().get(0).getOccupancy().size();
            ScheduleCapacityResponse.TechnicianCapacityView view =
                    day.getTechnicians().get(0);
            assertThat(view.getOnDuty()).as("onDuty on %s", day.getDate()).hasSize(slots);
            assertThat(view.getAssigned()).as("assigned on %s", day.getDate()).hasSize(slots);
        }
        assertThat(technician(response, SATURDAY, person).getOnDuty())
                .as("08:00-13:30 is six slots, the partial trailing hour included")
                .hasSize(6);
    }

    @Test
    @DisplayName(
            "#2527 AC3 - an assignment 10:00-12:00 marks the 10:00 and 11:00 slots, 120 minutes; cancelled counts nothing")
    void assignmentMarksItsHoursAndCancelledContributesNothing() {
        UUID locationId = persistLocation();
        UUID bayId = persistBay(locationId);
        UUID person = UUIDv7Generator.generate();
        rosterTechnician(person, locationId, null, null);
        Mechanic mechanic = persistMechanic(person);
        assign(
                mechanic,
                persistAppointment(locationId, bayId, at(MONDAY, 10), at(MONDAY, 12), AppointmentStatus.SCHEDULED),
                AssignmentStatusEnum.ASSIGNED);
        assign(
                mechanic,
                persistAppointment(locationId, bayId, at(MONDAY, 14), at(MONDAY, 16), AppointmentStatus.CANCELLED),
                AssignmentStatusEnum.ASSIGNED);
        assign(
                mechanic,
                persistAppointment(locationId, bayId, at(MONDAY, 15), at(MONDAY, 16), AppointmentStatus.SCHEDULED),
                AssignmentStatusEnum.CANCELLED);
        flushAndClear();

        ScheduleCapacityResponse.TechnicianCapacityView view =
                technician(scheduleCapacityService.getCapacity(locationId, MONDAY, MONDAY), MONDAY, person);

        assertThat(view.getAssigned()).containsExactly(0, 0, 1, 1, 0, 0, 0, 0, 0);
        assertThat(view.getAssignedMinutes()).isEqualTo(120);
    }

    @Test
    @DisplayName("#2527 - an appointment booked on the technician counts, and once when also assigned")
    void directBookingCountsOnceAlongsideAnAssignment() {
        UUID locationId = persistLocation();
        persistBay(locationId);
        UUID person = UUIDv7Generator.generate();
        rosterTechnician(person, locationId, null, null);
        Mechanic mechanic = persistMechanic(person);
        Appointment both =
                persistAppointment(locationId, person, at(MONDAY, 9), at(MONDAY, 10), AppointmentStatus.SCHEDULED);
        assign(mechanic, both, AssignmentStatusEnum.ASSIGNED);
        persistAppointment(locationId, person, at(MONDAY, 13), at(MONDAY, 14, 30), AppointmentStatus.SCHEDULED);
        flushAndClear();

        ScheduleCapacityResponse.TechnicianCapacityView view =
                technician(scheduleCapacityService.getCapacity(locationId, MONDAY, MONDAY), MONDAY, person);

        assertThat(view.getAssigned()).containsExactly(0, 1, 0, 0, 0, 1, 1, 0, 0);
        assertThat(view.getAssignedMinutes()).isEqualTo(150);
    }

    @Test
    @DisplayName("#2527 - assignments overlapping in one hour count each, and the window is clamped to the day")
    void overlappingAssignmentsCountEachAndClampToTheDay() {
        UUID locationId = persistLocation();
        UUID bayId = persistBay(locationId);
        UUID person = UUIDv7Generator.generate();
        rosterTechnician(person, locationId, null, null);
        Mechanic mechanic = persistMechanic(person);
        assign(
                mechanic,
                persistAppointment(locationId, bayId, at(MONDAY, 7), at(MONDAY, 9), AppointmentStatus.SCHEDULED),
                AssignmentStatusEnum.ASSIGNED);
        assign(
                mechanic,
                persistAppointment(locationId, bayId, at(MONDAY, 8, 30), at(MONDAY, 9), AppointmentStatus.SCHEDULED),
                AssignmentStatusEnum.IN_PROGRESS);
        flushAndClear();

        ScheduleCapacityResponse.TechnicianCapacityView view =
                technician(scheduleCapacityService.getCapacity(locationId, MONDAY, MONDAY), MONDAY, person);

        assertThat(view.getAssigned().get(0)).isEqualTo(2);
        assertThat(view.getAssignedMinutes()).isEqualTo(90);
    }

    @Test
    @DisplayName("#2527 AC4 - no staffing data at the location is UNAVAILABLE, never an empty roster")
    void noStaffingDataIsReportedUnavailable() {
        UUID locationId = persistLocation();
        persistBay(locationId);
        UUID elsewhere = persistLocation();
        rosterTechnician(UUIDv7Generator.generate(), elsewhere, null, null);
        flushAndClear();

        ScheduleCapacityResponse response = scheduleCapacityService.getCapacity(locationId, MONDAY, TUESDAY);

        assertThat(response.getStaffingStatus()).isEqualTo(ScheduleCapacityStaffingStatus.UNAVAILABLE);
        assertThat(response.getDays())
                .allSatisfy(day -> assertThat(day.getTechnicians()).isEmpty());
    }

    @Test
    @DisplayName("#2527 AC4 - staffing data with nobody in a technician role is AVAILABLE and empty")
    void staffedButNoTechniciansIsAvailableAndEmpty() {
        UUID locationId = persistLocation();
        persistBay(locationId);
        roster(UUIDv7Generator.generate(), locationId, "SERVICE_ADVISOR", null, null);
        flushAndClear();

        ScheduleCapacityResponse response = scheduleCapacityService.getCapacity(locationId, MONDAY, MONDAY);

        assertThat(response.getStaffingStatus()).isEqualTo(ScheduleCapacityStaffingStatus.AVAILABLE);
        assertThat(response.getDays().get(0).getTechnicians()).isEmpty();
    }

    @Test
    @DisplayName("#2527 AC6 - technicians cost two fixed statements, independent of the number of days")
    void techniciansCostTwoFixedStatements() {
        UUID locationId = persistLocation();
        UUID bayId = persistBay(locationId);
        UUID person = UUIDv7Generator.generate();
        rosterTechnician(person, locationId, null, null);
        assign(
                person,
                persistAppointment(locationId, bayId, at(MONDAY, 10), at(MONDAY, 12), AppointmentStatus.SCHEDULED));
        flushAndClear();

        Statistics statistics =
                entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);

        long oneDay = measure(statistics, locationId, MONDAY, MONDAY);
        long fortyTwoDays = measure(statistics, locationId, MONDAY, MONDAY.plusDays(41));

        assertThat(oneDay)
                .as("location, bays, appointments, workorder actuals, bays held, staffing, mechanic "
                        + "assignments: seven statements")
                .isEqualTo(7L);
        assertThat(fortyTwoDays).isEqualTo(oneDay);
    }

    // -------------------------------------------------------------------------

    private long measure(Statistics statistics, UUID locationId, LocalDate from, LocalDate to) {
        flushAndClear();
        statistics.clear();
        assertThat(scheduleCapacityService.getCapacity(locationId, from, to).getDays())
                .isNotEmpty();
        return statistics.getPrepareStatementCount();
    }

    private static List<UUID> sorted(UUID... ids) {
        List<UUID> list = new ArrayList<>(List.of(ids));
        list.sort(Comparator.comparing(UUID::toString));
        return list;
    }

    private static ScheduleCapacityResponse.TechnicianCapacityView technician(
            ScheduleCapacityResponse response, LocalDate date, UUID personId) {
        return response.getDays().stream()
                .filter(day -> day.getDate().equals(date))
                .flatMap(day -> day.getTechnicians().stream())
                .filter(view -> view.getMechanicPersonId().equals(personId))
                .findFirst()
                .orElseThrow();
    }

    private static Instant at(LocalDate date, int hour) {
        return at(date, hour, 0);
    }

    private static Instant at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).toInstant(ZoneOffset.UTC);
    }

    private UUID persistLocation() {
        UUID locationId = UUIDv7Generator.generate();
        em.persist(ExtLocationReplica.builder()
                .locationId(locationId)
                .code("LOC-" + locationId)
                .name("Test Location")
                .active(true)
                .aggregateVersion(1)
                .syncedAt(NOW)
                .timezone("UTC")
                .operatingHours(HOURS)
                .build());
        return locationId;
    }

    private UUID persistBay(UUID locationId) {
        UUID bayId = UUIDv7Generator.generate();
        em.persist(ExtBayReplica.builder()
                .bayId(bayId)
                .locationId(locationId)
                .name("Bay 1")
                .active(true)
                .aggregateVersion(1)
                .updatedAt(NOW)
                .build());
        return bayId;
    }

    /** {@code resource} is a bay, a technician's person id, or null when unassigned. */
    private Appointment persistAppointment(
            UUID locationId, @Nullable UUID resource, Instant startAt, Instant endAt, AppointmentStatus status) {
        Appointment appointment = Appointment.builder()
                .status(status)
                .locationId(locationId)
                .resourceId(resource == null ? null : resource.toString())
                .crmCustomerId(CUSTOMER_ID)
                .crmVehicleId(VEHICLE_ID)
                .startAt(startAt)
                .endAt(endAt)
                .build();
        em.persist(appointment);
        return appointment;
    }

    private void rosterTechnician(
            UUID personId, UUID locationId, @Nullable LocalDate effectiveFrom, @Nullable LocalDate effectiveTo) {
        roster(personId, locationId, "TECHNICIAN", effectiveFrom, effectiveTo);
    }

    private void roster(
            UUID personId,
            UUID locationId,
            String role,
            @Nullable LocalDate effectiveFrom,
            @Nullable LocalDate effectiveTo) {
        em.persist(ExtStaffingAssignmentReplica.builder()
                .assignmentId(UUIDv7Generator.generate())
                .employeeId(UUIDv7Generator.generate())
                .personId(personId)
                .locationId(locationId)
                .role(role)
                .primary(true)
                .status("ACTIVE")
                .effectiveFrom(effectiveFrom)
                .effectiveTo(effectiveTo)
                .aggregateVersion(1)
                .updatedAt(NOW)
                .build());
    }

    private Mechanic persistMechanic(UUID personId) {
        Mechanic mechanic = Mechanic.builder()
                .personId(personId)
                .firstName("Test")
                .lastName("Technician")
                .status(MechanicStatus.ACTIVE)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
        em.persist(mechanic);
        return mechanic;
    }

    private void assign(UUID personId, Appointment appointment) {
        assign(persistMechanic(personId), appointment, AssignmentStatusEnum.ASSIGNED);
    }

    private void assign(Mechanic mechanic, Appointment appointment, AssignmentStatusEnum status) {
        Assignment assignment = Assignment.builder()
                .appointment(appointment)
                .status(status)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
        em.persist(assignment);
        em.persist(AssignmentMechanic.builder()
                .assignment(assignment)
                .mechanic(mechanic)
                .role(MechanicRoleEnum.LEAD)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build());
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }
}
