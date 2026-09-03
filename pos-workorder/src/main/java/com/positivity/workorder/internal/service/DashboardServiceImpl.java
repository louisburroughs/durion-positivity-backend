package com.positivity.workorder.internal.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.workorder.internal.dto.BayStatus;
import com.positivity.workorder.internal.dto.ConflictEntry;
import com.positivity.workorder.internal.dto.DashboardResponse;
import com.positivity.workorder.internal.dto.MechanicStatus;
import com.positivity.workorder.internal.dto.MobileUnitStatus;
import com.positivity.workorder.internal.dto.PeopleAvailabilityResponse;
import com.positivity.workorder.internal.dto.PeopleAvailabilityResponse.BreakInfo;
import com.positivity.workorder.internal.dto.PeopleAvailabilityResponse.PersonAvailability;
import com.positivity.workorder.internal.dto.PeopleAvailabilityResponse.PtoBlock;
import com.positivity.workorder.internal.dto.PtoEntry;
import com.positivity.workorder.internal.dto.WorkorderSummary;
import com.positivity.workorder.internal.entity.ExtBayReplica;
import com.positivity.workorder.internal.entity.ExtMobileUnitReplica;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.ResourceType;
import com.positivity.workorder.internal.repository.ExtBayReplicaRepository;
import com.positivity.workorder.internal.repository.ExtMobileUnitReplicaRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

/**
 * Implementation of {@link DashboardService} for the Daily Dispatch Board
 * Dashboard.
 * Aggregates workorder, mechanic, bay and mobile-unit data for conflict detection and
 * display.
 *
 * <p>Resource identity (#1656) is resolved from the {@code ext_bay} and {@code ext_mobile_unit}
 * replicas fed by {@code location.events.v1} (ADR-0044 §6) — never by a synchronous call into
 * pos-location and never by reading its tables. That distinction is what the retired shopmgr
 * bay-status client got wrong; see the note in {@link #getDashboard}.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {
    private static final String WARNING = "WARNING";

    private static final String MECHANIC_PREFIX = "Mechanic ";

    private static final String BLOCKING = "BLOCKING";

    private final Clock clock;

    private final WorkorderRepository workorderRepository;
    private final ExtBayReplicaRepository extBayReplicaRepository;
    private final ExtMobileUnitReplicaRepository extMobileUnitReplicaRepository;
    private final PeopleAvailabilityLocalService peopleAvailabilityLocalService;
    private final EstimatedLaborService estimatedLaborService;
    private final ObjectMapper objectMapper;

    @Override
    public DashboardResponse getDashboard(@NonNull String locationId, @NonNull LocalDate date) {
        UUID locationUuid = parseLocationUuid(locationId);

        List<Workorder> workorders = workorderRepository.findByScheduledDateAndLocationId(date, locationUuid);

        // Availability is computed from local replicas (#877); a null response means the
        // replica lookup could not produce data, which the dashboard surfaces as degraded.
        PeopleAvailabilityResponse availability = peopleAvailabilityLocalService.fetchAvailability(locationId, date);
        boolean peopleDegraded = availability == null;
        List<PersonAvailability> people =
                availability != null && availability.getPeople() != null ? availability.getPeople() : List.of();

        // Resource panels are built from the location replicas joined to the day's assignments
        // (#1656). The predecessor here was the shopmgr bay-status client (#898): a synchronous
        // cross-domain read whose endpoint no longer existed and which always yielded an empty
        // list, so bay enrichment never reached this dashboard at all. The replacement is the
        // event-fed replica (ADR-0044 §6), not another client.
        List<WorkorderSummary> workorderSummaries = buildWorkorderSummaries(workorders);
        List<MechanicStatus> mechanicStatuses = buildMechanicStatuses(workorders, people);
        List<BayStatus> bayStatuses = buildBayStatuses(locationUuid, workorders);
        List<MobileUnitStatus> mobileUnitStatuses = buildMobileUnitStatuses(locationUuid, workorders);

        List<ConflictEntry> conflicts = detectAllConflicts(workorders, people, date);

        return DashboardResponse.builder()
                .date(date)
                .locationId(locationId)
                .workorders(workorderSummaries)
                .mechanics(mechanicStatuses)
                .bays(bayStatuses)
                .mobileUnits(mobileUnitStatuses)
                .conflicts(conflicts)
                .lastRefreshed(Instant.now(clock))
                .dataQualityWarning(peopleDegraded)
                .build();
    }

    private UUID parseLocationUuid(String locationId) {
        try {
            return UUID.fromString(locationId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("locationId is not a valid UUID: " + locationId, e);
        }
    }

    private List<WorkorderSummary> buildWorkorderSummaries(List<Workorder> workorders) {
        return workorders.stream()
                .map(wo -> WorkorderSummary.builder()
                        .workorderId(wo.getId())
                        .status(wo.getStatus() != null ? wo.getStatus().name() : null)
                        .scheduledDate(wo.getScheduledDate())
                        .assignedMechanicId(parseFirstMechanic(wo.getMechanicIds()))
                        .assignedBayId(
                                wo.getResourceId() != null ? wo.getResourceId().toString() : null)
                        // #1569: the overlap-aware sum of the workorder's agreed labor hours —
                        // the field stops serialising as an unkept promise. One line query per
                        // workorder; dashboard pages are small, and the summation reads
                        // snapshots, not the live catalog.
                        .estimatedLaborHours(estimatedLaborService
                                .estimateForWorkorder(wo.getId())
                                .estimatedHours())
                        .build())
                .toList();
    }

    private List<MechanicStatus> buildMechanicStatuses(List<Workorder> workorders, List<PersonAvailability> people) {
        Map<String, String> workorderByMechanicId = new LinkedHashMap<>();
        for (Workorder wo : workorders) {
            for (String mId : parseMechanicIds(wo.getMechanicIds())) {
                workorderByMechanicId.putIfAbsent(
                        mId, wo.getId() != null ? wo.getId().toString() : null);
            }
        }

        return people.stream()
                .map(pa -> MechanicStatus.builder()
                        .personId(pa.getPersonId())
                        .firstName(pa.getFirstName())
                        .lastName(pa.getLastName())
                        .currentStatus(pa.getCurrentStatus())
                        .onBreak(pa.getBreakInfo() != null && pa.getBreakInfo().isOnBreak())
                        .breakExpectedReturn(
                                pa.getBreakInfo() != null ? pa.getBreakInfo().getExpectedReturn() : null)
                        .assignedWorkorderId(workorderByMechanicId.get(pa.getPersonId()))
                        .ptoEntries(
                                pa.getPto() != null
                                        ? pa.getPto().stream()
                                                .map(p -> PtoEntry.builder()
                                                        .ptoId(p.getPtoId())
                                                        .start(p.getStart())
                                                        .end(p.getEnd())
                                                        .ptoType(p.getPtoType())
                                                        .build())
                                                .toList()
                                        : List.of())
                        .build())
                .toList();
    }

    private List<BayStatus> buildBayStatuses(UUID locationId, List<Workorder> workorders) {
        Map<UUID, String> namesById = new LinkedHashMap<>();
        // Not Collectors.toMap: a replica row with no name yet is legitimate, and toMap rejects a
        // null value outright.
        for (ExtBayReplica bay : extBayReplicaRepository.findByLocationIdAndActiveTrueOrderByNameAsc(locationId)) {
            namesById.putIfAbsent(bay.getBayId(), bay.getName());
        }

        return buildResourcePanel(
                        ResourceType.BAY,
                        workorders,
                        namesById,
                        id -> extBayReplicaRepository.findById(id).map(ExtBayReplica::getName))
                .stream()
                .map(row -> BayStatus.builder()
                        .bayId(row.resourceId().toString())
                        .bayName(row.name())
                        .status(row.status())
                        .available(row.available())
                        .assignedWorkorderId(row.assignedWorkorderId())
                        .build())
                .toList();
    }

    private List<MobileUnitStatus> buildMobileUnitStatuses(UUID locationId, List<Workorder> workorders) {
        Map<UUID, String> namesById = new LinkedHashMap<>();
        // Not Collectors.toMap — see buildBayStatuses.
        for (ExtMobileUnitReplica unit :
                extMobileUnitReplicaRepository.findByBaseLocationIdAndActiveTrueOrderByNameAsc(locationId)) {
            namesById.putIfAbsent(unit.getMobileUnitId(), unit.getName());
        }

        return buildResourcePanel(
                        ResourceType.MOBILE_UNIT,
                        workorders,
                        namesById,
                        id -> extMobileUnitReplicaRepository.findById(id).map(ExtMobileUnitReplica::getName))
                .stream()
                .map(row -> MobileUnitStatus.builder()
                        .unitId(row.resourceId().toString())
                        .unitName(row.name())
                        .status(row.status())
                        .available(row.available())
                        .assignedWorkorderId(row.assignedWorkorderId())
                        .build())
                .toList();
    }

    /**
     * One row of a resource panel, before it is shaped into the bay- or unit-flavoured DTO. Bays and
     * mobile units are different aggregates upstream but answer the same dispatch question, so the
     * resolution rules live here once instead of being written twice and drifting.
     */
    private record ResourcePanelRow(
            UUID resourceId, String name, String status, boolean available, String assignedWorkorderId) {}

    /**
     * Builds one resource panel: every active resource of {@code resourceType} at the location, plus
     * any resource the day's open work still points at, each marked occupied or idle.
     *
     * <p>Three rules, each of which the pre-#1656 code got wrong or could not express:
     *
     * <ul>
     *   <li><b>Idle resources are rows, not omissions.</b> The panel starts from the replica's active
     *       set for the location, so a bay or unit with no work today reports
     *       {@code assignedWorkorderId: null} instead of vanishing. Deriving the list from the day's
     *       workorders — as {@code buildBayStatuses} used to — can only ever show busy resources.
     *   <li><b>Locked work does not occupy anything.</b> {@link Workorder#isLocked()} is the single
     *       authority (CANCELLED, or COMPLETED and not reopened). A naive
     *       {@code status ∈ {COMPLETED, CANCELLED}} test would re-lock a reopened workorder and show
     *       its resource as free while someone is still working on it.
     *   <li><b>A resource still holding open work is always rendered</b>, even when it is not in the
     *       active set — it was decommissioned mid-job, or its own replica row has not arrived yet.
     *       Both cases are handled at the one marked spot below.
     * </ul>
     *
     * @param resourceType the panel being built
     * @param workorders the day's workorders at this location
     * @param activeNamesById active resources at the location, id → name, in display order
     * @param nameLookup resolves the name of a resource outside the active set, empty when unknown
     * @return the panel rows, active resources first in replica order
     */
    private List<ResourcePanelRow> buildResourcePanel(
            ResourceType resourceType,
            List<Workorder> workorders,
            Map<UUID, String> activeNamesById,
            Function<UUID, Optional<String>> nameLookup) {

        // Only open work occupies a resource; a cancelled or completed-not-reopened workorder that
        // still carries its old assignment is stale and must not hold the resource down.
        Map<UUID, Workorder> occupantByResourceId = new LinkedHashMap<>();
        for (Workorder workorder : workorders) {
            if (workorder.getResourceId() == null || workorder.isLocked()) {
                continue;
            }
            if (effectiveResourceType(workorder) != resourceType) {
                continue;
            }
            occupantByResourceId.merge(workorder.getResourceId(), workorder, DashboardServiceImpl::mostRecentlyUpdated);
        }

        Map<UUID, String> panelNamesById = new LinkedHashMap<>(activeNamesById);
        // Open work on a resource the active set does not contain. Two causes, one least-surprising
        // answer: render the row. Either the resource was decommissioned or deleted while a job was
        // still on it (lifecycle semantics pos-location owns, not this module — open follow-up), or
        // its replica row simply has not landed yet (the assignment fact overtook the resource fact).
        // Hiding the row would make live work invisible on the board, which is strictly worse than
        // showing a row whose name is momentarily null.
        for (UUID resourceId : occupantByResourceId.keySet()) {
            // Not computeIfAbsent: a resource whose name resolves to null still needs a row, and
            // computeIfAbsent drops a null mapping on the floor.
            if (!panelNamesById.containsKey(resourceId)) {
                panelNamesById.put(resourceId, nameLookup.apply(resourceId).orElse(null));
            }
        }

        List<ResourcePanelRow> rows = new ArrayList<>(panelNamesById.size());
        for (Map.Entry<UUID, String> entry : panelNamesById.entrySet()) {
            Workorder occupant = occupantByResourceId.get(entry.getKey());
            rows.add(new ResourcePanelRow(
                    entry.getKey(),
                    entry.getValue(),
                    occupant != null ? "OCCUPIED" : "AVAILABLE",
                    occupant == null,
                    occupant != null && occupant.getId() != null
                            ? occupant.getId().toString()
                            : null));
        }
        return rows;
    }

    /**
     * The resource type to file a workorder's assignment under.
     *
     * <p>A row can still carry a null {@code resourceType} with a non-null {@code resourceId}: V27
     * backfills the rows that existed when the column was added, but a workorder written by a path
     * that predates the backfill — or replayed from an older event — can arrive untyped. Reading it
     * as {@link ResourceType#BAY} is the same fallback the write path applies, so a null here means
     * "bay", exactly as it did before mobile units were representable.
     */
    private static ResourceType effectiveResourceType(Workorder workorder) {
        return ResourceType.orDefault(workorder.getResourceType());
    }

    /** Later {@code updatedAt} wins; a null timestamp loses, and ties keep the incumbent. */
    private static Workorder mostRecentlyUpdated(Workorder incumbent, Workorder challenger) {
        if (challenger.getUpdatedAt() == null) {
            return incumbent;
        }
        if (incumbent.getUpdatedAt() == null) {
            return challenger;
        }
        return challenger.getUpdatedAt().isAfter(incumbent.getUpdatedAt()) ? challenger : incumbent;
    }

    private List<ConflictEntry> detectAllConflicts(
            List<Workorder> workorders, List<PersonAvailability> people, LocalDate date) {
        List<ConflictEntry> conflicts = new ArrayList<>();
        detectBayDoubleBooking(workorders, conflicts);
        detectMechanicDoubleBookingFromWorkorders(workorders, conflicts);
        detectMechanicStatusConflicts(workorders, people, date, conflicts);
        detectLocationMismatch(workorders, people, conflicts);
        detectMechanicSkillMismatch(workorders, people, conflicts);
        return conflicts;
    }

    private List<String> parseMechanicIds(String mechanicIds) {
        if (mechanicIds == null || mechanicIds.isBlank()) {
            return Collections.emptyList();
        }
        try {
            ObjectMapper mapper = objectMapper;
            return mapper.readValue(mechanicIds, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse mechanicIds JSON: {}", mechanicIds, e);
            return Collections.emptyList();
        }
    }

    private String parseFirstMechanic(String mechanicIds) {
        List<String> ids = parseMechanicIds(mechanicIds);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private List<String> parseCertifications(String certifications) {
        if (certifications == null || certifications.isBlank()) {
            return Collections.emptyList();
        }
        try {
            ObjectMapper mapper = objectMapper;
            return mapper.readValue(certifications, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse requiredCertifications JSON: {}", certifications, e);
            return Collections.emptyList();
        }
    }

    private void detectBayDoubleBooking(List<Workorder> workorders, List<ConflictEntry> conflicts) {
        Map<UUID, List<Workorder>> byBay = workorders.stream()
                .filter(wo -> wo.getResourceId() != null)
                .collect(Collectors.groupingBy(Workorder::getResourceId));
        for (Map.Entry<UUID, List<Workorder>> entry : byBay.entrySet()) {
            if (entry.getValue().size() > 1) {
                conflicts.add(ConflictEntry.builder()
                        .conflictType("BAY_DOUBLE_BOOKED")
                        .severity(BLOCKING)
                        .message("Bay " + entry.getKey() + " is assigned to multiple workorders")
                        .affectedResourceId(entry.getKey().toString())
                        .build());
            }
        }
    }

    private void detectMechanicDoubleBookingFromWorkorders(List<Workorder> workorders, List<ConflictEntry> conflicts) {
        Map<String, Long> mechanicCounts = workorders.stream()
                .flatMap(wo -> parseMechanicIds(wo.getMechanicIds()).stream())
                .collect(Collectors.groupingBy(id -> id, Collectors.counting()));
        for (Map.Entry<String, Long> entry : mechanicCounts.entrySet()) {
            if (entry.getValue() > 1) {
                conflicts.add(ConflictEntry.builder()
                        .conflictType("DOUBLE_BOOKED_MECHANIC")
                        .severity(BLOCKING)
                        .message(MECHANIC_PREFIX + entry.getKey() + " is assigned to multiple workorders")
                        .affectedResourceId(entry.getKey())
                        .build());
            }
        }
    }

    private void detectMechanicStatusConflicts(
            List<Workorder> workorders,
            List<PersonAvailability> people,
            LocalDate date,
            List<ConflictEntry> conflicts) {

        Map<String, PersonAvailability> availabilityByPersonId =
                people.stream().collect(Collectors.toMap(PersonAvailability::getPersonId, pa -> pa, (a, b) -> a));

        List<String> assignedMechanicIds = workorders.stream()
                .flatMap(wo -> parseMechanicIds(wo.getMechanicIds()).stream())
                .distinct()
                .toList();

        Instant dayStart = date.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant dayEnd = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant now = Instant.now(clock);
        Instant fifteenMinFromNow = now.plusSeconds(900);

        for (String mechanicId : assignedMechanicIds) {
            PersonAvailability personAvailability = availabilityByPersonId.get(mechanicId);
            if (personAvailability == null) {
                continue;
            }

            detectClockOutMismatch(mechanicId, personAvailability, conflicts);
            detectPtoOverlap(mechanicId, personAvailability, dayStart, dayEnd, conflicts);
            detectBreakOverlap(mechanicId, personAvailability, now, fifteenMinFromNow, conflicts);
        }
    }

    private void detectClockOutMismatch(
            String mechanicId, PersonAvailability personAvailability, List<ConflictEntry> conflicts) {
        if ("ON_JOB".equals(personAvailability.getCurrentStatus())) {
            conflicts.add(ConflictEntry.builder()
                    .conflictType("CLOCK_OUT_MISMATCH")
                    .severity(WARNING)
                    .message(MECHANIC_PREFIX + mechanicId + " is clocked in for another job and has not clocked out")
                    .affectedResourceId(personAvailability.getPersonId())
                    .build());
        }
    }

    private void detectPtoOverlap(
            String mechanicId,
            PersonAvailability personAvailability,
            Instant dayStart,
            Instant dayEnd,
            List<ConflictEntry> conflicts) {
        if (personAvailability.getPto() == null) {
            return;
        }
        for (PtoBlock pto : personAvailability.getPto()) {
            if (ptoOverlapsDate(pto, dayStart, dayEnd)) {
                conflicts.add(ConflictEntry.builder()
                        .conflictType("MECHANIC_PTO_OVERLAP")
                        .severity(BLOCKING)
                        .message(MECHANIC_PREFIX + mechanicId + " has PTO on this date")
                        .affectedResourceId(mechanicId)
                        .build());
                break;
            }
        }
    }

    private boolean ptoOverlapsDate(PtoBlock pto, Instant dayStart, Instant dayEnd) {
        return pto.getStart() != null
                && pto.getEnd() != null
                && pto.getStart().isBefore(dayEnd)
                && pto.getEnd().isAfter(dayStart);
    }

    /**
     * AC-6: Break overlap — expects return within 15 min of query time. Note: workorder scheduled
     * start time is not yet in the data model (scheduledDate only). This uses query-time
     * proximity as a proxy until scheduled start is added.
     */
    private void detectBreakOverlap(
            String mechanicId,
            PersonAvailability personAvailability,
            Instant now,
            Instant fifteenMinFromNow,
            List<ConflictEntry> conflicts) {
        if (!isReturningSoonFromBreak(personAvailability.getBreakInfo(), now, fifteenMinFromNow)) {
            return;
        }
        conflicts.add(ConflictEntry.builder()
                .conflictType("MECHANIC_BREAK_OVERLAP")
                .severity(WARNING)
                .message("Job overlaps with expected break time for mechanic " + mechanicId)
                .affectedResourceId(mechanicId)
                .build());
    }

    private boolean isReturningSoonFromBreak(BreakInfo breakInfo, Instant now, Instant fifteenMinFromNow) {
        return breakInfo != null
                && breakInfo.isOnBreak()
                && breakInfo.getExpectedReturn() != null
                && breakInfo.getExpectedReturn().isAfter(now)
                && breakInfo.getExpectedReturn().isBefore(fifteenMinFromNow);
    }

    private void detectLocationMismatch(
            List<Workorder> workorders,
            List<PeopleAvailabilityResponse.PersonAvailability> people,
            List<ConflictEntry> conflicts) {
        Map<String, PeopleAvailabilityResponse.PersonAvailability> availabilityByPersonId = people.stream()
                .collect(Collectors.toMap(
                        PeopleAvailabilityResponse.PersonAvailability::getPersonId, pa -> pa, (a, b) -> a));
        for (Workorder wo : workorders) {
            if (wo.getLocationId() == null) {
                continue;
            }
            String workorderLocationStr = wo.getLocationId().toString();
            for (String mechanicId : parseMechanicIds(wo.getMechanicIds())) {
                PeopleAvailabilityResponse.PersonAvailability pa = availabilityByPersonId.get(mechanicId);
                if (pa == null || pa.getCurrentLocationId() == null) {
                    continue;
                }
                if (!workorderLocationStr.equals(pa.getCurrentLocationId())) {
                    conflicts.add(ConflictEntry.builder()
                            .conflictType("LOCATION_MISMATCH")
                            .severity(WARNING)
                            .message(MECHANIC_PREFIX + mechanicId + " is at a different location than the workorder")
                            .affectedResourceId(mechanicId)
                            .build());
                }
            }
        }
    }

    private void detectMechanicSkillMismatch(
            List<Workorder> workorders,
            List<PeopleAvailabilityResponse.PersonAvailability> people,
            List<ConflictEntry> conflicts) {
        Map<String, PeopleAvailabilityResponse.PersonAvailability> availabilityByPersonId = people.stream()
                .collect(Collectors.toMap(
                        PeopleAvailabilityResponse.PersonAvailability::getPersonId, pa -> pa, (a, b) -> a));
        for (Workorder wo : workorders) {
            List<String> requiredCerts = parseCertifications(wo.getRequiredCertifications());
            if (requiredCerts.isEmpty()) {
                continue;
            }
            detectMissingCertificationsForWorkorder(wo, requiredCerts, availabilityByPersonId, conflicts);
        }
    }

    private void detectMissingCertificationsForWorkorder(
            Workorder wo,
            List<String> requiredCerts,
            Map<String, PeopleAvailabilityResponse.PersonAvailability> availabilityByPersonId,
            List<ConflictEntry> conflicts) {
        for (String mechanicId : parseMechanicIds(wo.getMechanicIds())) {
            PeopleAvailabilityResponse.PersonAvailability pa = availabilityByPersonId.get(mechanicId);
            if (pa == null) {
                continue;
            }
            String missingCert = firstMissingCertification(pa, requiredCerts);
            if (missingCert != null) {
                conflicts.add(ConflictEntry.builder()
                        .conflictType("MECHANIC_SKILL_MISMATCH")
                        .severity(WARNING)
                        .message(MECHANIC_PREFIX + mechanicId + " is missing required certification: " + missingCert)
                        .affectedResourceId(mechanicId)
                        .build());
            }
        }
    }

    /** First required certification the mechanic's profile does not list, or {@code null} if it holds them all. */
    private String firstMissingCertification(
            PeopleAvailabilityResponse.PersonAvailability pa, List<String> requiredCerts) {
        List<String> mechanicCerts = pa.getCertifications() != null ? pa.getCertifications() : List.of();
        for (String required : requiredCerts) {
            if (!mechanicCerts.contains(required)) {
                return required;
            }
        }
        return null;
    }
}
