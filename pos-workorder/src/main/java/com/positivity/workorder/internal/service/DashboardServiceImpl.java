package com.positivity.workorder.internal.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.workorder.internal.client.PeopleAvailabilityClient;
import com.positivity.workorder.internal.client.ShopmgrOperationalContextClient;
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
import com.positivity.workorder.service.DashboardService;
import java.nio.charset.StandardCharsets;
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
 * Implementation of {@link DashboardService} for the Daily Dispatch Board Dashboard.
 * Aggregates workorder, mechanic, and bay data for conflict detection and display.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final WorkorderRepository workorderRepository;
    private final PeopleAvailabilityClient peopleAvailabilityClient;
    @SuppressWarnings("unused")
    private final ShopmgrOperationalContextClient shopmgrOperationalContextClient;
    private final ObjectMapper objectMapper;

    @Override
    public DashboardResponse getDashboard(@NonNull String locationId, @NonNull LocalDate date) {
        UUID locationUuid;
        try {
            locationUuid = UUID.fromString(locationId);
        } catch (IllegalArgumentException e) {
            locationUuid = UUID.nameUUIDFromBytes(locationId.getBytes(StandardCharsets.UTF_8));
        }

        List<Workorder> workorders = workorderRepository.findByScheduledDateAndLocationId(date, locationUuid);

        PeopleAvailabilityResponse availability = peopleAvailabilityClient.fetchAvailability(locationId, date);
        List<PersonAvailability> people = availability != null && availability.getPeople() != null
                ? availability.getPeople() : List.of();

        List<WorkorderSummary> workorderSummaries = workorders.stream()
                .map(wo -> WorkorderSummary.builder()
                        .workorderId(wo.getId())
                        .status(wo.getStatus() != null ? wo.getStatus().name() : null)
                        .scheduledDate(wo.getScheduledDate())
                        .assignedMechanicId(parseFirstMechanic(wo.getMechanicIds()))
                        .assignedBayId(wo.getResourceId() != null ? wo.getResourceId().toString() : null)
                        .build())
                .collect(Collectors.toList());

        List<MechanicStatus> mechanicStatuses = people.stream()
                .map(pa -> MechanicStatus.builder()
                        .personId(pa.getPersonId())
                        .firstName(pa.getFirstName())
                        .lastName(pa.getLastName())
                        .currentStatus(pa.getCurrentStatus())
                        .onBreak(pa.getBreakInfo() != null && pa.getBreakInfo().isOnBreak())
                        .breakExpectedReturn(pa.getBreakInfo() != null ? pa.getBreakInfo().getExpectedReturn() : null)
                        .ptoEntries(pa.getPto() != null
                                ? pa.getPto().stream()
                                        .map(p -> PtoEntry.builder()
                                                .ptoId(p.getPtoId())
                                                .start(p.getStart())
                                                .end(p.getEnd())
                                                .ptoType(p.getPtoType())
                                                .build())
                                        .collect(Collectors.toList())
                                : List.of())
                        .build())
                .collect(Collectors.toList());

        Map<UUID, BayStatus> bayMap = new LinkedHashMap<>();
        for (Workorder workorder : workorders) {
            if (workorder.getResourceId() != null && !bayMap.containsKey(workorder.getResourceId())) {
                bayMap.put(workorder.getResourceId(), BayStatus.builder()
                        .bayId(workorder.getResourceId().toString())
                        .available(false)
                        .assignedWorkorderId(workorder.getId() != null ? workorder.getId().toString() : null)
                        .build());
            }
        }
        List<BayStatus> bayStatuses = new ArrayList<>(bayMap.values());

        List<ConflictEntry> conflicts = new ArrayList<>();
        detectBayDoubleBooking(workorders, conflicts);
        detectMechanicDoubleBookingFromWorkorders(workorders, conflicts);
        detectMechanicStatusConflicts(workorders, people, date, conflicts);

        return DashboardResponse.builder()
                .date(date)
                .locationId(locationId)
                .workorders(workorderSummaries)
                .mechanics(mechanicStatuses)
                .bays(bayStatuses)
                .conflicts(conflicts)
                .lastRefreshed(Instant.now())
                .build();
    }

    private List<String> parseMechanicIds(String mechanicIds) {
        if (mechanicIds == null || mechanicIds.isBlank()) {
            return Collections.emptyList();
        }
        try {
            ObjectMapper mapper = objectMapper != null ? objectMapper : new ObjectMapper();
            return mapper.readValue(mechanicIds, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("Failed to parse mechanicIds JSON: {}", mechanicIds, e);
            return Collections.emptyList();
        }
    }

    private String parseFirstMechanic(String mechanicIds) {
        List<String> ids = parseMechanicIds(mechanicIds);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void detectBayDoubleBooking(List<Workorder> workorders, List<ConflictEntry> conflicts) {
        Map<UUID, List<Workorder>> byBay = workorders.stream()
                .filter(wo -> wo.getResourceId() != null)
                .collect(Collectors.groupingBy(Workorder::getResourceId));
        for (Map.Entry<UUID, List<Workorder>> entry : byBay.entrySet()) {
            if (entry.getValue().size() > 1) {
                conflicts.add(ConflictEntry.builder()
                        .conflictType("BAY_DOUBLE_BOOKED")
                        .severity("BLOCKING")
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
                        .severity("BLOCKING")
                        .message("Mechanic " + entry.getKey() + " is assigned to multiple workorders")
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

        Map<String, PersonAvailability> availabilityByPersonId = people.stream()
                .collect(Collectors.toMap(PersonAvailability::getPersonId, pa -> pa, (a, b) -> a));

        List<String> assignedMechanicIds = workorders.stream()
                .flatMap(wo -> parseMechanicIds(wo.getMechanicIds()).stream())
                .distinct()
                .collect(Collectors.toList());

        Instant dayStart = date.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant dayEnd = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant fifteenMinFromNow = Instant.now().plusSeconds(900);

        for (String mechanicId : assignedMechanicIds) {
            PersonAvailability personAvailability = availabilityByPersonId.get(mechanicId);
            if (personAvailability == null) {
                continue;
            }

            if ("ON_JOB".equals(personAvailability.getCurrentStatus())) {
                conflicts.add(ConflictEntry.builder()
                        .conflictType("DOUBLE_BOOKED_MECHANIC")
                        .severity("BLOCKING")
                        .message("Mechanic " + mechanicId + " is currently on a job and unavailable")
                        .affectedResourceId(personAvailability.getPersonId())
                        .build());
            }

            if (personAvailability.getPto() != null) {
                for (PtoBlock pto : personAvailability.getPto()) {
                    if (pto.getStart() != null && pto.getEnd() != null
                            && pto.getStart().isBefore(dayEnd)
                            && pto.getEnd().isAfter(dayStart)) {
                        conflicts.add(ConflictEntry.builder()
                                .conflictType("MECHANIC_PTO_OVERLAP")
                                .severity("BLOCKING")
                                .message("Mechanic " + mechanicId + " has PTO on this date")
                                .affectedResourceId(mechanicId)
                                .build());
                        break;
                    }
                }
            }

            BreakInfo breakInfo = personAvailability.getBreakInfo();
            if (breakInfo != null && breakInfo.isOnBreak()
                    && breakInfo.getExpectedReturn() != null
                    && breakInfo.getExpectedReturn().isBefore(fifteenMinFromNow)) {
                conflicts.add(ConflictEntry.builder()
                        .conflictType("MECHANIC_BREAK_OVERLAP")
                        .severity("WARNING")
                        .message("Job overlaps with expected break time for mechanic " + mechanicId)
                        .affectedResourceId(mechanicId)
                        .build());
            }
        }
    }
}
