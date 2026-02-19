package com.positivity.people.internal.service;

import com.positivity.people.internal.client.LocationReferenceClient;
import com.positivity.people.internal.dto.AssignStaffRequest;
import com.positivity.people.internal.entity.Person;
import com.positivity.people.internal.entity.PersonLocationAssignment;
import com.positivity.people.internal.exception.LocationAssignmentNotFoundException;
import com.positivity.people.internal.repository.PersonLocationAssignmentRepository;
import com.positivity.people.internal.repository.PersonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocationServiceImplTest {

    private LocationReferenceClient locationReferenceClient;
    private PersonLocationAssignmentRepository assignmentRepository;
    private PersonRepository personRepository;
    private LocationServiceImpl service;

    @BeforeEach
    void setUp() {
        locationReferenceClient = mock(LocationReferenceClient.class);
        assignmentRepository = mock(PersonLocationAssignmentRepository.class);
        personRepository = mock(PersonRepository.class);
        service = new LocationServiceImpl(locationReferenceClient, assignmentRepository, personRepository);
    }

    @Test
    void unassignStaff_endsMostRecentActiveAssignment_andSkipsFutureDatedAssignment() {
        UUID locationId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        LocalDate today = LocalDate.now();

        PersonLocationAssignment futureAssignment = assignment(
                locationId,
                personId,
                today.plusDays(2),
                null,
                UUID.randomUUID(),
                Instant.parse("2026-01-01T12:00:00Z"));
        PersonLocationAssignment olderActive = assignment(
                locationId,
                personId,
                today.minusDays(15),
                null,
                UUID.randomUUID(),
                Instant.parse("2026-01-05T12:00:00Z"));
        PersonLocationAssignment mostRecentActive = assignment(
                locationId,
                personId,
                today.minusDays(1),
                null,
                UUID.randomUUID(),
                Instant.parse("2026-01-10T12:00:00Z"));

        when(assignmentRepository.findByLocationIdAndPersonId(locationId, personId))
                .thenReturn(List.of(futureAssignment, olderActive, mostRecentActive));
        when(assignmentRepository.save(any(PersonLocationAssignment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.unassignStaff(locationId, personId);

        assertEquals(today, mostRecentActive.getEffectiveTo());
        assertFalse(mostRecentActive.isPrimary());
        assertNull(futureAssignment.getEffectiveTo());

        verify(assignmentRepository).save(mostRecentActive);
        verify(assignmentRepository, never()).save(futureAssignment);
        verify(assignmentRepository, never()).save(olderActive);
    }

    @Test
    void unassignStaff_whenNoAssignmentIsActiveToday_throwsNotFound() {
        UUID locationId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        LocalDate today = LocalDate.now();

        PersonLocationAssignment endedAssignment = assignment(
                locationId,
                personId,
                today.minusDays(20),
                today.minusDays(10),
                UUID.randomUUID(),
                Instant.parse("2026-01-02T12:00:00Z"));
        PersonLocationAssignment futureAssignment = assignment(
                locationId,
                personId,
                today.plusDays(1),
                null,
                UUID.randomUUID(),
                Instant.parse("2026-01-12T12:00:00Z"));

        when(assignmentRepository.findByLocationIdAndPersonId(locationId, personId))
                .thenReturn(List.of(endedAssignment, futureAssignment));

        assertThrows(LocationAssignmentNotFoundException.class, () -> service.unassignStaff(locationId, personId));
        verify(assignmentRepository, never()).save(any(PersonLocationAssignment.class));
    }

    @Test
    void unassignStaff_withSameEffectiveFrom_usesMostRecentlyCreatedAsTieBreaker() {
        UUID locationId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        LocalDate today = LocalDate.now();
        LocalDate sameStart = today.minusDays(3);

        PersonLocationAssignment earlierCreated = assignment(
                locationId,
                personId,
                sameStart,
                null,
                UUID.randomUUID(),
                Instant.parse("2026-01-01T12:00:00Z"));
        PersonLocationAssignment laterCreated = assignment(
                locationId,
                personId,
                sameStart,
                null,
                UUID.randomUUID(),
                Instant.parse("2026-01-15T12:00:00Z"));

        when(assignmentRepository.findByLocationIdAndPersonId(locationId, personId))
                .thenReturn(List.of(earlierCreated, laterCreated));
        when(assignmentRepository.save(any(PersonLocationAssignment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.unassignStaff(locationId, personId);

        assertEquals(today, laterCreated.getEffectiveTo());
        assertNull(earlierCreated.getEffectiveTo());
        verify(assignmentRepository).save(laterCreated);
        verify(assignmentRepository, never()).save(earlierCreated);
    }

    @Test
    void assignStaff_demotesSameDayPrimaryWithoutInvalidDateRange() {
        UUID personId = UUID.randomUUID();
        UUID currentLocationId = UUID.randomUUID();
        UUID newLocationId = UUID.randomUUID();
        LocalDate today = LocalDate.now();

        PersonLocationAssignment existingPrimary = assignment(
                currentLocationId,
                personId,
                today,
                null,
                UUID.randomUUID(),
                Instant.parse("2026-01-03T12:00:00Z"));

        when(personRepository.findById(personId)).thenReturn(Optional.of(Person.builder().id(personId).build()));
        when(assignmentRepository.findByLocationIdAndPersonId(newLocationId, personId)).thenReturn(List.of());
        when(assignmentRepository.findByPersonId(personId)).thenReturn(List.of(existingPrimary));
        when(assignmentRepository.save(any(PersonLocationAssignment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AssignStaffRequest request = new AssignStaffRequest();
        request.setPersonId(personId);
        request.setIsPrimary(true);
        request.setEffectiveFrom(today);

        service.assignStaff(newLocationId, request);

        assertFalse(existingPrimary.isPrimary());
        assertNotNull(existingPrimary.getEffectiveTo());
        assertEquals(existingPrimary.getEffectiveFrom(), existingPrimary.getEffectiveTo());
    }

    private PersonLocationAssignment assignment(
            UUID locationId,
            UUID personId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            UUID assignmentId,
            Instant createdAt) {
        PersonLocationAssignment assignment = new PersonLocationAssignment();
        assignment.setLocationId(locationId);
        assignment.setPersonId(personId);
        assignment.setRole("ASSOCIATE");
        assignment.setPrimary(true);
        assignment.setEffectiveFrom(effectiveFrom);
        assignment.setEffectiveTo(effectiveTo);
        assignment.setAssignmentId(assignmentId);
        assignment.setCreatedAt(createdAt);
        return assignment;
    }
}
