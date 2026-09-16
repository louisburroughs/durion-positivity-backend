package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.shopmanager.internal.dto.ScheduleCapacityResponse;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.ExtBayReplica;
import com.positivity.shopmanager.internal.entity.ExtLocationReplica;
import com.positivity.shopmanager.internal.entity.ExtWorkorderReplica;
import com.positivity.shopmanager.internal.entity.WorkOrderAppointmentMapping;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.enums.ScheduleCapacityDayStatus;
import com.positivity.shopmanager.internal.exception.ScheduleCapacityRangeExceededException;
import com.positivity.shopmanager.internal.exception.ShopManagerValidationException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.jspecify.annotations.Nullable;
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
                locationId, bayId, instant(MONDAY, 10, 0), instant(MONDAY, 12, 0), AppointmentStatus.SCHEDULED);
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
    @DisplayName("#2023 F1 regression - a bay-booked appointment built the way persistAppointment "
            + "actually builds one (resourceType left null) still occupies its bay")
    void appointmentWithNullResourceTypeStillOccupiesItsBay() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        Appointment appointment = persistAppointment(
                locationId, bayId, instant(MONDAY, 10, 0), instant(MONDAY, 12, 0), AppointmentStatus.SCHEDULED);
        // The bug (#2023 F1): AppointmentsServiceImpl#persistAppointment never sets
        // resourceType, so a predicate on that column matches no real appointment. Assert the
        // fixture really is null before relying on it to prove the fix.
        assertThat(appointment.getResourceType()).isNull();
        flushAndClear();

        ScheduleCapacityResponse.BayCapacityView bay = scheduleCapacityService
                .getCapacity(locationId, MONDAY, MONDAY)
                .getDays()
                .get(0)
                .getBays()
                .get(0);

        assertThat(bay.getOccupiedMinutes())
                .as("resourceType-null appointments must still occupy their bay (#2023 F1)")
                .isEqualTo(120);
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
                locationId, bayId, instant(MONDAY, 10, 0), instant(MONDAY, 11, 0), AppointmentStatus.SCHEDULED);
        persistAppointment(
                locationId, bayId, instant(MONDAY, 10, 30), instant(MONDAY, 11, 30), AppointmentStatus.SCHEDULED);
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
                locationId, bayId, instant(MONDAY, 10, 0), instant(MONDAY, 12, 0), AppointmentStatus.CANCELLED);
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

    @Test
    @DisplayName("#2023 F1 - an appointment whose resourceId names no active bay (unassigned or a "
            + "technician lane) does not occupy any bay")
    void appointmentWithUnmatchedResourceIdDoesNotOccupyAnyBay() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        // resourceId is "UNASSIGNED" (defaultResourceId's sentinel) - not a UUID, so it cannot
        // name a bay. Must be skipped, never thrown as an error.
        em.persist(Appointment.builder()
                .status(AppointmentStatus.SCHEDULED)
                .locationId(locationId)
                .resourceId("UNASSIGNED")
                .crmCustomerId(CUSTOMER_ID)
                .crmVehicleId(VEHICLE_ID)
                .startAt(instant(MONDAY, 10, 0))
                .endAt(instant(MONDAY, 12, 0))
                .build());
        flushAndClear();

        ScheduleCapacityResponse.BayCapacityView bay = scheduleCapacityService
                .getCapacity(locationId, MONDAY, MONDAY)
                .getDays()
                .get(0)
                .getBays()
                .get(0);

        assertThat(bay.getBayId()).isEqualTo(bayId);
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

    @Test
    @DisplayName("#2023 F7 - an unrecognised dayOfWeek in operatingHours reports every date "
            + "UNAVAILABLE, never CLOSED for that weekday")
    void unrecognisedDayOfWeekMakesEveryDateUnavailableNotClosed() {
        String hoursWithBadDayOfWeek = """
                [{"dayOfWeek":"MONDAY","openTime":"08:00:00","closeTime":"17:00:00"},
                 {"dayOfWeek":"FUNDAY","openTime":"08:00:00","closeTime":"17:00:00"}]""";
        UUID locationId = persistLocation(UTC, hoursWithBadDayOfWeek, null);
        flushAndClear();

        // Monday has a perfectly good entry of its own, but a malformed hours fact is unknown for
        // the whole location (matching the producer's own all-or-nothing null-vs-list rule), not a
        // confirmed closure for just the unparsable weekday - so even Monday must not silently
        // report CLOSED or OK from a partially-trusted payload.
        ScheduleCapacityResponse response = scheduleCapacityService.getCapacity(locationId, MONDAY, TUESDAY);

        assertThat(response.getDays())
                .extracting(ScheduleCapacityResponse.DayCapacityView::getStatus)
                .as("a malformed dayOfWeek must never surface as a confirmed CLOSED weekday")
                .containsOnly(ScheduleCapacityDayStatus.UNAVAILABLE);
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
                locationId, bayId, instant(THURSDAY, 16, 0), instant(FRIDAY, 9, 0), AppointmentStatus.SCHEDULED);
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
    @DisplayName("#2023 AC8 - 42 days, 10 bays, 500 appointments completes (elapsed time logged, not gated)")
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
                    locationId, bayId, instant(date, 9, 0), instant(date, 10, 0), AppointmentStatus.SCHEDULED);
        }
        flushAndClear();

        // Warm up once so the measured call is not paying JIT/connection-pool startup cost.
        scheduleCapacityService.getCapacity(locationId, from, to);

        long startNanos = System.nanoTime();
        ScheduleCapacityResponse response = scheduleCapacityService.getCapacity(locationId, from, to);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(response.getDays()).hasSize(42);
        // #2023 S2: this stays diagnostic, not gating. A single H2 call in a shared CI runner
        // cannot establish a P95 service-boundary budget the way a hard-coded wall-clock
        // assertion implies, and it flakes under unrelated load regardless of where the threshold
        // is set - a slower number is the same flake with a longer fuse. A real AC8 verdict needs
        // repeated runs against PostgreSQL, not one H2 invocation; this test only proves the read
        // completes and records the number for a human to look at, while the bounded-statement-count
        // tests above (AC7) remain the actual gating guard against this endpoint regressing to a
        // per-day fan-out.
        System.out.println("#2023 AC8 measured elapsed ms (42 days, 10 bays, 500 appointments, diagnostic only): "
                + elapsedMillis);
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

    /**
     * Builds the row the way {@code AppointmentsServiceImpl#persistAppointment} actually does:
     * {@code resourceId} set, {@code resourceType} left null (#2023 F1). A fixture that sets
     * {@code resourceType} directly asserts a shape production never creates; bay membership must
     * resolve from {@code resourceId} alone against the active bay roster.
     */
    private Appointment persistAppointment(
            UUID locationId, UUID bayId, Instant startAt, Instant endAt, AppointmentStatus status) {
        Appointment appointment = Appointment.builder()
                .status(status)
                .locationId(locationId)
                .resourceId(bayId.toString())
                .crmCustomerId(CUSTOMER_ID)
                .crmVehicleId(VEHICLE_ID)
                .startAt(startAt)
                .endAt(endAt)
                .build();
        em.persist(appointment);
        return appointment;
    }

    /**
     * Links an appointment to a workorder the way #1658's status sync resolves through (F9), and
     * seeds the workorder's actual-time block (#2021) on the replica the capacity read joins
     * against.
     */
    private void persistWorkorderLink(
            UUID workOrderId, Appointment appointment, @Nullable Instant workStartedAt, @Nullable Instant completedAt) {
        em.persist(WorkOrderAppointmentMapping.builder()
                .workOrderId(workOrderId)
                .appointment(appointment)
                .build());
        em.persist(ExtWorkorderReplica.builder()
                .workorderId(workOrderId)
                .aggregateVersion(1)
                .updatedAt(Instant.now())
                .workStartedAt(workStartedAt)
                .completedAt(completedAt)
                .build());
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    // -------------------------------------------------------------------------
    // Actual-vs-planned occupancy and carry-over (#2021 AC1-AC6)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("#2021 AC1/AC3 - occupancy on the source day reflects the actual window, bounded to that day")
    void sourceDayOccupancyReflectsActualWindowBoundedToDay() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        Appointment appointment = persistAppointment(
                locationId, bayId, instant(MONDAY, 15, 0), instant(MONDAY, 17, 0), AppointmentStatus.SCHEDULED);
        persistWorkorderLink(UUIDv7Generator.generate(), appointment, instant(MONDAY, 15, 5), instant(MONDAY, 18, 30));
        flushAndClear();

        ScheduleCapacityResponse.BayCapacityView mondayBay = scheduleCapacityService
                .getCapacity(locationId, MONDAY, MONDAY)
                .getDays()
                .get(0)
                .getBays()
                .get(0);

        // 15:05 -> day close 17:00 = 115 minutes; the overrun past close does not double count here.
        assertThat(mondayBay.getOccupiedMinutes()).isEqualTo(115);
        assertThat(mondayBay.getCarryOverIn()).isEmpty();
    }

    @Test
    @DisplayName("#2021 AC4/AC5 - an overrun past close carries onto the next open day, netted into occupiedMinutes")
    void overrunPastCloseCarriesOverToNextOpenDayNetted() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        Appointment appointment = persistAppointment(
                locationId, bayId, instant(MONDAY, 15, 0), instant(MONDAY, 17, 0), AppointmentStatus.SCHEDULED);
        UUID workOrderId = UUIDv7Generator.generate();
        // 1.5 hours past Monday's 17:00 close.
        persistWorkorderLink(workOrderId, appointment, instant(MONDAY, 15, 5), instant(MONDAY, 18, 30));
        flushAndClear();

        ScheduleCapacityResponse response = scheduleCapacityService.getCapacity(locationId, MONDAY, TUESDAY);

        ScheduleCapacityResponse.BayCapacityView tuesdayBay =
                response.getDays().get(1).getBays().get(0);
        assertThat(tuesdayBay.getOccupiedMinutes()).isEqualTo(90);
        assertThat(tuesdayBay.getCarryOverIn()).hasSize(1);
        ScheduleCapacityResponse.CarryOverView carryOver =
                tuesdayBay.getCarryOverIn().get(0);
        assertThat(carryOver.getFromDate()).isEqualTo(MONDAY);
        assertThat(carryOver.getAppointmentId()).isEqualTo(appointment.getAppointmentId());
        assertThat(carryOver.getWorkorderId()).isEqualTo(workOrderId);
        assertThat(carryOver.getBayHours()).isEqualByComparingTo(new BigDecimal("1.5"));
        // Marked from the start of Tuesday's window (AC5 - netted, not merely annotated).
        assertThat(tuesdayBay.getOccupancy().get(0)).isEqualTo(1);
        assertThat(tuesdayBay.getOccupancy().get(1)).isEqualTo(1);
        assertThat(tuesdayBay.getOccupancy().get(2)).isEqualTo(0);
    }

    @Test
    @DisplayName("#2021 AC6 - carry-over skips a closed day and lands on the next open day")
    void overrunIntoClosedDayCarriesToNextOpenDayNotTheClosedOne() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        Appointment appointment = persistAppointment(
                locationId, bayId, instant(SATURDAY, 10, 0), instant(SATURDAY, 12, 0), AppointmentStatus.SCHEDULED);
        UUID workOrderId = UUIDv7Generator.generate();
        // Short Saturday closes at 13:00; the job actually runs to 14:00. Sunday is CLOSED
        // (WEEKDAY_HOURS has no Sunday entry), so the carry-over must land on the following Monday.
        persistWorkorderLink(workOrderId, appointment, instant(SATURDAY, 10, 5), instant(SATURDAY, 14, 0));
        LocalDate followingMonday = SATURDAY.plusDays(2);
        flushAndClear();

        ScheduleCapacityResponse response = scheduleCapacityService.getCapacity(locationId, SATURDAY, followingMonday);

        assertThat(response.getDays())
                .extracting(ScheduleCapacityResponse.DayCapacityView::getDate)
                .containsExactly(SATURDAY, SUNDAY, followingMonday);
        assertThat(response.getDays().get(1).getStatus()).isEqualTo(ScheduleCapacityDayStatus.CLOSED);
        assertThat(response.getDays().get(1).getBays()).isEmpty();

        ScheduleCapacityResponse.BayCapacityView mondayBay =
                response.getDays().get(2).getBays().get(0);
        assertThat(mondayBay.getCarryOverIn()).hasSize(1);
        ScheduleCapacityResponse.CarryOverView carryOver =
                mondayBay.getCarryOverIn().get(0);
        assertThat(carryOver.getFromDate()).isEqualTo(SATURDAY);
        assertThat(carryOver.getBayHours()).isEqualByComparingTo(new BigDecimal("1.0"));
        assertThat(mondayBay.getOccupiedMinutes()).isEqualTo(60);
    }

    @Test
    @DisplayName("#2021 AC2 - a rescheduled appointment's carry-over is computed from its current planned window")
    void rescheduledAppointmentCarryOverUsesCurrentPlannedWindow() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        // No linked workorder: the "actual or planned finish" is the planned finish, which itself
        // overruns the day's close after the appointment was moved here by a reschedule.
        Appointment appointment = persistAppointment(
                locationId, bayId, instant(MONDAY, 16, 0), instant(MONDAY, 17, 30), AppointmentStatus.SCHEDULED);
        flushAndClear();

        ScheduleCapacityResponse response = scheduleCapacityService.getCapacity(locationId, MONDAY, TUESDAY);

        ScheduleCapacityResponse.BayCapacityView tuesdayBay =
                response.getDays().get(1).getBays().get(0);
        assertThat(tuesdayBay.getCarryOverIn()).hasSize(1);
        assertThat(tuesdayBay.getCarryOverIn().get(0).getAppointmentId()).isEqualTo(appointment.getAppointmentId());
        assertThat(tuesdayBay.getCarryOverIn().get(0).getWorkorderId()).isNull();
        assertThat(tuesdayBay.getCarryOverIn().get(0).getBayHours()).isEqualByComparingTo(new BigDecimal("0.5"));
    }

    @Test
    @DisplayName("#2021 - a bay with no overrunning appointment reports no carry-over")
    void noOverrunMeansNoCarryOver() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        persistAppointment(
                locationId, bayId, instant(MONDAY, 10, 0), instant(MONDAY, 12, 0), AppointmentStatus.SCHEDULED);
        flushAndClear();

        ScheduleCapacityResponse response = scheduleCapacityService.getCapacity(locationId, MONDAY, TUESDAY);

        assertThat(response.getDays().get(1).getBays().get(0).getCarryOverIn()).isEmpty();
    }

    @Test
    @DisplayName("#2021 - the batched workorder-actuals query stays bounded across 1 vs 42 days")
    void capacityReadWithWorkorderActualsStaysBoundedAcrossRangeLength() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        Appointment appointment = persistAppointment(
                locationId, bayId, instant(MONDAY, 10, 0), instant(MONDAY, 12, 0), AppointmentStatus.SCHEDULED);
        persistWorkorderLink(UUIDv7Generator.generate(), appointment, instant(MONDAY, 10, 0), instant(MONDAY, 11, 30));
        flushAndClear();

        Statistics statistics =
                entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);

        long queriesForOneDay = measureCapacityStatements(statistics, locationId, MONDAY, MONDAY);
        long queriesForFortyTwoDays = measureCapacityStatements(statistics, locationId, MONDAY, MONDAY.plusDays(41));

        assertThat(queriesForFortyTwoDays)
                .as("42 days must not cost more queries than 1 day, even with actuals to resolve")
                .isEqualTo(queriesForOneDay);
        assertThat(queriesForOneDay)
                .as("location replica, active bays, appointments in range, batched workorder actuals: "
                        + "four fixed statements")
                .isEqualTo(4L);
    }

    // -------------------------------------------------------------------------
    // F3 - one appointment resolving more than one workorder mapping
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("#2023 F3 - an appointment with more than one workorder mapping resolves the "
            + "current one deterministically, never throws")
    void appointmentWithDuplicateMappingsResolvesCurrentOneWithoutThrowing() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        Appointment appointment = persistAppointment(
                locationId, bayId, instant(MONDAY, 10, 0), instant(MONDAY, 12, 0), AppointmentStatus.SCHEDULED);
        // A reopened work order: the earlier mapping is not deleted, so this appointment now
        // resolves two mapping rows. Only workOrderId is unique in the baseline schema (ERD:
        // appointment -> mapping is one-to-many), so Collectors.toMap must not throw on the
        // duplicate appointmentId key.
        UUID staleWorkOrderId = UUID.fromString("00000000-0000-7000-8000-000000000001");
        UUID currentWorkOrderId = UUID.fromString("00000000-0000-7000-8000-000000000002");
        persistWorkorderLink(staleWorkOrderId, appointment, instant(MONDAY, 10, 0), instant(MONDAY, 10, 30));
        persistWorkorderLink(currentWorkOrderId, appointment, instant(MONDAY, 10, 0), instant(MONDAY, 11, 45));
        flushAndClear();

        ScheduleCapacityResponse.BayCapacityView bay = scheduleCapacityService
                .getCapacity(locationId, MONDAY, MONDAY)
                .getDays()
                .get(0)
                .getBays()
                .get(0);

        // The current (greatest workOrderId) mapping's actuals win: 10:00-11:45 = 105 minutes, not
        // the stale mapping's 10:00-10:30.
        assertThat(bay.getOccupiedMinutes()).isEqualTo(105);
    }

    // -------------------------------------------------------------------------
    // F8 - an overrun longer than one operating day
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("#2023 F8 - an overrun longer than one operating day's window is distributed "
            + "across successive open days, keeping occupiedMinutes and occupancy consistent")
    void overrunLongerThanOneOperatingDayIsDistributedAcrossSuccessiveOpenDays() {
        // Tuesday and Wednesday are holidays, so Monday's next open day is Thursday, then Friday.
        // The appointment's actual finish (Tuesday 11:00) still falls inside that closed stretch —
        // chronologically before Thursday even opens - so pass 1 does not directly overlap
        // Thursday or Friday; the whole 18-hour gap between Monday's close and the actual finish
        // must be carried forward by pass 2. That is longer than one weekday's 540-minute window,
        // which is exactly the shape that exposed #2023 F8: the old code dumped the full 1080
        // minutes onto Thursday alone, disagreeing with what its 9 hourly slots could represent.
        String closures = """
                [{"date":"2026-10-06","reason":"Holiday"},{"date":"2026-10-07","reason":"Holiday"}]""";
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, closures);
        UUID bayId = persistBay("Bay 1", locationId);
        Appointment appointment = persistAppointment(
                locationId, bayId, instant(MONDAY, 15, 0), instant(MONDAY, 17, 0), AppointmentStatus.SCHEDULED);
        UUID workOrderId = UUIDv7Generator.generate();
        persistWorkorderLink(workOrderId, appointment, instant(MONDAY, 15, 5), instant(TUESDAY, 11, 0));
        flushAndClear();

        ScheduleCapacityResponse response = scheduleCapacityService.getCapacity(locationId, MONDAY, FRIDAY);

        assertThat(response.getDays())
                .extracting(ScheduleCapacityResponse.DayCapacityView::getStatus)
                .containsExactly(
                        ScheduleCapacityDayStatus.OK,
                        ScheduleCapacityDayStatus.HOLIDAY,
                        ScheduleCapacityDayStatus.HOLIDAY,
                        ScheduleCapacityDayStatus.OK,
                        ScheduleCapacityDayStatus.OK);
        ScheduleCapacityResponse.BayCapacityView thursdayBay =
                response.getDays().get(3).getBays().get(0);
        ScheduleCapacityResponse.BayCapacityView fridayBay =
                response.getDays().get(4).getBays().get(0);

        // Thursday absorbs its full 540-minute window and no more - occupiedMinutes and the
        // occupancy array agree with each other (#2023 F8): every one of its 9 hourly slots
        // occupied, never a total the slots cannot represent (9 slots x 60 minutes = 540).
        assertThat(thursdayBay.getOccupiedMinutes()).isEqualTo(540);
        assertThat(thursdayBay.getOccupancy()).containsOnly(1);
        assertThat(thursdayBay.getOccupiedMinutes())
                .as("occupiedMinutes must never exceed what the occupancy slots can represent")
                .isLessThanOrEqualTo(thursdayBay.getOccupancy().size() * 60);

        // The remaining 540 minutes (1080 - 540) roll forward to Friday, the next open day.
        assertThat(fridayBay.getOccupiedMinutes()).isEqualTo(540);
        assertThat(fridayBay.getOccupancy()).containsOnly(1);

        // Both hops carry the original source date, split into two 9-bay-hour carry-overs.
        assertThat(thursdayBay.getCarryOverIn()).hasSize(1);
        assertThat(thursdayBay.getCarryOverIn().get(0).getFromDate()).isEqualTo(MONDAY);
        assertThat(thursdayBay.getCarryOverIn().get(0).getBayHours()).isEqualByComparingTo(new BigDecimal("9.0"));
        assertThat(fridayBay.getCarryOverIn()).hasSize(1);
        assertThat(fridayBay.getCarryOverIn().get(0).getFromDate()).isEqualTo(MONDAY);
        assertThat(fridayBay.getCarryOverIn().get(0).getBayHours()).isEqualByComparingTo(new BigDecimal("9.0"));
    }
}
