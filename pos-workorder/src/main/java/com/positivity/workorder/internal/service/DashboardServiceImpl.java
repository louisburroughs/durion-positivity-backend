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
import com.positivity.workorder.internal.enums.WorkorderStatus;
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
        //
        // Occupancy comes from its own query, not from the day's rows (#1656 review finding 3).
        // Once a panel positively asserts AVAILABLE it is making a claim, and the day's rows cannot
        // support that claim: a work-in-progress job scheduled two days ago is still in its bay
        // today and would never appear in findByScheduledDateAndLocationId for today. One extra
        // query per render covers both panels — never one per unit. The panels themselves cost one
        // roster query each, plus at most one batched findAllById each for resources the roster does
        // not list; nothing in this method scales with the number of bays or units.
        List<Workorder> resourceHolders = workorderRepository.findOpenResourceHoldersAtLocation(locationUuid, date);

        List<WorkorderSummary> workorderSummaries = buildWorkorderSummaries(workorders);
        List<MechanicStatus> mechanicStatuses = buildMechanicStatuses(workorders, people);
        List<BayStatus> bayStatuses = buildBayStatuses(locationUuid, resourceHolders);
        List<MobileUnitStatus> mobileUnitStatuses = buildMobileUnitStatuses(locationUuid, resourceHolders);

        // Conflicts are computed off the same resourceHolders set the panels use (#1656):
        // resource double-booking is an occupancy question, and answering it from the
        // day's rows while the panels answered it from the holders let the two disagree in the one
        // case that matters — a bay claimed by a job that started yesterday and a job scheduled for
        // today. Mechanic, status, location and skill conflicts stay on the day's rows: those are
        // questions about today's schedule, not about who is physically in the bay.
        List<ConflictEntry> conflicts = detectAllConflicts(workorders, resourceHolders, people, date);

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
                        // #1656: id and type ship together. The retired assignedBayId key put a
                        // mobile unit's id under a bay-named field that joined to nothing in bays[].
                        .assignedResourceId(
                                wo.getResourceId() != null ? wo.getResourceId().toString() : null)
                        .resourceType(wo.getResourceId() != null ? effectiveResourceType(wo) : null)
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
     *     panel, and not at all when the active set already covers every occupied resource —
     *     {@code ext_bay} and {@code ext_mobile_unit} are empty until pos-location publishes those
     *     facts (#1668), so today every occupied resource takes this branch and a per-id lookup
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
            // One batched lookup, not one per resource. The active set is empty in production until
            // pos-location starts publishing bay and mobile-unit facts (#1668), so this branch is
            // taken by *every* occupied resource today — a findById per id here would be exactly the
            // per-unit fan-out the occupancy query was written to remove.
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
            LocalDate date) {
        List<ConflictEntry> conflicts = new ArrayList<>();
        detectResourceDoubleBooking(resourceHolders, conflicts);
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
                .filter(wo -> wo.getResourceId() != null && !wo.isLocked())
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
