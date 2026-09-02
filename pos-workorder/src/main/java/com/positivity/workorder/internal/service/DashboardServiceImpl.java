package com.positivity.workorder.internal.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.workorder.internal.dto.BayStatus;
import com.positivity.workorder.internal.dto.ConflictEntry;
import com.positivity.workorder.internal.dto.DashboardResponse;
import com.positivity.workorder.internal.dto.MechanicStatus;
import com.positivity.workorder.internal.dto.PeopleAvailabilityResponse;
import com.positivity.workorder.internal.dto.PeopleAvailabilityResponse.BreakInfo;
import com.positivity.workorder.internal.dto.PeopleAvailabilityResponse.PersonAvailability;
import com.positivity.workorder.internal.dto.PeopleAvailabilityResponse.PtoBlock;
import com.positivity.workorder.internal.dto.PtoEntry;
import com.positivity.workorder.internal.dto.WorkorderSummary;
import com.positivity.workorder.internal.entity.Workorder;
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
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

/**
 * Implementation of {@link DashboardService} for the Daily Dispatch Board
 * Dashboard.
 * Aggregates workorder, mechanic, and bay data for conflict detection and
 * display.
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

        // Bay panel is derived from the workorders' own assignments (#898): the shopmgr
        // bay-status client was retired — its endpoint no longer existed and always yielded an
        // empty list, so bay open/closed enrichment never reached this dashboard.
        List<WorkorderSummary> workorderSummaries = buildWorkorderSummaries(workorders);
        List<MechanicStatus> mechanicStatuses = buildMechanicStatuses(workorders, people);
        List<BayStatus> bayStatuses = buildBayStatuses(workorders);

        List<ConflictEntry> conflicts = detectAllConflicts(workorders, people, date);

        return DashboardResponse.builder()
                .date(date)
                .locationId(locationId)
                .workorders(workorderSummaries)
                .mechanics(mechanicStatuses)
                .bays(bayStatuses)
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

    private List<BayStatus> buildBayStatuses(List<Workorder> workorders) {
        Map<UUID, BayStatus> bayMap = new LinkedHashMap<>();
        for (Workorder workorder : workorders) {
            if (workorder.getResourceId() != null) {
                UUID bayUuid = workorder.getResourceId();
                String assignedId =
                        workorder.getId() != null ? workorder.getId().toString() : null;
                bayMap.put(
                        bayUuid,
                        BayStatus.builder()
                                .bayId(bayUuid.toString())
                                .status("OCCUPIED")
                                .available(false)
                                .assignedWorkorderId(assignedId)
                                .build());
            }
        }
        return new ArrayList<>(bayMap.values());
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
