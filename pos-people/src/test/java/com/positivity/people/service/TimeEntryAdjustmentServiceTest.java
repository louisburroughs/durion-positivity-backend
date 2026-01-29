package com.positivity.people.service;

import com.positivity.people.internal.entity.TimeEntryAdjustment;
import com.positivity.people.internal.entity.TimeEntryAudit;
import com.positivity.people.internal.repository.TimeEntryAdjustmentRepository;
import com.positivity.people.internal.repository.TimeEntryAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class TimeEntryAdjustmentServiceTest {

    private TimeEntryAdjustmentRepository adjustmentRepository;
    private TimeEntryAuditRepository auditRepository;
    private TimeEntryAdjustmentService service;

    @BeforeEach
    public void setup() {
        adjustmentRepository = mock(TimeEntryAdjustmentRepository.class);
        auditRepository = mock(TimeEntryAuditRepository.class);
        service = new TimeEntryAdjustmentService(adjustmentRepository, auditRepository);
    }

    @Test
    public void approveAdjustment_withPermission_succeeds() {
        UUID id = UUID.randomUUID();
        TimeEntryAdjustment adj = new TimeEntryAdjustment();
        adj.setAdjustmentId(id);
        adj.setTimeEntryId("TE-1");
        adj.setStatus(com.positivity.people.internal.model.AdjustmentStatus.PENDING);

        when(adjustmentRepository.findById(id)).thenReturn(Optional.of(adj));
        when(adjustmentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        boolean ok = service.approveAdjustment(id, "manager1", Set.of("people:timeAdjustment:approve"), "cid-1");
        assertTrue(ok);

        ArgumentCaptor<TimeEntryAdjustment> captor = ArgumentCaptor.forClass(TimeEntryAdjustment.class);
        verify(adjustmentRepository).save(captor.capture());
        TimeEntryAdjustment saved = captor.getValue();
        assertEquals(com.positivity.people.internal.model.AdjustmentStatus.APPROVED, saved.getStatus());
        assertEquals("manager1", saved.getDecidedBy());

        verify(auditRepository).save(any(TimeEntryAudit.class));
    }

    @Test
    public void approveAdjustment_withoutPermission_fails() {
        UUID id = UUID.randomUUID();
        TimeEntryAdjustment adj = new TimeEntryAdjustment();
        adj.setAdjustmentId(id);
        adj.setTimeEntryId("TE-2");
        adj.setStatus(com.positivity.people.internal.model.AdjustmentStatus.PENDING);

        when(adjustmentRepository.findById(id)).thenReturn(Optional.of(adj));

        boolean ok = service.approveAdjustment(id, "user1", Set.of("people:timeEntry:approve"), "cid-2");
        assertFalse(ok);
        verify(adjustmentRepository, never()).save(any());
        verify(auditRepository).save(any(TimeEntryAudit.class));
    }
}
