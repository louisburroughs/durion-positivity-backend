package com.positivity.shopmanager.internal.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.shopmanager.internal.dto.ScheduleCapacityResponse;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.ExtBayReplica;
import com.positivity.shopmanager.internal.entity.ExtLocationReplica;
import com.positivity.shopmanager.internal.enums.ScheduleCapacityDayStatus;
import com.positivity.shopmanager.internal.exception.ScheduleCapacityRangeExceededException;
import com.positivity.shopmanager.internal.exception.ShopManagerValidationException;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.ExtBayReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtLocationReplicaRepository;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles {@code GET /v1/schedules/capacity} from this module's local replicas (issue #2023).
 *
 * <h2>Query budget (AC7)</h2>
 *
 * The whole response costs a <em>fixed</em> number of statements, independent of the number of
 * days requested:
 *
 * <ol>
 *   <li>the location replica row, for timezone/hours/closures;
 *   <li>the active bays at the location, in display order;
 *   <li>one appointment query spanning the entire {@code [from, to]} range.
 * </ol>
 *
 * <p>Building the per-day, per-bay occupancy from those three result sets is pure in-memory work —
 * looping days never issues another query, which is what keeps this endpoint from simply moving
 * {@code getScheduleView}'s per-day fan-out (#2023 F2/F6) onto the server.
 *
 * <h2>Day status precedence</h2>
 *
 * For each date, in order: no replica row or an unknown/blank timezone is {@code UNAVAILABLE}
 * (never silently UTC, AC11); weekly hours never configured is {@code UNAVAILABLE} (an unknown fact
 * is not a closure); a dated {@code holidayClosures} entry for the date is {@code HOLIDAY}, which
 * wins over an otherwise-open weekday because it is the more specific, dated fact
 * (DECISION-SHOPMGMT-008); an empty hours list, or no entry for the date's day-of-week, is {@code
 * CLOSED}; anything else — including a malformed hours entry for that one date — is {@code OK} or,
 * failing that, {@code UNAVAILABLE} for that date alone, never omitted (AC4).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleCapacityServiceImpl implements ScheduleCapacityService {

    /** Policy limit on the requested span, inclusive of both endpoints (issue #2023 AC3). */
    static final int MAX_RANGE_DAYS = 42;

    private final Clock clock;
    private final ExtLocationReplicaRepository extLocationReplicaRepository;
    private final ExtBayReplicaRepository extBayReplicaRepository;
    private final AppointmentRepository appointmentRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public @NonNull ScheduleCapacityResponse getCapacity(
            @NonNull UUID locationId, @NonNull LocalDate from, @NonNull LocalDate to) {
        validateRange(from, to);

        ExtLocationReplica location =
                extLocationReplicaRepository.findById(locationId).orElse(null);
        List<ExtBayReplica> bays = extBayReplicaRepository.findActiveByLocationOrdered(locationId);

        ZoneId zoneId = resolveZoneId(location);
        boolean hoursNeverConfigured = location == null || location.getOperatingHours() == null;
        Map<DayOfWeek, RawOperatingHoursEntry> hoursByDow =
                hoursNeverConfigured ? null : parseOperatingHours(locationId, location.getOperatingHours());
        Map<LocalDate, String> closuresByDate =
                location == null ? Map.of() : parseHolidayClosures(locationId, location.getHolidayClosures());

        List<LocalDate> dates = from.datesUntil(to.plusDays(1)).toList();
        List<DayAssembly> assemblies = dates.stream()
                .map(date -> assembleDay(date, zoneId, hoursNeverConfigured, hoursByDow, closuresByDate))
                .toList();

        // One query spanning the whole range (AC7) — never one per day, and skipped entirely when
        // the timezone cannot be resolved, since no day in the range can be OK in that case anyway.
        Map<String, List<Appointment>> appointmentsByBay = zoneId == null
                ? Map.of()
                : appointmentRepository
                        .findBayAppointmentsForCapacity(
                                locationId,
                                to.plusDays(1).atStartOfDay(zoneId).toInstant(),
                                from.atStartOfDay(zoneId).toInstant())
                        .stream()
                        .collect(Collectors.groupingBy(Appointment::getResourceId));

        List<ScheduleCapacityResponse.DayCapacityView> dayViews = assemblies.stream()
                .map(assembly -> toDayView(assembly, bays, appointmentsByBay))
                .toList();

        ScheduleCapacityResponse response = new ScheduleCapacityResponse();
        response.setLocationId(locationId);
        response.setFrom(from);
        response.setTo(to);
        response.setTimezone(zoneId == null ? null : zoneId.getId());
        response.setViewGeneratedAt(Instant.now(clock));
        response.setDays(dayViews);
        return response;
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new ShopManagerValidationException("to must not be before from");
        }
        long spanDays = ChronoUnit.DAYS.between(from, to) + 1;
        if (spanDays > MAX_RANGE_DAYS) {
            throw new ScheduleCapacityRangeExceededException(MAX_RANGE_DAYS, spanDays);
        }
    }

    /** Null when the location has no replica row, or its timezone is unknown/blank/unparseable (AC11). */
    private @Nullable ZoneId resolveZoneId(@Nullable ExtLocationReplica location) {
        if (location == null) {
            return null;
        }
        String timezone = location.getTimezone();
        if (timezone == null || timezone.isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException e) {
            log.warn(
                    "Location {} has unrecognised timezone '{}'; capacity days are reported UNAVAILABLE",
                    location.getLocationId(),
                    timezone);
            return null;
        }
    }

    /**
     * Parses the replicated {@code operating_hours} JSON into a per-day-of-week map.
     *
     * @return {@code null} when the JSON cannot be parsed at all (distinct from an empty, valid
     *     list) so the caller reports every date {@code UNAVAILABLE} rather than asserting a
     *     closure the data does not actually confirm
     */
    private @Nullable Map<DayOfWeek, RawOperatingHoursEntry> parseOperatingHours(UUID locationId, String json) {
        try {
            List<RawOperatingHoursEntry> entries =
                    objectMapper.readValue(json, new TypeReference<List<RawOperatingHoursEntry>>() {});
            Map<DayOfWeek, RawOperatingHoursEntry> byDayOfWeek = new EnumMap<>(DayOfWeek.class);
            for (RawOperatingHoursEntry entry : entries) {
                if (entry == null || entry.dayOfWeek() == null) {
                    continue;
                }
                try {
                    byDayOfWeek.put(DayOfWeek.valueOf(entry.dayOfWeek()), entry);
                } catch (IllegalArgumentException e) {
                    log.warn(
                            "Location {} operatingHours entry has an unrecognised dayOfWeek '{}'; skipping it",
                            locationId,
                            entry.dayOfWeek());
                }
            }
            return byDayOfWeek;
        } catch (Exception e) {
            log.warn(
                    "Location {} has unparsable operatingHours; capacity days are reported UNAVAILABLE", locationId, e);
            return null;
        }
    }

    /**
     * Parses the replicated {@code holiday_closures} JSON into date-keyed reasons. A malformed
     * payload degrades to "no closures known" rather than failing the whole request — this is
     * supplementary information layered on top of an otherwise-assemblable day, not a precondition
     * for assembling it.
     */
    private Map<LocalDate, String> parseHolidayClosures(UUID locationId, @Nullable String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            List<RawHolidayClosure> entries =
                    objectMapper.readValue(json, new TypeReference<List<RawHolidayClosure>>() {});
            Map<LocalDate, String> byDate = new HashMap<>();
            for (RawHolidayClosure entry : entries) {
                if (entry == null || entry.date() == null) {
                    continue;
                }
                byDate.put(LocalDate.parse(entry.date()), entry.reason());
            }
            return byDate;
        } catch (Exception e) {
            log.warn("Location {} has unparsable holidayClosures; treating the range as having none", locationId, e);
            return Map.of();
        }
    }

    private DayAssembly assembleDay(
            LocalDate date,
            @Nullable ZoneId zoneId,
            boolean hoursNeverConfigured,
            @Nullable Map<DayOfWeek, RawOperatingHoursEntry> hoursByDow,
            Map<LocalDate, String> closuresByDate) {
        if (zoneId == null || hoursNeverConfigured || hoursByDow == null) {
            return DayAssembly.unavailable(date);
        }
        if (closuresByDate.containsKey(date)) {
            return DayAssembly.holiday(date, closuresByDate.get(date));
        }
        RawOperatingHoursEntry entry = hoursByDow.get(date.getDayOfWeek());
        if (entry == null) {
            return DayAssembly.closed(date);
        }
        try {
            LocalTime openTime = LocalTime.parse(entry.openTime());
            LocalTime closeTime = LocalTime.parse(entry.closeTime());
            if (!openTime.isBefore(closeTime)) {
                throw new IllegalStateException("openTime must be before closeTime");
            }
            Instant dayStartAt = ZonedDateTime.of(date.atTime(openTime), zoneId).toInstant();
            Instant dayEndAt = ZonedDateTime.of(date.atTime(closeTime), zoneId).toInstant();
            return DayAssembly.ok(date, dayStartAt, dayEndAt);
        } catch (Exception e) {
            // This date's own hours entry is malformed (e.g. a missing or out-of-order time). Only
            // this date fails to assemble; every other date in the range is unaffected (AC4).
            log.warn("Date {} has an unassemblable operatingHours entry; reporting UNAVAILABLE", date, e);
            return DayAssembly.unavailable(date);
        }
    }

    private ScheduleCapacityResponse.DayCapacityView toDayView(
            DayAssembly assembly, List<ExtBayReplica> bays, Map<String, List<Appointment>> appointmentsByBay) {
        ScheduleCapacityResponse.DayCapacityView view = new ScheduleCapacityResponse.DayCapacityView();
        view.setDate(assembly.date());
        view.setStatus(assembly.status());
        view.setClosureReason(assembly.closureReason());
        view.setDayStartAt(assembly.dayStartAt());
        view.setDayEndAt(assembly.dayEndAt());
        view.setBays(
                assembly.status() == ScheduleCapacityDayStatus.OK
                        ? buildBayViews(assembly, bays, appointmentsByBay)
                        : List.of());
        return view;
    }

    /**
     * Every active bay for one OK date, occupancy included even for a bay with zero appointments
     * (AC9 — the F2 gap this endpoint exists to close).
     */
    private List<ScheduleCapacityResponse.BayCapacityView> buildBayViews(
            DayAssembly day, List<ExtBayReplica> bays, Map<String, List<Appointment>> appointmentsByBay) {
        int slotCount = computeSlotCount(day.dayStartAt(), day.dayEndAt());
        List<ScheduleCapacityResponse.BayCapacityView> views = new ArrayList<>(bays.size());
        for (ExtBayReplica bay : bays) {
            List<Appointment> bayAppointments =
                    appointmentsByBay.getOrDefault(bay.getBayId().toString(), List.of());
            int[] occupancy = new int[slotCount];
            long occupiedMinutes = 0;
            for (Appointment appointment : bayAppointments) {
                Instant overlapStart = maxInstant(appointment.getStartAt(), day.dayStartAt());
                Instant overlapEnd = minInstant(appointment.getEndAt(), day.dayEndAt());
                if (!overlapStart.isBefore(overlapEnd)) {
                    continue;
                }
                occupiedMinutes += Duration.between(overlapStart, overlapEnd).toMinutes();
                markSlots(occupancy, day.dayStartAt(), overlapStart, overlapEnd);
            }
            ScheduleCapacityResponse.BayCapacityView view = new ScheduleCapacityResponse.BayCapacityView();
            view.setBayId(bay.getBayId());
            view.setName(bay.getName());
            view.setOccupiedMinutes((int) occupiedMinutes);
            view.setOccupancy(Arrays.stream(occupancy).boxed().toList());
            views.add(view);
        }
        return views;
    }

    /** One slot per hour of the window; a partial trailing hour still gets a slot (AC6). */
    private int computeSlotCount(Instant dayStartAt, Instant dayEndAt) {
        long seconds = Duration.between(dayStartAt, dayEndAt).getSeconds();
        return (int) Math.ceil(seconds / 3600.0);
    }

    private void markSlots(int[] occupancy, Instant dayStartAt, Instant overlapStart, Instant overlapEnd) {
        long firstSlot = Duration.between(dayStartAt, overlapStart).getSeconds() / 3600;
        long lastSlotExclusive =
                (long) Math.ceil(Duration.between(dayStartAt, overlapEnd).getSeconds() / 3600.0);
        int from = (int) Math.max(0, firstSlot);
        int to = (int) Math.min(occupancy.length, lastSlotExclusive);
        for (int slot = from; slot < to; slot++) {
            occupancy[slot]++;
        }
    }

    private Instant maxInstant(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private Instant minInstant(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    /** Raw shape of one {@code operating_hours} array element, deserialized as strings only. */
    private record RawOperatingHoursEntry(String dayOfWeek, String openTime, String closeTime) {}

    /** Raw shape of one {@code holiday_closures} array element, deserialized as strings only. */
    private record RawHolidayClosure(String date, String reason) {}

    private record DayAssembly(
            LocalDate date,
            ScheduleCapacityDayStatus status,
            @Nullable String closureReason,
            @Nullable Instant dayStartAt,
            @Nullable Instant dayEndAt) {

        static DayAssembly unavailable(LocalDate date) {
            return new DayAssembly(date, ScheduleCapacityDayStatus.UNAVAILABLE, null, null, null);
        }

        static DayAssembly closed(LocalDate date) {
            return new DayAssembly(date, ScheduleCapacityDayStatus.CLOSED, null, null, null);
        }

        static DayAssembly holiday(LocalDate date, @Nullable String reason) {
            return new DayAssembly(date, ScheduleCapacityDayStatus.HOLIDAY, reason, null, null);
        }

        static DayAssembly ok(LocalDate date, Instant dayStartAt, Instant dayEndAt) {
            return new DayAssembly(date, ScheduleCapacityDayStatus.OK, null, dayStartAt, dayEndAt);
        }
    }
}
