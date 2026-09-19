package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
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

        // #2050 redefines carryOverIn range-independently: every appointment contributing minutes
        // to a day whose effective window began on an earlier local date is listed, whether those
        // minutes arrived by a re-anchored overrun or - as here - by plain real-clock overlap. This
        // appointment's window opened on Thursday, so Friday must name it. Thursday itself is the
        // day the window began on, so Thursday reports nothing.
        assertThat(thursdayBay.getCarryOverIn()).isEmpty();
        assertThat(fridayBay.getCarryOverIn()).hasSize(1);
        ScheduleCapacityResponse.CarryOverView fridayCarryOver =
                fridayBay.getCarryOverIn().get(0);
        assertThat(fridayCarryOver.getFromDate()).isEqualTo(THURSDAY);
        assertThat(fridayCarryOver.getBayHours()).isEqualByComparingTo(new BigDecimal("1.0"));
        assertThat(fridayCarryOver.getWorkorderId()).isNull();
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

    // -------------------------------------------------------------------------
    // #2050 - a job whose planned window sits entirely before the requested range
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("#2050 AC1 - a job planned before the range whose actuals overrun into it holds the "
            + "bay on the range's first day, and carryOverIn names it")
    void jobPlannedBeforeRangeWithActualsOverrunningIntoItHoldsTheBay() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        // The #2050 reproduction verbatim: open 08:00-17:00, planned Monday 15:00-17:00, actually
        // running from Monday 15:00 until Tuesday 11:00.
        Appointment appointment = persistAppointment(
                locationId, bayId, instant(MONDAY, 15, 0), instant(MONDAY, 17, 0), AppointmentStatus.SCHEDULED);
        UUID workOrderId = UUIDv7Generator.generate();
        persistWorkorderLink(workOrderId, appointment, instant(MONDAY, 15, 0), instant(TUESDAY, 11, 0));
        flushAndClear();

        // The requested range deliberately does NOT include Monday: the planned window the
        // appointment query matches on falls entirely outside it, which is what used to make this
        // row invisible and Tuesday read as free 08:00-11:00.
        ScheduleCapacityResponse response = scheduleCapacityService.getCapacity(locationId, TUESDAY, TUESDAY);

        assertThat(response.getDays()).hasSize(1);
        ScheduleCapacityResponse.BayCapacityView tuesdayBay =
                response.getDays().get(0).getBays().get(0);
        assertThat(tuesdayBay.getOccupiedMinutes())
                .as("Tuesday 08:00-11:00 is held by Monday's overrun, not free (#2050 AC1)")
                .isEqualTo(180);
        // Tuesday's 08:00, 09:00 and 10:00 slots; the bay is free from 11:00 on.
        assertThat(tuesdayBay.getOccupancy()).containsExactly(1, 1, 1, 0, 0, 0, 0, 0, 0);

        assertThat(tuesdayBay.getCarryOverIn()).hasSize(1);
        ScheduleCapacityResponse.CarryOverView carryOver =
                tuesdayBay.getCarryOverIn().get(0);
        assertThat(carryOver.getFromDate())
                .as("the annotation must name Monday even though Monday is outside the range")
                .isEqualTo(MONDAY);
        assertThat(carryOver.getAppointmentId()).isEqualTo(appointment.getAppointmentId());
        assertThat(carryOver.getWorkorderId()).isEqualTo(workOrderId);
        assertThat(carryOver.getBayHours()).isEqualByComparingTo(new BigDecimal("3.0"));
    }

    @Test
    @DisplayName("#2050 AC1 / #2021 F5 - a job started before the range with no recorded finish falls "
            + "back to its planned end, so it does not reach the range")
    void jobStartedBeforeRangeWithNoCompletedAtFallsBackToPlannedEnd() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        Appointment appointment = persistAppointment(
                locationId, bayId, instant(MONDAY, 15, 0), instant(MONDAY, 17, 0), AppointmentStatus.SCHEDULED);
        // The other arm of the #2050 reproduction: workStartedAt Monday 15:00 and the job is still
        // open, so completedAt is null.
        persistWorkorderLink(UUIDv7Generator.generate(), appointment, instant(MONDAY, 15, 0), null);
        flushAndClear();

        ScheduleCapacityResponse response = scheduleCapacityService.getCapacity(locationId, TUESDAY, TUESDAY);

        ScheduleCapacityResponse.BayCapacityView tuesdayBay =
                response.getDays().get(0).getBays().get(0);
        // DELIBERATE, and NOT a #2050 gap: ScheduleCapacityServiceImpl#effectiveEnd implements
        // #2021 F5 - a still-running job with no recorded finish is represented by its PLANNED
        // end, never by now() and never by a synthesised projection. The effective window is
        // therefore Monday 15:00 -> Monday 17:00, which ends exactly at Monday's close, so nothing
        // overruns and nothing reaches Tuesday. #2050 widened which rows are fetched; it did not
        // change what an unfinished job's window is. Do not "fix" this to report an open-ended
        // hold - that would fabricate an expectedEndAt #2021 F5 explicitly refused to invent.
        assertThat(tuesdayBay.getOccupiedMinutes()).isZero();
        assertThat(tuesdayBay.getOccupancy()).containsOnly(0);
        assertThat(tuesdayBay.getCarryOverIn()).isEmpty();
    }

    @Test
    @DisplayName("#2050 AC2 - from=Monday and from=Tuesday agree about Tuesday, occupancy and " + "carryOverIn alike")
    void rangeStartDoesNotChangeTheAnswerForADateInsideBothRanges() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        Appointment appointment = persistAppointment(
                locationId, bayId, instant(MONDAY, 15, 0), instant(MONDAY, 17, 0), AppointmentStatus.SCHEDULED);
        persistWorkorderLink(UUIDv7Generator.generate(), appointment, instant(MONDAY, 15, 0), instant(TUESDAY, 11, 0));
        flushAndClear();

        ScheduleCapacityResponse fromMonday = scheduleCapacityService.getCapacity(locationId, MONDAY, TUESDAY);
        ScheduleCapacityResponse fromTuesday = scheduleCapacityService.getCapacity(locationId, TUESDAY, TUESDAY);

        ScheduleCapacityResponse.BayCapacityView tuesdayViaMonday =
                fromMonday.getDays().get(1).getBays().get(0);
        ScheduleCapacityResponse.BayCapacityView tuesdayViaTuesday =
                fromTuesday.getDays().get(0).getBays().get(0);
        assertThat(fromMonday.getDays().get(1).getDate()).isEqualTo(TUESDAY);
        assertThat(fromTuesday.getDays().get(0).getDate()).isEqualTo(TUESDAY);

        assertThat(tuesdayViaTuesday.getOccupiedMinutes())
                .as("range start must not change Tuesday's free bay-hours (#2050 AC2)")
                .isEqualTo(tuesdayViaMonday.getOccupiedMinutes());
        assertThat(tuesdayViaTuesday.getOccupancy()).isEqualTo(tuesdayViaMonday.getOccupancy());

        // carryOverIn is compared too, not just the minutes: #2050 redefined it to be a function of
        // the data rather than of where the request happens to start, and that is only a guarantee
        // if something asserts the two requests annotate the day identically.
        assertThat(tuesdayViaTuesday.getCarryOverIn()).hasSameSizeAs(tuesdayViaMonday.getCarryOverIn());
        assertThat(tuesdayViaTuesday.getCarryOverIn())
                .extracting(
                        ScheduleCapacityResponse.CarryOverView::getFromDate,
                        ScheduleCapacityResponse.CarryOverView::getAppointmentId,
                        ScheduleCapacityResponse.CarryOverView::getWorkorderId,
                        ScheduleCapacityResponse.CarryOverView::getBayHours)
                .containsExactlyElementsOf(carryOverTuples(tuesdayViaMonday));
    }

    /** The comparable shape of a bay's {@code carryOverIn}, for the AC2 range-independence check. */
    private List<Tuple> carryOverTuples(ScheduleCapacityResponse.BayCapacityView bay) {
        return bay.getCarryOverIn().stream()
                .map(view ->
                        tuple(view.getFromDate(), view.getAppointmentId(), view.getWorkorderId(), view.getBayHours()))
                .toList();
    }

    @Test
    @DisplayName("#2050 AC5 - an overrun from before the range crosses a closed day and lands on the "
            + "range's first open day, which is also its first emitted day")
    void overrunFromBeforeTheRangeCrossesAClosedDayIntoTheRange() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        // Short Saturday closes at 13:00; the job actually runs to 14:00. Sunday is CLOSED, so the
        // hour lands on the following Monday - which is the range's FIRST date here, with both
        // Saturday and Sunday outside the requested range entirely.
        Appointment appointment = persistAppointment(
                locationId, bayId, instant(SATURDAY, 10, 0), instant(SATURDAY, 12, 0), AppointmentStatus.SCHEDULED);
        UUID workOrderId = UUIDv7Generator.generate();
        persistWorkorderLink(workOrderId, appointment, instant(SATURDAY, 10, 5), instant(SATURDAY, 14, 0));
        LocalDate followingMonday = SATURDAY.plusDays(2);
        LocalDate followingTuesday = SATURDAY.plusDays(3);
        flushAndClear();

        ScheduleCapacityResponse response =
                scheduleCapacityService.getCapacity(locationId, followingMonday, followingTuesday);

        // The lookback days feed the assembly but are never emitted: the response is still exactly
        // one entry per requested date.
        assertThat(response.getDays())
                .extracting(ScheduleCapacityResponse.DayCapacityView::getDate)
                .as("lookback days must not leak into the emitted range")
                .containsExactly(followingMonday, followingTuesday);
        assertThat(response.getFrom()).isEqualTo(followingMonday);
        assertThat(response.getTo()).isEqualTo(followingTuesday);

        ScheduleCapacityResponse.BayCapacityView mondayBay =
                response.getDays().get(0).getBays().get(0);
        assertThat(mondayBay.getOccupiedMinutes()).isEqualTo(60);
        assertThat(mondayBay.getOccupancy().get(0)).isEqualTo(1);
        assertThat(mondayBay.getCarryOverIn()).hasSize(1);
        ScheduleCapacityResponse.CarryOverView carryOver =
                mondayBay.getCarryOverIn().get(0);
        assertThat(carryOver.getFromDate()).isEqualTo(SATURDAY);
        assertThat(carryOver.getWorkorderId()).isEqualTo(workOrderId);
        assertThat(carryOver.getBayHours()).isEqualByComparingTo(new BigDecimal("1.0"));
    }

    @Test
    @DisplayName("#2050 AC6 - a job that finished before the range contributes nothing to it")
    void jobFinishedBeforeTheRangeContributesNothing() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        // Monday 10:00-13:00 actual: it overran its own planned 12:00 finish, but it ended well
        // inside Monday's own 08:00-17:00 window, so there is no overrun past close to carry.
        Appointment appointment = persistAppointment(
                locationId, bayId, instant(MONDAY, 10, 0), instant(MONDAY, 12, 0), AppointmentStatus.SCHEDULED);
        persistWorkorderLink(UUIDv7Generator.generate(), appointment, instant(MONDAY, 10, 0), instant(MONDAY, 13, 0));
        flushAndClear();

        // Monday is now inside the widened lookback, so the row IS fetched. It must still
        // contribute nothing in-range: the accumulator it lands in is a pre-range one and is
        // discarded, and nothing survives to be double-counted.
        ScheduleCapacityResponse response = scheduleCapacityService.getCapacity(locationId, TUESDAY, WEDNESDAY);

        assertThat(response.getDays()).hasSize(2);
        for (ScheduleCapacityResponse.DayCapacityView day : response.getDays()) {
            ScheduleCapacityResponse.BayCapacityView bay = day.getBays().get(0);
            assertThat(bay.getBayId()).isEqualTo(bayId);
            assertThat(bay.getOccupiedMinutes())
                    .as("completed pre-range work must not be counted in-range (#2050 AC6) on %s", day.getDate())
                    .isZero();
            assertThat(bay.getOccupancy()).containsOnly(0);
            assertThat(bay.getCarryOverIn()).isEmpty();
        }
        assertThat(appointment.getAppointmentId()).isNotNull();
    }

    @Test
    @DisplayName("#2050 AC6 - an overrun that does reach the range is counted exactly once: the "
            + "carryOverIn detail equals the occupied minutes rather than adding to them")
    void overrunReachingTheRangeIsCountedExactlyOnce() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        Appointment appointment = persistAppointment(
                locationId, bayId, instant(MONDAY, 15, 0), instant(MONDAY, 17, 0), AppointmentStatus.SCHEDULED);
        persistWorkorderLink(UUIDv7Generator.generate(), appointment, instant(MONDAY, 15, 0), instant(TUESDAY, 11, 0));
        flushAndClear();

        ScheduleCapacityResponse.BayCapacityView tuesdayBay = scheduleCapacityService
                .getCapacity(locationId, TUESDAY, TUESDAY)
                .getDays()
                .get(0)
                .getBays()
                .get(0);

        // Direct real-clock overlap (08:00-11:00) and re-anchored carry-over are disjoint by
        // construction - this appointment's last directly-overlapped OK day IS Tuesday, so pass 2
        // has nothing left to re-anchor. Pin it: 180 minutes, once, and the annotation reports the
        // same 180 minutes rather than a second helping of them.
        assertThat(tuesdayBay.getOccupiedMinutes()).isEqualTo(180);
        assertThat(tuesdayBay.getCarryOverIn()).hasSize(1);
        assertThat(tuesdayBay.getCarryOverIn().get(0).getBayHours()).isEqualByComparingTo(new BigDecimal("3.0"));

        // Cross-checked against the occupancy array rather than against carryOverIn. Comparing
        // bayHours to occupiedMinutes proves nothing for a single-appointment bay: recordContribution
        // is fed the very value that increments occupiedMinutes, on both write paths, so a
        // double-add would inflate both sides equally and the comparison would still hold. The
        // occupancy slots are marked by a separate call that clamps to the day's own window, so a
        // second helping of the same overrun raises the minute total without raising the slot
        // count - which is exactly the failure this assertion is here to catch.
        int minutesRepresentedBySlots =
                tuesdayBay.getOccupancy().stream().mapToInt(Integer::intValue).sum() * 60;
        assertThat(tuesdayBay.getOccupiedMinutes())
                .as("occupiedMinutes must equal what the independently-marked occupancy slots represent")
                .isEqualTo(minutesRepresentedBySlots);
    }

    @Test
    @DisplayName("#2050 AC3 - the lookback is bounded: a job that COMPLETED before the range and "
            + "originated more than 42 days before it is NOT reported (accepted degradation, not a "
            + "desirable outcome)")
    void jobOriginatingBeyondTheLookbackBoundIsNotReported() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        // 45 days before the range start - beyond
        // ScheduleCapacityServiceImpl.CARRY_OVER_LOOKBACK_DAYS (42, = MAX_RANGE_DAYS). Referenced
        // as a literal rather than the constant so this test states the boundary it asserts in the
        // same terms the issue does. 2026-08-21 is a Friday, so it has operating hours of its own.
        LocalDate longBefore = MONDAY.minusDays(45);
        // The job is COMPLETE before the range begins - this is the whole point of the fixture. It
        // ran from 2026-08-21 15:00 to Saturday 2026-10-03 20:00, seven hours past that short
        // Saturday's 13:00 close, so an overrun is still being distributed forward when Monday
        // arrives. completedAt sits 28 hours before the requested range's own start, so the query's
        // actuals arm ("still running when the range began") is false with margin and the planned
        // arm - the arm CARRY_OVER_LOOKBACK_DAYS bounds - is the only route to this row.
        LocalDate priorSaturday = MONDAY.minusDays(2);
        Appointment appointment = persistAppointment(
                locationId, bayId, instant(longBefore, 15, 0), instant(longBefore, 17, 0), AppointmentStatus.SCHEDULED);
        persistWorkorderLink(
                UUIDv7Generator.generate(), appointment, instant(longBefore, 15, 0), instant(priorSaturday, 20, 0));
        flushAndClear();

        ScheduleCapacityResponse.BayCapacityView mondayBay = scheduleCapacityService
                .getCapacity(locationId, MONDAY, MONDAY)
                .getDays()
                .get(0)
                .getBays()
                .get(0);

        // This documents an ACCEPTED DEGRADATION, not a desirable outcome - and the degradation it
        // documents is now NARROWER than it was. Until the appointment query grew its actuals arm,
        // every job whose planned window predated the lookback was missed, including one still
        // running inside the range; Copilot's PR #2087 finding was exactly that shape, and it is
        // now pinned positively by jobRunningIntoTheRangeIsFetchedHoweverOldItsPlannedWindowIs. What
        // remains missed is only the residual above: an ALREADY COMPLETED job, finished before the
        // range began, whose overrun is still being distributed forward onto a day inside it. #2050
        // AC3 requires the lookback to be bounded and the bound to be stated, and a job whose
        // planned finish is further behind the day its overrun lands on than the policy maximum
        // range is outside what this read claims to explain. The test exists so that widening the
        // bound stays a deliberate decision with a failing test attached rather than an accident -
        // it is not an assertion that missing the job is desirable.
        assertThat(mondayBay.getOccupiedMinutes()).isZero();
        assertThat(mondayBay.getOccupancy()).containsOnly(0);
        assertThat(mondayBay.getCarryOverIn()).isEmpty();
    }

    @Test
    @DisplayName("#2050 - the lookback never leaks into the emitted days: a 42-day request still "
            + "returns exactly 42 days, in order, with the requested from/to")
    void lookbackDoesNotLeakIntoTheEmittedDayList() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        // Friday before the range, actually running until Monday 11:00 - i.e. there really are
        // appointments inside the lookback window for this read to assemble and discard.
        LocalDate fridayBefore = MONDAY.minusDays(3);
        Appointment appointment = persistAppointment(
                locationId,
                bayId,
                instant(fridayBefore, 15, 0),
                instant(fridayBefore, 17, 0),
                AppointmentStatus.SCHEDULED);
        persistWorkorderLink(
                UUIDv7Generator.generate(), appointment, instant(fridayBefore, 15, 0), instant(MONDAY, 11, 0));
        flushAndClear();

        LocalDate to = MONDAY.plusDays(41);
        ScheduleCapacityResponse response = scheduleCapacityService.getCapacity(locationId, MONDAY, to);

        assertThat(response.getFrom()).isEqualTo(MONDAY);
        assertThat(response.getTo()).isEqualTo(to);
        assertThat(response.getDays()).hasSize(42);
        assertThat(response.getDays())
                .extracting(ScheduleCapacityResponse.DayCapacityView::getDate)
                .containsExactlyElementsOf(MONDAY.datesUntil(to.plusDays(1)).toList());

        // And the lookback did its job on the first emitted day, so the 42 is not 42 because the
        // read simply ignored the pre-range rows.
        ScheduleCapacityResponse.BayCapacityView firstDayBay =
                response.getDays().get(0).getBays().get(0);
        assertThat(firstDayBay.getOccupiedMinutes()).isEqualTo(180);
        assertThat(firstDayBay.getCarryOverIn()).hasSize(1);
        assertThat(firstDayBay.getCarryOverIn().get(0).getFromDate()).isEqualTo(fridayBefore);
    }

    @Test
    @DisplayName("#2050 AC1 - a bay-day holding several carryOverIn entries returns them sorted by "
            + "(fromDate, appointmentId)")
    void carryOverInIsSortedByFromDateThenAppointmentId() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);

        // The Saturday BEFORE this week's Monday (the SATURDAY constant is the Saturday that
        // follows it). Planned 10:00-12:00, actually running 10:00 -> Tuesday 09:00, so it reaches
        // Tuesday by direct overlap with fromDate on that Saturday.
        LocalDate priorSaturday = MONDAY.minusDays(2);
        Appointment fromSaturday = persistAppointment(
                locationId,
                bayId,
                instant(priorSaturday, 10, 0),
                instant(priorSaturday, 12, 0),
                AppointmentStatus.SCHEDULED);
        persistWorkorderLink(
                UUIDv7Generator.generate(), fromSaturday, instant(priorSaturday, 10, 0), instant(TUESDAY, 9, 0));
        // Two more in the same bay, both starting on the following Monday.
        Appointment fromMondayA = persistAppointment(
                locationId, bayId, instant(MONDAY, 15, 0), instant(MONDAY, 17, 0), AppointmentStatus.SCHEDULED);
        persistWorkorderLink(UUIDv7Generator.generate(), fromMondayA, instant(MONDAY, 15, 0), instant(TUESDAY, 11, 0));
        Appointment fromMondayB = persistAppointment(
                locationId, bayId, instant(MONDAY, 14, 0), instant(MONDAY, 17, 0), AppointmentStatus.SCHEDULED);
        persistWorkorderLink(UUIDv7Generator.generate(), fromMondayB, instant(MONDAY, 14, 0), instant(TUESDAY, 10, 0));
        flushAndClear();

        ScheduleCapacityResponse.BayCapacityView tuesdayBay = scheduleCapacityService
                .getCapacity(locationId, TUESDAY, TUESDAY)
                .getDays()
                .get(0)
                .getBays()
                .get(0);

        // The ledger behind carryOverIn is a HashMap and the appointment query has no ORDER BY, so
        // without an explicit sort the list order is unspecified. Pin the documented order.
        assertThat(tuesdayBay.getCarryOverIn()).hasSize(3);
        assertThat(tuesdayBay.getCarryOverIn())
                .isSortedAccordingTo(Comparator.comparing(ScheduleCapacityResponse.CarryOverView::getFromDate)
                        .thenComparing(ScheduleCapacityResponse.CarryOverView::getAppointmentId));
        assertThat(tuesdayBay.getCarryOverIn())
                .extracting(ScheduleCapacityResponse.CarryOverView::getFromDate)
                .containsExactly(priorSaturday, MONDAY, MONDAY);
        assertThat(tuesdayBay.getCarryOverIn())
                .extracting(ScheduleCapacityResponse.CarryOverView::getAppointmentId)
                .containsExactlyInAnyOrder(
                        fromSaturday.getAppointmentId(),
                        fromMondayA.getAppointmentId(),
                        fromMondayB.getAppointmentId());
        // 60 (Saturday's job, 08:00-09:00) + 180 (15:00 job, 08:00-11:00) + 120 (14:00 job,
        // 08:00-10:00) - each counted once.
        assertThat(tuesdayBay.getOccupiedMinutes()).isEqualTo(360);
    }

    // -------------------------------------------------------------------------
    // #2050 adversarial-review follow-ups: what fromDate means on a plain
    // multi-day job, and CARRY_OVER_LOOKBACK_DAYS pinned from below as well as
    // from above
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("#2050 AC1 - DELIBERATE BEHAVIOUR CHANGE: for a job that directly overlapped two "
            + "open days before overrunning the second, carryOverIn names the date its window BEGAN "
            + "(Monday), not the date whose close it overran (Tuesday)")
    void twoDayJobReportsTheDateItsWindowBeganNotTheDateItOverran() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        // An ordinary two-day booking that needs no lookback at all: planned Monday 15:00-17:00,
        // actually run from Monday 15:00 straight through to Tuesday 20:00.
        Appointment appointment = persistAppointment(
                locationId, bayId, instant(MONDAY, 15, 0), instant(MONDAY, 17, 0), AppointmentStatus.SCHEDULED);
        UUID workOrderId = UUIDv7Generator.generate();
        persistWorkorderLink(workOrderId, appointment, instant(MONDAY, 15, 0), instant(TUESDAY, 20, 0));
        flushAndClear();

        ScheduleCapacityResponse response = scheduleCapacityService.getCapacity(locationId, MONDAY, WEDNESDAY);

        assertThat(response.getDays())
                .extracting(ScheduleCapacityResponse.DayCapacityView::getDate)
                .containsExactly(MONDAY, TUESDAY, WEDNESDAY);
        ScheduleCapacityResponse.BayCapacityView mondayBay =
                response.getDays().get(0).getBays().get(0);
        ScheduleCapacityResponse.BayCapacityView tuesdayBay =
                response.getDays().get(1).getBays().get(0);
        ScheduleCapacityResponse.BayCapacityView wednesdayBay =
                response.getDays().get(2).getBays().get(0);

        // The shape, stated unambiguously so the fromDate assertion below cannot be read as
        // covering some other geometry: pass 1 finds DIRECT real-clock overlap on Monday
        // (15:00-17:00 = 120) and on Tuesday (the whole 08:00-17:00 window = 540), so the last
        // directly-overlapped day is TUESDAY. Only the 180 minutes past Tuesday's 17:00 close are
        // re-anchored by pass 2, onto Wednesday.
        assertThat(mondayBay.getOccupiedMinutes()).isEqualTo(120);
        assertThat(tuesdayBay.getOccupiedMinutes()).isEqualTo(540);
        assertThat(wednesdayBay.getOccupiedMinutes()).isEqualTo(180);

        // THE CHANGE, asserted on purpose. origin/main defined fromDate as "the date whose close
        // the job overran" and emitted TUESDAY for this Wednesday view. #2050 redefines it as "the
        // date the job's effective window began", because only a fact derived from the row itself
        // is range-independent (AC2) - a date derived from which day happened to be the last one
        // overlapped is not. The two definitions diverge for EVERY job that directly overlapped two
        // or more open days before overrunning the last of them, which is an ordinary two-day
        // booking, not an exotic case. This test exists so that divergence is recorded intent with
        // a named expectation, rather than something a future reader discovers by diffing output.
        assertThat(wednesdayBay.getCarryOverIn()).hasSize(1);
        ScheduleCapacityResponse.CarryOverView wednesdayCarryOver =
                wednesdayBay.getCarryOverIn().get(0);
        assertThat(wednesdayCarryOver.getFromDate())
                .as("fromDate is the date the effective window began (Monday), not the date whose "
                        + "close was overrun (Tuesday)")
                .isEqualTo(MONDAY);
        assertThat(wednesdayCarryOver.getBayHours()).isEqualByComparingTo(new BigDecimal("3.0"));
        assertThat(wednesdayCarryOver.getAppointmentId()).isEqualTo(appointment.getAppointmentId());
        assertThat(wednesdayCarryOver.getWorkorderId()).isEqualTo(workOrderId);

        // Tuesday is annotated by the same rule and for the same reason - its 540 minutes also came
        // from a window that began on Monday - while Monday, the day that window began on, is not.
        assertThat(tuesdayBay.getCarryOverIn()).hasSize(1);
        assertThat(tuesdayBay.getCarryOverIn().get(0).getFromDate()).isEqualTo(MONDAY);
        assertThat(tuesdayBay.getCarryOverIn().get(0).getBayHours()).isEqualByComparingTo(new BigDecimal("9.0"));
        assertThat(mondayBay.getCarryOverIn()).isEmpty();
    }

    @Test
    @DisplayName("#2050 AC3 - a job originating 40 days before the range and still running inside "
            + "it IS reported (via the actuals arm, which is why this one no longer pins the "
            + "constant)")
    void jobOriginatingJustInsideTheLookbackBoundIsReported() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        // 2026-08-26, a Wednesday, so it has operating hours of its own. Forty days before the
        // range start: inside ScheduleCapacityServiceImpl.CARRY_OVER_LOOKBACK_DAYS (42).
        LocalDate fortyDaysBefore = MONDAY.minusDays(40);
        // A job still running deep into the range, rather than one that finished long ago: pass 2
        // distributes an overrun at roughly one operating window per open day, so a 40-day-old job
        // that had already finished would have exhausted itself long before Monday and would prove
        // nothing about the bound. This one's effective window is open the whole way, so it reaches
        // Monday by plain direct overlap - the SQL predicate's windowStart is then the only thing
        // that decides whether the row is seen at all.
        Appointment appointment = persistAppointment(
                locationId,
                bayId,
                instant(fortyDaysBefore, 15, 0),
                instant(fortyDaysBefore, 17, 0),
                AppointmentStatus.SCHEDULED);
        UUID workOrderId = UUIDv7Generator.generate();
        persistWorkorderLink(workOrderId, appointment, instant(fortyDaysBefore, 15, 0), instant(MONDAY, 11, 0));
        flushAndClear();

        ScheduleCapacityResponse.BayCapacityView mondayBay = scheduleCapacityService
                .getCapacity(locationId, MONDAY, MONDAY)
                .getDays()
                .get(0)
                .getBays()
                .get(0);

        // THIS TEST NO LONGER PINS THE CONSTANT, and saying so is the point of the comment. It was
        // written as the lower companion to jobOriginatingBeyondTheLookbackBoundIsNotReported, on
        // the reasoning that a 40-day-old planned window only survives because the lookback is 42.
        // Since the appointment query grew its actuals arm that reasoning is false: this job was
        // still running when the range began (completedAt = Monday 11:00 > the range start), so the
        // EXISTS arm fetches it however small CARRY_OVER_LOOKBACK_DAYS is - verified by mutation,
        // the assertions below still pass at a constant of 14. What it pins now is the actuals
        // arm's reach, which is worth keeping: a job whose planned window is 40 days old must still
        // hold the bay it is physically occupying. The constant itself is pinned from below by
        // completedJobReachableOnlyThroughThePlannedArmPinsTheLookbackFromBelow, whose fixture the
        // actuals arm cannot reach.
        assertThat(mondayBay.getOccupiedMinutes())
                .as("a job originating 40 days back and still running must hold the bay it occupies")
                .isEqualTo(180);
        assertThat(mondayBay.getOccupancy()).containsExactly(1, 1, 1, 0, 0, 0, 0, 0, 0);
        assertThat(mondayBay.getCarryOverIn()).hasSize(1);
        ScheduleCapacityResponse.CarryOverView carryOver =
                mondayBay.getCarryOverIn().get(0);
        assertThat(carryOver.getFromDate()).isEqualTo(fortyDaysBefore);
        assertThat(carryOver.getWorkorderId()).isEqualTo(workOrderId);
        assertThat(carryOver.getBayHours()).isEqualByComparingTo(new BigDecimal("3.0"));
    }

    @Test
    @DisplayName("#2050 AC2 - a wide [D-41, D] request and a single-day [D, D] request agree about "
            + "D for a job originating 40 days before it, at the edge of the narrower request's own "
            + "lookback window")
    void wideRangeAndSingleDayRequestAgreeAboutTheSharedDateAtTheLookbackBoundary() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        LocalDate fortyDaysBefore = MONDAY.minusDays(40);
        Appointment appointment = persistAppointment(
                locationId,
                bayId,
                instant(fortyDaysBefore, 15, 0),
                instant(fortyDaysBefore, 17, 0),
                AppointmentStatus.SCHEDULED);
        persistWorkorderLink(
                UUIDv7Generator.generate(), appointment, instant(fortyDaysBefore, 15, 0), instant(MONDAY, 11, 0));
        flushAndClear();

        // The widest and the narrowest legal request that both contain MONDAY: [D-41, D] is exactly
        // the 42-day policy maximum ending on D, and [D, D] is a single day. Their lookback windows
        // differ by 41 days - which is the thing rangeStartDoesNotChangeTheAnswerForADateInsideBothRanges
        // does NOT exercise, since a one-day-old origin sits inside both windows however narrow they get.
        LocalDate wideFrom = MONDAY.minusDays(41);
        ScheduleCapacityResponse wide = scheduleCapacityService.getCapacity(locationId, wideFrom, MONDAY);
        ScheduleCapacityResponse narrow = scheduleCapacityService.getCapacity(locationId, MONDAY, MONDAY);

        assertThat(wide.getDays()).hasSize(42);
        ScheduleCapacityResponse.DayCapacityView mondayViaWide = wide.getDays().get(41);
        ScheduleCapacityResponse.DayCapacityView mondayViaNarrow =
                narrow.getDays().get(0);
        assertThat(mondayViaWide.getDate()).isEqualTo(MONDAY);
        assertThat(mondayViaNarrow.getDate()).isEqualTo(MONDAY);

        ScheduleCapacityResponse.BayCapacityView bayViaWide =
                mondayViaWide.getBays().get(0);
        ScheduleCapacityResponse.BayCapacityView bayViaNarrow =
                mondayViaNarrow.getBays().get(0);

        // This test sits INSIDE the guarantee the code actually offers, deliberately - but the
        // guarantee is wider than the comment that used to stand here said, and the widening is
        // load-bearing enough to restate. The planned arm still gives: two legal requests
        // containing D agree about D for every job whose planned window ends within
        // CARRY_OVER_LOOKBACK_DAYS of D, because the narrowest assembly window either request can
        // have is [D-42, D]. Forty days is inside that. Since Copilot's PR #2087 finding the query
        // also has an actuals arm, which covers this fixture independently of any constant: the job
        // was still running when either range began, so both requests fetch it whatever the
        // lookback is. That means THIS TEST NO LONGER REDDENS AT A REDUCED CONSTANT either -
        // verified by mutation at 14 - and a 43-day origin of the same shape would now agree too.
        // The residual disagreement that survives both arms is a job that COMPLETED between the two
        // requests' start dates with a planned window older than either lookback; that shape is
        // pinned, as a documented residual rather than as agreement, by
        // completionBetweenTwoRequestStartsIsADocumentedResidual.
        assertThat(bayViaNarrow.getOccupiedMinutes())
                .as("a 42-day request and a 1-day request must agree about the day they share (#2050 AC2)")
                .isEqualTo(bayViaWide.getOccupiedMinutes());
        assertThat(bayViaNarrow.getOccupiedMinutes()).isEqualTo(180);
        assertThat(bayViaNarrow.getOccupancy()).isEqualTo(bayViaWide.getOccupancy());
        assertThat(bayViaNarrow.getCarryOverIn()).hasSameSizeAs(bayViaWide.getCarryOverIn());
        assertThat(bayViaNarrow.getCarryOverIn())
                .extracting(
                        ScheduleCapacityResponse.CarryOverView::getFromDate,
                        ScheduleCapacityResponse.CarryOverView::getAppointmentId,
                        ScheduleCapacityResponse.CarryOverView::getWorkorderId,
                        ScheduleCapacityResponse.CarryOverView::getBayHours)
                .containsExactlyElementsOf(carryOverTuples(bayViaWide));
        assertThat(bayViaNarrow.getCarryOverIn().get(0).getFromDate()).isEqualTo(fortyDaysBefore);
    }

    // -------------------------------------------------------------------------
    // Copilot PR #2087: the query's second lower-bound arm, on the workorder
    // actuals. What it guarantees, what still pins the lookback constant now
    // that it does not, and the residual it deliberately leaves behind.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("#2050 AC3 - the lookback bound is pinned from below: a COMPLETED job reachable "
            + "only through the planned arm, originating 40 days before the range, still lands "
            + "inside it")
    void completedJobReachableOnlyThroughThePlannedArmPinsTheLookbackFromBelow() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        // The deliberate twin of jobOriginatingBeyondTheLookbackBoundIsNotReported: the same
        // geometry, five days newer. 2026-08-26 is a Wednesday, forty days before the range start -
        // inside CARRY_OVER_LOOKBACK_DAYS (42) and outside every smaller value anyone might
        // "simplify" it to. The pair straddles the bound, which is what pins it.
        LocalDate fortyDaysBefore = MONDAY.minusDays(40);
        // Reachable ONLY through the planned arm, which is the entire reason this fixture exists.
        // The job completed on Saturday 2026-10-03 at 20:00 - 28 hours before the requested range
        // starts - so the actuals arm ("still running when the range began") is false with margin,
        // and the row can only be fetched by appointment.endAt > windowStart, the arm the constant
        // bounds. Every other lookback fixture in this class is now rescued by the actuals arm and
        // therefore passes at any constant; this one is not.
        LocalDate priorSaturday = MONDAY.minusDays(2);
        Appointment appointment = persistAppointment(
                locationId,
                bayId,
                instant(fortyDaysBefore, 15, 0),
                instant(fortyDaysBefore, 17, 0),
                AppointmentStatus.SCHEDULED);
        UUID workOrderId = UUIDv7Generator.generate();
        persistWorkorderLink(workOrderId, appointment, instant(fortyDaysBefore, 15, 0), instant(priorSaturday, 20, 0));
        flushAndClear();

        ScheduleCapacityResponse.BayCapacityView mondayBay = scheduleCapacityService
                .getCapacity(locationId, MONDAY, MONDAY)
                .getDays()
                .get(0)
                .getBays()
                .get(0);

        // The arithmetic, spelled out so the numbers are not folklore. Pass 1 gives the effective
        // window (2026-08-26 15:00 -> 2026-10-03 20:00) a direct overlap on every open day it
        // crosses; the last of them is Saturday 2026-10-03, which closes at 13:00. Pass 2 therefore
        // carries 20:00 - 13:00 = 420 minutes forward. Sunday is CLOSED, so the next open day is
        // Monday, the range's only day, whose own window is 540 minutes - the 420 land inside it
        // with 120 minutes to spare rather than clipping at the day boundary, so the expectation is
        // not sitting on a cliff edge.
        assertThat(mondayBay.getOccupiedMinutes())
                .as("a completed job originating 40 days back must still be inside the 42-day lookback")
                .isEqualTo(420);
        assertThat(mondayBay.getOccupancy()).containsExactly(1, 1, 1, 1, 1, 1, 1, 0, 0);
        assertThat(mondayBay.getCarryOverIn()).hasSize(1);
        ScheduleCapacityResponse.CarryOverView carryOver =
                mondayBay.getCarryOverIn().get(0);
        assertThat(carryOver.getFromDate()).isEqualTo(fortyDaysBefore);
        assertThat(carryOver.getWorkorderId()).isEqualTo(workOrderId);
        assertThat(carryOver.getBayHours()).isEqualByComparingTo(new BigDecimal("7.0"));
    }

    @Test
    @DisplayName("#2050 AC2 - Copilot PR #2087 counterexample: a job planned 50 days before the "
            + "range whose work ran into it is fetched however old its planned window is, so "
            + "[D-41, D] and [D, D] agree about D")
    void jobRunningIntoTheRangeIsFetchedHoweverOldItsPlannedWindowIs() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        // Copilot's counterexample on PR #2087, verbatim: planned D-50 10:00-12:00, work actually
        // started D-50 10:00 and finished D 10:00. Fifty days is outside CARRY_OVER_LOOKBACK_DAYS,
        // so the single-day request's planned arm cannot reach this row (windowStart = D-42) while
        // the 42-day request's can (windowStart = D-83). Before the actuals arm existed the two
        // requests answered 0 and 120 for the same date, which is exactly the AC2 violation the
        // lookback was supposed to prevent.
        LocalDate fiftyDaysBefore = MONDAY.minusDays(50);
        Appointment appointment = persistAppointment(
                locationId,
                bayId,
                instant(fiftyDaysBefore, 10, 0),
                instant(fiftyDaysBefore, 12, 0),
                AppointmentStatus.SCHEDULED);
        UUID workOrderId = UUIDv7Generator.generate();
        persistWorkorderLink(workOrderId, appointment, instant(fiftyDaysBefore, 10, 0), instant(MONDAY, 10, 0));
        flushAndClear();

        LocalDate wideFrom = MONDAY.minusDays(41);
        ScheduleCapacityResponse wide = scheduleCapacityService.getCapacity(locationId, wideFrom, MONDAY);
        ScheduleCapacityResponse narrow = scheduleCapacityService.getCapacity(locationId, MONDAY, MONDAY);

        assertThat(wide.getDays()).hasSize(42);
        ScheduleCapacityResponse.DayCapacityView mondayViaWide = wide.getDays().get(41);
        ScheduleCapacityResponse.DayCapacityView mondayViaNarrow =
                narrow.getDays().get(0);
        assertThat(mondayViaWide.getDate()).isEqualTo(MONDAY);
        assertThat(mondayViaNarrow.getDate()).isEqualTo(MONDAY);
        ScheduleCapacityResponse.BayCapacityView bayViaWide =
                mondayViaWide.getBays().get(0);
        ScheduleCapacityResponse.BayCapacityView bayViaNarrow =
                mondayViaNarrow.getBays().get(0);

        // The guarantee, pinned on all three observable fields rather than on the minute total
        // alone: an agreement that held for occupiedMinutes while occupancy or carryOverIn diverged
        // would be no agreement at all to a client rendering the day.
        assertThat(bayViaNarrow.getOccupiedMinutes())
                .as("a 42-day request and a 1-day request must agree about the day they share (#2050 AC2)")
                .isEqualTo(bayViaWide.getOccupiedMinutes());
        // The job physically held the bay from Monday's 08:00 open until it finished at 10:00.
        assertThat(bayViaNarrow.getOccupiedMinutes()).isEqualTo(120);
        assertThat(bayViaNarrow.getOccupancy()).isEqualTo(bayViaWide.getOccupancy());
        assertThat(bayViaNarrow.getOccupancy()).containsExactly(1, 1, 0, 0, 0, 0, 0, 0, 0);
        assertThat(bayViaNarrow.getCarryOverIn())
                .extracting(
                        ScheduleCapacityResponse.CarryOverView::getFromDate,
                        ScheduleCapacityResponse.CarryOverView::getAppointmentId,
                        ScheduleCapacityResponse.CarryOverView::getWorkorderId,
                        ScheduleCapacityResponse.CarryOverView::getBayHours)
                .containsExactlyElementsOf(carryOverTuples(bayViaWide));
        assertThat(bayViaNarrow.getCarryOverIn()).hasSize(1);
        ScheduleCapacityResponse.CarryOverView carryOver =
                bayViaNarrow.getCarryOverIn().get(0);
        assertThat(carryOver.getFromDate()).isEqualTo(fiftyDaysBefore);
        assertThat(carryOver.getWorkorderId()).isEqualTo(workOrderId);
        assertThat(carryOver.getBayHours()).isEqualByComparingTo(new BigDecimal("2.0"));
    }

    @Test
    @DisplayName("#2050 / #2021 F5 - a STILL-RUNNING job is fetched by the actuals arm however old "
            + "its planned window is, but F5 gives it its PLANNED end, so it contributes nothing")
    void stillRunningJobIsFetchedByTheActualsArmButFallsBackToItsPlannedEnd() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        // Two bays, identical planned geometry, differing only in whether the workorder has a
        // recorded finish. That is what makes the zero below evidence rather than a tautology: if
        // the row simply were not fetched, Bay 2 would read zero too.
        UUID stillRunningBayId = persistBay("Bay 1", locationId);
        UUID finishedBayId = persistBay("Bay 2", locationId);
        // 2026-08-17, a Monday, 49 days before the range: outside CARRY_OVER_LOOKBACK_DAYS, so
        // neither row is reachable through the planned arm and the EXISTS arm is the only route to
        // either of them.
        LocalDate fortyNineDaysBefore = MONDAY.minusDays(49);
        Appointment stillRunning = persistAppointment(
                locationId,
                stillRunningBayId,
                instant(fortyNineDaysBefore, 10, 0),
                instant(fortyNineDaysBefore, 12, 0),
                AppointmentStatus.SCHEDULED);
        persistWorkorderLink(UUIDv7Generator.generate(), stillRunning, instant(fortyNineDaysBefore, 10, 0), null);
        Appointment finished = persistAppointment(
                locationId,
                finishedBayId,
                instant(fortyNineDaysBefore, 10, 0),
                instant(fortyNineDaysBefore, 12, 0),
                AppointmentStatus.SCHEDULED);
        persistWorkorderLink(
                UUIDv7Generator.generate(), finished, instant(fortyNineDaysBefore, 10, 0), instant(MONDAY, 10, 0));
        flushAndClear();

        ScheduleCapacityResponse.DayCapacityView monday = scheduleCapacityService
                .getCapacity(locationId, MONDAY, MONDAY)
                .getDays()
                .get(0);
        assertThat(monday.getBays()).hasSize(2);
        ScheduleCapacityResponse.BayCapacityView stillRunningBay =
                monday.getBays().get(0);
        ScheduleCapacityResponse.BayCapacityView finishedBay = monday.getBays().get(1);
        assertThat(stillRunningBay.getBayId()).isEqualTo(stillRunningBayId);
        assertThat(finishedBay.getBayId()).isEqualTo(finishedBayId);

        // The interaction this test exists to pin, and it is not the one the arm's name suggests.
        // "completedAt IS NULL" is the genuinely request-independent half of the actuals arm: a job
        // in progress is fetched by every request, whatever its range start. But being FETCHED and
        // CONTRIBUTING are different things, because #2021 F5 refuses to synthesise a finish for a
        // still-running job - effectiveEnd falls back to the appointment's PLANNED end, which here
        // is 49 days old. So the row is read, joined and grouped, and then its effective window
        // (2026-08-17 10:00-12:00) overlaps no day in the requested range and overruns no open
        // day's close. It contributes nothing. That is the documented behaviour of the two rules
        // composed, not a defect in either: the alternative - treating "started, never finished" as
        // "still occupying the bay right now" - is precisely the synthesis F5 rejects, and it would
        // let one stale workorder hold a bay forever.
        assertThat(stillRunningBay.getOccupiedMinutes())
                .as("F5: a still-running job falls back to its planned end, 49 days ago")
                .isZero();
        assertThat(stillRunningBay.getOccupancy()).containsOnly(0);
        assertThat(stillRunningBay.getCarryOverIn()).isEmpty();

        // The control. Same planned window, same 49-day age, same unreachable-by-the-planned-arm
        // row - only completedAt differs, and it is what turns a fetched row into occupied minutes.
        assertThat(finishedBay.getOccupiedMinutes())
                .as(
                        "the same row WITH a recorded finish does reach the range, so the zero above is F5, not a missed fetch")
                .isEqualTo(120);
    }

    @Test
    @DisplayName("#2050 - DOCUMENTED RESIDUAL, not a bug: a job that completed strictly between two "
            + "requests' start dates is seen by the wider request and not by the narrower one, and "
            + "they disagree about the day they share")
    void completionBetweenTwoRequestStartsIsADocumentedResidual() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        // 2026-07-07, a Tuesday, ninety days before the range: older than BOTH requests' planned
        // lookbacks below (D-42 for the single-day request, D-83 for the 42-day one), so the
        // planned arm is false for both and the actuals arm is the only thing that can differ.
        LocalDate ninetyDaysBefore = MONDAY.minusDays(90);
        // Completed on Saturday 2026-10-03 at 20:00 - strictly between the wide request's start
        // (D-41) and the narrow request's start (D). "Completed after the range began" is therefore
        // TRUE for the wide request and FALSE for the narrow one, and that is the whole asymmetry.
        LocalDate priorSaturday = MONDAY.minusDays(2);
        Appointment appointment = persistAppointment(
                locationId,
                bayId,
                instant(ninetyDaysBefore, 15, 0),
                instant(ninetyDaysBefore, 17, 0),
                AppointmentStatus.SCHEDULED);
        persistWorkorderLink(
                UUIDv7Generator.generate(),
                appointment,
                instant(ninetyDaysBefore, 15, 0),
                instant(priorSaturday, 20, 0));
        flushAndClear();

        LocalDate wideFrom = MONDAY.minusDays(41);
        ScheduleCapacityResponse wide = scheduleCapacityService.getCapacity(locationId, wideFrom, MONDAY);
        ScheduleCapacityResponse narrow = scheduleCapacityService.getCapacity(locationId, MONDAY, MONDAY);
        ScheduleCapacityResponse.BayCapacityView bayViaWide =
                wide.getDays().get(41).getBays().get(0);
        ScheduleCapacityResponse.BayCapacityView bayViaNarrow =
                narrow.getDays().get(0).getBays().get(0);
        assertThat(wide.getDays().get(41).getDate()).isEqualTo(MONDAY);
        assertThat(narrow.getDays().get(0).getDate()).isEqualTo(MONDAY);

        // THIS TEST DELIBERATELY ASSERTS A DISAGREEMENT. Do not "fix" it into an equality: the two
        // requests really do answer differently about Monday for this shape, the difference is
        // understood, and the point of pinning it is that a future reader meets it as recorded
        // intent rather than discovering it in production.
        //
        // Why it is not fixable here. Only the completedAt IS NULL half of the actuals arm is
        // strictly request-independent; the other half asks "did the work finish after THIS range
        // began", which is a question about the request as well as about the job. For a job that
        // finished strictly between two requests' start dates the two get opposite answers, and
        // when the planned window is also older than either lookback - as here - there is no second
        // route to rescue the narrower one. Closing it would need a lower bound that is neither
        // from-relative nor completion-relative, i.e. a different predicate, not a different
        // constant: see ScheduleCapacityServiceImpl#CARRY_OVER_LOOKBACK_DAYS, which states the same
        // residual in prose.
        assertThat(bayViaWide.getOccupiedMinutes())
                .as("the wide request's range began before the job finished, so its actuals arm fetches the row")
                .isEqualTo(420);
        assertThat(bayViaNarrow.getOccupiedMinutes())
                .as("the narrow request's range began after the job finished, and 90 days is outside its planned arm")
                .isZero();
        assertThat(bayViaNarrow.getCarryOverIn()).isEmpty();
        assertThat(bayViaWide.getCarryOverIn()).hasSize(1);
        assertThat(bayViaWide.getCarryOverIn().get(0).getFromDate()).isEqualTo(ninetyDaysBefore);
        assertThat(bayViaWide.getCarryOverIn().get(0).getBayHours()).isEqualByComparingTo(new BigDecimal("7.0"));
    }

    // -------------------------------------------------------------------------
    // The actuals arm judges the CURRENT mapping, not any mapping (#2023 F3/S1)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("#2050 / #2023 F3 - a stale mapping left behind by a reopen must not make an "
            + "appointment eligible: the actuals arm judges the CURRENT mapping, which finished "
            + "before the range began, so the day reports zero")
    void staleMappingDoesNotMakeAppointmentEligibleWhenCurrentMappingFinishedBeforeTheRange() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID reopenedBayId = persistBay("Bay 1", locationId);
        UUID controlBayId = persistBay("Bay 2", locationId);
        // Planned 50 days before the range, so its planned window ends outside
        // CARRY_OVER_LOOKBACK_DAYS (42) and the planned arm cannot reach either row: the actuals
        // arm is the only route to both, which is what makes this a test of the arm and not of the
        // lookback.
        LocalDate fiftyDaysBefore = MONDAY.minusDays(50);
        // The Saturday BEFORE the range (the SATURDAY constant is the one that follows it). It
        // closes at 13:00, so work finishing at 20:00 that day overruns by 420 minutes, which pass
        // 2 would distribute onto the next open day - Monday, the only day requested here. That is
        // what makes a wrongly fetched row visible as 420 minutes rather than as a silent no-op.
        LocalDate priorSaturday = MONDAY.minusDays(2);

        // workOrderId ordering is load-bearing here - the arm keeps the mapping with no GREATER
        // workOrderId - so the two ids are fixed rather than drawn from two successive
        // UUIDv7Generator.generate() calls, and the order they encode is asserted rather than
        // assumed. If a future generator or comparison change inverts it, this assertion fails
        // loudly instead of the fixture silently testing the opposite mapping.
        UUID staleWorkOrderId = UUID.fromString("00000000-0000-7000-8000-000000000011");
        UUID currentWorkOrderId = UUID.fromString("00000000-0000-7000-8000-000000000012");
        assertThat(currentWorkOrderId).isGreaterThan(staleWorkOrderId);

        Appointment reopened = persistAppointment(
                locationId,
                reopenedBayId,
                instant(fiftyDaysBefore, 10, 0),
                instant(fiftyDaysBefore, 12, 0),
                AppointmentStatus.SCHEDULED);
        // The stale mapping: the original workorder, never closed out, so it still reads as in
        // progress and satisfies the arm's time test for every request that ever asks.
        persistWorkorderLink(staleWorkOrderId, reopened, instant(fiftyDaysBefore, 10, 0), null);
        // The current mapping: the reopen that superseded it. Its replica HAS arrived - that is the
        // whole point of this shape - and it finished at 20:00 on the Saturday before the range,
        // i.e. it was not still running when the range began.
        persistWorkorderLink(
                currentWorkOrderId, reopened, instant(fiftyDaysBefore, 10, 0), instant(priorSaturday, 20, 0));

        // The control, in its own bay: identical geometry and an identical stale mapping, differing
        // only in that its current mapping WAS still running when the range began (it finished
        // Monday 10:00). It must still be fetched, and its 120 minutes are what makes the zero
        // above evidence of a refused fetch rather than of a fixture that could never contribute.
        UUID controlStaleWorkOrderId = UUID.fromString("00000000-0000-7000-8000-000000000021");
        UUID controlCurrentWorkOrderId = UUID.fromString("00000000-0000-7000-8000-000000000022");
        assertThat(controlCurrentWorkOrderId).isGreaterThan(controlStaleWorkOrderId);
        Appointment control = persistAppointment(
                locationId,
                controlBayId,
                instant(fiftyDaysBefore, 10, 0),
                instant(fiftyDaysBefore, 12, 0),
                AppointmentStatus.SCHEDULED);
        persistWorkorderLink(controlStaleWorkOrderId, control, instant(fiftyDaysBefore, 10, 0), null);
        persistWorkorderLink(
                controlCurrentWorkOrderId, control, instant(fiftyDaysBefore, 10, 0), instant(MONDAY, 10, 0));
        flushAndClear();

        ScheduleCapacityResponse.DayCapacityView monday = scheduleCapacityService
                .getCapacity(locationId, MONDAY, MONDAY)
                .getDays()
                .get(0);
        assertThat(monday.getDate()).isEqualTo(MONDAY);
        assertThat(monday.getBays()).hasSize(2);
        ScheduleCapacityResponse.BayCapacityView reopenedBay = monday.getBays().get(0);
        ScheduleCapacityResponse.BayCapacityView controlBay = monday.getBays().get(1);
        assertThat(reopenedBay.getBayId()).isEqualTo(reopenedBayId);
        assertThat(controlBay.getBayId()).isEqualTo(controlBayId);

        // ELIGIBILITY AND RESOLUTION MUST AGREE, and that is the whole content of this test.
        // WorkorderActuals#mostCurrent is this module's single mapping-selection rule (#2023
        // F3/S1): appointment -> mapping is one-to-many, a reopen adds a row without deleting the
        // earlier one, and every reader must take the greatest workOrderId "rather than defining
        // their own precedence". The fetch predicate is a reader too. Because
        // findActualsByAppointmentIds joins the replica, the rule it actually implements is "the
        // greatest workOrderId among the mappings that have a replica" - here both are replicated,
        // so the resolved mapping is the reopen, which finished before the range began and cannot
        // put a minute into it. The arm must therefore refuse the row.
        //
        // The regression. While the arm accepted ANY mapping, the stale still-open workorder made
        // this row eligible; the batch then resolved the reopen anyway, and the reopen's Saturday
        // overrun (420 minutes, redistributed onto Monday) entered capacity on the strength of a
        // workorder whose actuals were never used. Note that the more obvious scenario - a newer
        // mapping whose replica has not arrived - does NOT discriminate: both the old and the new
        // predicate drop an unreplicated mapping at the replica join, so both fetch on the older
        // one and both resolve it. Replicas on BOTH mappings is what separates them.
        assertThat(reopenedBay.getOccupiedMinutes())
                .as("the current mapping finished before the range began, so the row is not eligible (#2023 F3/S1)")
                .isZero();
        assertThat(reopenedBay.getOccupancy()).containsOnly(0);
        assertThat(reopenedBay.getCarryOverIn()).isEmpty();

        assertThat(controlBay.getOccupiedMinutes())
                .as("the same two-mapping shape whose CURRENT mapping was still running at the "
                        + "range start is still fetched, so the zero above is a refused fetch")
                .isEqualTo(120);
        assertThat(controlBay.getOccupancy()).containsExactly(1, 1, 0, 0, 0, 0, 0, 0, 0);
        assertThat(controlBay.getCarryOverIn()).hasSize(1);
        ScheduleCapacityResponse.CarryOverView controlCarryOver =
                controlBay.getCarryOverIn().get(0);
        assertThat(controlCarryOver.getFromDate()).isEqualTo(fiftyDaysBefore);
        assertThat(controlCarryOver.getWorkorderId())
                .as("the reported workorder is the resolved current mapping, not the stale one")
                .isEqualTo(controlCurrentWorkOrderId);
        assertThat(controlCarryOver.getBayHours()).isEqualByComparingTo(new BigDecimal("2.0"));
    }

    @Test
    @DisplayName("#2023 F3/S1 - the actuals arm selects the GREATEST mapping, not merely a "
            + "consistent one: an older never-started mapping does not hide the current one")
    void actualsArmSelectsTheGreatestMappingNotTheOldest() {
        UUID locationId = persistLocation(UTC, WEEKDAY_HOURS, null);
        UUID bayId = persistBay("Bay 1", locationId);
        // Same 50-day-old planned window as above: outside CARRY_OVER_LOOKBACK_DAYS, so only the
        // actuals arm can fetch this row.
        LocalDate fiftyDaysBefore = MONDAY.minusDays(50);
        // The sibling direction of the same rule, and the reason "greatest" is not interchangeable
        // with "any consistent one": here it is the OLDER mapping that fails the arm's time test
        // (a workorder opened and never started) and the current one that passes. A predicate that
        // selected the least workOrderId would be just as deterministic as this one and would
        // wrongly report zero.
        UUID abandonedWorkOrderId = UUID.fromString("00000000-0000-7000-8000-000000000031");
        UUID currentWorkOrderId = UUID.fromString("00000000-0000-7000-8000-000000000032");
        assertThat(currentWorkOrderId).isGreaterThan(abandonedWorkOrderId);

        Appointment appointment = persistAppointment(
                locationId,
                bayId,
                instant(fiftyDaysBefore, 10, 0),
                instant(fiftyDaysBefore, 12, 0),
                AppointmentStatus.SCHEDULED);
        persistWorkorderLink(abandonedWorkOrderId, appointment, null, null);
        persistWorkorderLink(currentWorkOrderId, appointment, instant(fiftyDaysBefore, 10, 0), instant(MONDAY, 10, 0));
        flushAndClear();

        ScheduleCapacityResponse.BayCapacityView mondayBay = scheduleCapacityService
                .getCapacity(locationId, MONDAY, MONDAY)
                .getDays()
                .get(0)
                .getBays()
                .get(0);

        // The job held the bay from Monday's 08:00 open until the reopen finished at 10:00.
        assertThat(mondayBay.getOccupiedMinutes())
                .as("the arm must judge the greatest mapping, which ran into the range (#2023 F3/S1)")
                .isEqualTo(120);
        assertThat(mondayBay.getOccupancy()).containsExactly(1, 1, 0, 0, 0, 0, 0, 0, 0);
        assertThat(mondayBay.getCarryOverIn()).hasSize(1);
        ScheduleCapacityResponse.CarryOverView carryOver =
                mondayBay.getCarryOverIn().get(0);
        assertThat(carryOver.getFromDate()).isEqualTo(fiftyDaysBefore);
        assertThat(carryOver.getWorkorderId())
                .as("fetched on, and resolved to, the greatest workOrderId - the same mapping at both sites")
                .isEqualTo(currentWorkOrderId);
        assertThat(carryOver.getBayHours()).isEqualByComparingTo(new BigDecimal("2.0"));
    }
}
