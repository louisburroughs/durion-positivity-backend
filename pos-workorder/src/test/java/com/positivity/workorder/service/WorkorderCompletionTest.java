package com.positivity.workorder.service;

import tools.jackson.databind.ObjectMapper;
import com.positivity.workorder.internal.entity.*;
import com.positivity.workorder.internal.event.WorkCompletedEvent;
import com.positivity.workorder.internal.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkorderCompletionTest {

    @Mock
    private WorkorderRepository workOrderRepository;

    @Mock
    private WorkorderStateTransitionRepository transitionRepository;

    @Mock
    private WorkorderSnapshotRepository snapshotRepository;

    @Mock
    private ChangeRequestRepository changeRequestRepository;

    @Mock
    private AuditEventRepository auditEventRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private WorkorderStateMachine stateMachine;

    @InjectMocks
    private WorkorderService workOrderService;

    private Workorder testWorkorder;
    private Long userId = 100L;

    @BeforeEach
    void setUp() {
        testWorkorder = Workorder.builder()
                .id(1L)
                .shopId(1L)
                .vehicleId(1L)
                .customerId(1L)
                .status(WorkorderStatus.WORK_IN_PROGRESS)
                .build();

        // Re-inject mocks for WorkorderService since it has WorkorderStateMachine as
        // dependency
        workOrderService = new WorkorderService(workOrderRepository, null, null, stateMachine, auditEventRepository);
    }

    @Test
    void testCompleteWorkorder_Success_FromWorkInProgress() throws Exception {
        when(workOrderRepository.findById(1L)).thenReturn(Optional.of(testWorkorder));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        stateMachine.completeWorkorder(1L, userId, "Work completed successfully");

        ArgumentCaptor<Workorder> workOrderCaptor = ArgumentCaptor.forClass(Workorder.class);
        verify(workOrderRepository, atLeastOnce()).save(workOrderCaptor.capture());

        Workorder savedWorkorder = workOrderCaptor.getValue();
        assertNotNull(savedWorkorder.getCompletedAt());
        assertEquals(userId, savedWorkorder.getCompletedBy());
        assertEquals("Work completed successfully", savedWorkorder.getCompletionNotes());
        assertEquals(WorkorderStatus.COMPLETED, savedWorkorder.getStatus());

        verify(snapshotRepository).save(any(WorkorderSnapshot.class));
        verify(transitionRepository).save(any(WorkorderStateTransition.class));
        verify(auditEventRepository).save(any(AuditEvent.class));
    }

    @Test
    void testCompleteWorkorder_Success_FromAwaitingParts() throws Exception {
        testWorkorder.setStatus(WorkorderStatus.AWAITING_PARTS);
        when(workOrderRepository.findById(1L)).thenReturn(Optional.of(testWorkorder));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        stateMachine.completeWorkorder(1L, userId, "Parts arrived, work completed");

        ArgumentCaptor<Workorder> workOrderCaptor = ArgumentCaptor.forClass(Workorder.class);
        verify(workOrderRepository, atLeastOnce()).save(workOrderCaptor.capture());

        Workorder savedWorkorder = workOrderCaptor.getValue();
        assertEquals(WorkorderStatus.COMPLETED, savedWorkorder.getStatus());
        assertNotNull(savedWorkorder.getCompletedAt());
    }

    @Test
    void testCompleteWorkorder_Success_FromReadyForPickup() throws Exception {
        testWorkorder.setStatus(WorkorderStatus.READY_FOR_PICKUP);
        when(workOrderRepository.findById(1L)).thenReturn(Optional.of(testWorkorder));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        stateMachine.completeWorkorder(1L, userId, "Customer picked up");

        ArgumentCaptor<Workorder> workOrderCaptor = ArgumentCaptor.forClass(Workorder.class);
        verify(workOrderRepository, atLeastOnce()).save(workOrderCaptor.capture());

        assertEquals(WorkorderStatus.COMPLETED, workOrderCaptor.getValue().getStatus());
    }

    @Test
    void testCompleteWorkorder_AlreadyCompleted_ThrowsException() {
        testWorkorder.setStatus(WorkorderStatus.COMPLETED);
        when(workOrderRepository.findById(1L)).thenReturn(Optional.of(testWorkorder));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            stateMachine.completeWorkorder(1L, userId, "Trying to complete again");
        });

        assertTrue(exception.getMessage().contains("already completed"));
        verify(workOrderRepository, never()).save(any(Workorder.class));
        verify(auditEventRepository, never()).save(any(AuditEvent.class));
    }

    @Test
    void testCompleteWorkorder_CancelledStatus_ThrowsException() {
        testWorkorder.setStatus(WorkorderStatus.CANCELLED);
        when(workOrderRepository.findById(1L)).thenReturn(Optional.of(testWorkorder));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            stateMachine.completeWorkorder(1L, userId, "Trying to complete cancelled order");
        });

        assertTrue(exception.getMessage().contains("cancelled"));
        verify(workOrderRepository, never()).save(any(Workorder.class));
    }

    @Test
    void testCompleteWorkorder_InvalidStatus_ThrowsException() {
        testWorkorder.setStatus(WorkorderStatus.DRAFT);
        when(workOrderRepository.findById(1L)).thenReturn(Optional.of(testWorkorder));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            stateMachine.completeWorkorder(1L, userId, "Invalid completion attempt");
        });

        assertTrue(exception.getMessage().contains("cannot be completed from status"));
        verify(workOrderRepository, never()).save(any(Workorder.class));
    }

    @Test
    void testCompleteWorkorder_WorkorderNotFound_ThrowsException() {
        when(workOrderRepository.findById(999L)).thenReturn(Optional.empty());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            stateMachine.completeWorkorder(999L, userId, "Work order not found");
        });

        assertTrue(exception.getMessage().contains("Workorder not found"));
    }

    @Test
    void testCompleteWorkorder_AuditEventCreated() throws Exception {
        when(workOrderRepository.findById(1L)).thenReturn(Optional.of(testWorkorder));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        stateMachine.completeWorkorder(1L, userId, "Completion notes");

        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventRepository).save(auditCaptor.capture());

        AuditEvent auditEvent = auditCaptor.getValue();
        assertEquals("Workorder", auditEvent.getEntityType());
        assertEquals(1L, auditEvent.getEntityId());
        assertEquals("StateTransition", auditEvent.getEventType());
        assertEquals(userId, auditEvent.getUserId());
        assertTrue(auditEvent.getDetails().contains("WORK_IN_PROGRESS"));
        assertTrue(auditEvent.getDetails().contains("COMPLETED"));
    }

    @Test
    void testCompleteWorkorder_EventEmission() throws Exception {
        testWorkorder.setStatus(WorkorderStatus.WORK_IN_PROGRESS);
        when(workOrderRepository.findById(1L))
                .thenReturn(Optional.of(testWorkorder)) // First call by completeWorkorder in service
                .thenReturn(Optional.of(testWorkorder)); // Second call after completion
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        // Simulate what happens when completeWorkorder is called
        WorkCompletedEvent event = workOrderService.completeWorkorder(1L, userId, "Work done");

        // Verify the event was created correctly
        assertNotNull(event);
        assertEquals("WorkCompleted", event.getEventType());
        assertEquals("workexec", event.getSourceDomain());
        assertNotNull(event.getEventId());
        assertNotNull(event.getIdempotencyKey());
        assertTrue(event.getIdempotencyKey().contains(String.valueOf(1L)));

        WorkCompletedEvent.WorkCompletedPayload payload = event.getPayload();
        assertEquals(1L, payload.getWorkorderId());
        assertEquals(userId, payload.getCompletedBy());
        assertNotNull(payload.getCompletedAt());
        assertNotNull(payload.getFinalBillableScope());
    }

    @Test
    void testWorkorderIsLocked_AfterCompletion() {
        testWorkorder.setStatus(WorkorderStatus.COMPLETED);
        assertTrue(testWorkorder.isLocked());
    }

    @Test
    void testWorkorderIsNotLocked_BeforeCompletion() {
        testWorkorder.setStatus(WorkorderStatus.WORK_IN_PROGRESS);
        assertFalse(testWorkorder.isLocked());
    }

    @Test
    void testWorkorderIsLocked_AfterCancellation() {
        testWorkorder.setStatus(WorkorderStatus.CANCELLED);
        assertTrue(testWorkorder.isLocked());
    }
}
