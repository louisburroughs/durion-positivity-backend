package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.shopmanager.internal.dto.ScheduleCapacityResponse;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.ExtBayReplica;
import com.positivity.shopmanager.internal.entity.ExtLocationReplica;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.enums.ScheduleCapacityDayStatus;
import com.positivity.shopmanager.internal.exception.ScheduleCapacityRangeExceededException;
import com.positivity.shopmanager.internal.exception.ShopManagerValidationException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence-backed tests for {@code GET /v1/schedules/capacity} (issue #2023).
 *
 * <p>Like {@link ShopDashboardServiceTest}, this reads a join across replica tables with a query
 * budget it must not exceed, so it runs against H2 rather than mocked repositories.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("ScheduleCapacityServiceTest")
class ScheduleCapacityServiceTest {

    private static final UUID CUSTOMER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VEHICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String UTC = "UTC";

    // Monday..Sunday of one fixed week, so day-of-week arithmetic in tests is not re-derived.
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 6);
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 7);
    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 8);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 9);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 10, 10);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 11);

    private static final String WEEKDAY_HOURS = """
            [{"dayOfWeek":"MONDAY","openTime":"08:00:00","closeTime":"17:00:00"},
             {"dayOfWeek":"TUESDAY","openTime":"08:00:00","closeTime":"17:00:00"},
             {"dayOfWeek":"WEDNESDAY","openTime":"08:00:00","closeTime":"17:00:00"},
             {"dayOfWeek":"THURSDAY","openTime":"08:00:00","closeTime":"17:00:00"},
             {"dayOfWeek":"FRIDAY","openTime":"08:00:00","closeTime":"17:00:00"},
             {"dayOfWeek":"SATURDAY","openTime":"08:00:00","closeTime":"13:00:00"}]""";

    @Autowired
    private ScheduleCapacityService scheduleCapacityService;

    @Autowired
    private EntityManager em;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    // -------------------------------------------------------------------------
    // OK day, occupancy and occupiedMinutes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("#2023 - an OK day computes hourly occupancy and real-overlap occupiedMinutes")
    void singleOpenDayComputesOccupancyAndMinutes() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        persistAppointment(
                locationId, bayId, "BAY", instant(MONDAY, 10, 0), instant(MONDAY, 12, 0), AppointmentStatus.SCHEDULED);
        flushAndClear();

        ScheduleCapacityResponse response = scheduleCapacityService.getCapacity(locationId, MONDAY, MONDAY);

        assertThat(response.getDays()).hasSize(1);
        ScheduleCapacityResponse.DayCapacityView day = response.getDays().get(0);
        assertThat(day.getStatus()).isEqualTo(ScheduleCapacityDayStatus.OK);
        assertThat(day.getDayStartAt()).isEqualTo(instant(MONDAY, 8, 0));
        assertThat(day.getDayEndAt()).isEqualTo(instant(MONDAY, 17, 0));
        assertThat(day.getBays()).hasSize(1);
        ScheduleCapacityResponse.BayCapacityView bay = day.getBays().get(0);
        assertThat(bay.getBayId()).isEqualTo(bayId);
        assertThat(bay.getOccupiedMinutes()).isEqualTo(120);
        // 08-17 is a 9-hour window -> 9 slots; 10:00-12:00 covers slots index 2 (10-11) and 3 (11-12).
        assertThat(bay.getOccupancy()).hasSize(9);
        assertThat(bay.getOccupancy()).containsExactly(0, 0, 1, 1, 0, 0, 0, 0, 0);
    }

    @Test
    @DisplayName("#2023 AC9 - a bay with zero appointments is still listed, with occupiedMinutes 0")
    void emptyBayIsListedWithZeroOccupiedMinutes() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Empty Bay", locationId);
        flushAndClear();

        ScheduleCapacityResponse response = scheduleCapacityService.getCapacity(locationId, MONDAY, MONDAY);

        ScheduleCapacityResponse.BayCapacityView bay =
                response.getDays().get(0).getBays().get(0);
        assertThat(bay.getBayId()).isEqualTo(bayId);
        assertThat(bay.getOccupiedMinutes()).isZero();
        assertThat(bay.getOccupancy()).containsOnly(0);
    }

    @Test
    @DisplayName("#2023 - overlapping appointments in the same bay/hour push occupancy above 1")
    void doubleBookingCountsGreaterThanOneInOverlappingSlot() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        persistAppointment(
                locationId, bayId, "BAY", instant(MONDAY, 10, 0), instant(MONDAY, 11, 0), AppointmentStatus.SCHEDULED);
        persistAppointment(
                locationId,
                bayId,
                "BAY",
                instant(MONDAY, 10, 30),
                instant(MONDAY, 11, 30),
                AppointmentStatus.SCHEDULED);
        flushAndClear();

        ScheduleCapacityResponse.BayCapacityView bay = scheduleCapacityService
                .getCapacity(locationId, MONDAY, MONDAY)
                .getDays()
                .get(0)
                .getBays()
                .get(0);

        // slot index 2 is 10:00-11:00, overlapped by both appointments.
        assertThat(bay.getOccupancy().get(2)).isEqualTo(2);
    }

    @Test
    @DisplayName("#2023 - a CANCELLED appointment does not occupy a bay")
    void cancelledAppointmentDoesNotOccupyBay() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        persistAppointment(
                locationId, bayId, "BAY", instant(MONDAY, 10, 0), instant(MONDAY, 12, 0), AppointmentStatus.CANCELLED);
        flushAndClear();

        ScheduleCapacityResponse.BayCapacityView bay = scheduleCapacityService
                .getCapacity(locationId, MONDAY, MONDAY)
                .getDays()
                .get(0)
                .getBays()
                .get(0);

        assertThat(bay.getOccupiedMinutes()).isZero();
        assertThat(bay.getOccupancy()).containsOnly(0);
    }

    // -------------------------------------------------------------------------
    // Day status precedence: CLOSED, HOLIDAY, short Saturday, unassemblable
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("#2023 AC6 - a date with no operatingHours entry for its day-of-week is CLOSED")
    void closedDayHasNoBays() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        flushAndClear();

        ScheduleCapacityResponse.DayCapacityView day = scheduleCapacityService
                .getCapacity(locationId, SUNDAY, SUNDAY)
                .getDays()
                .get(0);

        assertThat(day.getStatus()).isEqualTo(ScheduleCapacityDayStatus.CLOSED);
        assertThat(day.getBays()).isEmpty();
        assertThat(day.getDayStartAt()).isNull();
        assertThat(day.getClosureReason()).isNull();
    }

    @Test
    @DisplayName("#2023 AC6 - a holidayClosures entry wins over an otherwise-open weekday")
    void holidayOverridesOtherwiseOpenWeekday() {
        String closures = """
                [{"date":"2026-10-07","reason":"Test Holiday"}]""";
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, closures);
        flushAndClear();

        ScheduleCapacityResponse.DayCapacityView day = scheduleCapacityService
                .getCapacity(locationId, WEDNESDAY, WEDNESDAY)
                .getDays()
                .get(0);

        assertThat(day.getStatus()).isEqualTo(ScheduleCapacityDayStatus.HOLIDAY);
        assertThat(day.getClosureReason()).isEqualTo("Test Holiday");
        assertThat(day.getBays()).isEmpty();
    }

    @Test
    @DisplayName("#2023 AC6 - a short Saturday gets a five-hour window with five hourly slots, not nine")
    void shortSaturdayWindowHasFiveHourlySlots() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        flushAndClear();

        ScheduleCapacityResponse.DayCapacityView day = scheduleCapacityService
                .getCapacity(locationId, SATURDAY, SATURDAY)
                .getDays()
                .get(0);

        assertThat(day.getStatus()).isEqualTo(ScheduleCapacityDayStatus.OK);
        assertThat(day.getDayStartAt()).isEqualTo(instant(SATURDAY, 8, 0));
        assertThat(day.getDayEndAt()).isEqualTo(instant(SATURDAY, 13, 0));
        assertThat(day.getBays().get(0).getOccupancy()).hasSize(5);
        assertThat(bayId).isNotNull();
    }

    @Test
    @DisplayName("#2023 AC4 - a date with a malformed hours entry is UNAVAILABLE; other dates are unaffected")
    void dayWithMalformedHoursEntryIsUnavailableButOthersAreNot() {
        String hoursWithBrokenTuesday = """
                [{"dayOfWeek":"MONDAY","openTime":"08:00:00","closeTime":"17:00:00"},
                 {"dayOfWeek":"TUESDAY","openTime":"08:00:00","closeTime":null}]""";
        UUID locationId = persistLocation(UTC, hoursWithBrokenTuesday, null);
        flushAndClear();

        ScheduleCapacityResponse response = scheduleCapacityService.getCapacity(locationId, MONDAY, TUESDAY);

        assertThat(response.getDays()).hasSize(2);
        assertThat(response.getDays().get(0).getStatus()).isEqualTo(ScheduleCapacityDayStatus.OK);
        assertThat(response.getDays().get(1).getStatus()).isEqualTo(ScheduleCapacityDayStatus.UNAVAILABLE);
        assertThat(response.getDays().get(1).getBays()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Cross-day appointments
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("#2023 AC13 - an appointment spanning two days is counted in each day's own window")
    void appointmentSpanningTwoDaysIsCountedInEachDaysOwnWindow() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        // Thursday 16:00 through Friday 09:00 - overlaps the tail of Thursday's window and the
        // head of Friday's window, one hour each.
        persistAppointment(
                locationId, bayId, "BAY", instant(THURSDAY, 16, 0), instant(FRIDAY, 9, 0), AppointmentStatus.SCHEDULED);
        flushAndClear();

        ScheduleCapacityResponse response = scheduleCapacityService.getCapacity(locationId, THURSDAY, FRIDAY);

        ScheduleCapacityResponse.BayCapacityView thursdayBay =
                response.getDays().get(0).getBays().get(0);
        ScheduleCapacityResponse.BayCapacityView fridayBay =
                response.getDays().get(1).getBays().get(0);
        assertThat(thursdayBay.getOccupiedMinutes()).isEqualTo(60);
        assertThat(fridayBay.getOccupiedMinutes()).isEqualTo(60);
        // Thursday's last slot (16:00-17:00) and Friday's first slot (08:00-09:00).
        assertThat(thursdayBay.getOccupancy().get(thursdayBay.getOccupancy().size() - 1))
                .isEqualTo(1);
        assertThat(fridayBay.getOccupancy().get(0)).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Unknown location / unknown timezone / never-configured hours (AC11)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("#2023 AC11 - a location whose replica has no hours yet is UNAVAILABLE, even with a known timezone")
    void locationWithNoHoursConfiguredIsUnavailable() {
        UUID locationId = persistLocation(UTC, null, null);
        flushAndClear();

        ScheduleCapacityResponse response = scheduleCapacityService.getCapacity(locationId, MONDAY, TUESDAY);

        assertThat(response.getTimezone()).isEqualTo(UTC);
        assertThat(response.getDays())
                .extracting(ScheduleCapacityResponse.DayCapacityView::getStatus)
                .containsOnly(ScheduleCapacityDayStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("#2023 AC11 - a location with an unrecognised timezone is UNAVAILABLE, never silently UTC")
    void locationWithUnknownTimezoneIsUnavailable() {
        UUID locationId = persistLocation("Not/AZone", WEEKDAY_HOURS, null);
        flushAndClear();

        ScheduleCapacityResponse response = scheduleCapacityService.getCapacity(locationId, MONDAY, MONDAY);

        assertThat(response.getTimezone()).isNull();
        assertThat(response.getDays().get(0).getStatus()).isEqualTo(ScheduleCapacityDayStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("#2023 AC4 - a location with no replica row at all reports every date UNAVAILABLE, never omitted")
    void noReplicaRowIsUnavailableForEveryDate() {
        UUID unknownLocationId = UUIDv7Generator.generate();

        ScheduleCapacityResponse response = scheduleCapacityService.getCapacity(unknownLocationId, MONDAY, TUESDAY);

        assertThat(response.getTimezone()).isNull();
        assertThat(response.getDays()).hasSize(2);
        assertThat(response.getDays())
                .extracting(ScheduleCapacityResponse.DayCapacityView::getStatus)
                .containsOnly(ScheduleCapacityDayStatus.UNAVAILABLE);
    }

    // -------------------------------------------------------------------------
    // Range validation (AC1, AC3)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("#2023 AC3 - to before from is a syntactic validation failure")
    void toBeforeFromThrowsValidationException() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);

        assertThatThrownBy(() -> scheduleCapacityService.getCapacity(locationId, TUESDAY, MONDAY))
                .isInstanceOf(ShopManagerValidationException.class);
    }

    @Test
    @DisplayName("#2023 AC1 - a 42-day range is accepted and returns exactly 42 days, one call")
    void fortyTwoDayRangeIsAccepted() {
        UUID locationId = persistLocation(UTC, null, null);
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 11, 11);

        ScheduleCapacityResponse response = scheduleCapacityService.getCapacity(locationId, from, to);

        assertThat(response.getDays()).hasSize(42);
    }

    @Test
    @DisplayName("#2023 AC3 - a 43-day range exceeds the policy limit and is rejected")
    void fortyThreeDayRangeIsRejected() {
        UUID locationId = persistLocation(UTC, null, null);
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 11, 12);

        assertThatThrownBy(() -> scheduleCapacityService.getCapacity(locationId, from, to))
                .isInstanceOf(ScheduleCapacityRangeExceededException.class);
    }

    // -------------------------------------------------------------------------
    // AC7 - the query budget does not grow with the number of days
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("#2023 AC7 - the capacity read costs a fixed number of queries regardless of range length")
    void capacityReadUsesBoundedQueryCount() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        persistBay("Bay 1", locationId);
        flushAndClear();

        Statistics statistics =
                entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);

        long queriesForOneDay = measureCapacityStatements(statistics, locationId, MONDAY, MONDAY);
        long queriesForFortyTwoDays = measureCapacityStatements(statistics, locationId, MONDAY, MONDAY.plusDays(41));

        assertThat(queriesForFortyTwoDays)
                .as("42 days must not cost more queries than 1 day")
                .isEqualTo(queriesForOneDay);
        assertThat(queriesForOneDay)
                .as("location replica, active bays, appointments in range: three fixed statements")
                .isEqualTo(3L);
    }

    private long measureCapacityStatements(Statistics statistics, UUID locationId, LocalDate from, LocalDate to) {
        flushAndClear();
        statistics.clear();
        ScheduleCapacityResponse response = scheduleCapacityService.getCapacity(locationId, from, to);
        assertThat(response.getDays()).isNotEmpty();
        return statistics.getPrepareStatementCount();
    }

    // -------------------------------------------------------------------------
    // AC8 - performance budget
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("#2023 AC8 - 42 days, 10 bays, 500 appointments returns within the stated budget")
    void largeRangeMeetsPerformanceBudget() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        List<UUID> bayIds = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            bayIds.add(persistBay("Bay " + i, locationId));
        }
        LocalDate from = MONDAY;
        LocalDate to = MONDAY.plusDays(41);
        for (int i = 0; i < 500; i++) {
            UUID bayId = bayIds.get(i % bayIds.size());
            LocalDate date = from.plusDays(i % 42);
            persistAppointment(
                    locationId, bayId, "BAY", instant(date, 9, 0), instant(date, 10, 0), AppointmentStatus.SCHEDULED);
        }
        flushAndClear();

        // Warm up once so the measured call is not paying JIT/connection-pool startup cost.
        scheduleCapacityService.getCapacity(locationId, from, to);

        long startNanos = System.nanoTime();
        ScheduleCapacityResponse response = scheduleCapacityService.getCapacity(locationId, from, to);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(response.getDays()).hasSize(42);
        System.out.println("#2023 AC8 measured elapsed ms (42 days, 10 bays, 500 appointments): " + elapsedMillis);
        assertThat(elapsedMillis).as("AC8 budget: P95 < 500ms").isLessThan(500);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Instant instant(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(ZoneId.of(UTC)).toInstant();
    }

    private UUID persistLocation(String timezone, String operatingHoursJson, String holidayClosuresJson) {
        UUID locationId = UUIDv7Generator.generate();
        em.persist(ExtLocationReplica.builder()
                .locationId(locationId)
                .code("LOC-" + locationId)
                .name("Test Location")
                .active(true)
                .aggregateVersion(1)
                .syncedAt(Instant.now())
                .timezone(timezone)
                .operatingHours(operatingHoursJson)
                .holidayClosures(holidayClosuresJson)
                .build());
        return locationId;
    }

    private UUID persistBay(String name, UUID locationId) {
        UUID bayId = UUIDv7Generator.generate();
        em.persist(ExtBayReplica.builder()
                .bayId(bayId)
                .locationId(locationId)
                .name(name)
                .active(true)
                .aggregateVersion(1)
                .updatedAt(Instant.now())
                .build());
        return bayId;
    }

    private void persistAppointment(
            UUID locationId,
            UUID bayId,
            String resourceType,
            Instant startAt,
            Instant endAt,
            AppointmentStatus status) {
        em.persist(Appointment.builder()
                .status(status)
                .locationId(locationId)
                .resourceId(bayId.toString())
                .resourceType(resourceType)
                .crmCustomerId(CUSTOMER_ID)
                .crmVehicleId(VEHICLE_ID)
                .startAt(startAt)
                .endAt(endAt)
                .build());
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }
}
