package com.positivity.people.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.service.LocationReferenceService;
import com.positivity.people.internal.dto.ApprovedTimeExportResponse;
import com.positivity.people.internal.entity.ExtPersonReplica;
import com.positivity.people.internal.entity.TimeEntry;
import com.positivity.people.internal.enums.TimeEntryStatus;
import com.positivity.people.internal.repository.ExtJobTimeReplicaRepository;
import com.positivity.people.internal.repository.ExtPersonReplicaRepository;
import com.positivity.people.internal.repository.TimeEntryRepository;
import com.positivity.people.internal.service.PeopleReportsServiceImpl;
import com.positivity.people.internal.service.TimekeepingThresholdCache;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for approved-time export read behavior.
 *
 * Issue: #79
 */
class PeopleReportsServiceTest {

    private TimeEntryRepository timeEntryRepository;

    private ExtPersonReplicaRepository extPersonReplicaRepository;

    private ExtJobTimeReplicaRepository extJobTimeReplicaRepository;

    private TimekeepingThresholdCache timekeepingThresholdCache;

    private LocationReferenceService locationReferenceService;

    private PeopleReportsService service;

    @BeforeEach
    void setup() {
        timeEntryRepository = mock(TimeEntryRepository.class);
        extPersonReplicaRepository = mock(ExtPersonReplicaRepository.class);
        extJobTimeReplicaRepository = mock(ExtJobTimeReplicaRepository.class);
        timekeepingThresholdCache = mock(TimekeepingThresholdCache.class);
        locationReferenceService = mock(LocationReferenceService.class);

        service = new PeopleReportsServiceImpl(
                timeEntryRepository,
                extPersonReplicaRepository,
                extJobTimeReplicaRepository,
                locationReferenceService,
                timekeepingThresholdCache,
                java.time.Clock.systemUTC());
    }

    @Test
    void getApprovedTimeForExport_onlyApprovedRowsReturned() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID personUuid = UUID.fromString("00000000-0000-0000-0000-000000000009");
        UUID approvedId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID rejectedId = UUID.fromString("00000000-0000-0000-0000-000000000021");

        TimeEntry approved = new TimeEntry();
        approved.setTimeEntryId(approvedId);
        approved.setPersonId(personUuid);
        approved.setLocationId(locationId);
        approved.setStatus(TimeEntryStatus.APPROVED);
        approved.setAttendanceStartAt(Instant.parse("2026-02-10T08:00:00Z"));
        approved.setAttendanceEndAt(Instant.parse("2026-02-10T16:30:00Z"));
        approved.setApprovedAt(Instant.parse("2026-02-11T01:15:00Z"));
        approved.setApprovedBy("manager-1");

        TimeEntry rejected = new TimeEntry();
        rejected.setTimeEntryId(rejectedId);
        rejected.setPersonId(personUuid);
        rejected.setLocationId(locationId);
        rejected.setStatus(TimeEntryStatus.REJECTED);
        rejected.setAttendanceStartAt(Instant.parse("2026-02-10T09:00:00Z"));
        rejected.setAttendanceEndAt(Instant.parse("2026-02-10T10:00:00Z"));
        rejected.setApprovedAt(Instant.parse("2026-02-11T01:15:00Z"));
        rejected.setApprovedBy("manager-1");

        when(locationReferenceService.isLocationActive(locationId)).thenReturn(true);
        when(locationReferenceService.getLocationName(locationId)).thenReturn("North Shop");
        when(extPersonReplicaRepository.findAllById(any()))
                .thenReturn(List.of(ExtPersonReplica.builder()
                        .personId(personUuid)
                        .firstName("Jane")
                        .lastName("Doe")
                        .aggregateVersion(0)
                        .updatedAt(Instant.now())
                        .build()));
        when(timeEntryRepository.findApprovedForExport(
                        eq(TimeEntryStatus.APPROVED), any(), any(), eq(List.of(locationId))))
                .thenReturn(List.of(approved));

        List<ApprovedTimeExportResponse> result = service.getApprovedTimeForExport(
                LocalDate.parse("2026-02-10"),
                LocalDate.parse("2026-02-10"),
                List.of(locationId),
                "test-actor",
                "corr-1");

        assertEquals(1, result.size());
        assertEquals(approvedId.toString(), result.get(0).timeEntryId());
        assertEquals("Jane Doe", result.get(0).employeeName());
        assertEquals("North Shop", result.get(0).locationName());
        assertEquals("8.50", result.get(0).hoursWorked().toPlainString());
        assertEquals("manager-1", result.get(0).approvedBy());
        assertFalse(result.stream().anyMatch(row -> row.timeEntryId().equals(rejectedId.toString())));
    }

    @Test
    void getApprovedTimeForExport_emptyResultReturns200EquivalentList() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(locationReferenceService.isLocationActive(locationId)).thenReturn(true);
        when(locationReferenceService.getLocationName(locationId)).thenReturn("North Shop");
        when(timeEntryRepository.findApprovedForExport(
                        eq(TimeEntryStatus.APPROVED), any(), any(), eq(List.of(locationId))))
                .thenReturn(List.of());

        List<ApprovedTimeExportResponse> result = service.getApprovedTimeForExport(
                LocalDate.parse("2026-02-01"),
                LocalDate.parse("2026-02-02"),
                List.of(locationId),
                "test-actor",
                "corr-1");

        assertTrue(result.isEmpty());
    }

    @Test
    void getApprovedTimeForExport_invalidDateRangeThrowsBadRequestError() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        LocalDate startDate = LocalDate.parse("2026-02-11");
        LocalDate endDate = LocalDate.parse("2026-02-10");
        List<UUID> locations = List.of(locationId);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.getApprovedTimeForExport(startDate, endDate, locations, "test-actor", "corr-1"));

        assertTrue(exception.getMessage().contains("endDate"));
    }

    @Test
    void getApprovedTimeForExport_unknownLocationThrowsBadRequestError() {
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        LocalDate startDate = LocalDate.parse("2026-02-10");
        LocalDate endDate = LocalDate.parse("2026-02-11");
        List<UUID> locations = List.of(locationId);
        when(locationReferenceService.isLocationActive(locationId)).thenReturn(false);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.getApprovedTimeForExport(startDate, endDate, locations, "test-actor", "corr-1"));

        assertTrue(exception.getMessage().contains("Unknown locationId"));
    }
}
