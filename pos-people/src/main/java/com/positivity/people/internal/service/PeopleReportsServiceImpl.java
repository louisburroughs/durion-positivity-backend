package com.positivity.people.internal.service;

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
import java.time.Clock;
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
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class PeopleReportsServiceImpl implements PeopleReportsService {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

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
            @NonNull List<UUID> locationIds,
            @NonNull String actorId,
            String correlationId) {
        log.info(
                "Reading approved time export rows actorId={}, correlationId={}, startDate={}, endDate={}, locationCount={}",
                maskValue(actorId),
                maskValue(correlationId),
                maskDate(startDate),
                maskDate(endDate),
                locationIds.size());

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
        Instant windowEndExclusive =
                endDate.plusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant();

        List<TimeEntry> entries = timeEntryRepository.findApprovedForExport(
                TimeEntryStatus.APPROVED, windowStartInclusive, windowEndExclusive, locationIds);

        if (entries.isEmpty()) {
            return List.of();
        }

        Map<UUID, String> locationNamesById = loadLocationNames(locationIds);

        return entries.stream()
                .filter(entry -> entry.getApprovedAt() != null
                        && entry.getApprovedBy() != null
                        && entry.getAttendanceStartAt() != null
                        && entry.getAttendanceEndAt() != null
                        && !entry.getAttendanceEndAt().isBefore(entry.getAttendanceStartAt()))
                .map(entry -> {
                    BigDecimal hoursWorked = BigDecimal.valueOf(
                                    Duration.between(entry.getAttendanceStartAt(), entry.getAttendanceEndAt())
                                            .toMinutes())
                            .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);

                    String employeeId = getPersonIdString(entry);
                    String employeeName = resolveDisplayName(entry.getPerson(), employeeId);
                    String locationName = locationNamesById.getOrDefault(
                            entry.getLocationId(), entry.getLocationId().toString());

                    return new ApprovedTimeExportResponse(
                            entry.getTimeEntryId().toString(),
                            employeeId,
                            employeeName,
                            entry.getLocationId(),
                            locationName,
                            entry.getAttendanceStartAt()
                                    .atZone(ZoneId.of("UTC"))
                                    .toLocalDate(),
                            hoursWorked,
                            entry.getApprovedAt(),
                            entry.getApprovedBy());
                })
                .sorted(Comparator.comparing(ApprovedTimeExportResponse::entryDate)
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
            boolean flaggedOnly,
            @NonNull String actorId,
            String correlationId) {
        log.info(
                "Generating attendance job-time discrepancy report actorId={}, correlationId={}, startDate={}, endDate={}, timezone={}, locationId={}, technicianCount={}, flaggedOnly={}",
                maskValue(actorId),
                maskValue(correlationId),
                maskDate(startDate),
                maskDate(endDate),
                maskValue(timezone),
                maskUuid(locationId),
                technicianIds.size(),
                flaggedOnly);

        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must be on or after startDate");
        }

        ZoneId zoneId = parseZoneId(timezone);
        Instant windowStartInclusive = startDate.atStartOfDay(zoneId).toInstant();
        Instant windowEndExclusive = endDate.plusDays(1).atStartOfDay(zoneId).toInstant();
        boolean includeAllTechnicians = technicianIds.isEmpty();

        List<TimeEntry> attendanceEntries = timeEntryRepository.findAttendanceOverlappingWindow(
                windowStartInclusive, windowEndExclusive, locationId, technicianIds, includeAllTechnicians);

        Map<AttendanceReportKey, Long> attendanceMinutesByKey =
                aggregateAttendanceMinutes(attendanceEntries, windowStartInclusive, windowEndExclusive, zoneId);

        List<WorkexecJobTimeTotal> jobTotals =
                workexecJobTimeClient.getJobTimeTotals(startDate, endDate, timezone, locationId, technicianIds);
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

        rows.sort(Comparator.comparing(AttendanceDiscrepancyReportResponse::getReportDate)
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

    private String maskValue(String value) {
        if (value == null || value.isBlank()) {
            return "n/a";
        }
        if (value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
    }

    private String maskDate(LocalDate date) {
        if (date == null) {
            return "n/a";
        }
        return date.getYear() + "-" + String.format("%02d", date.getMonthValue()) + "-**";
    }

    private String maskUuid(UUID value) {
        if (value == null) {
            return "n/a";
        }
        String raw = value.toString();
        return raw.substring(0, 8) + "-****-****-****-************";
    }

    private Map<AttendanceReportKey, Long> aggregateAttendanceMinutes(
            List<TimeEntry> entries, Instant windowStartInclusive, Instant windowEndExclusive, ZoneId zoneId) {
        Map<AttendanceReportKey, Long> minutesByKey = new HashMap<>();
        Instant now = Instant.now(clock);

        for (TimeEntry entry : entries) {
            accumulateAttendanceForEntry(entry, windowStartInclusive, windowEndExclusive, zoneId, now, minutesByKey);
        }

        return minutesByKey;
    }

    private void accumulateAttendanceForEntry(
            TimeEntry entry,
            Instant windowStartInclusive,
            Instant windowEndExclusive,
            ZoneId zoneId,
            Instant now,
            Map<AttendanceReportKey, Long> minutesByKey) {
        if (!hasAttendanceDimensions(entry)) {
            return;
        }

        Instant effectiveStart = maxInstant(entry.getAttendanceStartAt(), windowStartInclusive);
        Instant effectiveEnd = minInstant(resolveAttendanceEnd(entry, now), windowEndExclusive);
        if (!effectiveEnd.isAfter(effectiveStart)) {
            return;
        }

        accumulateDailyAttendanceSegments(entry, effectiveStart, effectiveEnd, zoneId, minutesByKey);
    }

    private boolean hasAttendanceDimensions(TimeEntry entry) {
        return entry.getAttendanceStartAt() != null
                && entry.getPerson() != null
                && entry.getPerson().getId() != null
                && entry.getLocationId() != null;
    }

    private Instant resolveAttendanceEnd(TimeEntry entry, Instant now) {
        return entry.getAttendanceEndAt() == null ? now : entry.getAttendanceEndAt();
    }

    private Instant maxInstant(Instant left, Instant right) {
        return left.isBefore(right) ? right : left;
    }

    private Instant minInstant(Instant left, Instant right) {
        return left.isAfter(right) ? right : left;
    }

    private void accumulateDailyAttendanceSegments(
            TimeEntry entry,
            Instant startInclusive,
            Instant endExclusive,
            ZoneId zoneId,
            Map<AttendanceReportKey, Long> minutesByKey) {
        Instant cursor = startInclusive;
        while (cursor.isBefore(endExclusive)) {
            LocalDate localDate = cursor.atZone(zoneId).toLocalDate();
            Instant dayBoundary = localDate.plusDays(1).atStartOfDay(zoneId).toInstant();
            Instant segmentEnd = minInstant(dayBoundary, endExclusive);
            addAttendanceSegmentMinutes(minutesByKey, entry, localDate, cursor, segmentEnd);
            cursor = segmentEnd;
        }
    }

    private void addAttendanceSegmentMinutes(
            Map<AttendanceReportKey, Long> minutesByKey,
            TimeEntry entry,
            LocalDate localDate,
            Instant segmentStartInclusive,
            Instant segmentEndExclusive) {
        long segmentMinutes =
                Duration.between(segmentStartInclusive, segmentEndExclusive).toMinutes();
        if (segmentMinutes <= 0) {
            return;
        }

        AttendanceReportKey key = new AttendanceReportKey(
                getPersonIdString(entry), entry.getLocationId().toString(), localDate);
        long currentMinutes = minutesByKey.getOrDefault(key, 0L);
        minutesByKey.put(key, currentMinutes + segmentMinutes);
    }

    private String getPersonIdString(TimeEntry entry) {
        return entry.getPerson() == null || entry.getPerson().getId() == null
                ? ""
                : entry.getPerson().getId().toString();
    }

    private String resolveDisplayName(Person person, String fallbackId) {
        if (person == null) {
            return fallbackId;
        }
        String displayName = ((person.getFirstName() == null ? "" : person.getFirstName()) + " "
                        + (person.getLastName() == null ? "" : person.getLastName()))
                .trim();
        if (displayName.isBlank()
                && person.getLegalName() != null
                && !person.getLegalName().isBlank()) {
            displayName = person.getLegalName();
        }
        if (displayName.isBlank()
                && person.getPreferredName() != null
                && !person.getPreferredName().isBlank()) {
            displayName = person.getPreferredName();
        }
        return displayName.isBlank() ? fallbackId : displayName;
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
                    row.getTechnicianId().toString(), row.getLocationId().toString(), row.getLocalDate());
            long currentMinutes = minutesByKey.getOrDefault(key, 0L);
            minutesByKey.put(key, currentMinutes + row.getTotalJobMinutes().longValue());
        }
        return minutesByKey;
    }

    private Map<String, String> loadTechnicianNames(Set<AttendanceReportKey> keys) {
        Set<UUID> technicianIds = new HashSet<>();
        int skippedTechnicianIds = 0;
        for (AttendanceReportKey key : keys) {
            UUID technicianId = parseUuidOrNull(key.getTechnicianId());
            if (technicianId == null) {
                skippedTechnicianIds++;
                continue;
            }
            technicianIds.add(technicianId);
        }

        if (skippedTechnicianIds > 0 && log.isWarnEnabled()) {
            log.warn(
                    "Skipping {} non-UUID technician identifier(s) while loading technician names",
                    skippedTechnicianIds);
        }

        if (technicianIds.isEmpty()) {
            return Map.of();
        }

        Map<String, String> namesById = new HashMap<>();
        for (Person person : personRepository.findAllById(technicianIds)) {
            String fullName = ((person.getFirstName() == null ? "" : person.getFirstName()) + " "
                            + (person.getLastName() == null ? "" : person.getLastName()))
                    .trim();
            namesById.put(
                    person.getId().toString(),
                    fullName.isBlank() ? person.getId().toString() : fullName);
        }
        return namesById;
    }

    private UUID parseUuidOrNull(String value) {
        if (value == null || !UUID_PATTERN.matcher(value).matches()) {
            return null;
        }
        return UUID.fromString(value);
    }

    private Map<UUID, String> loadLocationNames(List<UUID> locationIds) {
        Map<UUID, String> namesById = new HashMap<>();
        for (UUID locationId : locationIds) {
            namesById.put(locationId, locationReferenceClient.getLocationName(locationId));
        }
        return namesById;
    }
}
