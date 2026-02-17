package com.positivity.people.service;

import com.positivity.people.internal.dto.TimeEntryAdjustmentRequest;
import com.positivity.people.internal.entity.TimeEntryAdjustment;
import com.positivity.people.internal.entity.TimeEntryAudit;
import com.positivity.people.internal.entity.TimeEntry;
import com.positivity.people.internal.exception.NotFoundException;
import com.positivity.people.internal.enums.TimeEntryStatus;
import com.positivity.people.internal.repository.TimeEntryAdjustmentRepository;
import com.positivity.people.internal.repository.TimeEntryAuditRepository;
import com.positivity.people.internal.repository.TimeEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TimeEntryAdjustmentServiceTest {

    private TimeEntryAdjustmentRepository adjustmentRepository;
    private TimeEntryAuditRepository auditRepository;
    private TimeEntryRepository timeEntryRepository;
    private TimeEntryAdjustmentService service;

    @BeforeEach
    void setup() {
        adjustmentRepository = mock(TimeEntryAdjustmentRepository.class);
        auditRepository = mock(TimeEntryAuditRepository.class);
        timeEntryRepository = mock(TimeEntryRepository.class);
        service = new TimeEntryAdjustmentService(adjustmentRepository, auditRepository, timeEntryRepository);
    }

    @Test
    void approveAdjustment_withPermission_succeeds() {
        UUID id = UUID.randomUUID();
        TimeEntryAdjustment adj = new TimeEntryAdjustment();
        adj.setAdjustmentId(id);
        adj.setTimeEntryId("TE-1");
        adj.setStatus(com.positivity.people.internal.enums.AdjustmentStatus.PENDING);

        when(adjustmentRepository.findById(id)).thenReturn(Optional.of(adj));
        when(adjustmentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        boolean ok = service.approveAdjustment(id, "manager1", Set.of("people:timeAdjustment:approve"), "cid-1");
        assertTrue(ok);

        ArgumentCaptor<TimeEntryAdjustment> captor = ArgumentCaptor.forClass(TimeEntryAdjustment.class);
        verify(adjustmentRepository).save(captor.capture());
        TimeEntryAdjustment saved = captor.getValue();
        assertEquals(com.positivity.people.internal.enums.AdjustmentStatus.APPROVED, saved.getStatus());
        assertEquals("manager1", saved.getDecidedBy());

        verify(auditRepository).save(any(TimeEntryAudit.class));
    }

    @Test
    void approveAdjustment_withoutPermission_fails() {
        UUID id = UUID.randomUUID();
        TimeEntryAdjustment adj = new TimeEntryAdjustment();
        adj.setAdjustmentId(id);
        adj.setTimeEntryId("TE-2");
        adj.setStatus(com.positivity.people.internal.enums.AdjustmentStatus.PENDING);

        when(adjustmentRepository.findById(id)).thenReturn(Optional.of(adj));
        Set<String> permissions = Set.of("people:timeEntry:approve");

        assertThrows(AccessDeniedException.class,
                () -> service.approveAdjustment(id, "user1", permissions, "cid-2"));
        verify(adjustmentRepository, never()).save(any());
        verify(auditRepository).save(any(TimeEntryAudit.class));
    }

    @Test
    void createAdjustment_invalidUuid_throwsBadRequest() {
        TimeEntryAdjustmentRequest request = new TimeEntryAdjustmentRequest();
        request.setReasonCode("RC1");
        request.setTimeEntryId("not-a-uuid");
        request.setMinutesDelta(5);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createAdjustment(request));
        assertEquals("timeEntryId must be a valid UUID", ex.getMessage());
    }

    @Test
    void createAdjustment_timeEntryNotFound_throwsNotFound() {
        UUID timeEntryId = UUID.randomUUID();
        TimeEntryAdjustmentRequest request = new TimeEntryAdjustmentRequest();
        request.setReasonCode("RC1");
        request.setTimeEntryId(timeEntryId.toString());
        request.setMinutesDelta(5);

        when(timeEntryRepository.findById(timeEntryId)).thenReturn(Optional.empty());

        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> service.createAdjustment(request));
        assertEquals("Time entry not found", ex.getMessage());
    }

    @Test
    void createAdjustment_invalidState_throwsConflict() {
        UUID timeEntryId = UUID.randomUUID();
        TimeEntryAdjustmentRequest request = new TimeEntryAdjustmentRequest();
        request.setReasonCode("RC1");
        request.setTimeEntryId(timeEntryId.toString());
        request.setMinutesDelta(5);

        TimeEntry entry = new TimeEntry();
        entry.setStatus(TimeEntryStatus.APPROVED);
        when(timeEntryRepository.findById(timeEntryId)).thenReturn(Optional.of(entry));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.createAdjustment(request));
        assertTrue(ex.getMessage().contains("PENDING_APPROVAL"));
    }

    @Test
    void approveAdjustment_notFound_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(adjustmentRepository.findById(id)).thenReturn(Optional.empty());
        Set<String> permissions = Set.of("people:timeAdjustment:approve");

        assertThrows(NotFoundException.class,
                () -> service.approveAdjustment(id, "manager1", permissions, "cid-1"));
    }
}
