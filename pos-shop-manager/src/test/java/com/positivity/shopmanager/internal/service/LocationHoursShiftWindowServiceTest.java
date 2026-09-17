package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.shopmanager.internal.entity.ExtLocationReplica;
import com.positivity.shopmanager.internal.enums.ShiftSource;
import com.positivity.shopmanager.internal.enums.ShiftStatus;
import com.positivity.shopmanager.internal.service.LocationHoursShiftWindowService.ShiftWindow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The PLACEHOLDER shift window (issue #2060): the location's operating hours for the day, or an
 * honest CLOSED / UNKNOWN — never a default window.
 */
class LocationHoursShiftWindowServiceTest {

    private static final UUID LOCATION_ID = UUID.fromString("01960011-0000-7000-8000-000000000003");

    private static final String NEW_YORK = "America/New_York";

    private static final String ALL_WEEK_08_17 = """
            [{"dayOfWeek":"MONDAY","openTime":"08:00","closeTime":"17:00"},
             {"dayOfWeek":"TUESDAY","openTime":"08:00","closeTime":"17:00"},
             {"dayOfWeek":"WEDNESDAY","openTime":"08:00","closeTime":"17:00"},
             {"dayOfWeek":"THURSDAY","openTime":"08:00","closeTime":"17:00"},
             {"dayOfWeek":"FRIDAY","openTime":"08:00","closeTime":"17:00"},
             {"dayOfWeek":"SATURDAY","openTime":"08:00","closeTime":"17:00"},
             {"dayOfWeek":"SUNDAY","openTime":"08:00","closeTime":"17:00"}]
            """;

    private final LocationHoursShiftWindowService service =
            new LocationHoursShiftWindowService(new LocationHoursParser(new ObjectMapper()));

    private static ExtLocationReplica replica(String timezone, String hours, String closures) {
        return ExtLocationReplica.builder()
                .locationId(LOCATION_ID)
                .timezone(timezone)
                .operatingHours(hours)
                .holidayClosures(closures)
                .build();
    }

    private static void assertNoWindow(ShiftWindow window, ShiftStatus status) {
        assertThat(window.status()).isEqualTo(status);
        assertThat(window.source()).isEqualTo(ShiftSource.LOCATION_HOURS);
        assertThat(window.start()).isNull();
        assertThat(window.end()).isNull();
        assertThat(window.minutes()).isNull();
    }

    @Test
    @DisplayName("AC1: a weekday with hours yields the open and close of that day in the location zone, as UTC")
    void derivesTheWindowFromTheWeekdayEntryInTheLocationZone() {
        // Tuesday 2026-09-15, EDT (UTC-4).
        ShiftWindow window =
                service.resolve(LOCATION_ID, replica(NEW_YORK, ALL_WEEK_08_17, null), LocalDate.parse("2026-09-15"));

        assertThat(window.status()).isEqualTo(ShiftStatus.DERIVED);
        assertThat(window.source()).isEqualTo(ShiftSource.LOCATION_HOURS);
        assertThat(window.start()).isEqualTo(Instant.parse("2026-09-15T12:00:00Z"));
        assertThat(window.end()).isEqualTo(Instant.parse("2026-09-15T21:00:00Z"));
        assertThat(window.minutes()).isEqualTo(540);
    }

    @Test
    @DisplayName("AC2: no replica row is UNKNOWN, not a default window")
    void missingReplicaIsUnknown() {
        assertNoWindow(service.resolve(LOCATION_ID, null, LocalDate.parse("2026-09-15")), ShiftStatus.UNKNOWN);
    }

    @Test
    @DisplayName("AC2: a blank or unrecognised timezone is UNKNOWN")
    void missingOrBadTimezoneIsUnknown() {
        assertNoWindow(
                service.resolve(LOCATION_ID, replica(null, ALL_WEEK_08_17, null), LocalDate.parse("2026-09-15")),
                ShiftStatus.UNKNOWN);
        assertNoWindow(
                service.resolve(
                        LOCATION_ID, replica("Mars/Olympus", ALL_WEEK_08_17, null), LocalDate.parse("2026-09-15")),
                ShiftStatus.UNKNOWN);
    }

    @Test
    @DisplayName("AC2: never-configured or unparsable hours are UNKNOWN (all-or-nothing, as the parser reads them)")
    void missingOrUnparsableHoursAreUnknown() {
        assertNoWindow(
                service.resolve(LOCATION_ID, replica(NEW_YORK, null, null), LocalDate.parse("2026-09-15")),
                ShiftStatus.UNKNOWN);
        assertNoWindow(
                service.resolve(LOCATION_ID, replica(NEW_YORK, "not json", null), LocalDate.parse("2026-09-15")),
                ShiftStatus.UNKNOWN);
        String oneBadDay = """
                [{"dayOfWeek":"TUESDAY","openTime":"08:00","closeTime":"17:00"},
                 {"dayOfWeek":"FUNDAY","openTime":"08:00","closeTime":"17:00"}]
                """;
        assertNoWindow(
                service.resolve(LOCATION_ID, replica(NEW_YORK, oneBadDay, null), LocalDate.parse("2026-09-15")),
                ShiftStatus.UNKNOWN);
    }

    @Test
    @DisplayName("AC2: a weekday with no entry is UNKNOWN for a person's shift, not CLOSED")
    void weekdayWithoutAnEntryIsUnknown() {
        String weekdaysOnly = """
                [{"dayOfWeek":"MONDAY","openTime":"08:00","closeTime":"17:00"}]
                """;
        // 2026-09-13 is a Sunday.
        assertNoWindow(
                service.resolve(LOCATION_ID, replica(NEW_YORK, weekdaysOnly, null), LocalDate.parse("2026-09-13")),
                ShiftStatus.UNKNOWN);
    }

    @Test
    @DisplayName("AC3: a holiday closure on the date is CLOSED, distinct from UNKNOWN, even with hours present")
    void holidayClosureWins() {
        String closures = """
                [{"date":"2026-09-15","reason":"Inventory day"}]
                """;
        assertNoWindow(
                service.resolve(
                        LOCATION_ID, replica(NEW_YORK, ALL_WEEK_08_17, closures), LocalDate.parse("2026-09-15")),
                ShiftStatus.CLOSED);
        // The day after is a normal derived window again.
        assertThat(service.resolve(
                                LOCATION_ID, replica(NEW_YORK, ALL_WEEK_08_17, closures), LocalDate.parse("2026-09-16"))
                        .status())
                .isEqualTo(ShiftStatus.DERIVED);
    }

    @Test
    @DisplayName("AC4: spring-forward — a window across the gap spans its real elapsed minutes, positive")
    void springForwardYieldsTheRealSpan() {
        // 2026-03-08: New York skips 02:00–03:00. A 01:00–05:00 window is three real hours.
        String earlyHours = """
                [{"dayOfWeek":"SUNDAY","openTime":"01:00","closeTime":"05:00"}]
                """;
        ShiftWindow window =
                service.resolve(LOCATION_ID, replica(NEW_YORK, earlyHours, null), LocalDate.parse("2026-03-08"));

        assertThat(window.status()).isEqualTo(ShiftStatus.DERIVED);
        assertThat(window.start()).isEqualTo(Instant.parse("2026-03-08T06:00:00Z")); // 01:00 EST
        assertThat(window.end()).isEqualTo(Instant.parse("2026-03-08T09:00:00Z")); // 05:00 EDT
        assertThat(window.minutes()).isEqualTo(180);

        // And an ordinary 08:00–17:00 on the same day is the usual nine hours, entirely in EDT.
        ShiftWindow dayWindow =
                service.resolve(LOCATION_ID, replica(NEW_YORK, ALL_WEEK_08_17, null), LocalDate.parse("2026-03-08"));
        assertThat(dayWindow.start()).isEqualTo(Instant.parse("2026-03-08T12:00:00Z"));
        assertThat(dayWindow.minutes()).isEqualTo(540);
    }

    @Test
    @DisplayName("AC4: fall-back — a window across the repeated hour spans its real elapsed minutes")
    void fallBackYieldsTheRealSpan() {
        // 2026-11-01: New York repeats 01:00–02:00. A 01:00–05:00 window is five real hours.
        String earlyHours = """
                [{"dayOfWeek":"SUNDAY","openTime":"01:00","closeTime":"05:00"}]
                """;
        ShiftWindow window =
                service.resolve(LOCATION_ID, replica(NEW_YORK, earlyHours, null), LocalDate.parse("2026-11-01"));

        assertThat(window.status()).isEqualTo(ShiftStatus.DERIVED);
        assertThat(window.start()).isEqualTo(Instant.parse("2026-11-01T05:00:00Z")); // 01:00 EDT (first)
        assertThat(window.end()).isEqualTo(Instant.parse("2026-11-01T10:00:00Z")); // 05:00 EST
        assertThat(window.minutes()).isEqualTo(300);

        ShiftWindow dayWindow =
                service.resolve(LOCATION_ID, replica(NEW_YORK, ALL_WEEK_08_17, null), LocalDate.parse("2026-11-01"));
        assertThat(dayWindow.start()).isEqualTo(Instant.parse("2026-11-01T13:00:00Z")); // 08:00 EST
        assertThat(dayWindow.minutes()).isEqualTo(540);
    }

    @Test
    @DisplayName("AC5: openTime not before closeTime is UNKNOWN with no negative minutes")
    void invertedHoursAreUnknown() {
        String inverted = """
                [{"dayOfWeek":"TUESDAY","openTime":"17:00","closeTime":"08:00"}]
                """;
        assertNoWindow(
                service.resolve(LOCATION_ID, replica(NEW_YORK, inverted, null), LocalDate.parse("2026-09-15")),
                ShiftStatus.UNKNOWN);
        String zeroLength = """
                [{"dayOfWeek":"TUESDAY","openTime":"08:00","closeTime":"08:00"}]
                """;
        assertNoWindow(
                service.resolve(LOCATION_ID, replica(NEW_YORK, zeroLength, null), LocalDate.parse("2026-09-15")),
                ShiftStatus.UNKNOWN);
    }

    @Test
    @DisplayName("BR6: the check-in and cleanup buffers do not widen or narrow the window")
    void buffersAreIgnored() {
        ExtLocationReplica withBuffers = ExtLocationReplica.builder()
                .locationId(LOCATION_ID)
                .timezone(NEW_YORK)
                .operatingHours(ALL_WEEK_08_17)
                .checkInBufferMinutes(15)
                .cleanupBufferMinutes(30)
                .build();

        ShiftWindow window = service.resolve(LOCATION_ID, withBuffers, LocalDate.parse("2026-09-15"));

        assertThat(window.start()).isEqualTo(Instant.parse("2026-09-15T12:00:00Z"));
        assertThat(window.end()).isEqualTo(Instant.parse("2026-09-15T21:00:00Z"));
        assertThat(window.minutes()).isEqualTo(540);
    }
}
