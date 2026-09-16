package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.dto.OpeningSearchQuery;
import com.positivity.shopmanager.internal.dto.OpeningSearchResponse;
import com.positivity.shopmanager.internal.dto.OpeningSearchResponse.BayEligibility;
import com.positivity.shopmanager.internal.dto.OpeningSearchResponse.Opening;
import com.positivity.shopmanager.internal.dto.OpeningSearchResponse.StaffingAdvisory;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.ExtBayReplica;
import com.positivity.shopmanager.internal.entity.ExtCatalogServiceReplica;
import com.positivity.shopmanager.internal.entity.ExtLocationReplica;
import com.positivity.shopmanager.internal.entity.ExtPersonCredentialReplica;
import com.positivity.shopmanager.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.enums.OpeningSearchEnums.AbsenceScope;
import com.positivity.shopmanager.internal.enums.OpeningSearchEnums.NoOpeningReason;
import com.positivity.shopmanager.internal.enums.OpeningSearchEnums.OpeningConstraint;
import com.positivity.shopmanager.internal.enums.OpeningSearchEnums.SkillFulfillment;
import com.positivity.shopmanager.internal.enums.OpeningSearchEnums.StaffingAdvisoryCode;
import com.positivity.shopmanager.internal.exception.LocationNotFoundException;
import com.positivity.shopmanager.internal.exception.OpeningSearchPolicyException;
import com.positivity.shopmanager.internal.exception.ResourceNotFoundException;
import com.positivity.shopmanager.internal.exception.ShopManagerValidationException;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.ExtBayReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtCatalogServiceReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtLocationReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtStaffingAssignmentReplicaRepository;
import com.positivity.shopmanager.internal.service.LocationHoursParser.RawOperatingHoursEntry;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes openings from replicas alone (ADR-0044 §6) in a fixed number of queries whatever the
 * horizon: the location, its bays, the services, the vehicle, the staffing roster, everyone's
 * credentials, and one appointment read spanning the whole horizon. The per-day, per-bay work is
 * then in memory.
 *
 * <p>Eligibility (CAP-325 D13/D14): a bay is eligible for an operation it claims; an operation no
 * bay at the location claims is general work — a definite answer, not an unknown — which every bay
 * but a {@code WASH_DETAIL} one may do, general bays ranked before specialty bays so the rack stays
 * free for alignment work without the shop ever reading as full. A bay whose {@code maxDutyClass}
 * is below the vehicle's GVWR class is out, and counted separately from a capability miss.
 *
 * <p>An opening is the earliest start in a free gap of one eligible bay at which the job, with the
 * location's check-in buffer before and cleanup buffer after, fits inside the gap and inside the
 * day's operating window, and at which a technician rostered that day is not already on an
 * overlapping appointment. One opening per gap per bay; ranked earliest first, CERTIFIED before
 * AWAITING at equal starts.
 *
 * <p>Skill (CAP-329, D10): competence never withholds an opening. A technician holding every
 * required skill on the facility-local date is preferred and the opening reads CERTIFIED; otherwise
 * the opening names a free technician and reads AWAITING with the codes they lack. The
 * window-invariant fact — nobody rostered here in the horizon holds a required skill, or nobody is
 * rostered at all — is reported once as {@code staffingAdvisory} (D10.2), never as a reason the
 * list is empty.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpeningSearchServiceImpl implements OpeningSearchService {

    /** Forward horizon bound, in facility-local days from earliestStart's date (#2022 AC11). */
    public static final int MAX_HORIZON_DAYS = 30;
    /** Result cap (#2022 AC11). */
    public static final int MAX_LIMIT = 50;
    /** A job is one visit; more services than this is a planning problem, not a search. */
    public static final int MAX_SERVICES = 10;
    /** An opening never spans a closing, so a job longer than a day has no opening by definition. */
    static final int MAX_DURATION_MINUTES = 24 * 60;

    static final String ROSTER_GRAIN_DAY = "DAY";

    private final Clock clock;
    private final ExtLocationReplicaRepository locationRepository;
    private final ExtBayReplicaRepository bayRepository;
    private final ExtCatalogServiceReplicaRepository catalogServiceRepository;
    private final ExtStaffingAssignmentReplicaRepository staffingRepository;
    private final AppointmentRepository appointmentRepository;
    private final LocationHoursParser locationHoursParser;
    private final SkillRequirementResolver skillRequirementResolver;

    @Override
    @Transactional(readOnly = true)
    public @NonNull OpeningSearchResponse search(@NonNull OpeningSearchQuery query) {
        validate(query);

        ExtLocationReplica location = locationRepository
                .findById(query.locationId())
                .orElseThrow(() -> new LocationNotFoundException(query.locationId()));
        ZoneId zone = locationHoursParser.parseZone(location.getLocationId(), location.getTimezone());
        Map<DayOfWeek, RawOperatingHoursEntry> hoursByDow = location.getOperatingHours() == null
                ? null
                : locationHoursParser.parseOperatingHours(location.getLocationId(), location.getOperatingHours());
        if (zone == null || hoursByDow == null) {
            // Hours are HARD and Location is authoritative (DECISION-SHOPMGMT-008). Without them
            // every instant would read as open, and a 03:00 opening is not an answer.
            throw new OpeningSearchPolicyException(
                    OpeningSearchPolicyException.LOCATION_HOURS_UNKNOWN,
                    "Location " + query.locationId()
                            + " has not published a recognised timezone and operating hours; openings cannot be"
                            + " computed until it does.");
        }
        Map<LocalDate, String> closures =
                locationHoursParser.parseHolidayClosures(location.getLocationId(), location.getHolidayClosures());
        Duration checkIn = Duration.ofMinutes(zeroIfNull(location.getCheckInBufferMinutes()));
        Duration cleanup = Duration.ofMinutes(zeroIfNull(location.getCleanupBufferMinutes()));
        Duration duration = Duration.ofMinutes(query.durationMinutes());

        Map<UUID, ExtCatalogServiceReplica> services = loadServices(query.serviceIds());
        Integer gvwrClass = skillRequirementResolver.gvwrClassOf(query.vehicleId());
        Set<UUID> configured = skillRequirementResolver.configuredServices(query.serviceIds());
        Map<String, UUID> required = skillRequirementResolver.requiredSkillsOf(configured, gvwrClass);
        List<OpeningConstraint> constraints = constraints(!configured.isEmpty());

        List<ExtBayReplica> bays = bayRepository.findActiveByLocationOrdered(query.locationId());
        Eligibility eligibility = eligibleBays(bays, services.values(), gvwrClass);

        LocalDate firstDate = query.earliestStart().atZone(zone).toLocalDate();
        Instant horizonEnd =
                firstDate.plusDays(query.horizonDays()).atStartOfDay(zone).toInstant();

        OpeningSearchResponse response = new OpeningSearchResponse();
        response.setLocationId(query.locationId());
        response.setTimezone(zone.getId());
        response.setServiceIds(List.copyOf(query.serviceIds()));
        response.setDurationMinutes(query.durationMinutes());
        response.setSearchedFrom(query.earliestStart());
        response.setSearchedTo(horizonEnd);
        response.setVehicleGvwrClass(gvwrClass);
        response.setRequiredSkillCodes(List.copyOf(required.keySet()));
        response.setBayEligibility(eligibility.summary());
        response.setGeneratedAt(Instant.now(clock));

        if (eligibility.eligible().isEmpty()) {
            response.setNoOpeningReason(NoOpeningReason.NO_ELIGIBLE_BAY_AT_LOCATION);
            return response;
        }

        // Everything the day loop needs, read once for the whole horizon.
        List<ExtStaffingAssignmentReplica> technicians =
                staffingRepository
                        .findByLocationIdAndStatus(query.locationId(), SkillRequirementResolver.STAFFING_ACTIVE)
                        .stream()
                        .filter(SkillRequirementResolver::isTechnician)
                        .filter(assignment -> query.technicianId() == null
                                || query.technicianId().equals(assignment.getPersonId()))
                        .toList();
        Set<UUID> everyoneHere = technicians.stream()
                .map(ExtStaffingAssignmentReplica::getPersonId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<ExtPersonCredentialReplica> credentials = skillRequirementResolver.credentialsOf(everyoneHere);
        Map<String, List<Interval>> busyByResource = busyByResource(appointmentRepository.findHeldOverlappingAtLocation(
                query.locationId(),
                firstDate.atStartOfDay(zone).toInstant(),
                horizonEnd,
                AppointmentStatus.holdingAResource()));

        List<Opening> openings = new ArrayList<>();
        boolean anyoneRostered = false;
        Set<String> heldInHorizon = new LinkedHashSet<>();
        for (LocalDate date = firstDate;
                date.isBefore(firstDate.plusDays(query.horizonDays()));
                date = date.plusDays(1)) {
            Interval day = openWindow(date, zone, hoursByDow, closures);
            if (day == null) {
                continue; // closed or holiday: skipped, never reported as full (DECISION-SHOPMGMT-008)
            }
            LocalDate onDate = date;
            List<UUID> rostered = technicians.stream()
                    .filter(assignment -> SkillRequirementResolver.covers(assignment, onDate))
                    .map(ExtStaffingAssignmentReplica::getPersonId)
                    .distinct()
                    .toList();
            if (rostered.isEmpty()) {
                continue; // nobody to name: an opening depends on a technician (AC4)
            }
            anyoneRostered = true;
            Map<String, Set<UUID>> holders =
                    SkillRequirementResolver.holdersBySkill(credentials, rostered, required.keySet(), onDate);
            heldInHorizon.addAll(holders.keySet());
            Map<UUID, List<String>> unmetByTechnician = rostered.stream()
                    .collect(Collectors.toMap(
                            Function.identity(),
                            person -> required.keySet().stream()
                                    .filter(code -> !holders.getOrDefault(code, Set.of())
                                            .contains(person))
                                    .toList()));

            for (ExtBayReplica bay : eligibility.eligible()) {
                List<Interval> bayBusy =
                        busyByResource.getOrDefault(bay.getBayId().toString(), List.of());
                for (Interval gap : freeGaps(day, bayBusy)) {
                    Opening opening = firstFit(
                            gap,
                            query.earliestStart(),
                            duration,
                            checkIn,
                            cleanup,
                            rostered,
                            unmetByTechnician,
                            busyByResource,
                            bay,
                            onDate,
                            constraints);
                    if (opening != null) {
                        openings.add(opening);
                    }
                }
            }
        }

        Set<UUID> specialtyBays = eligibility.eligible().stream()
                .filter(bay -> !isGeneral(bay))
                .map(ExtBayReplica::getBayId)
                .collect(Collectors.toSet());
        openings.sort(Comparator.comparing(Opening::getStartAt)
                .thenComparing(opening -> opening.getSkillFulfillment() == SkillFulfillment.CERTIFIED ? 0 : 1)
                // D14: a specialty bay doing general work ranks after the general bays at the same start.
                .thenComparing(opening -> specialtyBays.contains(opening.getBayId()) ? 1 : 0)
                .thenComparing(opening -> Objects.requireNonNullElse(opening.getBayName(), ""))
                .thenComparing(Opening::getBayId));
        response.setOpenings(
                openings.size() > query.limit() ? new ArrayList<>(openings.subList(0, query.limit())) : openings);

        if (openings.isEmpty() && anyoneRostered) {
            response.setNoOpeningReason(NoOpeningReason.ALL_ELIGIBLE_BAYS_BOOKED);
        }
        response.setStaffingAdvisory(
                staffingAdvisory(anyoneRostered, everyoneHere, credentials, required, heldInHorizon, firstDate));
        return response;
    }

    // ── validation ──────────────────────────────────────────────────────────────────────────────

    private static void validate(OpeningSearchQuery query) {
        if (query.serviceIds().isEmpty()) {
            throw new ShopManagerValidationException("serviceIds must name at least one catalog service");
        }
        if (query.durationMinutes() < 1 || query.durationMinutes() > MAX_DURATION_MINUTES) {
            throw new ShopManagerValidationException("durationMinutes must be between 1 and " + MAX_DURATION_MINUTES
                    + " (an opening never spans a closing)");
        }
        if (query.horizonDays() < 1) {
            throw new ShopManagerValidationException("horizonDays must be at least 1");
        }
        if (query.limit() < 1) {
            throw new ShopManagerValidationException("limit must be at least 1");
        }
        if (query.serviceIds().size() > MAX_SERVICES) {
            throw new OpeningSearchPolicyException(
                    OpeningSearchPolicyException.TOO_MANY_SERVICES,
                    "A search covers at most " + MAX_SERVICES + " services; "
                            + query.serviceIds().size() + " were given.");
        }
        if (query.horizonDays() > MAX_HORIZON_DAYS) {
            throw new OpeningSearchPolicyException(
                    OpeningSearchPolicyException.HORIZON_EXCEEDED,
                    "horizonDays " + query.horizonDays() + " exceeds the " + MAX_HORIZON_DAYS
                            + "-day policy limit; search a later earliestStart for openings beyond it.");
        }
        if (query.limit() > MAX_LIMIT) {
            throw new OpeningSearchPolicyException(
                    OpeningSearchPolicyException.LIMIT_EXCEEDED,
                    "limit " + query.limit() + " exceeds the maximum of " + MAX_LIMIT + " openings per search.");
        }
    }

    private Map<UUID, ExtCatalogServiceReplica> loadServices(List<UUID> serviceIds) {
        Map<UUID, ExtCatalogServiceReplica> services =
                catalogServiceRepository.findAllByServiceIdIn(serviceIds).stream()
                        .collect(Collectors.toMap(
                                ExtCatalogServiceReplica::getServiceId,
                                Function.identity(),
                                (a, b) -> a,
                                LinkedHashMap::new));
        for (UUID serviceId : serviceIds) {
            if (!services.containsKey(serviceId)) {
                throw new ResourceNotFoundException("catalog service " + serviceId);
            }
        }
        return services;
    }

    // ── bay eligibility (CAP-325 D13/D14) ───────────────────────────────────────────────────────

    record Eligibility(List<ExtBayReplica> eligible, int active, int byCapability, int byDutyClass) {
        BayEligibility summary() {
            return BayEligibility.builder()
                    .activeBays(active)
                    .eligibleBays(eligible.size())
                    .excludedByCapability(byCapability)
                    .excludedByDutyClass(byDutyClass)
                    .build();
        }
    }

    static Eligibility eligibleBays(
            List<ExtBayReplica> bays, Iterable<ExtCatalogServiceReplica> services, @Nullable Integer gvwrClass) {
        List<ExtBayReplica> eligible = new ArrayList<>(bays);
        for (ExtCatalogServiceReplica service : services) {
            String operation = SkillRequirementResolver.normalize(service.getOperationCode());
            if (operation.isEmpty()) {
                continue; // a service without an operation code is general work
            }
            List<ExtBayReplica> claimants =
                    bays.stream().filter(bay -> claims(bay, operation)).toList();
            // Somebody claims it: only they may do it. Nobody claims it: general work, which every bay
            // but a wash bay may do — a specialty bay too, ranked last (D14).
            eligible.retainAll(
                    claimants.isEmpty()
                            ? bays.stream().filter(bay -> !isWashDetail(bay)).toList()
                            : claimants);
        }
        // General bays first, so a specialty bay is offered for general work only after them.
        eligible.sort(Comparator.comparing((ExtBayReplica bay) -> isGeneral(bay) ? 0 : 1));
        int byCapability = bays.size() - eligible.size();
        int before = eligible.size();
        if (gvwrClass != null) {
            eligible.removeIf(bay -> bay.getMaxDutyClass() != null && bay.getMaxDutyClass() < gvwrClass);
        }
        return new Eligibility(eligible, bays.size(), byCapability, before - eligible.size());
    }

    private static boolean claims(ExtBayReplica bay, String operation) {
        return bay.getServiceCapabilityCodes() != null
                && bay.getServiceCapabilityCodes().stream()
                        .map(SkillRequirementResolver::normalize)
                        .anyMatch(operation::equals);
    }

    private static boolean isGeneral(ExtBayReplica bay) {
        return bay.getServiceCapabilityCodes() == null
                || bay.getServiceCapabilityCodes().isEmpty();
    }

    /** The one bay type that never absorbs general mechanical work (D14: the exception to the default). */
    static final String WASH_DETAIL = "WASH_DETAIL";

    private static boolean isWashDetail(ExtBayReplica bay) {
        return WASH_DETAIL.equalsIgnoreCase(bay.getBayType());
    }

    // ── time ────────────────────────────────────────────────────────────────────────────────────

    /** A half-open instant range {@code [start, end)}. */
    record Interval(Instant start, Instant end) {
        boolean overlaps(Instant from, Instant to) {
            return start.isBefore(to) && end.isAfter(from);
        }
    }

    /** The day's operating window in the zone, or null when closed, a holiday, or unassemblable. */
    private static @Nullable Interval openWindow(
            LocalDate date,
            ZoneId zone,
            Map<DayOfWeek, RawOperatingHoursEntry> hoursByDow,
            Map<LocalDate, String> closures) {
        if (closures.containsKey(date)) {
            return null;
        }
        RawOperatingHoursEntry entry = hoursByDow.get(date.getDayOfWeek());
        if (entry == null) {
            return null;
        }
        try {
            LocalTime open = LocalTime.parse(entry.openTime());
            LocalTime close = LocalTime.parse(entry.closeTime());
            if (!open.isBefore(close)) {
                return null;
            }
            return new Interval(
                    date.atTime(open).atZone(zone).toInstant(),
                    date.atTime(close).atZone(zone).toInstant());
        } catch (RuntimeException e) {
            log.warn("Date {} has an unassemblable operatingHours entry; no openings are offered on it", date, e);
            return null;
        }
    }

    /** Held appointments grouped by the resource id they hold — a bay id or a technician's person id. */
    static Map<String, List<Interval>> busyByResource(List<Appointment> held) {
        Map<String, List<Interval>> busy = new HashMap<>();
        for (Appointment appointment : held) {
            if (appointment.getResourceId() == null
                    || appointment.getStartAt() == null
                    || appointment.getEndAt() == null) {
                continue;
            }
            busy.computeIfAbsent(appointment.getResourceId(), ignored -> new ArrayList<>())
                    .add(new Interval(appointment.getStartAt(), appointment.getEndAt()));
        }
        busy.values().forEach(intervals -> intervals.sort(Comparator.comparing(Interval::start)));
        return busy;
    }

    /** The free sub-ranges of {@code day} once {@code busy} (sorted by start) is removed. */
    static List<Interval> freeGaps(Interval day, List<Interval> busy) {
        List<Interval> gaps = new ArrayList<>();
        Instant cursor = day.start();
        for (Interval interval : busy) {
            if (!interval.overlaps(day.start(), day.end())) {
                continue;
            }
            if (interval.start().isAfter(cursor)) {
                gaps.add(new Interval(cursor, interval.start()));
            }
            if (interval.end().isAfter(cursor)) {
                cursor = interval.end();
            }
        }
        if (cursor.isBefore(day.end())) {
            gaps.add(new Interval(cursor, day.end()));
        }
        return gaps;
    }

    /**
     * The earliest start in {@code gap} at which the buffered job fits and a rostered technician is
     * free, or null. Candidate starts are the gap's own earliest point and every instant a
     * technician's overlapping appointment ends — nothing else can make an unavailable minute
     * available. A technician holding every required skill is preferred at each candidate.
     */
    private static @Nullable Opening firstFit(
            Interval gap,
            Instant earliestStart,
            Duration duration,
            Duration checkIn,
            Duration cleanup,
            List<UUID> rostered,
            Map<UUID, List<String>> unmetByTechnician,
            Map<String, List<Interval>> busyByResource,
            ExtBayReplica bay,
            LocalDate date,
            List<OpeningConstraint> constraints) {
        Instant base = gap.start().plus(checkIn);
        if (earliestStart.isAfter(base)) {
            base = earliestStart;
        }
        TreeSet<Instant> candidates = new TreeSet<>();
        candidates.add(base);
        for (UUID person : rostered) {
            for (Interval interval : busyByResource.getOrDefault(person.toString(), List.of())) {
                if (interval.end().isAfter(base) && interval.end().isBefore(gap.end())) {
                    candidates.add(interval.end());
                }
            }
        }
        for (Instant start : candidates) {
            Instant end = start.plus(duration);
            if (end.plus(cleanup).isAfter(gap.end())) {
                return null; // later starts only fit worse
            }
            UUID chosen = null;
            for (UUID person : rostered) {
                boolean free = busyByResource.getOrDefault(person.toString(), List.of()).stream()
                        .noneMatch(interval -> interval.overlaps(start, end));
                if (!free) {
                    continue;
                }
                if (unmetByTechnician.get(person).isEmpty()) {
                    chosen = person;
                    break;
                }
                if (chosen == null) {
                    chosen = person;
                }
            }
            if (chosen != null) {
                List<String> unmet = unmetByTechnician.get(chosen);
                return Opening.builder()
                        .startAt(start)
                        .endAt(end)
                        .localDate(date.toString())
                        .bayId(bay.getBayId())
                        .bayName(bay.getName())
                        .technicianId(chosen)
                        .technicianRosterGrain(ROSTER_GRAIN_DAY)
                        .skillFulfillment(unmet.isEmpty() ? SkillFulfillment.CERTIFIED : SkillFulfillment.AWAITING)
                        .unmetSkillCodes(unmet)
                        .constraintsEvaluated(constraints)
                        .build();
            }
        }
        return null;
    }

    // ── staffing advisory (D10.2) ───────────────────────────────────────────────────────────────

    /**
     * Nobody rostered on any open day in the horizon → MECHANIC_UNAVAILABLE, scoped by whether the
     * location has technicians at all. Otherwise, any required skill held by nobody rostered in the
     * horizon → NO_COMPETENT_MECHANIC_ROSTERED, scoped NOT_AT_THIS_LOCATION when nobody staffed here
     * holds it at all (the remedy is another branch), else NOT_ROSTERED_THIS_DAY (another day).
     */
    private static @Nullable StaffingAdvisory staffingAdvisory(
            boolean anyoneRostered,
            Set<UUID> everyoneHere,
            List<ExtPersonCredentialReplica> credentials,
            Map<String, UUID> required,
            Set<String> heldInHorizon,
            LocalDate firstDate) {
        if (!anyoneRostered) {
            return StaffingAdvisory.builder()
                    .code(StaffingAdvisoryCode.MECHANIC_UNAVAILABLE)
                    .missingSkillCodes(List.of())
                    .absenceScope(
                            everyoneHere.isEmpty()
                                    ? AbsenceScope.NOT_AT_THIS_LOCATION
                                    : AbsenceScope.NOT_ROSTERED_THIS_DAY)
                    .build();
        }
        List<String> missingInHorizon = required.keySet().stream()
                .filter(code -> !heldInHorizon.contains(code))
                .toList();
        if (missingInHorizon.isEmpty()) {
            return null;
        }
        Map<String, Set<UUID>> heldByAnyoneHere =
                SkillRequirementResolver.holdersBySkill(credentials, everyoneHere, missingInHorizon, firstDate);
        boolean structural = missingInHorizon.stream().anyMatch(code -> !heldByAnyoneHere.containsKey(code));
        return StaffingAdvisory.builder()
                .code(StaffingAdvisoryCode.NO_COMPETENT_MECHANIC_ROSTERED)
                .missingSkillCodes(missingInHorizon)
                .absenceScope(structural ? AbsenceScope.NOT_AT_THIS_LOCATION : AbsenceScope.NOT_ROSTERED_THIS_DAY)
                .build();
    }

    private static List<OpeningConstraint> constraints(boolean skillEvaluated) {
        List<OpeningConstraint> constraints = new ArrayList<>(List.of(
                OpeningConstraint.HOURS,
                OpeningConstraint.BAY,
                OpeningConstraint.DURATION,
                OpeningConstraint.BUFFER,
                OpeningConstraint.ROSTER));
        if (skillEvaluated) {
            constraints.add(OpeningConstraint.SKILL);
        }
        return List.copyOf(constraints);
    }

    private static int zeroIfNull(@Nullable Integer value) {
        return value == null ? 0 : value;
    }
}
