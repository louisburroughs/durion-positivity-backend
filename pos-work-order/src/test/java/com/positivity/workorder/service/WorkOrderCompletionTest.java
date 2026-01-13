package com.positivity.workorder.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.workorder.entity.*;
import com.positivity.workorder.event.WorkCompletedEvent;
import com.positivity.workorder.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkOrderCompletionTest {

    @Mock
    private WorkOrderRepository workOrderRepository;

    @Mock
    private WorkOrderStateTransitionRepository transitionRepository;

    @Mock
    private WorkOrderSnapshotRepository snapshotRepository;

    @Mock
    private ChangeRequestRepository changeRequestRepository;

    @Mock
    private AuditEventRepository auditEventRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private WorkOrderStateMachine stateMachine;

    @InjectMocks
    private WorkOrderService workOrderService;

    private WorkOrder testWorkOrder;
    private Long userId = 100L;

    @BeforeEach
    void setUp() {
        testWorkOrder = WorkOrder.builder()
                .id(1L)
                .shopId(1L)
                .vehicleId(1L)
                .customerId(1L)
                .status(WorkOrderStatus.WORK_IN_PROGRESS)
                .build();

        // Re-inject mocks for WorkOrderService since it has WorkOrderStateMachine as dependency
        workOrderService = new WorkOrderService(workOrderRepository, null, null, stateMachine, auditEventRepository);
    }

    @Test
    void testCompleteWorkOrder_Success_FromWorkInProgress() throws Exception {
        when(workOrderRepository.findById(1L)).thenReturn(Optional.of(testWorkOrder));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        stateMachine.completeWorkOrder(1L, userId, "Work completed successfully");

        ArgumentCaptor<WorkOrder> workOrderCaptor = ArgumentCaptor.forClass(WorkOrder.class);
        verify(workOrderRepository, atLeastOnce()).save(workOrderCaptor.capture());

        WorkOrder savedWorkOrder = workOrderCaptor.getValue();
        assertNotNull(savedWorkOrder.getCompletedAt());
        assertEquals(userId, savedWorkOrder.getCompletedBy());
        assertEquals("Work completed successfully", savedWorkOrder.getCompletionNotes());
        assertEquals(WorkOrderStatus.COMPLETED, savedWorkOrder.getStatus());

        verify(snapshotRepository).save(any(WorkOrderSnapshot.class));
        verify(transitionRepository).save(any(WorkOrderStateTransition.class));
        verify(auditEventRepository).save(any(AuditEvent.class));
    }

    @Test
    void testCompleteWorkOrder_Success_FromAwaitingParts() throws Exception {
        testWorkOrder.setStatus(WorkOrderStatus.AWAITING_PARTS);
        when(workOrderRepository.findById(1L)).thenReturn(Optional.of(testWorkOrder));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        stateMachine.completeWorkOrder(1L, userId, "Parts arrived, work completed");

        ArgumentCaptor<WorkOrder> workOrderCaptor = ArgumentCaptor.forClass(WorkOrder.class);
        verify(workOrderRepository, atLeastOnce()).save(workOrderCaptor.capture());

        WorkOrder savedWorkOrder = workOrderCaptor.getValue();
        assertEquals(WorkOrderStatus.COMPLETED, savedWorkOrder.getStatus());
        assertNotNull(savedWorkOrder.getCompletedAt());
    }

    @Test
    void testCompleteWorkOrder_Success_FromReadyForPickup() throws Exception {
        testWorkOrder.setStatus(WorkOrderStatus.READY_FOR_PICKUP);
        when(workOrderRepository.findById(1L)).thenReturn(Optional.of(testWorkOrder));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        stateMachine.completeWorkOrder(1L, userId, "Customer picked up");

        ArgumentCaptor<WorkOrder> workOrderCaptor = ArgumentCaptor.forClass(WorkOrder.class);
        verify(workOrderRepository, atLeastOnce()).save(workOrderCaptor.capture());

        assertEquals(WorkOrderStatus.COMPLETED, workOrderCaptor.getValue().getStatus());
    }

    @Test
    void testCompleteWorkOrder_AlreadyCompleted_ThrowsException() {
        testWorkOrder.setStatus(WorkOrderStatus.COMPLETED);
        when(workOrderRepository.findById(1L)).thenReturn(Optional.of(testWorkOrder));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            stateMachine.completeWorkOrder(1L, userId, "Trying to complete again");
        });

        assertTrue(exception.getMessage().contains("already completed"));
        verify(workOrderRepository, never()).save(any(WorkOrder.class));
        verify(auditEventRepository, never()).save(any(AuditEvent.class));
    }

    @Test
    void testCompleteWorkOrder_CancelledStatus_ThrowsException() {
        testWorkOrder.setStatus(WorkOrderStatus.CANCELLED);
        when(workOrderRepository.findById(1L)).thenReturn(Optional.of(testWorkOrder));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            stateMachine.completeWorkOrder(1L, userId, "Trying to complete cancelled order");
        });

        assertTrue(exception.getMessage().contains("cancelled"));
        verify(workOrderRepository, never()).save(any(WorkOrder.class));
    }

    @Test
    void testCompleteWorkOrder_InvalidStatus_ThrowsException() {
        testWorkOrder.setStatus(WorkOrderStatus.DRAFT);
        when(workOrderRepository.findById(1L)).thenReturn(Optional.of(testWorkOrder));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            stateMachine.completeWorkOrder(1L, userId, "Invalid completion attempt");
        });

        assertTrue(exception.getMessage().contains("cannot be completed from status"));
        verify(workOrderRepository, never()).save(any(WorkOrder.class));
    }

    @Test
    void testCompleteWorkOrder_WorkOrderNotFound_ThrowsException() {
        when(workOrderRepository.findById(999L)).thenReturn(Optional.empty());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            stateMachine.completeWorkOrder(999L, userId, "Work order not found");
        });

        assertTrue(exception.getMessage().contains("WorkOrder not found"));
    }

    @Test
    void testCompleteWorkOrder_AuditEventCreated() throws Exception {
        when(workOrderRepository.findById(1L)).thenReturn(Optional.of(testWorkOrder));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        stateMachine.completeWorkOrder(1L, userId, "Completion notes");

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
    void testCompleteWorkOrder_EventEmission() throws Exception {
        testWorkOrder.setStatus(WorkOrderStatus.WORK_IN_PROGRESS);
        when(workOrderRepository.findById(1L))
            .thenReturn(Optional.of(testWorkOrder))  // First call by completeWorkOrder in service
            .thenReturn(Optional.of(testWorkOrder)); // Second call after completion
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        // Simulate what happens when completeWorkOrder is called
        WorkCompletedEvent event = workOrderService.completeWorkOrder(1L, userId, "Work done");

        // Verify the event was created correctly
        assertNotNull(event);
        assertEquals("WorkCompleted", event.getEventType());
        assertEquals("workexec", event.getSourceDomain());
        assertNotNull(event.getEventId());
        assertNotNull(event.getIdempotencyKey());
        assertTrue(event.getIdempotencyKey().contains(String.valueOf(1L)));
        
        WorkCompletedEvent.WorkCompletedPayload payload = event.getPayload();
        assertEquals(1L, payload.getWorkOrderId());
        assertEquals(userId, payload.getCompletedBy());
        assertNotNull(payload.getCompletedAt());
        assertNotNull(payload.getFinalBillableScope());
    }

    @Test
    void testWorkOrderIsLocked_AfterCompletion() {
        testWorkOrder.setStatus(WorkOrderStatus.COMPLETED);
        assertTrue(testWorkOrder.isLocked());
    }

    @Test
    void testWorkOrderIsNotLocked_BeforeCompletion() {
        testWorkOrder.setStatus(WorkOrderStatus.WORK_IN_PROGRESS);
        assertFalse(testWorkOrder.isLocked());
    }

    @Test
    void testWorkOrderIsLocked_AfterCancellation() {
        testWorkOrder.setStatus(WorkOrderStatus.CANCELLED);
        assertTrue(testWorkOrder.isLocked());
    }
}
