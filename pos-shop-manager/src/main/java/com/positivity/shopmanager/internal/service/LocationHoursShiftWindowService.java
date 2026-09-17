package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.entity.ExtLocationReplica;
import com.positivity.shopmanager.internal.enums.ShiftSource;
import com.positivity.shopmanager.internal.enums.ShiftStatus;
import com.positivity.shopmanager.internal.service.LocationHoursParser.RawOperatingHoursEntry;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * PLACEHOLDER — derives a mechanic's shift window from the <em>shop location's</em> operating
 * hours (issue #2060). It is a stand-in, not a schedule.
 *
 * <p>The platform has no per-person shift schedule: {@code pos-people}'s staffing assignments are
 * date-granular, {@link StaffingScheduleService} synthesises day-long blocks for the same reason,
 * and the HR availability contract that would carry a real window is still the open CRITICAL
 * question on #271 that blocks #71. Rather than answer that here, this service reads the
 * operating-hours facts {@code ext_location} already mirrors from pos-location (#2023) through the
 * one existing reader, {@link LocationHoursParser}, and hands the dispatch board a window it can
 * subtract committed time from.
 *
 * <p><b>Every mechanic at a location gets the same window</b> (BR1). That is the defining
 * simplification and the reason this is a placeholder: staggered shifts, part-timers, split shifts,
 * overtime and PTO are all invisible to it. The window is the shop's hours, never the person's
 * roster, and {@link ShiftSource#LOCATION_HOURS} says so on every entry.
 *
 * <p>Rules, in order: a dated holiday closure wins ({@link ShiftStatus#CLOSED}, BR3); a missing or
 * unrecognised timezone, unparsable hours, or a weekday with no entry is {@link ShiftStatus#UNKNOWN}
 * — never a default 08:00–17:00 (BR2); the open and close times are resolved in the location's
 * zone and emitted as UTC instants so a DST transition day yields its real elapsed span (BR4); an
 * open time that is not before its close time is logged and reported {@code UNKNOWN} rather than
 * as a negative span, because the replica is untrusted input (BR5); the check-in and cleanup
 * buffers are appointment concerns and are not folded in (BR6).
 *
 * <p><b>To delete when #71 lands:</b> this class, {@link ShiftSource#LOCATION_HOURS}'s placeholder
 * wording (the enum itself stays, gaining the real source), the "Placeholder: mechanic shift
 * window" section of {@code pos-shop-manager/README.md}, and the placeholder sentences in
 * {@code TechnicianController.listLocationTechnicians}'s OpenAPI description and the
 * {@code shift*} field schemas on {@code LocationTechnicianRosterEntryResponse}. The fields
 * themselves are the contract the real implementation fills.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocationHoursShiftWindowService {

    private final LocationHoursParser locationHoursParser;

    /**
     * One derived window. {@code start}, {@code end} and {@code minutes} are all set for {@link
     * ShiftStatus#DERIVED} and all null otherwise.
     */
    public record ShiftWindow(
            @NonNull ShiftStatus status,
            @NonNull ShiftSource source,
            @Nullable Instant start,
            @Nullable Instant end,
            @Nullable Integer minutes) {

        static ShiftWindow unknown() {
            return new ShiftWindow(ShiftStatus.UNKNOWN, ShiftSource.LOCATION_HOURS, null, null, null);
        }

        static ShiftWindow closed() {
            return new ShiftWindow(ShiftStatus.CLOSED, ShiftSource.LOCATION_HOURS, null, null, null);
        }

        static ShiftWindow derived(Instant start, Instant end) {
            int minutes = (int) Duration.between(start, end).toMinutes();
            return new ShiftWindow(ShiftStatus.DERIVED, ShiftSource.LOCATION_HOURS, start, end, minutes);
        }
    }

    /**
     * The location's operating window on {@code date}, which every mechanic on that day's roster
     * receives unchanged.
     *
     * @param locationId the shop location, for logging
     * @param location the location's replica row, or {@code null} when none has arrived yet
     * @param date the roster date, already resolved in the location's own zone by the caller
     * @return the window; never null, and never a default when the facts are missing
     */
    public @NonNull ShiftWindow resolve(
            @NonNull UUID locationId, @Nullable ExtLocationReplica location, @NonNull LocalDate date) {
        if (location == null) {
            return ShiftWindow.unknown();
        }
        ZoneId zoneId = locationHoursParser.parseZone(locationId, location.getTimezone());
        if (zoneId == null || location.getOperatingHours() == null) {
            return ShiftWindow.unknown();
        }
        // BR3: a confirmed closure is a fact and outranks the weekly hours.
        if (locationHoursParser
                .parseHolidayClosures(locationId, location.getHolidayClosures())
                .containsKey(date)) {
            return ShiftWindow.closed();
        }
        Map<DayOfWeek, RawOperatingHoursEntry> hoursByDow =
                locationHoursParser.parseOperatingHours(locationId, location.getOperatingHours());
        if (hoursByDow == null) {
            return ShiftWindow.unknown();
        }
        RawOperatingHoursEntry entry = hoursByDow.get(date.getDayOfWeek());
        if (entry == null) {
            // BR2: no entry for the weekday is "unknown" for a person's shift, not a closure —
            // the capacity read may call the bay CLOSED, but nobody's roster is asserted here.
            return ShiftWindow.unknown();
        }
        return toWindow(locationId, zoneId, date, entry);
    }

    private ShiftWindow toWindow(UUID locationId, ZoneId zoneId, LocalDate date, RawOperatingHoursEntry entry) {
        LocalTime openTime;
        LocalTime closeTime;
        try {
            openTime = LocalTime.parse(entry.openTime());
            closeTime = LocalTime.parse(entry.closeTime());
        } catch (Exception e) {
            log.warn(
                    "Location {} has an unreadable operatingHours entry for {}; shift window is UNKNOWN",
                    locationId,
                    date.getDayOfWeek(),
                    e);
            return ShiftWindow.unknown();
        }
        if (!openTime.isBefore(closeTime)) {
            // BR5: pos-location validates this on write, but the replica is untrusted input.
            log.warn(
                    "Location {} operatingHours entry for {} has openTime {} not before closeTime {}; shift"
                            + " window is UNKNOWN rather than a negative span",
                    locationId,
                    date.getDayOfWeek(),
                    openTime,
                    closeTime);
            return ShiftWindow.unknown();
        }
        // BR4: resolve in the location's zone, emit UTC — the same conversion
        // ScheduleCapacityServiceImpl.assembleDay makes, so a DST day spans its real length.
        Instant start = ZonedDateTime.of(date.atTime(openTime), zoneId).toInstant();
        Instant end = ZonedDateTime.of(date.atTime(closeTime), zoneId).toInstant();
        return ShiftWindow.derived(start, end);
    }
}
