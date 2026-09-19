package com.positivity.shopmanager.internal.service;

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
import com.positivity.shopmanager.internal.repository.WorkOrderAppointmentMappingRepository;
import com.positivity.shopmanager.internal.repository.WorkorderActuals;
import com.positivity.shopmanager.internal.service.LocationHoursParser.RawOperatingHoursEntry;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles {@code GET /v1/schedules/capacity} from this module's local replicas (issue #2023,
 * extended for actual-vs-planned carry-over by issue #2021 and for pre-range overruns by issue
 * #2050).
 *
 * <h2>Query budget (AC7, #2021 AC5)</h2>
 *
 * The response costs a <em>fixed</em> number of statements, independent of the number of days
 * requested:
 *
 * <ol>
 *   <li>the location replica row, for timezone/hours/closures;
 *   <li>the active bays at the location, in display order;
 *   <li>one appointment query spanning the entire assembly window — {@code [from -
 *       CARRY_OVER_LOOKBACK_DAYS, to]}, see below;
 *   <li>one batch workorder-actuals query for every appointment fetched by (3) — issued only when
 *       (3) returned at least one row, so a location with nothing booked still costs 3.
 * </ol>
 *
 * <p>Building the per-day, per-bay occupancy — and now the carry-over overflow between days — from
 * those result sets is pure in-memory work: looping days never issues another query, which is what
 * keeps this endpoint from simply moving {@code getScheduleView}'s per-day fan-out (#2023 F2/F6)
 * onto the server. The lookback days of (3) are therefore free of statements: they are more rows on
 * one query that was already issued, and more in-memory day assemblies off the single {@code
 * ExtLocationReplica} row already loaded. The batch (4) deliberately <em>includes</em> the lookback
 * appointments — without their actuals a pre-range job falls back to its planned window and #2050
 * survives the fix — and it is already one statement gated on a non-empty id list, so covering them
 * adds none.
 *
 * <h2>Assembly window vs emission window (#2050)</h2>
 *
 * Two distinct ranges run through this class:
 *
 * <ul>
 *   <li>the <em>assembly</em> window, {@code [from - CARRY_OVER_LOOKBACK_DAYS, to]}, which pass 1 and
 *       pass 2 operate over;
 *   <li>the <em>emission</em> window, the requested {@code [from, to]}, which the response's {@code
 *       days} list is built from — one entry per date, in order, never omitting one (AC4).
 * </ul>
 *
 * <p>Pass 1's accumulators for pre-range days are built and then simply never emitted, and that is
 * precisely what makes #2050 AC6 work: a job whose effective window ends inside a pre-range open
 * day's own window lands entirely in a discarded accumulator and contributes nothing in-range. Only
 * what pass 2 carries forward past a pre-range close, hop by hop, can reach an emitted day. The
 * requested {@code from}/{@code to}/{@code timezone}/{@code viewGeneratedAt} on the response are
 * untouched by the lookback: a 42-day request still returns exactly 42 {@code DayCapacityView}s, and
 * {@link #validateRange} still measures the <em>requested</em> range alone, so the lookback never
 * counts toward the {@link #MAX_RANGE_DAYS} policy limit.
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
 *
 * <p>The same precedence runs over the lookback days, which are never emitted but do gate carry-over.
 * A malformed hours entry on a <em>lookback</em> day makes that pre-range day {@code UNAVAILABLE},
 * so an appointment whose last direct overlap would have been that day has no source day to overrun
 * and its carry-over is lost — the same degradation the existing in-range behaviour already has for
 * a malformed in-range day, never an exception.
 *
 * <h2>Actual-vs-planned occupancy and carry-over (#2021 AC1-AC6, #2050)</h2>
 *
 * Every appointment contributes its <em>effective</em> window to occupancy: the linked workorder's
 * actual {@code workStartedAt}/{@code completedAt} when known (resolved through {@code
 * WorkOrderAppointmentMapping}, #2021 F9), else the appointment's own planned {@code startAt}/{@code
 * endAt}. This is the same per-day overlap computation {@code #2023} always used, just fed the
 * better time when one is known — never a second, independently-derived number that could disagree
 * with it (AC5). An appointment's own planned window is never mutated by this: {@code startAt}/
 * {@code endAt} keep meaning the planned window (#2021 F2).
 *
 * <p>When an appointment's effective window runs past the close of the last day it directly
 * overlaps, the excess is carried onto the next {@code OK} day for the same bay (AC4), skipping any
 * {@code CLOSED}/{@code HOLIDAY}/{@code UNAVAILABLE} days in between (AC6). The carry-over minutes
 * are added directly into that day's {@code occupiedMinutes}/{@code occupancy} — netted, not
 * annotated (AC5) — so a board can explain the number. A carry-over target beyond the requested
 * range has nothing to net against and is silently dropped rather than fabricating a day.
 *
 * <p>An overrun longer than one operating day's own window is <em>distributed</em> across as many
 * successive {@code OK} days as it takes to exhaust it (#2023 F8), rather than dumped in full onto
 * the first one: each day absorbs at most its own window's worth of minutes — keeping {@code
 * occupiedMinutes} and {@code occupancy} consistent with each other on every day the carry-over
 * touches — and whatever does not fit continues on to the next {@code OK} day.
 *
 * <h2>What {@code carryOverIn} lists (#2050 AC1)</h2>
 *
 * On any {@code OK} day, for each bay, every appointment that contributes minutes to that day
 * <em>and whose effective window began on an earlier local date than that day</em> is listed once in
 * that bay's {@code carryOverIn}. {@code bayHours} is the total minutes that appointment contributes
 * to that day — direct overlap, re-anchored carry-over, or both — in tenths of an hour. {@code
 * fromDate} is the local date the effective window began on (actual start when known, else planned
 * start). {@code occupiedMinutes} and {@code occupancy} are untouched by this: it is pure annotation
 * over numbers passes 1 and 2 already produce.
 *
 * <p>This replaces the narrower "minutes re-anchored onto this day from a prior day's overrun", which
 * could not express #2050 AC1 at all: in the reproduction (open 08:00-17:00; planned Monday
 * 15:00-17:00; actuals Monday 15:00 to Tuesday 11:00) Tuesday's 180 minutes arrive through
 * <em>direct overlap</em>, not re-anchoring, so the old rule reported an empty {@code carryOverIn} on
 * the one day a reader most needs the explanation. The new rule is a strict superset of the old one —
 * every view the old rule emitted is still emitted, with the same {@code fromDate} and {@code
 * bayHours} — so it costs no existing guarantee.
 *
 * <p>Two details the rule turns on:
 *
 * <ul>
 *   <li>The comparison is on <em>local dates</em>, not {@code effectiveStart < dayStartAt}. An
 *       appointment planned 06:00-10:00 at a location open 08:00-17:00 begins before its own day's
 *       open time and must not be mislabelled carry-over. Comparing dates is safe because {@link
 *       #assembleDay} rejects any hours entry whose {@code openTime} is not before its {@code
 *       closeTime}, so a day window never crosses midnight.
 *   <li>{@code fromDate} is derived from the appointment's own effective start, not from the last day
 *       its window directly overlapped. That makes it a function of the data rather than of how far
 *       the lookback happens to reach, which is what #2050 AC2 needs: two overlapping legal requests
 *       must not disagree about a date they share.
 * </ul>
 *
 * <p>Each bay's {@code carryOverIn} is ordered by {@code (fromDate, appointmentId)}. Under the
 * widened rule a bay-day routinely holds several views, and the source ordering — a {@code HashMap}
 * iteration over a query with no {@code ORDER BY} — was never deterministic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleCapacityServiceImpl implements ScheduleCapacityService {

    /** Policy limit on the requested span, inclusive of both endpoints (issue #2023 AC3). */
    static final int MAX_RANGE_DAYS = 42;

    /**
     * How far before {@code from} the capacity read looks for an in-flight job whose actuals spill
     * into the requested range (issue #2050 AC3). The appointment query's lower bound becomes {@code
     * from - CARRY_OVER_LOOKBACK_DAYS}, and those extra days are assembled but never emitted.
     *
     * <p>It equals {@link #MAX_RANGE_DAYS} because that is exactly the bound at which #2050 AC2
     * becomes <em>provable</em> rather than merely true in practice. AC2 requires that two legal
     * requests never disagree about a date they both cover. If a date D appears in two legal
     * requests, then D is at most {@code MAX_RANGE_DAYS - 1} days after either request's {@code
     * from}, so a lookback of {@code MAX_RANGE_DAYS} always reaches back past the day any job
     * overrunning into D can have originated on — for both requests. Neither can therefore be missing
     * a row the other saw, and the two agree about D by construction. A shorter bound (14 days, say)
     * covers every realistic client, but it leaves a constructible pair of legal 42-day requests that
     * still disagree, and "no client does that yet" is not the guarantee AC2 asks for.
     *
     * <p>The cost is bounded and one-off, not proportional to anything: at most {@code
     * MAX_RANGE_DAYS} extra days of appointment rows fetched on a query that was already issued, and
     * then discarded. It adds no statement (see the class-level query budget).
     *
     * <p>Degradation: an overrun originating more than {@code CARRY_OVER_LOOKBACK_DAYS} before {@code
     * from} is not reported. A job that has been running for longer than the policy maximum range is
     * outside what this read claims to explain.
     */
    static final int CARRY_OVER_LOOKBACK_DAYS = MAX_RANGE_DAYS;

    private final Clock clock;
    private final ExtLocationReplicaRepository extLocationReplicaRepository;
    private final ExtBayReplicaRepository extBayReplicaRepository;
    private final AppointmentRepository appointmentRepository;
    private final WorkOrderAppointmentMappingRepository workOrderAppointmentMappingRepository;
    private final LocationHoursParser locationHoursParser;

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
        Map<DayOfWeek, RawOperatingHoursEntry> hoursByDow = hoursNeverConfigured
                ? null
                : locationHoursParser.parseOperatingHours(locationId, location.getOperatingHours());
        Map<LocalDate, String> closuresByDate = location == null
                ? Map.of()
                : locationHoursParser.parseHolidayClosures(locationId, location.getHolidayClosures());

        // The assembly window reaches CARRY_OVER_LOOKBACK_DAYS before the requested from, so a job
        // whose planned window sits entirely before the range but whose actuals overrun into it has
        // a source day to overrun from (#2050). Those pre-range days feed both passes and are then
        // dropped: only the requested [from, to] is emitted.
        LocalDate assemblyFrom = from.minusDays(CARRY_OVER_LOOKBACK_DAYS);
        List<DayAssembly> assemblies = assemblyFrom
                .datesUntil(to.plusDays(1))
                .map(date -> assembleDay(date, zoneId, hoursNeverConfigured, hoursByDow, closuresByDate))
                .toList();

        // One query spanning the whole assembly window (AC7) — never one per day, and skipped
        // entirely when the timezone cannot be resolved, since no day in the range can be OK in that
        // case anyway (and no lookback is meaningful without a zone to anchor it in).
        // Bay membership is resolved here, in memory, against the active bay roster already loaded
        // above: Appointment.resourceType is never written by any production create path (#2023
        // F1), so the SQL only narrows by locationId/range/status and this grouping step is what
        // actually decides which rows occupy a bay.
        Set<UUID> activeBayIds = bays.stream().map(ExtBayReplica::getBayId).collect(Collectors.toSet());
        Map<UUID, List<Appointment>> appointmentsByBay = zoneId == null
                ? Map.of()
                : groupByBay(
                        appointmentRepository.findAppointmentsForCapacity(
                                locationId,
                                to.plusDays(1).atStartOfDay(zoneId).toInstant(),
                                assemblyFrom.atStartOfDay(zoneId).toInstant()),
                        activeBayIds);

        // One more query (#2021), only when there is something to resolve: the workorder
        // actual-time block for every appointment (3) fetched — including the lookback ones, whose
        // actuals are the whole point of fetching them — batched rather than looped per appointment,
        // the same fixed-count discipline AC7 already enforces.
        Map<UUID, WorkorderActuals> actualsByAppointmentId = resolveActuals(appointmentsByBay);

        Map<UUID, AppointmentCarryOverContext> carryOverContexts =
                zoneId == null ? Map.of() : buildCarryOverContexts(appointmentsByBay, actualsByAppointmentId, zoneId);

        List<ScheduleCapacityResponse.DayCapacityView> dayViews =
                assembleDayViews(assemblies, from, bays, appointmentsByBay, actualsByAppointmentId, carryOverContexts);

        ScheduleCapacityResponse response = new ScheduleCapacityResponse();
        response.setLocationId(locationId);
        response.setFrom(from);
        response.setTo(to);
        response.setTimezone(zoneId == null ? null : zoneId.getId());
        response.setViewGeneratedAt(Instant.now(clock));
        response.setDays(dayViews);
        return response;
    }

    /**
     * Measures the <em>requested</em> span against {@link #MAX_RANGE_DAYS} (#2023 AC3) — deliberately
     * before, and independent of, the {@link #CARRY_OVER_LOOKBACK_DAYS} widening. The lookback is an
     * implementation detail of how the range is served and must never count toward the policy limit,
     * or a legal 42-day request would start being rejected with 422.
     */
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

    /**
     * Groups every appointment fetched for the range by the active bay it occupies (#2023 F1).
     *
     * <p>{@link Appointment#getResourceType()} is never populated on the production create path, so
     * bay membership cannot be read off the row: it is derived from {@link
     * Appointment#getResourceId()} against {@code activeBayIds}, the same active-bay roster already
     * loaded for the request. A {@code resourceId} that is not a UUID, or that is a UUID but does
     * not name an active bay at this location (unassigned, a technician lane, or a since-deactivated
     * bay), simply is not a bay for this read — never an error, and never grouped.
     */
    private Map<UUID, List<Appointment>> groupByBay(List<Appointment> appointments, Set<UUID> activeBayIds) {
        Map<UUID, List<Appointment>> byBay = new HashMap<>();
        for (Appointment appointment : appointments) {
            UUID bayId = parseBayId(appointment.getResourceId());
            if (bayId == null || !activeBayIds.contains(bayId)) {
                continue;
            }
            byBay.computeIfAbsent(bayId, ignored -> new ArrayList<>()).add(appointment);
        }
        return byBay;
    }

    /** Null for a blank/non-UUID {@code resourceId} (unassigned or a technician lane), not an error. */
    private @Nullable UUID parseBayId(@Nullable String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(resourceId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Batches the workorder actual-time lookup for every appointment {@link
     * #getCapacity} fetched (#2021 AC1-AC6), in one query rather than one per appointment — an
     * appointment with no linked workorder, or one whose workorder has not replicated, simply has
     * no entry.
     *
     * <p>An appointment can resolve more than one mapping row (#2023 F3 — appointment -> mapping is
     * one-to-many; only {@code workOrderId} is unique in the baseline schema), so the merge function
     * collapses duplicates through {@link WorkorderActuals#mostCurrent} rather than letting {@link
     * Collectors#toMap(java.util.function.Function, java.util.function.Function)} throw on a
     * duplicate key.
     */
    private Map<UUID, WorkorderActuals> resolveActuals(Map<UUID, List<Appointment>> appointmentsByBay) {
        List<UUID> appointmentIds = appointmentsByBay.values().stream()
                .flatMap(List::stream)
                .map(Appointment::getAppointmentId)
                .distinct()
                .toList();
        if (appointmentIds.isEmpty()) {
            return Map.of();
        }
        return workOrderAppointmentMappingRepository.findActualsByAppointmentIds(appointmentIds).stream()
                .collect(Collectors.toMap(
                        WorkorderActuals::appointmentId, actuals -> actuals, WorkorderActuals::mostCurrent));
    }

    /**
     * Builds every date's view in two passes over the same day/bay/appointment data so carry-over
     * (pass 2) can never disagree with the occupancy (pass 1) it is layered on top of (#2021 AC5),
     * plus a final annotation step that derives {@code carryOverIn} from what those two passes
     * actually contributed (#2050 AC1).
     *
     * <p>{@code assemblies} spans the assembly window; {@code emissionFrom} is the requested {@code
     * from}. Every assembly participates in both passes, and only those on or after {@code
     * emissionFrom} become a {@code DayCapacityView}.
     */
    private List<ScheduleCapacityResponse.DayCapacityView> assembleDayViews(
            List<DayAssembly> assemblies,
            LocalDate emissionFrom,
            List<ExtBayReplica> bays,
            Map<UUID, List<Appointment>> appointmentsByBay,
            Map<UUID, WorkorderActuals> actualsByAppointmentId,
            Map<UUID, AppointmentCarryOverContext> carryOverContexts) {

        // Pass 1: base occupancy per OK day, fed each appointment's effective (actual-if-known,
        // else planned) window. Tracks, per appointment, the last (chronologically latest) OK day
        // its effective window actually overlapped — carry-over in pass 2 only ever looks forward
        // from there.
        Map<LocalDate, Map<UUID, BayDayAccumulator>> accumulatorsByDate = new HashMap<>();
        Map<UUID, OverrunTracker> lastOverlapByAppointment = new HashMap<>();

        for (DayAssembly assembly : assemblies) {
            if (assembly.status() != ScheduleCapacityDayStatus.OK) {
                continue;
            }
            int slotCount = computeSlotCount(assembly.dayStartAt(), assembly.dayEndAt());
            Map<UUID, BayDayAccumulator> byBay = new HashMap<>();
            for (ExtBayReplica bay : bays) {
                BayDayAccumulator accumulator = new BayDayAccumulator(slotCount);
                List<Appointment> bayAppointments = appointmentsByBay.getOrDefault(bay.getBayId(), List.of());
                for (Appointment appointment : bayAppointments) {
                    WorkorderActuals actuals = actualsByAppointmentId.get(appointment.getAppointmentId());
                    Instant effectiveStart = effectiveStart(appointment, actuals);
                    Instant effectiveEnd = effectiveEnd(appointment, actuals);
                    Instant overlapStart = maxInstant(effectiveStart, assembly.dayStartAt());
                    Instant overlapEnd = minInstant(effectiveEnd, assembly.dayEndAt());
                    if (!overlapStart.isBefore(overlapEnd)) {
                        continue;
                    }
                    long overlapMinutes =
                            Duration.between(overlapStart, overlapEnd).toMinutes();
                    accumulator.occupiedMinutes += overlapMinutes;
                    accumulator.recordContribution(appointment.getAppointmentId(), overlapMinutes);
                    markSlots(accumulator.occupancy, assembly.dayStartAt(), overlapStart, overlapEnd);
                    lastOverlapByAppointment.put(
                            appointment.getAppointmentId(), new OverrunTracker(assembly, bay, effectiveEnd));
                }
                byBay.put(bay.getBayId(), accumulator);
            }
            accumulatorsByDate.put(assembly.date(), byBay);
        }

        // Pass 2: carry-over (#2021 AC4/AC5/AC6), additive on top of pass 1's accumulators.
        //
        // The overrun is a plain duration (minutes past the source day's close), not an absolute
        // instant to be re-anchored on a later day's clock — a carried-over job is netted as work
        // starting at the *target* day's own open time, regardless of what wall-clock time the
        // source day's overrun technically ended at (#2021 AC5's "netted, not annotated"). When
        // that duration is longer than one operating day's own window, dumping all of it onto the
        // immediate next open day made the day's occupiedMinutes total disagree with what
        // markSlots could actually mark, since markSlots clamps at that day's own slot count
        // (#2023 F8). Distributing the remainder across successive open days keeps the two
        // representations consistent at every hop: each day absorbs at most its own window's worth
        // of minutes, and whatever does not fit carries forward again.
        //
        // This pass now only does the minute arithmetic. It records each hop's minutes in the target
        // accumulator's ledger and builds no CarryOverView of its own — the final step below derives
        // the whole list, so a re-anchored hop and a direct overlap by the same appointment on the
        // same day become one entry rather than two (#2050 AC1). The source day can itself be a
        // pre-range lookback day, which is how a job planned entirely before the range reaches it.
        //
        // Iteration order over lastOverlapByAppointment is a HashMap's and therefore unspecified,
        // which is safe: every write below is commutative (minute addition, slot increments, ledger
        // merge) and the one ordered output, carryOverIn, is sorted in the final step.
        for (Map.Entry<UUID, OverrunTracker> entry : lastOverlapByAppointment.entrySet()) {
            OverrunTracker tracker = entry.getValue();
            DayAssembly sourceDay = tracker.lastOverlapDay();
            if (!tracker.effectiveEnd().isAfter(sourceDay.dayEndAt())) {
                continue;
            }
            long remainingMinutes = Duration.between(sourceDay.dayEndAt(), tracker.effectiveEnd())
                    .toMinutes();
            DayAssembly cursorDay = sourceDay;
            while (remainingMinutes > 0) {
                DayAssembly targetDay = nextOpenDay(assemblies, cursorDay.date());
                if (targetDay == null) {
                    // The next open day is beyond the requested range; nothing visible to net
                    // against for whatever remains.
                    break;
                }
                BayDayAccumulator targetAccumulator = accumulatorsByDate
                        .get(targetDay.date())
                        .get(tracker.bay().getBayId());
                long targetDayCapacityMinutes = Duration.between(targetDay.dayStartAt(), targetDay.dayEndAt())
                        .toMinutes();
                long minutesForThisDay = Math.min(remainingMinutes, targetDayCapacityMinutes);

                targetAccumulator.occupiedMinutes += minutesForThisDay;
                targetAccumulator.recordContribution(entry.getKey(), minutesForThisDay);
                markSlots(
                        targetAccumulator.occupancy,
                        targetDay.dayStartAt(),
                        targetDay.dayStartAt(),
                        targetDay.dayStartAt().plusSeconds(minutesForThisDay * 60));

                remainingMinutes -= minutesForThisDay;
                cursorDay = targetDay;
            }
        }

        // Final step: carryOverIn (#2050 AC1). Every appointment that contributed minutes to a
        // bay-day — by direct overlap, by a re-anchored hop, or by both — and whose effective window
        // began on an earlier local date than that day is listed once, with the total it contributed.
        // Nothing here touches occupiedMinutes or occupancy: the ledger is a record of what passes 1
        // and 2 already did, so the detail cannot disagree with the number it explains (#2021 AC5).
        for (Map.Entry<LocalDate, Map<UUID, BayDayAccumulator>> dayEntry : accumulatorsByDate.entrySet()) {
            for (BayDayAccumulator accumulator : dayEntry.getValue().values()) {
                accumulator.buildCarryOverIn(dayEntry.getKey(), carryOverContexts);
            }
        }

        List<ScheduleCapacityResponse.DayCapacityView> views = new ArrayList<>(assemblies.size());
        for (DayAssembly assembly : assemblies) {
            if (assembly.date().isBefore(emissionFrom)) {
                // A lookback day: it fed both passes above, and its accumulators are discarded here.
                continue;
            }
            views.add(toDayView(assembly, bays, accumulatorsByDate.get(assembly.date())));
        }
        return views;
    }

    /**
     * The per-appointment facts {@code carryOverIn} needs, resolved once for the whole read (#2050
     * AC1): the local date its effective window began on, and the linked workorder when there is one.
     *
     * <p>The start date comes from the appointment's own effective start — actual when known, else
     * planned — never from the last day its window happened to overlap. Two legal requests covering
     * the same date must report the same {@code fromDate} for the same appointment (#2050 AC2), and
     * only a fact derived from the row itself has that property; anything derived from the range
     * being served does not.
     */
    private Map<UUID, AppointmentCarryOverContext> buildCarryOverContexts(
            Map<UUID, List<Appointment>> appointmentsByBay,
            Map<UUID, WorkorderActuals> actualsByAppointmentId,
            ZoneId zoneId) {
        Map<UUID, AppointmentCarryOverContext> contexts = new HashMap<>();
        for (List<Appointment> bayAppointments : appointmentsByBay.values()) {
            for (Appointment appointment : bayAppointments) {
                WorkorderActuals actuals = actualsByAppointmentId.get(appointment.getAppointmentId());
                contexts.putIfAbsent(
                        appointment.getAppointmentId(),
                        new AppointmentCarryOverContext(
                                LocalDate.ofInstant(effectiveStart(appointment, actuals), zoneId),
                                actuals == null ? null : actuals.workOrderId()));
            }
        }
        return contexts;
    }

    private ScheduleCapacityResponse.DayCapacityView toDayView(
            DayAssembly assembly, List<ExtBayReplica> bays, @Nullable Map<UUID, BayDayAccumulator> byBay) {
        ScheduleCapacityResponse.DayCapacityView view = new ScheduleCapacityResponse.DayCapacityView();
        view.setDate(assembly.date());
        view.setStatus(assembly.status());
        view.setClosureReason(assembly.closureReason());
        view.setDayStartAt(assembly.dayStartAt());
        view.setDayEndAt(assembly.dayEndAt());
        if (assembly.status() != ScheduleCapacityDayStatus.OK || byBay == null) {
            view.setBays(List.of());
            return view;
        }
        List<ScheduleCapacityResponse.BayCapacityView> bayViews = new ArrayList<>(bays.size());
        for (ExtBayReplica bay : bays) {
            bayViews.add(byBay.get(bay.getBayId()).toView(bay));
        }
        view.setBays(bayViews);
        return view;
    }

    /** Actual start when the linked workorder has one, else the appointment's own planned start. */
    private Instant effectiveStart(Appointment appointment, @Nullable WorkorderActuals actuals) {
        return actuals != null && actuals.workStartedAt() != null ? actuals.workStartedAt() : appointment.getStartAt();
    }

    /**
     * Actual finish when the linked workorder has one, else the appointment's own planned finish
     * (#2021 AC1-AC3). Never {@code expectedEndAt} and never synthesised from the current time
     * (#2021 F5): a still-running job with no recorded finish is represented by its planned end.
     */
    private Instant effectiveEnd(Appointment appointment, @Nullable WorkorderActuals actuals) {
        return actuals != null && actuals.completedAt() != null ? actuals.completedAt() : appointment.getEndAt();
    }

    /**
     * The first {@code OK} day strictly after {@code afterDate}, in assembly order; null if none
     * (AC6).
     *
     * <p>Unchanged by the lookback: the list is still in date order, so prepending pre-range dates is
     * already handled. It deliberately does <em>not</em> skip ahead to the first in-range day — for a
     * job whose overrun is absorbed by pre-range open days, those hops must consume the minutes, or
     * {@code from} would be credited with work that was really done before the range began.
     */
    private @Nullable DayAssembly nextOpenDay(List<DayAssembly> assemblies, LocalDate afterDate) {
        for (DayAssembly assembly : assemblies) {
            if (assembly.date().isAfter(afterDate) && assembly.status() == ScheduleCapacityDayStatus.OK) {
                return assembly;
            }
        }
        return null;
    }

    /** Bay-hours in tenths of an hour (ADR-0059 vocabulary), for the carry-over detail (AC4). */
    private static BigDecimal bayHours(long minutes) {
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP);
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

    /** Mutable per-bay, per-day working totals; materialized into a {@code BayCapacityView} once. */
    private static final class BayDayAccumulator {
        private final int[] occupancy;
        private long occupiedMinutes;
        private final List<ScheduleCapacityResponse.CarryOverView> carryOverIn = new ArrayList<>();

        /**
         * Minutes this bay-day received from each appointment, whatever the route: direct overlap
         * (pass 1) and every re-anchored hop (pass 2) merge into the same entry, which is what lets
         * {@code carryOverIn} report one total per appointment per bay-day (#2050 AC1).
         *
         * <p>Created on first write rather than in the constructor: most bay-days in a 42-day range
         * with a 42-day lookback are empty, and one accumulator exists per bay per assembled day.
         */
        private @Nullable Map<UUID, Long> contributedMinutesByAppointment;

        BayDayAccumulator(int slotCount) {
            this.occupancy = new int[slotCount];
        }

        void recordContribution(UUID appointmentId, long minutes) {
            if (minutes <= 0) {
                return;
            }
            if (contributedMinutesByAppointment == null) {
                contributedMinutesByAppointment = new HashMap<>();
            }
            contributedMinutesByAppointment.merge(appointmentId, minutes, Long::sum);
        }

        /**
         * Turns the ledger into this bay-day's {@code carryOverIn}: one view per contributing
         * appointment whose effective window began before {@code dayDate}, ordered by {@code
         * (fromDate, appointmentId)} so the list is deterministic (the ledger is a {@code HashMap}
         * and the appointment query has no {@code ORDER BY}).
         */
        void buildCarryOverIn(LocalDate dayDate, Map<UUID, AppointmentCarryOverContext> contexts) {
            if (contributedMinutesByAppointment == null) {
                return;
            }
            for (Map.Entry<UUID, Long> contribution : contributedMinutesByAppointment.entrySet()) {
                AppointmentCarryOverContext context = contexts.get(contribution.getKey());
                if (context == null || !context.startedOn().isBefore(dayDate)) {
                    continue;
                }
                ScheduleCapacityResponse.CarryOverView view = new ScheduleCapacityResponse.CarryOverView();
                view.setFromDate(context.startedOn());
                view.setAppointmentId(contribution.getKey());
                view.setWorkorderId(context.workOrderId());
                view.setBayHours(bayHours(contribution.getValue()));
                carryOverIn.add(view);
            }
            carryOverIn.sort(Comparator.comparing(ScheduleCapacityResponse.CarryOverView::getFromDate)
                    .thenComparing(ScheduleCapacityResponse.CarryOverView::getAppointmentId));
        }

        ScheduleCapacityResponse.BayCapacityView toView(ExtBayReplica bay) {
            ScheduleCapacityResponse.BayCapacityView view = new ScheduleCapacityResponse.BayCapacityView();
            view.setBayId(bay.getBayId());
            view.setName(bay.getName());
            view.setOccupiedMinutes((int) occupiedMinutes);
            view.setOccupancy(Arrays.stream(occupancy).boxed().toList());
            view.setCarryOverIn(carryOverIn);
            return view;
        }
    }

    /**
     * The last OK day one appointment's effective window directly overlapped, and the bay it did so
     * in — the seed carry-over (pass 2) walks forward from. With the lookback in place this day can
     * itself be a pre-range day, which is exactly how a job planned entirely before {@code from}
     * reaches the requested range (#2050).
     */
    private record OverrunTracker(DayAssembly lastOverlapDay, ExtBayReplica bay, Instant effectiveEnd) {}

    /**
     * One appointment's carry-over identity: the local date its effective window began on, and the
     * linked workorder when known. Range-independent by construction (#2050 AC2).
     */
    private record AppointmentCarryOverContext(
            LocalDate startedOn, @Nullable UUID workOrderId) {}
}
