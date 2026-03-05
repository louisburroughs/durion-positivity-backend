package com.positivity.people.internal.service;

import java.time.Clock;

import com.positivity.people.internal.client.LocationReferenceClient;
import com.positivity.people.internal.client.WorkexecJobTimeClient;
import com.positivity.people.internal.client.dto.WorkexecJobTimeTotal;
import com.positivity.people.internal.dto.ApprovedTimeExportResponse;
import com.positivity.people.internal.dto.AttendanceDiscrepancyReportResponse;
import com.positivity.people.internal.dto.AttendanceReportKey;
import com.positivity.people.internal.entity.Person;
import com.positivity.people.internal.entity.TimeEntry;
import com.positivity.people.internal.enums.TimeEntryStatus;
import com.positivity.people.internal.repository.PersonRepository;
import com.positivity.people.internal.repository.TimeEntryRepository;
import com.positivity.people.service.PeopleReportsService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

@Service
public class PeopleReportsServiceImpl implements PeopleReportsService {
    private final Clock clock;


    private final TimeEntryRepository timeEntryRepository;
    private final PersonRepository personRepository;
    private final WorkexecJobTimeClient workexecJobTimeClient;
    private final LocationReferenceClient locationReferenceClient;
    private final TimekeepingThresholdCache timekeepingThresholdCache;

    public PeopleReportsServiceImpl(
            TimeEntryRepository timeEntryRepository,
            PersonRepository personRepository,
            WorkexecJobTimeClient workexecJobTimeClient,
            LocationReferenceClient locationReferenceClient,
            TimekeepingThresholdCache timekeepingThresholdCache,
            Clock clock) {
        this.clock = clock;
        this.timeEntryRepository = timeEntryRepository;
        this.personRepository = personRepository;
        this.workexecJobTimeClient = workexecJobTimeClient;
        this.locationReferenceClient = locationReferenceClient;
        this.timekeepingThresholdCache = timekeepingThresholdCache;
    }

    @Override
    @NonNull
    public List<ApprovedTimeExportResponse> getApprovedTimeForExport(
            @NonNull LocalDate startDate,
            @NonNull LocalDate endDate,
            @NonNull List<UUID> locationIds) {
        // Issue #79: enforce stable approved-only export read contract.
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must be on or after startDate");
        }
        if (locationIds.isEmpty()) {
            throw new IllegalArgumentException("At least one locationId is required");
        }

        for (UUID locationId : locationIds) {
            if (!locationReferenceClient.isLocationActive(locationId)) {
                throw new IllegalArgumentException("Unknown locationId: " + locationId);
            }
        }

        Instant windowStartInclusive = startDate.atStartOfDay(ZoneId.of("UTC")).toInstant();
        Instant windowEndExclusive = endDate.plusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant();

        List<TimeEntry> entries = timeEntryRepository.findApprovedForExport(
                TimeEntryStatus.APPROVED,
                windowStartInclusive,
                windowEndExclusive,
                locationIds);

        if (entries.isEmpty()) {
            return List.of();
        }

        Map<String, String> employeeNamesById = loadEmployeeNames(entries);
        Map<UUID, String> locationNamesById = loadLocationNames(locationIds);

        return entries.stream()
                .filter(entry -> entry.getApprovedAt() != null
                        && entry.getApprovedBy() != null
                        && entry.getAttendanceStartAt() != null
                        && entry.getAttendanceEndAt() != null
                        && !entry.getAttendanceEndAt().isBefore(entry.getAttendanceStartAt()))
                .map(entry -> {
                    BigDecimal hoursWorked = BigDecimal.valueOf(Duration
                            .between(entry.getAttendanceStartAt(), entry.getAttendanceEndAt())
                            .toMinutes())
                            .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);

                    String employeeId = entry.getPersonId() == null ? "" : entry.getPersonId();
                    String employeeName = employeeNamesById.getOrDefault(employeeId, employeeId);
                    String locationName = locationNamesById.getOrDefault(entry.getLocationId(),
                            entry.getLocationId().toString());

                    return new ApprovedTimeExportResponse(
                            entry.getTimeEntryId().toString(),
                            employeeId,
                            employeeName,
                            entry.getLocationId(),
                            locationName,
                            entry.getAttendanceStartAt().atZone(ZoneId.of("UTC")).toLocalDate(),
                            hoursWorked,
                            entry.getApprovedAt(),
                            entry.getApprovedBy());
                })
                .sorted(Comparator
                        .comparing(ApprovedTimeExportResponse::entryDate)
                        .thenComparing(ApprovedTimeExportResponse::timeEntryId))
                .toList();
    }

    @Override
    @NonNull
    public List<AttendanceDiscrepancyReportResponse> getAttendanceDiscrepancyReport(
            @NonNull LocalDate startDate,
            @NonNull LocalDate endDate,
            @NonNull String timezone,
            UUID locationId,
            @NonNull List<UUID> technicianIds,
            boolean flaggedOnly) {
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must be on or after startDate");
        }

        ZoneId zoneId = parseZoneId(timezone);
        Instant windowStartInclusive = startDate.atStartOfDay(zoneId).toInstant();
        Instant windowEndExclusive = endDate.plusDays(1).atStartOfDay(zoneId).toInstant();
        boolean includeAllTechnicians = technicianIds.isEmpty();
        List<String> technicianIdStrings = technicianIds.stream().map(UUID::toString).toList();

        List<TimeEntry> attendanceEntries = timeEntryRepository.findAttendanceOverlappingWindow(
                windowStartInclusive,
                windowEndExclusive,
                locationId,
                technicianIdStrings,
                includeAllTechnicians);

        Map<AttendanceReportKey, Long> attendanceMinutesByKey = aggregateAttendanceMinutes(
                attendanceEntries,
                windowStartInclusive,
                windowEndExclusive,
                zoneId);

        List<WorkexecJobTimeTotal> jobTotals = workexecJobTimeClient.getJobTimeTotals(
                startDate,
                endDate,
                timezone,
                locationId,
                technicianIds);
        Map<AttendanceReportKey, Long> jobMinutesByKey = aggregateJobMinutes(jobTotals);

        Set<AttendanceReportKey> allKeys = new HashSet<>();
        allKeys.addAll(attendanceMinutesByKey.keySet());
        allKeys.addAll(jobMinutesByKey.keySet());

        if (allKeys.isEmpty()) {
            return List.of();
        }

        var thresholdContext = timekeepingThresholdCache.createContext(allKeys, zoneId);
        Map<String, String> technicianNamesById = loadTechnicianNames(allKeys);

        List<AttendanceDiscrepancyReportResponse> rows = new ArrayList<>();
        for (AttendanceReportKey key : allKeys) {
            long attendanceMinutes = attendanceMinutesByKey.getOrDefault(key, 0L);
            long jobMinutes = jobMinutesByKey.getOrDefault(key, 0L);
            long discrepancyMinutes = attendanceMinutes - jobMinutes;
            int thresholdMinutes = thresholdContext.resolveThresholdMinutes(key.getLocationId(), key.getReportDate());
            boolean flagged = Math.abs(discrepancyMinutes) > thresholdMinutes;

            if (flaggedOnly && !flagged) {
                continue;
            }

            rows.add(new AttendanceDiscrepancyReportResponse(
                    key.getTechnicianId(),
                    technicianNamesById.getOrDefault(key.getTechnicianId(), key.getTechnicianId()),
                    key.getLocationId(),
                    key.getReportDate(),
                    attendanceMinutes / 60.0d,
                    jobMinutes / 60.0d,
                    discrepancyMinutes / 60.0d,
                    flagged,
                    thresholdMinutes));
        }

        rows.sort(Comparator
                .comparing(AttendanceDiscrepancyReportResponse::getReportDate)
                .thenComparing(AttendanceDiscrepancyReportResponse::getTechnicianId)
                .thenComparing(AttendanceDiscrepancyReportResponse::getLocationId));
        return rows;
    }

    private ZoneId parseZoneId(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (ZoneRulesException ex) {
            throw new IllegalArgumentException("timezone must be a valid IANA timezone");
        }
    }

    private Map<AttendanceReportKey, Long> aggregateAttendanceMinutes(
            List<TimeEntry> entries,
            Instant windowStartInclusive,
            Instant windowEndExclusive,
            ZoneId zoneId) {
        Map<AttendanceReportKey, Long> minutesByKey = new HashMap<>();
        Instant now = Instant.now(clock);

        for (TimeEntry entry : entries) {
            Instant rawStart = entry.getAttendanceStartAt();
            if (rawStart == null || entry.getPersonId() == null || entry.getLocationId() == null) {
                continue;
            }

            Instant rawEnd = entry.getAttendanceEndAt() == null ? now : entry.getAttendanceEndAt();
            Instant effectiveStart = rawStart.isBefore(windowStartInclusive) ? windowStartInclusive : rawStart;
            Instant effectiveEnd = rawEnd.isAfter(windowEndExclusive) ? windowEndExclusive : rawEnd;
            if (!effectiveEnd.isAfter(effectiveStart)) {
                continue;
            }

            Instant cursor = effectiveStart;
            while (cursor.isBefore(effectiveEnd)) {
                LocalDate localDate = cursor.atZone(zoneId).toLocalDate();
                Instant dayBoundary = localDate.plusDays(1).atStartOfDay(zoneId).toInstant();
                Instant segmentEnd = dayBoundary.isBefore(effectiveEnd) ? dayBoundary : effectiveEnd;
                long segmentMinutes = Duration.between(cursor, segmentEnd).toMinutes();
                if (segmentMinutes > 0) {
                    AttendanceReportKey key = new AttendanceReportKey(
                            entry.getPersonId(),
                            entry.getLocationId().toString(),
                            localDate);
                    long currentMinutes = minutesByKey.getOrDefault(key, 0L);
                    minutesByKey.put(key, currentMinutes + segmentMinutes);
                }
                cursor = segmentEnd;
            }
        }

        return minutesByKey;
    }

    private Map<AttendanceReportKey, Long> aggregateJobMinutes(List<WorkexecJobTimeTotal> rows) {
        Map<AttendanceReportKey, Long> minutesByKey = new HashMap<>();
        for (WorkexecJobTimeTotal row : rows) {
            if (row.getTechnicianId() == null
                    || row.getLocationId() == null
                    || row.getLocalDate() == null
                    || row.getTotalJobMinutes() == null) {
                continue;
            }
            AttendanceReportKey key = new AttendanceReportKey(
                    row.getTechnicianId().toString(),
                    row.getLocationId().toString(),
                    row.getLocalDate());
            long currentMinutes = minutesByKey.getOrDefault(key, 0L);
            minutesByKey.put(key, currentMinutes + row.getTotalJobMinutes().longValue());
        }
        return minutesByKey;
    }

    private Map<String, String> loadTechnicianNames(Set<AttendanceReportKey> keys) {
        Set<UUID> technicianIds = new HashSet<>();
        for (AttendanceReportKey key : keys) {
            try {
                technicianIds.add(UUID.fromString(key.getTechnicianId()));
            } catch (IllegalArgumentException ignored) {
                // Skip non-UUID identifiers, fallback to technicianId in the response.
            }
        }

        if (technicianIds.isEmpty()) {
            return Map.of();
        }

        Map<String, String> namesById = new HashMap<>();
        for (Person person : personRepository.findAllById(technicianIds)) {
            String fullName = ((person.getFirstName() == null ? "" : person.getFirstName()) + " "
                    + (person.getLastName() == null ? "" : person.getLastName())).trim();
            namesById.put(person.getId().toString(), fullName.isBlank() ? person.getId().toString() : fullName);
        }
        return namesById;
    }

    private Map<String, String> loadEmployeeNames(List<TimeEntry> entries) {
        Set<UUID> employeeIds = new HashSet<>();
        for (TimeEntry entry : entries) {
            if (entry.getPersonId() == null || entry.getPersonId().isBlank()) {
                continue;
            }
            try {
                employeeIds.add(UUID.fromString(entry.getPersonId()));
            } catch (IllegalArgumentException ignored) {
                // keep source identifier fallback for non-UUID person IDs
            }
        }

        if (employeeIds.isEmpty()) {
            return Map.of();
        }

        Map<String, String> namesById = new HashMap<>();
        for (Person person : personRepository.findAllById(employeeIds)) {
            String displayName = ((person.getFirstName() == null ? "" : person.getFirstName()) + " "
                    + (person.getLastName() == null ? "" : person.getLastName())).trim();
            if (displayName.isBlank() && person.getLegalName() != null && !person.getLegalName().isBlank()) {
                displayName = person.getLegalName();
            }
            if (displayName.isBlank() && person.getPreferredName() != null && !person.getPreferredName().isBlank()) {
                displayName = person.getPreferredName();
            }
            namesById.put(person.getId().toString(), displayName.isBlank() ? person.getId().toString() : displayName);
        }
        return namesById;
    }

    private Map<UUID, String> loadLocationNames(List<UUID> locationIds) {
        Map<UUID, String> namesById = new HashMap<>();
        for (UUID locationId : locationIds) {
            namesById.put(locationId, locationReferenceClient.getLocationName(locationId));
        }
        return namesById;
    }
}
