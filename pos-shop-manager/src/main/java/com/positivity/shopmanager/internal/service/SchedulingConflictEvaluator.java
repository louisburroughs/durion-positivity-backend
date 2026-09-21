package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.ConflictRule;
import com.positivity.shopmanager.internal.entity.ExtLocationReplica;
import com.positivity.shopmanager.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.enums.ConflictSeverity;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.ConflictRuleRepository;
import com.positivity.shopmanager.internal.repository.ExtBayReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtLocationReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtStaffingAssignmentReplicaRepository;
import com.positivity.shopmanager.internal.service.LocationHoursParser.RawOperatingHoursEntry;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Evaluates DECISION-SHOPMGMT-002's rules against one booking attempt (CAP-326, durion#483) —
 * the submit-time tier DECISION-011 calls the authoritative one. Replaces the no-op {@code
 * ConflictDetectionService}, which claimed this and did nothing.
 *
 * <p>Order matters and is HOURS, BAY, MECHANIC, CAPACITY (spec D18.1): a closed day is refused as
 * closed, never as full, and never reaches the exclusion constraint. What each rule reads:
 *
 * <ul>
 *   <li>{@code FACILITY_CLOSED} / {@code OUTSIDE_OPERATING_HOURS} (HARD, HOURS) — the location
 *       replica's timezone, weekly windows and dated closures, in facility-local time
 *       (DECISION-015). Hours never published, timezone unknown or no replica row: the HOURS rules
 *       do not fire and a WARN is logged — an unknown fact is not a confirmed closure (D18.1's
 *       default, DECISION-008's "warning mode first").
 *   <li>{@code BAY_DOUBLE_BOOKED} (HARD, BAY) — appointments still holding the same resource for
 *       any part of the window. Reporting only; the exclusion constraint (V8) is what makes it
 *       unbypassable under concurrency.
 *   <li>{@code MECHANIC_UNAVAILABLE} (HARD, MECHANIC) — no ACTIVE staffing assignment at the
 *       location covers the booking's local date (#2035 answer 5; competence is a different rule).
 *       The refusal names which of the two noes it is, an unstaffed location or a date no assignment
 *       covers, rather than asserting nobody is present (#2140).
 *   <li>{@code FACILITY_NEAR_CAPACITY} (SOFT, CAPACITY) — this booking would put held appointments
 *       at or above 90% of the location's active bays.
 *   <li>{@code NO_COMPETENT_MECHANIC_ROSTERED} / {@code COMPETENT_MECHANIC_UNAVAILABLE} (SOFT,
 *       SKILL; CAP-329) — see {@link #evaluateSkills}; competence is read through {@link
 *       SkillRequirementResolver}, the same reading the opening search uses.
 * </ul>
 *
 * <p>Not evaluated yet, though seeded: {@code MECHANIC_OVERTIME} needs timekeeping. A rule this
 * evaluator does not implement simply does not fire; nothing here pretends otherwise.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulingConflictEvaluator {

    static final String CODE_FACILITY_CLOSED = "FACILITY_CLOSED";
    static final String CODE_OUTSIDE_OPERATING_HOURS = "OUTSIDE_OPERATING_HOURS";
    static final String CODE_BAY_DOUBLE_BOOKED = "BAY_DOUBLE_BOOKED";
    static final String CODE_MECHANIC_UNAVAILABLE = "MECHANIC_UNAVAILABLE";
    static final String CODE_FACILITY_NEAR_CAPACITY = "FACILITY_NEAR_CAPACITY";
    static final String CODE_NO_COMPETENT_MECHANIC_ROSTERED = "NO_COMPETENT_MECHANIC_ROSTERED";
    static final String CODE_COMPETENT_MECHANIC_UNAVAILABLE = "COMPETENT_MECHANIC_UNAVAILABLE";
    static final String TECHNICIAN_RESOURCE_TYPE = "TECHNICIAN";
    static final String UNASSIGNED = "UNASSIGNED";
    static final String STAFFING_ACTIVE = "ACTIVE";
    static final double NEAR_CAPACITY_RATIO = 0.9;

    /**
     * What {@code {zone}} says when the location has no resolvable zone and the times fall back to
     * UTC. Not {@code ZoneOffset.UTC.getId()}, which is {@code "Z"}: see {@link #render}.
     */
    static final String UTC_LABEL = "UTC";

    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final ConflictRuleRepository conflictRuleRepository;
    private final ExtLocationReplicaRepository extLocationReplicaRepository;
    private final LocationHoursParser locationHoursParser;
    private final AppointmentRepository appointmentRepository;
    private final ExtStaffingAssignmentReplicaRepository staffingAssignmentRepository;
    private final ExtBayReplicaRepository extBayReplicaRepository;
    private final SkillRequirementResolver skillRequirementResolver;

    /**
     * One booking attempt. {@code excludeAppointmentId} is the appointment being rescheduled, whose
     * own current slot must not count against it.
     */
    public record BookingAttempt(
            @NonNull UUID locationId,
            @Nullable String resourceId,
            @NonNull Instant startAt,
            @NonNull Instant endAt,
            @Nullable UUID excludeAppointmentId,
            @NonNull List<UUID> serviceIds,
            @Nullable UUID vehicleId) {

        /** An attempt that names no services and no vehicle: the SKILL rules have nothing to resolve. */
        public BookingAttempt(
                @NonNull UUID locationId,
                @Nullable String resourceId,
                @NonNull Instant startAt,
                @NonNull Instant endAt,
                @Nullable UUID excludeAppointmentId) {
            this(locationId, resourceId, startAt, endAt, excludeAppointmentId, List.of(), null);
        }
    }

    /** One rule that fired, with the resource it names and its template rendered for this attempt. */
    public record DetectedConflict(
            @NonNull ConflictRule rule,
            @Nullable String resourceId,
            @NonNull String detail) {
        public String code() {
            return rule.getCode();
        }

        public ConflictSeverity severity() {
            return rule.getSeverity();
        }

        public boolean isHard() {
            return rule.getSeverity() == ConflictSeverity.HARD;
        }
    }

    public @NonNull List<DetectedConflict> evaluate(@NonNull BookingAttempt attempt) {
        List<DetectedConflict> detected = new ArrayList<>();
        ZoneId zone = evaluateHours(attempt, detected);
        if (!detected.isEmpty()) {
            // Refused as closed, never as full (D18.1): on a day the shop is shut, whether the bay
            // or a mechanic is free is not a question, so the answer names only the closure.
            return detected;
        }
        evaluateBay(attempt, zone, detected);
        boolean anyonePresent = evaluateStaffing(attempt, zone, detected);
        if (anyonePresent) {
            // Zero technicians is MECHANIC_UNAVAILABLE and never a competence failure (#2035 answer
            // 5): the SKILL rules ask about the people who are here, and there are none to ask about.
            evaluateSkills(attempt, zone, detected);
        }
        evaluateCapacity(attempt, zone, detected);
        return detected;
    }

    /** The BAY_DOUBLE_BOOKED conflict for an attempt the exclusion constraint refused. */
    public @NonNull DetectedConflict bayDoubleBooked(@NonNull BookingAttempt attempt) {
        ConflictRule rule = conflictRuleRepository
                .findByCode(CODE_BAY_DOUBLE_BOOKED)
                .orElseThrow(() -> new IllegalStateException("conflict_rule BAY_DOUBLE_BOOKED is not seeded"));
        return new DetectedConflict(rule, attempt.resourceId(), render(rule, attempt, resolveZone(attempt), null));
    }

    // ── HOURS ───────────────────────────────────────────────────────────────────────────────────

    private @Nullable ZoneId evaluateHours(BookingAttempt attempt, List<DetectedConflict> detected) {
        ExtLocationReplica location =
                extLocationReplicaRepository.findById(attempt.locationId()).orElse(null);
        if (location == null) {
            log.warn("Location {} has no replica row; HOURS rules not evaluated", attempt.locationId());
            return null;
        }
        ZoneId zone = locationHoursParser.parseZone(attempt.locationId(), location.getTimezone());
        if (zone == null || location.getOperatingHours() == null) {
            log.warn(
                    "Location {} has no usable timezone or never published operating hours; HOURS rules not evaluated",
                    attempt.locationId());
            return zone;
        }
        Map<DayOfWeek, RawOperatingHoursEntry> hoursByDow =
                locationHoursParser.parseOperatingHours(attempt.locationId(), location.getOperatingHours());
        if (hoursByDow == null) {
            return zone;
        }
        Map<LocalDate, String> closures =
                locationHoursParser.parseHolidayClosures(attempt.locationId(), location.getHolidayClosures());

        ZonedDateTime localStart = attempt.startAt().atZone(zone);
        ZonedDateTime localEnd = attempt.endAt().atZone(zone);
        LocalDate date = localStart.toLocalDate();

        if (closures.containsKey(date)) {
            fire(CODE_FACILITY_CLOSED, attempt, zone, closures.get(date), detected);
            return zone;
        }
        RawOperatingHoursEntry window = hoursByDow.get(date.getDayOfWeek());
        if (window == null) {
            fire(CODE_FACILITY_CLOSED, attempt, zone, null, detected);
            return zone;
        }
        LocalTime open;
        LocalTime close;
        try {
            open = LocalTime.parse(window.openTime());
            close = LocalTime.parse(window.closeTime());
        } catch (Exception e) {
            log.warn(
                    "Location {} has an unparsable window for {}; HOURS rules not evaluated for it",
                    attempt.locationId(),
                    date.getDayOfWeek());
            return zone;
        }
        // One window per day and no overnight ranges (DECISION-LOCATION-004): a booking that
        // crosses local midnight is outside hours by construction.
        boolean spansMidnight = !localEnd.toLocalDate().equals(date)
                && !(localEnd.toLocalTime().equals(LocalTime.MIDNIGHT)
                        && localEnd.toLocalDate().equals(date.plusDays(1))
                        && close.equals(LocalTime.MAX.withNano(0)));
        boolean startsEarly = localStart.toLocalTime().isBefore(open);
        boolean endsLate = !localEnd.toLocalDate().equals(date)
                ? spansMidnight
                : localEnd.toLocalTime().isAfter(close);
        if (spansMidnight || startsEarly || endsLate) {
            fire(CODE_OUTSIDE_OPERATING_HOURS, attempt, zone, null, detected);
        }
        return zone;
    }

    // ── BAY ─────────────────────────────────────────────────────────────────────────────────────

    private void evaluateBay(BookingAttempt attempt, @Nullable ZoneId zone, List<DetectedConflict> detected) {
        // Read the accessor once (S2637): findHeldOverlappingForResource's resourceId is
        // @NonNull, and re-calling attempt.resourceId() after the namesAResource guard leaves
        // static analysis unable to tell the two calls return the same value.
        String resourceId = attempt.resourceId();
        if (!namesAResource(resourceId)) {
            return;
        }
        boolean occupied = appointmentRepository
                .findHeldOverlappingForResource(
                        resourceId, attempt.startAt(), attempt.endAt(), AppointmentStatus.holdingAResource())
                .stream()
                .anyMatch(other -> !Objects.equals(other.getAppointmentId(), attempt.excludeAppointmentId()));
        if (occupied) {
            fire(CODE_BAY_DOUBLE_BOOKED, attempt, zone, null, detected);
        }
    }

    // ── MECHANIC ────────────────────────────────────────────────────────────────────────────────

    private boolean evaluateStaffing(BookingAttempt attempt, @Nullable ZoneId zone, List<DetectedConflict> detected) {
        List<ExtStaffingAssignmentReplica> technicianAssignments = activeTechnicianAssignments(attempt);
        LocalDate localDate = localDate(attempt, zone);
        boolean anyonePresent = technicianAssignments.stream()
                .anyMatch(assignment -> SkillRequirementResolver.covers(assignment, localDate));
        if (!anyonePresent) {
            fire(CODE_MECHANIC_UNAVAILABLE, attempt, zone, staffingDetail(technicianAssignments, localDate), detected);
        }
        return anyonePresent;
    }

    /**
     * Why the MECHANIC rule fired, in the terms the caller can act on (#2140). The rule asks one
     * question — does an ACTIVE TECHNICIAN staffing assignment at this location cover the booking's
     * facility-local date — and it has two different noes. "Nobody is in the building" is only the
     * first of them: an assignment that exists but starts after the date asked about is a shop that
     * is staffed and a date that is not covered, and a caller told only that no mechanic is present
     * cannot tell the two apart or act on either.
     *
     * <p>Effective dates are read as written and are not adjusted towards the question
     * (DECISION-SHOPMGMT-015 reads every staffing and credential fact on the facility-local date of
     * the attempt): a date before an assignment begins genuinely has nobody assigned on it, which is
     * as true of historical reporting as of a booking. Naming the date and the assignment count is
     * what makes that answer readable rather than misleading.
     */
    private static String staffingDetail(
            List<ExtStaffingAssignmentReplica> technicianAssignments, LocalDate localDate) {
        if (technicianAssignments.isEmpty()) {
            return "no ACTIVE technician staffing assignment exists at this location";
        }
        int count = technicianAssignments.size();
        return count
                + (count == 1
                        ? " ACTIVE technician staffing assignment exists"
                        : " ACTIVE technician staffing assignments exist")
                + " at this location, none effective on " + localDate;
    }

    /**
     * Technicians with an ACTIVE staffing assignment at the location covering the attempt's local
     * date. Role-filtered: a service advisor on the roster is somebody present, but not somebody who
     * can be the mechanic on the job (#2035 answer 5 asks about mechanics).
     */
    private Set<UUID> rosteredTechnicians(BookingAttempt attempt, @Nullable ZoneId zone) {
        LocalDate localDate = localDate(attempt, zone);
        return activeTechnicianAssignments(attempt).stream()
                .filter(assignment -> SkillRequirementResolver.covers(assignment, localDate))
                .map(ExtStaffingAssignmentReplica::getPersonId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Every ACTIVE TECHNICIAN staffing assignment at the location, before any date filter — the row
     * set both the presence answer and {@link #staffingDetail} are read from, so the refusal can say
     * whether the shop has no assignments at all or none covering the date.
     */
    private List<ExtStaffingAssignmentReplica> activeTechnicianAssignments(BookingAttempt attempt) {
        return staffingAssignmentRepository.findByLocationIdAndStatus(attempt.locationId(), STAFFING_ACTIVE).stream()
                .filter(SkillRequirementResolver::isTechnician)
                .toList();
    }

    private static LocalDate localDate(BookingAttempt attempt, @Nullable ZoneId zone) {
        return attempt.startAt().atZone(zone == null ? ZoneOffset.UTC : zone).toLocalDate();
    }

    // ── SKILL (CAP-329, spec D10.1) ─────────────────────────────────────────────────────────────

    /**
     * Resolves (services, vehicle GVWR class) → required skills from the catalog replica, then asks
     * two SOFT questions about the technicians rostered here that day: does anyone hold each skill
     * (else NO_COMPETENT_MECHANIC_ROSTERED, the window-invariant absence), and if so, is any holder
     * free in the window (else COMPETENT_MECHANIC_UNAVAILABLE, the contention). Neither withholds
     * a booking; a manager may override either (D10).
     *
     * <p>A credential counts only while held on the facility-local date of the attempt
     * (DECISION-SHOPMGMT-015); a lapsed one is named in the detail rather than silently ignored.
     * A service whose requirements were never configured contributes nothing here — "not
     * configured" is a different answer from "requires nothing", and it is #2022's
     * SERVICE_REQUIREMENTS_NOT_CONFIGURED to report, not a competence rule. Without a vehicle
     * class only ANY-class requirements apply, and the detail says the class was not determined.
     */
    private void evaluateSkills(BookingAttempt attempt, @Nullable ZoneId zone, List<DetectedConflict> detected) {
        if (attempt.serviceIds().isEmpty()) {
            return;
        }
        Integer gvwrClass = skillRequirementResolver.gvwrClassOf(attempt.vehicleId());
        Map<String, UUID> required = skillRequirementResolver.requiredSkills(attempt.serviceIds(), gvwrClass);
        if (required.isEmpty()) {
            return;
        }
        LocalDate localDate = localDate(attempt, zone);
        Set<UUID> rostered = rosteredTechnicians(attempt, zone);
        Map<String, Set<UUID>> holdersBySkill =
                skillRequirementResolver.holdersBySkill(rostered, required.keySet(), localDate);

        List<String> missing = SkillRequirementResolver.missing(required.keySet(), holdersBySkill);
        String classNote = gvwrClass == null ? " (vehicle duty class not determined)" : "";
        if (!missing.isEmpty()) {
            fire(CODE_NO_COMPETENT_MECHANIC_ROSTERED, attempt, zone, String.join(", ", missing) + classNote, detected);
        }
        List<String> contended = required.keySet().stream()
                .filter(code -> !holdersBySkill.getOrDefault(code, Set.of()).isEmpty())
                .filter(code -> holdersBySkill.get(code).stream().allMatch(person -> busy(person, attempt)))
                .toList();
        if (!contended.isEmpty()) {
            fire(
                    CODE_COMPETENT_MECHANIC_UNAVAILABLE,
                    attempt,
                    zone,
                    String.join(", ", contended) + classNote,
                    detected);
        }
    }

    /** Busy = already the technician on a held appointment overlapping the window (self excluded). */
    private boolean busy(UUID personId, BookingAttempt attempt) {
        return appointmentRepository
                .findHeldOverlappingForResource(
                        personId.toString(), attempt.startAt(), attempt.endAt(), AppointmentStatus.holdingAResource())
                .stream()
                .filter(appointment -> TECHNICIAN_RESOURCE_TYPE.equalsIgnoreCase(appointment.getResourceType()))
                .anyMatch(
                        appointment -> !Objects.equals(appointment.getAppointmentId(), attempt.excludeAppointmentId()));
    }

    // ── CAPACITY ────────────────────────────────────────────────────────────────────────────────

    private void evaluateCapacity(BookingAttempt attempt, @Nullable ZoneId zone, List<DetectedConflict> detected) {
        int bays = extBayReplicaRepository
                .findActiveByLocationOrdered(attempt.locationId())
                .size();
        if (bays == 0) {
            return;
        }
        long held = appointmentRepository
                .findHeldOverlappingAtLocation(
                        attempt.locationId(), attempt.startAt(), attempt.endAt(), AppointmentStatus.holdingAResource())
                .stream()
                .map(Appointment::getAppointmentId)
                .filter(id -> !Objects.equals(id, attempt.excludeAppointmentId()))
                .count();
        double ratio = (held + 1) / (double) bays;
        if (ratio >= NEAR_CAPACITY_RATIO) {
            fire(CODE_FACILITY_NEAR_CAPACITY, attempt, zone, null, detected);
        }
    }

    // ── plumbing ────────────────────────────────────────────────────────────────────────────────

    private void fire(
            String code,
            BookingAttempt attempt,
            @Nullable ZoneId zone,
            @Nullable String detail,
            List<DetectedConflict> detected) {
        Optional<ConflictRule> rule = activeRule(code);
        if (rule.isEmpty()) {
            return;
        }
        detected.add(new DetectedConflict(rule.get(), attempt.resourceId(), render(rule.get(), attempt, zone, detail)));
    }

    /** Empty when the platform has switched the rule off; absent from the seed is a deployment defect. */
    private Optional<ConflictRule> activeRule(String code) {
        ConflictRule rule = conflictRuleRepository
                .findByCode(code)
                .orElseThrow(() -> new IllegalStateException("conflict_rule " + code + " is not seeded"));
        return rule.isActive() ? Optional.of(rule) : Optional.empty();
    }

    private @Nullable ZoneId resolveZone(BookingAttempt attempt) {
        return extLocationReplicaRepository
                .findById(attempt.locationId())
                .map(location -> locationHoursParser.parseZone(attempt.locationId(), location.getTimezone()))
                .orElse(null);
    }

    static boolean namesAResource(@Nullable String resourceId) {
        return resourceId != null && !resourceId.isBlank() && !UNASSIGNED.equals(resourceId);
    }

    /**
     * Renders the rule's {@code {placeholders}} in facility-local time when the zone is known.
     * {@code detail} is the rule's free-text detail: the closure's reason for the HOURS templates,
     * the skill codes for the SKILL templates, why presence failed for the MECHANIC one.
     *
     * <p>{@code {zone}} names the zone the times were rendered in (#2139). Every template that
     * quotes a window carries it, because a bare {@code 04:00–05:00} for a booking the caller sent
     * as {@code 09:00Z} reads as a platform error rather than as the facility-local conversion
     * DECISION-SHOPMGMT-015 requires — the offset was left to be deduced, and a run was lost
     * deducing it wrongly. When the zone is unknown the times are rendered in UTC, and
     * {@code {zone}} says UTC: it names the zone the numbers are in, which is the one fact the
     * caller cannot otherwise recover.
     */
    static String render(ConflictRule rule, BookingAttempt attempt, @Nullable ZoneId zone, @Nullable String detail) {
        ZoneId renderZone = zone == null ? ZoneOffset.UTC : zone;
        // ZoneOffset.UTC.getId() is "Z", which is neither the label this method documents nor an
        // IANA zone id -- "04:00-05:00 Z falls outside ..." reads as a typo rather than as the one
        // fact the caller cannot otherwise recover. A resolved zone still names itself.
        String zoneLabel = zone == null ? UTC_LABEL : zone.getId();
        ZonedDateTime start = attempt.startAt().atZone(renderZone);
        ZonedDateTime end = attempt.endAt().atZone(renderZone);
        return rule.getMessageTemplate()
                .replace("{resource}", attempt.resourceId() == null ? "(unassigned)" : attempt.resourceId())
                .replace("{start}", start.format(LOCAL_TIME))
                .replace("{end}", end.format(LOCAL_TIME))
                .replace("{zone}", zoneLabel)
                .replace("{date}", start.toLocalDate().toString())
                .replace("{reason}", detail == null || detail.isBlank() ? "" : " (" + detail + ")")
                .replace("{skills}", detail == null || detail.isBlank() ? "the required skills" : detail);
    }
}
