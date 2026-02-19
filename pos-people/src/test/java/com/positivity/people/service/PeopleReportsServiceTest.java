package com.positivity.people.service;

import com.positivity.people.internal.client.LocationReferenceClient;
import com.positivity.people.internal.client.WorkexecJobTimeClient;
import com.positivity.people.internal.dto.ApprovedTimeExportResponse;
import com.positivity.people.internal.entity.Person;
import com.positivity.people.internal.entity.TimeEntry;
import com.positivity.people.internal.enums.TimeEntryStatus;
import com.positivity.people.internal.repository.PersonRepository;
import com.positivity.people.internal.repository.TimeEntryRepository;
import com.positivity.people.internal.service.PeopleReportsServiceImpl;
import com.positivity.people.internal.service.TimekeepingThresholdCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for approved-time export read behavior.
 *
 * Issue: #79
 */
class PeopleReportsServiceTest {

    private TimeEntryRepository timeEntryRepository;
    private PersonRepository personRepository;
    private WorkexecJobTimeClient workexecJobTimeClient;
    private TimekeepingThresholdCache timekeepingThresholdCache;
    private LocationReferenceClient locationReferenceClient;
    private PeopleReportsService service;

    @BeforeEach
    void setup() {
        timeEntryRepository = mock(TimeEntryRepository.class);
        personRepository = mock(PersonRepository.class);
        workexecJobTimeClient = mock(WorkexecJobTimeClient.class);
        timekeepingThresholdCache = mock(TimekeepingThresholdCache.class);
        locationReferenceClient = mock(LocationReferenceClient.class);

        service = new PeopleReportsServiceImpl(
                timeEntryRepository,
                personRepository,
                workexecJobTimeClient,
                locationReferenceClient,
                timekeepingThresholdCache);
    }

    @Test
    void getApprovedTimeForExport_onlyApprovedRowsReturned() {
        UUID locationId = UUID.randomUUID();
        UUID personUuid = UUID.randomUUID();
        UUID approvedId = UUID.randomUUID();
        UUID rejectedId = UUID.randomUUID();

        TimeEntry approved = new TimeEntry();
        approved.setTimeEntryId(approvedId);
        approved.setPersonId(personUuid.toString());
        approved.setLocationId(locationId);
        approved.setStatus(TimeEntryStatus.APPROVED);
        approved.setAttendanceStartAt(Instant.parse("2026-02-10T08:00:00Z"));
        approved.setAttendanceEndAt(Instant.parse("2026-02-10T16:30:00Z"));
        approved.setApprovedAt(Instant.parse("2026-02-11T01:15:00Z"));
        approved.setApprovedBy("manager-1");

        TimeEntry rejected = new TimeEntry();
        rejected.setTimeEntryId(rejectedId);
        rejected.setPersonId(personUuid.toString());
        rejected.setLocationId(locationId);
        rejected.setStatus(TimeEntryStatus.REJECTED);
        rejected.setAttendanceStartAt(Instant.parse("2026-02-10T09:00:00Z"));
        rejected.setAttendanceEndAt(Instant.parse("2026-02-10T10:00:00Z"));
        rejected.setApprovedAt(Instant.parse("2026-02-11T01:15:00Z"));
        rejected.setApprovedBy("manager-1");

        Person person = Person.builder()
                .id(personUuid)
                .firstName("Jane")
                .lastName("Doe")
                .build();

        when(locationReferenceClient.isLocationActive(locationId)).thenReturn(true);
        when(locationReferenceClient.getLocationName(locationId)).thenReturn("North Shop");
        when(timeEntryRepository.findApprovedForExport(eq(TimeEntryStatus.APPROVED), any(), any(),
                eq(List.of(locationId))))
                .thenReturn(List.of(approved));
        when(personRepository.findAllById(any())).thenReturn(List.of(person));

        List<ApprovedTimeExportResponse> result = service.getApprovedTimeForExport(
                LocalDate.parse("2026-02-10"),
                LocalDate.parse("2026-02-10"),
                List.of(locationId));

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
        UUID locationId = UUID.randomUUID();
        when(locationReferenceClient.isLocationActive(locationId)).thenReturn(true);
        when(locationReferenceClient.getLocationName(locationId)).thenReturn("North Shop");
        when(timeEntryRepository.findApprovedForExport(eq(TimeEntryStatus.APPROVED), any(), any(),
                eq(List.of(locationId))))
                .thenReturn(List.of());

        List<ApprovedTimeExportResponse> result = service.getApprovedTimeForExport(
                LocalDate.parse("2026-02-01"),
                LocalDate.parse("2026-02-02"),
                List.of(locationId));

        assertTrue(result.isEmpty());
    }

    @Test
    void getApprovedTimeForExport_invalidDateRangeThrowsBadRequestError() {
        UUID locationId = UUID.randomUUID();
        LocalDate startDate = LocalDate.parse("2026-02-11");
        LocalDate endDate = LocalDate.parse("2026-02-10");
        List<UUID> locations = List.of(locationId);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.getApprovedTimeForExport(startDate, endDate, locations));

        assertTrue(exception.getMessage().contains("endDate"));
    }

    @Test
    void getApprovedTimeForExport_unknownLocationThrowsBadRequestError() {
        UUID locationId = UUID.randomUUID();
        LocalDate startDate = LocalDate.parse("2026-02-10");
        LocalDate endDate = LocalDate.parse("2026-02-11");
        List<UUID> locations = List.of(locationId);
        when(locationReferenceClient.isLocationActive(locationId)).thenReturn(false);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.getApprovedTimeForExport(startDate, endDate, locations));

        assertTrue(exception.getMessage().contains("Unknown locationId"));
    }
}
