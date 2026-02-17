package com.positivity.people.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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

import com.positivity.people.internal.client.WorkexecJobTimeClient;
import com.positivity.people.internal.client.dto.WorkexecJobTimeTotal;
import com.positivity.people.internal.dto.AttendanceDiscrepancyReportResponse;
import com.positivity.people.internal.dto.AttendanceReportKey;
import com.positivity.people.internal.entity.TimeEntry;
import com.positivity.people.internal.repository.PersonRepository;
import com.positivity.people.internal.repository.TimeEntryRepository;
import com.positivity.people.internal.service.TimekeepingThresholdCache;

@Service
public class PeopleReportsService {

    private final TimeEntryRepository timeEntryRepository;
    private final TimekeepingThresholdCache timekeepingThresholdCache;
    private final WorkexecJobTimeClient workexecJobTimeClient;
    private final PersonRepository personRepository;

    public PeopleReportsService(
            TimeEntryRepository timeEntryRepository,
            TimekeepingThresholdCache timekeepingThresholdCache,
            WorkexecJobTimeClient workexecJobTimeClient,
            PersonRepository personRepository) {
        this.timeEntryRepository = timeEntryRepository;
        this.timekeepingThresholdCache = timekeepingThresholdCache;
        this.workexecJobTimeClient = workexecJobTimeClient;
        this.personRepository = personRepository;
    }

    @NonNull
    public List<AttendanceDiscrepancyReportResponse> getAttendanceDiscrepancyReport(
            @NonNull LocalDate startDate,
            @NonNull LocalDate endDate,
            @NonNull String timezone,
            UUID locationId,
            @NonNull List<UUID> technicianIds,
            boolean flaggedOnly) {
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must be greater than or equal to startDate");
        }

        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(timezone);
        } catch (Exception ex) {
            throw new IllegalArgumentException("timezone must be a valid IANA timezone");
        }

        Instant windowStartInclusive = startDate.atStartOfDay(zoneId).toInstant();
        Instant windowEndExclusive = endDate.plusDays(1).atStartOfDay(zoneId).toInstant();
        boolean includeAllTechnicians = technicianIds.isEmpty();
        List<String> technicianIdsAsString = technicianIds.stream().map(UUID::toString).toList();
        List<String> technicianIdsForQuery = includeAllTechnicians
                ? List.of("__ALL_TECHNICIANS__")
                : technicianIdsAsString;

        List<TimeEntry> attendanceEntries = timeEntryRepository.findAttendanceOverlappingWindow(
                windowStartInclusive,
                windowEndExclusive,
                locationId,
                technicianIdsForQuery,
                includeAllTechnicians);

        Map<AttendanceReportKey, Integer> attendanceMinutesByKey = aggregateAttendanceByDay(
                attendanceEntries,
                zoneId,
                windowStartInclusive,
                windowEndExclusive);

        List<WorkexecJobTimeTotal> jobTotals = workexecJobTimeClient.getJobTimeTotals(
                startDate,
                endDate,
                timezone,
                locationId,
                technicianIds);
        Map<AttendanceReportKey, Integer> jobMinutesByKey = aggregateJobMinutes(jobTotals);

        Set<AttendanceReportKey> allKeys = new HashSet<>();
        allKeys.addAll(attendanceMinutesByKey.keySet());
        allKeys.addAll(jobMinutesByKey.keySet());

        addRequestedTechnicianRowsWithoutData(allKeys, technicianIds, locationId, startDate, endDate);

        Map<String, String> technicianNameCache = new HashMap<>();
        TimekeepingThresholdCache.ThresholdResolverContext thresholdResolver = timekeepingThresholdCache.createContext(
                allKeys,
                zoneId);
        List<AttendanceDiscrepancyReportResponse> rows = new ArrayList<>();

        for (AttendanceReportKey key : allKeys) {
            int attendanceMinutes = attendanceMinutesByKey.getOrDefault(key, 0);
            int totalJobMinutes = jobMinutesByKey.getOrDefault(key, 0);
            boolean skipEmptyNoise = attendanceMinutes == 0 && totalJobMinutes == 0 && technicianIds.isEmpty();
            if (!skipEmptyNoise) {
                int threshold = thresholdResolver.resolveThresholdMinutes(key.locationId(), key.reportDate());
                int discrepancyMinutes = attendanceMinutes - totalJobMinutes;
                boolean isFlagged = Math.abs(discrepancyMinutes) > threshold;
                boolean includeRow = !flaggedOnly || isFlagged;

                if (includeRow) {
                    String technicianName = technicianNameCache.computeIfAbsent(
                            key.technicianId(),
                            this::resolveTechnicianName);

                    rows.add(new AttendanceDiscrepancyReportResponse(
                            key.technicianId(),
                            technicianName,
                            key.locationId(),
                            key.reportDate(),
                            minutesToHours(attendanceMinutes),
                            minutesToHours(totalJobMinutes),
                            minutesToHours(discrepancyMinutes),
                            isFlagged,
                            threshold));
                }
            }
        }

        rows.sort(Comparator
                .comparing(AttendanceDiscrepancyReportResponse::reportDate)
                .thenComparing(AttendanceDiscrepancyReportResponse::technicianName)
                .thenComparing(AttendanceDiscrepancyReportResponse::technicianId));

        return rows;
    }

    private Map<AttendanceReportKey, Integer> aggregateAttendanceByDay(
            List<TimeEntry> attendanceEntries,
            ZoneId zoneId,
            Instant windowStartInclusive,
            Instant windowEndExclusive) {
        Map<AttendanceReportKey, Integer> attendanceMinutesByKey = new HashMap<>();
        Instant now = Instant.now();

        for (TimeEntry entry : attendanceEntries) {
            boolean hasRequiredFields = entry.getPersonId() != null
                    && entry.getLocationId() != null
                    && entry.getAttendanceStartAt() != null;
            if (hasRequiredFields) {
                Instant effectiveStart = maxInstant(entry.getAttendanceStartAt(), windowStartInclusive);
                Instant rawEnd = entry.getAttendanceEndAt() == null ? now : entry.getAttendanceEndAt();
                Instant effectiveEnd = minInstant(rawEnd, windowEndExclusive);

                if (effectiveEnd.isAfter(effectiveStart)) {
                    splitAndAccumulateByLocalDay(
                            attendanceMinutesByKey,
                            entry.getPersonId(),
                            entry.getLocationId().toString(),
                            effectiveStart,
                            effectiveEnd,
                            zoneId);
                }
            }
        }

        return attendanceMinutesByKey;
    }

    private Map<AttendanceReportKey, Integer> aggregateJobMinutes(List<WorkexecJobTimeTotal> jobTotals) {
        Map<AttendanceReportKey, Integer> jobMinutesByKey = new HashMap<>();
        for (WorkexecJobTimeTotal row : jobTotals) {
            if (row.getTechnicianId() == null || row.getLocationId() == null || row.getLocalDate() == null) {
                continue;
            }
            int minutes = row.getTotalJobMinutes() == null ? 0 : row.getTotalJobMinutes();
            AttendanceReportKey key = new AttendanceReportKey(
                    row.getTechnicianId().toString(),
                    row.getLocationId().toString(),
                    row.getLocalDate());
            Integer existingMinutes = jobMinutesByKey.get(key);
            int updatedMinutes = (existingMinutes == null ? 0 : existingMinutes) + minutes;
            jobMinutesByKey.put(key, updatedMinutes);
        }
        return jobMinutesByKey;
    }

    private void addRequestedTechnicianRowsWithoutData(
            Set<AttendanceReportKey> allKeys,
            List<UUID> technicianIds,
            UUID locationId,
            LocalDate startDate,
            LocalDate endDate) {
        if (technicianIds.isEmpty() || locationId == null) {
            return;
        }

        for (UUID technicianId : technicianIds) {
            LocalDate current = startDate;
            while (!current.isAfter(endDate)) {
                allKeys.add(new AttendanceReportKey(technicianId.toString(), locationId.toString(), current));
                current = current.plusDays(1);
            }
        }
    }

    private void splitAndAccumulateByLocalDay(
            Map<AttendanceReportKey, Integer> attendanceMinutesByKey,
            String technicianId,
            String locationId,
            Instant segmentStart,
            Instant segmentEnd,
            ZoneId zoneId) {
        ZonedDateTime cursor = segmentStart.atZone(zoneId);
        ZonedDateTime end = segmentEnd.atZone(zoneId);

        while (cursor.isBefore(end)) {
            ZonedDateTime nextDayBoundary = cursor.toLocalDate().plusDays(1).atStartOfDay(zoneId);
            ZonedDateTime segmentBoundary = nextDayBoundary.isBefore(end) ? nextDayBoundary : end;

            int minutes = (int) Duration.between(cursor, segmentBoundary).toMinutes();
            if (minutes > 0) {
                AttendanceReportKey key = new AttendanceReportKey(technicianId, locationId, cursor.toLocalDate());
                Integer existingMinutes = attendanceMinutesByKey.get(key);
                int updatedMinutes = (existingMinutes == null ? 0 : existingMinutes) + minutes;
                attendanceMinutesByKey.put(key, updatedMinutes);
            }

            cursor = segmentBoundary;
        }
    }

    private String resolveTechnicianName(String technicianId) {
        try {
            UUID personId = UUID.fromString(technicianId);
            return personRepository.findById(personId)
                    .map(person -> {
                        String firstName = person.getFirstName() == null ? "" : person.getFirstName().trim();
                        String lastName = person.getLastName() == null ? "" : person.getLastName().trim();
                        String fullName = (firstName + " " + lastName).trim();
                        return fullName.isBlank() ? technicianId : fullName;
                    })
                    .orElse(technicianId);
        } catch (IllegalArgumentException ex) {
            return technicianId;
        }
    }

    private double minutesToHours(int minutes) {
        return BigDecimal.valueOf(minutes)
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private Instant maxInstant(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private Instant minInstant(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }
}
