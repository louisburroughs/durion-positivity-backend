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
import com.positivity.workorder.internal.entity.ExtCustomerPartyReplica;
import com.positivity.workorder.internal.entity.ExtMobileUnitReplica;
import com.positivity.workorder.internal.entity.ExtVehicleReplica;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.ResourceType;
import com.positivity.workorder.internal.enums.WorkorderItemStatus;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.exception.WorkorderRequestValidationException;
import com.positivity.workorder.internal.repository.ExtBayReplicaRepository;
import com.positivity.workorder.internal.repository.ExtCustomerPartyReplicaRepository;
import com.positivity.workorder.internal.repository.ExtMobileUnitReplicaRepository;
import com.positivity.workorder.internal.repository.ExtVehicleReplicaRepository;
import com.positivity.workorder.internal.repository.TechnicianAssignmentRepository;
import com.positivity.workorder.internal.repository.WorkorderLaborEntryRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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

    /** Service-line descriptions a summary carries; a card has room for a lead line, not a list. */
    private static final int MAX_SYNOPSIS_DESCRIPTIONS = 3;

    private final Clock clock;

    private final WorkorderRepository workorderRepository;
    private final ExtBayReplicaRepository extBayReplicaRepository;
    private final ExtMobileUnitReplicaRepository extMobileUnitReplicaRepository;
    private final ExtVehicleReplicaRepository extVehicleReplicaRepository;
    private final ExtCustomerPartyReplicaRepository extCustomerPartyReplicaRepository;
    private final TechnicianAssignmentRepository technicianAssignmentRepository;
    private final WorkorderServiceRepository workorderServiceRepository;
    private final WorkorderLaborEntryRepository workorderLaborEntryRepository;
    private final PeopleAvailabilityLocalService peopleAvailabilityLocalService;
    private final EstimatedLaborService estimatedLaborService;
    private final ObjectMapper objectMapper;

    @Override
    public DashboardResponse getDashboard(@NonNull String locationId, @NonNull LocalDate date) {
        UUID locationUuid = parseLocationUuid(locationId);

        List<Workorder> scheduledForDate = workorderRepository.findByScheduledDateAndLocationId(date, locationUuid);

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
        //
        // Occupancy comes from its own query, not from the day's rows (#1656 review finding 3).
        // Once a panel positively asserts AVAILABLE it is making a claim, and the day's rows cannot
        // support that claim: a work-in-progress job scheduled two days ago is still in its bay
        // today and would never appear in findByScheduledDateAndLocationId for today. One extra
        // query per render covers both panels — never one per unit. The panels themselves cost one
        // roster query each, plus at most one batched findAllById each for resources the roster does
        // not list; nothing in this method scales with the number of bays or units.
        List<Workorder> resourceHolders = workorderRepository.findOpenResourceHoldersAtLocation(locationUuid, date);

        // #2002: the roster is the day's scheduled rows plus the carryover the panels already
        // render. Selecting it on scheduledDate alone made the board contradict itself — a
        // multi-day job that started yesterday held its bay, so bays[] reported that bay OCCUPIED
        // by a workorderId that appeared nowhere in workorders[], and the dispatcher had an
        // occupied resource with no job attached to it. Both panels now answer from one set.
        List<Workorder> workorders = mergeRoster(scheduledForDate, resourceHolders);

        Map<Workorder, List<String>> mechanicsByWorkorder = assignedMechanics(workorders);
        List<WorkorderSummary> workorderSummaries = buildWorkorderSummaries(
                workorders,
                mechanicsByWorkorder,
                vehicleDescriptions(workorders),
                customerNames(workorders),
                synopses(workorders));
        List<MechanicStatus> mechanicStatuses = buildMechanicStatuses(workorders, people, mechanicsByWorkorder);
        List<BayStatus> bayStatuses = buildBayStatuses(locationUuid, resourceHolders);
        List<MobileUnitStatus> mobileUnitStatuses = buildMobileUnitStatuses(locationUuid, resourceHolders);

        // Conflicts are computed off the same resourceHolders set the panels use (#1656):
        // resource double-booking is an occupancy question, and answering it from the
        // day's rows while the panels answered it from the holders let the two disagree in the one
        // case that matters — a bay claimed by a job that started yesterday and a job scheduled for
        // today. Mechanic, status, location and skill conflicts run on the roster, which is the
        // board's answer to "what is this shop working on today" — and since #2002 that answer
        // includes the carryover job in bay 3. A mechanic put on a new job this morning while still
        // owning yesterday's unfinished one is double-booked today whatever the second job's
        // scheduledDate says.
        List<ConflictEntry> conflicts =
                detectAllConflicts(workorders, resourceHolders, people, date, mechanicsByWorkorder);

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

    /**
     * The dispatch board's workorder roster: everything scheduled for the date, plus the open work
     * still holding a resource from an earlier date (#2002).
     *
     * <p>The two inputs answer different questions and the board needs both. The first is the day's
     * schedule — every workorder at this location dated today, whatever its status and whether or
     * not it has been placed anywhere. The second is occupancy, bounded at the board's date by
     * {@code WorkorderRepository#findOpenResourceHoldersAtLocation}: the multi-day job scheduled on
     * Monday is still in its bay on Wednesday, and Wednesday's board must show it. Merging them
     * here rather than widening the date predicate of the first query is what keeps the two panels
     * honest about each other — the roster is, by construction, a superset of every workorder the
     * bay and mobile-unit panels name as an occupant.
     *
     * <p>Two filters apply to the carryover half, and both are the panels' own. Locked holders are
     * dropped because {@link Workorder#isLocked()} is the single authority on whether a job is
     * still live, and a cancelled workorder that still carries a stale resource id is not work
     * anybody is dispatching today. Non-exclusive holders are dropped because the query answers
     * "holds a resource id", which a parked workorder does too — {@code (HOLD, its own locationId)}
     * — and a hold is a lot, not a slot: no panel renders it, so nothing here obliges the roster to
     * carry it, and admitting it would silently add every open parked job from every past date
     * under a rule this method does not claim. A workorder parked today still reaches the board
     * through the day's schedule, which is not filtered at all — a workorder completed this morning
     * likewise belongs on today's board as completed work.
     *
     * <p>Order is the day's schedule first, then carryover, each in query order; ids already
     * present are not added twice. Rows without an id cannot be matched against anything, so they
     * are carried through as they arrive rather than silently collapsed.
     *
     * @param scheduledForDate rows whose {@code scheduledDate} is exactly the board's date
     * @param resourceHolders open rows holding any resource id on or before the board's date,
     *     {@link com.positivity.workorder.internal.enums.ResourceType#HOLD} included
     * @return the roster, deduplicated by workorder id
     */
    private static List<Workorder> mergeRoster(List<Workorder> scheduledForDate, List<Workorder> resourceHolders) {
        List<Workorder> roster = new ArrayList<>(scheduledForDate);
        Set<UUID> seen = new HashSet<>();
        for (Workorder workorder : scheduledForDate) {
            if (workorder.getId() != null) {
                seen.add(workorder.getId());
            }
        }
        for (Workorder holder : resourceHolders) {
            if (holder.isLocked() || !effectiveResourceType(holder).isExclusive()) {
                continue;
            }
            if (holder.getId() != null && !seen.add(holder.getId())) {
                continue;
            }
            roster.add(holder);
        }
        return roster;
    }

    private UUID parseLocationUuid(String locationId) {
        try {
            return UUID.fromString(locationId);
        } catch (IllegalArgumentException e) {
            throw new WorkorderRequestValidationException("locationId is not a valid UUID: " + locationId);
        }
    }

    /**
     * The mechanics on each roster workorder: its current {@code technician_assignment} first, then
     * any {@code mechanic_ids} entry not already named.
     *
     * <p>Both are live writers. The technician assign API records only {@code technician_assignment}
     * (#1985), while the assignment-context event and the dispatch override still write
     * {@code mechanic_ids}. Reading the column alone left every technician-assigned workorder
     * "Unassigned" on the board and invisible to the mechanic conflict checks. One batched query
     * covers the roster.
     *
     * <p>Keyed by identity rather than id so a row without an id still carries its column value.
     */
    private Map<Workorder, List<String>> assignedMechanics(List<Workorder> workorders) {
        Set<UUID> workorderIds = workorders.stream()
                .map(Workorder::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> currentTechnicianByWorkorder = new HashMap<>();
        if (!workorderIds.isEmpty()) {
            for (TechnicianAssignmentRepository.CurrentTechnician current :
                    technicianAssignmentRepository.findCurrentTechnicians(workorderIds)) {
                if (current.getWorkorderId() != null && current.getTechnicianId() != null) {
                    currentTechnicianByWorkorder.putIfAbsent(
                            current.getWorkorderId(), current.getTechnicianId().toString());
                }
            }
        }

        Map<Workorder, List<String>> mechanicsByWorkorder = new IdentityHashMap<>();
        for (Workorder wo : workorders) {
            Set<String> mechanicIds = new LinkedHashSet<>();
            String currentTechnician = wo.getId() != null ? currentTechnicianByWorkorder.get(wo.getId()) : null;
            if (currentTechnician != null) {
                mechanicIds.add(currentTechnician);
            }
            mechanicIds.addAll(parseMechanicIds(wo.getMechanicIds()));
            mechanicsByWorkorder.put(wo, List.copyOf(mechanicIds));
        }
        return mechanicsByWorkorder;
    }

    /**
     * A display description per vehicle id on the roster, from the {@code ext_vehicle} replica
     * (ADR-0044 §6): unit number, plate and VIN, whichever are known. It is the board's fallback
     * when the structured registry lookup cannot name the vehicle. One batched query.
     */
    private Map<UUID, String> vehicleDescriptions(List<Workorder> workorders) {
        Set<UUID> vehicleIds = workorders.stream()
                .map(Workorder::getVehicleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> descriptions = new HashMap<>();
        if (vehicleIds.isEmpty()) {
            return descriptions;
        }
        for (ExtVehicleReplica vehicle : extVehicleReplicaRepository.findAllById(vehicleIds)) {
            String description = Stream.of(vehicle.getUnitNumber(), vehicle.getLicensePlate(), vehicle.getVin())
                    .filter(part -> part != null && !part.isBlank())
                    .map(String::strip)
                    .collect(Collectors.joining(" · "));
            if (!description.isEmpty()) {
                descriptions.put(vehicle.getVehicleId(), description);
            }
        }
        return descriptions;
    }

    /**
     * The customer's display name per customer id on the roster, from the {@code ext_customer_party}
     * replica (ADR-0044 §6), so the board can say who each job is for. One batched query; a party
     * that is not replicated or has a blank name is left out.
     */
    private Map<UUID, String> customerNames(List<Workorder> workorders) {
        Set<UUID> customerIds = workorders.stream()
                .map(Workorder::getCustomerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> names = new HashMap<>();
        if (customerIds.isEmpty()) {
            return names;
        }
        for (ExtCustomerPartyReplica party : extCustomerPartyReplicaRepository.findAllById(customerIds)) {
            if (party.getDisplayName() != null && !party.getDisplayName().isBlank()) {
                names.put(party.getPartyId(), party.getDisplayName().strip());
            }
        }
        return names;
    }

    /** What a card says about the work on a job: line counts, the lead descriptions and hours logged. */
    private record WorkSynopsis(
            int serviceCount,
            int completedServiceCount,
            List<String> serviceDescriptions,
            BigDecimal actualLaborHours) {
        private static final WorkSynopsis NONE = new WorkSynopsis(0, 0, List.of(), null);
    }

    /**
     * A glance at the work on each roster workorder (#2025): how many service lines are in play, how many
     * are done, the first few descriptions, and the hours logged against them. Cancelled lines and lines
     * the customer declined are not work anyone will do, so they are left out of every figure. Two batched
     * queries cover the roster; hours sum over the workorder's service lines, which is what the detail view
     * totals, and stay null when nothing has been logged.
     */
    private Map<UUID, WorkSynopsis> synopses(List<Workorder> workorders) {
        Set<UUID> workorderIds = workorders.stream()
                .map(Workorder::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, WorkSynopsis> synopses = new HashMap<>();
        if (workorderIds.isEmpty()) {
            return synopses;
        }

        Map<UUID, List<WorkorderServiceRepository.ServiceLineGlance>> linesByWorkorder = new HashMap<>();
        for (WorkorderServiceRepository.ServiceLineGlance line :
                workorderServiceRepository.findGlancesByWorkorderIds(workorderIds)) {
            if (line.getWorkorderId() == null
                    || line.getStatus() == WorkorderItemStatus.CANCELLED
                    || Boolean.TRUE.equals(line.getDeclined())) {
                continue;
            }
            linesByWorkorder
                    .computeIfAbsent(line.getWorkorderId(), id -> new ArrayList<>())
                    .add(line);
        }

        Map<UUID, BigDecimal> hoursByWorkorder = new HashMap<>();
        for (WorkorderLaborEntryRepository.WorkorderLaborHours hours :
                workorderLaborEntryRepository.sumHoursByWorkorderIds(workorderIds)) {
            if (hours.getWorkorderId() != null
                    && hours.getHours() != null
                    && hours.getHours().signum() > 0) {
                hoursByWorkorder.put(hours.getWorkorderId(), hours.getHours());
            }
        }

        for (UUID workorderId : workorderIds) {
            List<WorkorderServiceRepository.ServiceLineGlance> lines =
                    linesByWorkorder.getOrDefault(workorderId, List.of());
            synopses.put(
                    workorderId,
                    new WorkSynopsis(
                            lines.size(),
                            (int) lines.stream()
                                    .filter(line -> line.getStatus() == WorkorderItemStatus.COMPLETED)
                                    .count(),
                            lines.stream()
                                    .map(WorkorderServiceRepository.ServiceLineGlance::getDescription)
                                    .filter(description -> description != null && !description.isBlank())
                                    .map(String::strip)
                                    .limit(MAX_SYNOPSIS_DESCRIPTIONS)
                                    .toList(),
                            hoursByWorkorder.get(workorderId)));
        }
        return synopses;
    }

    private List<WorkorderSummary> buildWorkorderSummaries(
            List<Workorder> workorders,
            Map<Workorder, List<String>> mechanicsByWorkorder,
            Map<UUID, String> vehicleDescriptions,
            Map<UUID, String> customerNames,
            Map<UUID, WorkSynopsis> synopses) {
        return workorders.stream()
                .map(wo -> buildWorkorderSummary(
                        wo,
                        mechanicsByWorkorder,
                        vehicleDescriptions,
                        customerNames,
                        wo.getId() != null ? synopses.getOrDefault(wo.getId(), WorkSynopsis.NONE) : WorkSynopsis.NONE))
                .toList();
    }

    private WorkorderSummary buildWorkorderSummary(
            Workorder wo,
            Map<Workorder, List<String>> mechanicsByWorkorder,
            Map<UUID, String> vehicleDescriptions,
            Map<UUID, String> customerNames,
            WorkSynopsis synopsis) {
        return WorkorderSummary.builder()
                .workorderId(wo.getId())
                // The board links each job by its human number; without it the client
                // could only fall back to printing the UUID.
                .workorderNumber(wo.getWorkorderNumber())
                .customerName(wo.getCustomerId() != null ? customerNames.get(wo.getCustomerId()) : null)
                .status(wo.getStatus() != null ? wo.getStatus().name() : null)
                .scheduledDate(wo.getScheduledDate())
                .vehicleDescription(wo.getVehicleId() != null ? vehicleDescriptions.get(wo.getVehicleId()) : null)
                .assignedMechanicId(mechanicsOf(wo, mechanicsByWorkorder).stream()
                        .findFirst()
                        .orElse(null))
                // #1656: id and type ship together. The retired assignedBayId key put a
                // mobile unit's id under a bay-named field that joined to nothing in bays[].
                .assignedResourceId(
                        wo.getResourceId() != null ? wo.getResourceId().toString() : null)
                .resourceType(wo.getResourceId() != null ? effectiveResourceType(wo) : null)
                // #1569: the overlap-aware sum of the workorder's agreed labor hours —
                // the field stops serialising as an unkept promise. One line query per
                // workorder; dashboard pages are small, and the summation reads
                // snapshots, not the live catalog.
                .estimatedLaborHours(
                        estimatedLaborService.estimateForWorkorder(wo.getId()).estimatedHours())
                .serviceCount(synopsis.serviceCount())
                .completedServiceCount(synopsis.completedServiceCount())
                .serviceDescriptions(synopsis.serviceDescriptions())
                .actualLaborHours(synopsis.actualLaborHours())
                .build();
    }

    private List<MechanicStatus> buildMechanicStatuses(
            List<Workorder> workorders,
            List<PersonAvailability> people,
            Map<Workorder, List<String>> mechanicsByWorkorder) {
        Map<String, String> workorderByMechanicId = new LinkedHashMap<>();
        for (Workorder wo : workorders) {
            for (String mId : mechanicsOf(wo, mechanicsByWorkorder)) {
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

    private List<BayStatus> buildBayStatuses(UUID locationId, List<Workorder> resourceHolders) {
        Map<UUID, String> namesById = new LinkedHashMap<>();
        // Not Collectors.toMap: a replica row with no name yet is legitimate, and toMap rejects a
        // null value outright.
        for (ExtBayReplica bay : extBayReplicaRepository.findActiveByLocationOrdered(locationId)) {
            namesById.putIfAbsent(bay.getBayId(), bay.getName());
        }

        return buildResourcePanel(
                        ResourceType.BAY,
                        resourceHolders,
                        namesById,
                        ids -> namesOf(
                                extBayReplicaRepository.findAllById(ids),
                                ExtBayReplica::getBayId,
                                ExtBayReplica::getName))
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

    private List<MobileUnitStatus> buildMobileUnitStatuses(UUID locationId, List<Workorder> resourceHolders) {
        Map<UUID, String> namesById = new LinkedHashMap<>();
        // Not Collectors.toMap — see buildBayStatuses.
        for (ExtMobileUnitReplica unit : extMobileUnitReplicaRepository.findActiveByBaseLocationOrdered(locationId)) {
            namesById.putIfAbsent(unit.getMobileUnitId(), unit.getName());
        }

        return buildResourcePanel(
                        ResourceType.MOBILE_UNIT,
                        resourceHolders,
                        namesById,
                        ids -> namesOf(
                                extMobileUnitReplicaRepository.findAllById(ids),
                                ExtMobileUnitReplica::getMobileUnitId,
                                ExtMobileUnitReplica::getName))
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
     *       its resource as free while someone is still working on it. The repository query already
     *       encodes the same rule; it is re-applied here so the invariant survives any caller.
     *   <li><b>A resource still holding open work is always rendered</b>, even when it is not in the
     *       active set — it was decommissioned mid-job, or its own replica row has not arrived yet.
     *       Both cases are handled at the one marked spot below.
     * </ul>
     *
     * @param resourceType the panel being built
     * @param resourceHolders open, resource-holding workorders at this location (see
     *     {@code WorkorderRepository#findOpenResourceHoldersAtLocation}); not restricted to the
     *     dashboard date, so a multi-day job started earlier still holds its unit
     * @param activeNamesById active resources at the location, id → name, in display order
     * @param nameLookup resolves the names of resources outside the active set in one batch; ids it
     *     cannot resolve are simply absent from the returned map. It is called at most once per
     *     panel, and not at all when the active set already covers every occupied resource. It
     *     still matters once pos-location's facts (#1668) populate {@code ext_bay} /
     *     {@code ext_mobile_unit}: a resource occupied but not active — out of service, or not yet
     *     reached by the owner's backfill — is absent from the active set, and a per-id lookup
     *     would be a fan-out on every render (#1657)
     * @return the panel rows, active resources first in replica order
     */
    private List<ResourcePanelRow> buildResourcePanel(
            ResourceType resourceType,
            List<Workorder> resourceHolders,
            Map<UUID, String> activeNamesById,
            Function<List<UUID>, Map<UUID, String>> nameLookup) {

        // Only open work occupies a resource; a cancelled or completed-not-reopened workorder that
        // still carries its old assignment is stale and must not hold the resource down.
        Map<UUID, Workorder> occupantByResourceId = new LinkedHashMap<>();
        for (Workorder workorder : resourceHolders) {
            if (workorder.getResourceId() == null || workorder.isLocked()) {
                continue;
            }
            if (effectiveResourceType(workorder) != resourceType) {
                continue;
            }
            occupantByResourceId.merge(workorder.getResourceId(), workorder, DashboardServiceImpl::preferredOccupant);
        }

        Map<UUID, String> panelNamesById = new LinkedHashMap<>(activeNamesById);
        // Open work on a resource the active set does not contain. Two causes, one least-surprising
        // answer: render the row. Either the resource was decommissioned or deleted while a job was
        // still on it (lifecycle semantics pos-location owns, not this module — open follow-up), or
        // its replica row simply has not landed yet (the assignment fact overtook the resource fact).
        // Hiding the row would make live work invisible on the board, which is strictly worse than
        // showing a row whose name is momentarily null.
        List<UUID> unresolved = occupantByResourceId.keySet().stream()
                .filter(resourceId -> !panelNamesById.containsKey(resourceId))
                .toList();
        if (!unresolved.isEmpty()) {
            // One batched lookup, not one per resource. This branch covers every occupied resource
            // whose id the active set does not name — everything, until pos-location's facts
            // (#1668) reach this replica, and afterwards any resource that is occupied but not
            // active. A findById per id here would be exactly the per-unit fan-out the occupancy
            // query was written to remove.
            Map<UUID, String> resolved = nameLookup.apply(unresolved);
            for (UUID resourceId : unresolved) {
                // Not computeIfAbsent: a resource whose name resolves to null still needs a row, and
                // computeIfAbsent drops a null mapping on the floor.
                panelNamesById.put(resourceId, resolved.get(resourceId));
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

    /**
     * Collects a batch of replica rows into an id → name map.
     *
     * <p>{@code Collectors.toMap} is not usable here for the same reason it is not usable in
     * {@link #buildBayStatuses}: a replica row whose name has not arrived yet is legitimate, and
     * {@code toMap} throws on a null value.
     *
     * @param rows the replica rows returned by a batched {@code findAllById}
     * @param idOf the row's identifier accessor
     * @param nameOf the row's display-name accessor
     * @param <T> the replica entity type
     * @return id → name, names possibly null, ids not present in {@code rows} simply absent
     */
    private static <T> Map<UUID, String> namesOf(Iterable<T> rows, Function<T, UUID> idOf, Function<T, String> nameOf) {
        Map<UUID, String> namesById = new LinkedHashMap<>();
        for (T row : rows) {
            namesById.putIfAbsent(idOf.apply(row), nameOf.apply(row));
        }
        return namesById;
    }

    /**
     * Picks which of two open workorders claiming the same resource the panel names as its occupant
     * (#1656).
     *
     * <p>This used to be "whichever row was written to most recently", and that was wrong in a way
     * that showed on the board: {@code updatedAt} is a database fact about when a row was touched,
     * not evidence that a vehicle is in a bay. A job scheduled for today and merely ASSIGNED this
     * morning has a newer {@code updatedAt} than the job that has been physically in that bay since
     * yesterday, so the panel named the job that has not started as the occupant.
     *
     * <p>The order is therefore, in strict precedence:
     *
     * <ol>
     *   <li><b>A started job beats a merely-scheduled one.</b> Among workorders that are still open
     *       — locked ones are filtered out before this point — DRAFT, APPROVED and ASSIGNED mean
     *       "booked but not begun"; every other status means work has started and the resource is
     *       genuinely in use. See {@link #hasStarted(Workorder)}.
     *   <li><b>Then the earlier {@code scheduledDate}</b>, the job that was due on the resource
     *       first. Unscheduled work (a null date) loses to dated work; it is holding the resource
     *       but says nothing about when it claimed it.
     *   <li><b>Then the lower workorder id.</b> Nothing about a resource distinguishes the two at
     *       this point, so the tiebreak's only job is to be total and stable: the same two rows
     *       always produce the same occupant regardless of query order or row-touch times. Ids are
     *       UUIDv7, so in practice this usually reads as "the older workorder".
     * </ol>
     *
     * <p>Two workorders reaching rule 2 or 3 are a genuine double-booking, which
     * {@link #detectResourceDoubleBooking} reports as BLOCKING off this very same set — the panel
     * names one of them deterministically rather than picking arbitrarily and silently.
     *
     * @param incumbent the occupant chosen so far
     * @param challenger the next claim on the same resource
     * @return the workorder the panel reports as occupying the resource
     */
    private static Workorder preferredOccupant(Workorder incumbent, Workorder challenger) {
        if (hasStarted(challenger) != hasStarted(incumbent)) {
            return hasStarted(challenger) ? challenger : incumbent;
        }
        int byScheduledDate = compareNullsLast(incumbent.getScheduledDate(), challenger.getScheduledDate());
        if (byScheduledDate != 0) {
            return byScheduledDate < 0 ? incumbent : challenger;
        }
        return compareNullsLast(incumbent.getId(), challenger.getId()) <= 0 ? incumbent : challenger;
    }

    /**
     * Whether an open workorder has actually started, and so is physically occupying its resource.
     *
     * <p>Expressed as the complement of the not-yet-begun statuses rather than as a list of started
     * ones, so a status added later is treated as "started" — the conservative answer for a panel
     * whose other option is to advertise an occupied bay as free. A null status is read as not
     * started, matching the DRAFT the entity's builder defaults to.
     *
     * @param workorder an open, resource-holding workorder
     * @return true when the job is under way rather than merely booked
     */
    private static boolean hasStarted(Workorder workorder) {
        WorkorderStatus status = workorder.getStatus();
        return status != null
                && status != WorkorderStatus.DRAFT
                && status != WorkorderStatus.APPROVED
                && status != WorkorderStatus.ASSIGNED;
    }

    /** Natural order with nulls sorted last, so an absent value never wins a tiebreak. */
    private static <T extends Comparable<T>> int compareNullsLast(T left, T right) {
        if (left == null) {
            return right == null ? 0 : 1;
        }
        return right == null ? -1 : left.compareTo(right);
    }

    private List<ConflictEntry> detectAllConflicts(
            List<Workorder> workorders,
            List<Workorder> resourceHolders,
            List<PersonAvailability> people,
            LocalDate date,
            Map<Workorder, List<String>> mechanicsByWorkorder) {
        List<ConflictEntry> conflicts = new ArrayList<>();
        detectResourceDoubleBooking(resourceHolders, conflicts);
        detectMechanicDoubleBookingFromWorkorders(workorders, mechanicsByWorkorder, conflicts);
        detectMechanicStatusConflicts(workorders, people, date, mechanicsByWorkorder, conflicts);
        detectLocationMismatch(workorders, people, mechanicsByWorkorder, conflicts);
        detectMechanicSkillMismatch(workorders, people, mechanicsByWorkorder, conflicts);
        return conflicts;
    }

    private static List<String> mechanicsOf(Workorder wo, Map<Workorder, List<String>> mechanicsByWorkorder) {
        return mechanicsByWorkorder.getOrDefault(wo, List.of());
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

    /** The identity a double-booking is judged on: the resource id <em>and</em> what kind it is. */
    private record ResourceKey(UUID resourceId, ResourceType resourceType) {}

    /**
     * Flags a resource that more than one still-open workorder is claiming (#1656).
     *
     * <p>Three things the bay-only predecessor got wrong. It grouped on {@code resourceId} alone, so
     * a double-booked <em>van</em> was reported as {@code BAY_DOUBLE_BOOKED} with a "Bay
     * &lt;uuid&gt;" message naming a bay that does not exist. It counted every workorder carrying
     * the id, including cancelled and completed ones, so a stale assignment produced a BLOCKING
     * conflict against a unit the panels in the very same response reported as AVAILABLE. And it
     * read the <em>day's</em> rows while the panels read the open resource holders, so the two
     * disagreed exactly where it mattered: a bay held since yesterday by a running job and claimed
     * again by a job scheduled for today appears once in the day's rows and twice in the holders,
     * and the genuine double-booking went unreported (#1656).
     *
     * <p>This method is therefore given the same {@code resourceHolders} list the panels are built
     * from, and applies the same {@link Workorder#isLocked()} authority to it. Panels and conflicts
     * agree because they are two readings of one set, not because two filters were written to
     * match.
     *
     * @param resourceHolders open, resource-holding workorders at the location — the panels' own
     *     input, not the dashboard date's rows
     * @param conflicts the accumulating conflict list
     */
    private void detectResourceDoubleBooking(List<Workorder> resourceHolders, List<ConflictEntry> conflicts) {
        Map<ResourceKey, Long> claimCounts = resourceHolders.stream()
                // A hold position is a lot, not a slot: every parked workorder at a site shares the
                // one (HOLD, siteId) key, so counting them as claims on the same resource would
                // report the parking lot as double-booked the moment a second car is parked in it
                // (#1984). Only exclusive positions can be double-booked.
                .filter(wo -> wo.getResourceId() != null
                        && !wo.isLocked()
                        && effectiveResourceType(wo).isExclusive())
                .collect(Collectors.groupingBy(
                        wo -> new ResourceKey(wo.getResourceId(), effectiveResourceType(wo)),
                        LinkedHashMap::new,
                        Collectors.counting()));
        for (Map.Entry<ResourceKey, Long> entry : claimCounts.entrySet()) {
            if (entry.getValue() > 1) {
                ResourceKey key = entry.getKey();
                conflicts.add(ConflictEntry.builder()
                        .conflictType(doubleBookedConflictType(key.resourceType()))
                        .severity(BLOCKING)
                        .message(resourceLabel(key.resourceType()) + key.resourceId()
                                + " is assigned to multiple workorders")
                        .affectedResourceId(key.resourceId().toString())
                        .build());
            }
        }
    }

    /**
     * The conflict type for a double-booked resource of this kind. Bays keep {@code BAY_DOUBLE_BOOKED}
     * — the meaning is unchanged for them — and mobile units get their own type rather than being
     * mislabelled as bays.
     */
    private static String doubleBookedConflictType(ResourceType resourceType) {
        return resourceType == ResourceType.MOBILE_UNIT ? "MOBILE_UNIT_DOUBLE_BOOKED" : "BAY_DOUBLE_BOOKED";
    }

    /** Human label for a resource kind, used to open a conflict message. */
    private static String resourceLabel(ResourceType resourceType) {
        return resourceType == ResourceType.MOBILE_UNIT ? "Mobile unit " : "Bay ";
    }

    private void detectMechanicDoubleBookingFromWorkorders(
            List<Workorder> workorders,
            Map<Workorder, List<String>> mechanicsByWorkorder,
            List<ConflictEntry> conflicts) {
        Map<String, Long> mechanicCounts = workorders.stream()
                .flatMap(wo -> mechanicsOf(wo, mechanicsByWorkorder).stream())
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
            Map<Workorder, List<String>> mechanicsByWorkorder,
            List<ConflictEntry> conflicts) {

        Map<String, PersonAvailability> availabilityByPersonId =
                people.stream().collect(Collectors.toMap(PersonAvailability::getPersonId, pa -> pa, (a, b) -> a));

        List<String> assignedMechanicIds = workorders.stream()
                .flatMap(wo -> mechanicsOf(wo, mechanicsByWorkorder).stream())
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
            Map<Workorder, List<String>> mechanicsByWorkorder,
            List<ConflictEntry> conflicts) {
        Map<String, PeopleAvailabilityResponse.PersonAvailability> availabilityByPersonId = people.stream()
                .collect(Collectors.toMap(
                        PeopleAvailabilityResponse.PersonAvailability::getPersonId, pa -> pa, (a, b) -> a));
        for (Workorder wo : workorders) {
            if (wo.getLocationId() == null) {
                continue;
            }
            String workorderLocationStr = wo.getLocationId().toString();
            for (String mechanicId : mechanicsOf(wo, mechanicsByWorkorder)) {
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
            Map<Workorder, List<String>> mechanicsByWorkorder,
            List<ConflictEntry> conflicts) {
        Map<String, PeopleAvailabilityResponse.PersonAvailability> availabilityByPersonId = people.stream()
                .collect(Collectors.toMap(
                        PeopleAvailabilityResponse.PersonAvailability::getPersonId, pa -> pa, (a, b) -> a));
        for (Workorder wo : workorders) {
            List<String> requiredCerts = parseCertifications(wo.getRequiredCertifications());
            if (requiredCerts.isEmpty()) {
                continue;
            }
            detectMissingCertificationsForWorkorder(
                    mechanicsOf(wo, mechanicsByWorkorder), requiredCerts, availabilityByPersonId, conflicts);
        }
    }

    private void detectMissingCertificationsForWorkorder(
            List<String> mechanicIds,
            List<String> requiredCerts,
            Map<String, PeopleAvailabilityResponse.PersonAvailability> availabilityByPersonId,
            List<ConflictEntry> conflicts) {
        for (String mechanicId : mechanicIds) {
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
