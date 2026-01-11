package com.positivity.workorder.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.workorder.entity.*;
import com.positivity.workorder.repository.ChangeRequestRepository;
import com.positivity.workorder.repository.WorkOrderRepository;
import com.positivity.workorder.repository.WorkOrderSnapshotRepository;
import com.positivity.workorder.repository.WorkOrderStateTransitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkOrderStateMachineTest {

    @Mock
    private WorkOrderRepository workOrderRepository;

    @Mock
    private WorkOrderStateTransitionRepository transitionRepository;

    @Mock
    private WorkOrderSnapshotRepository snapshotRepository;

    @Mock
    private ChangeRequestRepository changeRequestRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private WorkOrderStateMachine stateMachine;

    private WorkOrder testWorkOrder;
    private Long userId = 100L;

    @BeforeEach
    void setUp() {
        testWorkOrder = WorkOrder.builder()
                .id(1L)
                .shopId(1L)
                .vehicleId(1L)
                .customerId(1L)
                .status(WorkOrderStatus.APPROVED)
                .build();
    }

    @Test
    void testStartWorkOrder_Success() throws Exception {
        when(workOrderRepository.findById(1L)).thenReturn(Optional.of(testWorkOrder));
        when(changeRequestRepository.findByWorkOrderIdAndStatus(1L, ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW))
                .thenReturn(Collections.emptyList());
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        stateMachine.startWorkOrder(1L, userId, "Starting work");

        verify(workOrderRepository, atLeastOnce()).save(any(WorkOrder.class));
        verify(snapshotRepository).save(any(WorkOrderSnapshot.class));
        verify(transitionRepository).save(any(WorkOrderStateTransition.class));
    }

    @Test
    void testStartWorkOrder_InvalidStatus_ThrowsException() {
        testWorkOrder.setStatus(WorkOrderStatus.DRAFT);
        when(workOrderRepository.findById(1L)).thenReturn(Optional.of(testWorkOrder));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            stateMachine.startWorkOrder(1L, userId, "Starting work");
        });

        assertTrue(exception.getMessage().contains("cannot be started from status"));
    }

    @Test
    void testStartWorkOrder_PendingChangeRequest_ThrowsException() {
        when(workOrderRepository.findById(1L)).thenReturn(Optional.of(testWorkOrder));
        ChangeRequest pendingRequest = ChangeRequest.builder()
                .id(1L)
                .workOrderId(1L)
                .status(ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW)
                .build();
        when(changeRequestRepository.findByWorkOrderIdAndStatus(1L, ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW))
                .thenReturn(List.of(pendingRequest));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            stateMachine.startWorkOrder(1L, userId, "Starting work");
        });

        assertTrue(exception.getMessage().contains("pending change request"));
    }

    @Test
    void testTransitionWorkOrder_ValidTransition() {
        testWorkOrder.setStatus(WorkOrderStatus.WORK_IN_PROGRESS);
        when(workOrderRepository.findById(1L)).thenReturn(Optional.of(testWorkOrder));

        stateMachine.transitionWorkOrder(1L, WorkOrderStatus.AWAITING_PARTS, userId, "Waiting for parts");

        ArgumentCaptor<WorkOrder> workOrderCaptor = ArgumentCaptor.forClass(WorkOrder.class);
        verify(workOrderRepository).save(workOrderCaptor.capture());
        assertEquals(WorkOrderStatus.AWAITING_PARTS, workOrderCaptor.getValue().getStatus());

        verify(transitionRepository).save(any(WorkOrderStateTransition.class));
    }

    @Test
    void testTransitionWorkOrder_InvalidTransition_ThrowsException() {
        testWorkOrder.setStatus(WorkOrderStatus.DRAFT);
        when(workOrderRepository.findById(1L)).thenReturn(Optional.of(testWorkOrder));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            stateMachine.transitionWorkOrder(1L, WorkOrderStatus.WORK_IN_PROGRESS, userId, "Invalid transition");
        });

        assertTrue(exception.getMessage().contains("Invalid state transition"));
    }

    @Test
    void testCaptureSnapshot_Success() throws Exception {
        when(objectMapper.writeValueAsString(testWorkOrder)).thenReturn("{\"id\":1,\"status\":\"APPROVED\"}");

        stateMachine.captureSnapshot(testWorkOrder, userId, "TEST_SNAPSHOT", "Test reason");

        ArgumentCaptor<WorkOrderSnapshot> snapshotCaptor = ArgumentCaptor.forClass(WorkOrderSnapshot.class);
        verify(snapshotRepository).save(snapshotCaptor.capture());

        WorkOrderSnapshot captured = snapshotCaptor.getValue();
        assertEquals(1L, captured.getWorkOrderId());
        assertEquals(WorkOrderStatus.APPROVED, captured.getStatus());
        assertEquals("TEST_SNAPSHOT", captured.getSnapshotType());
        assertEquals("Test reason", captured.getReason());
    }

    @Test
    void testGetTransitionHistory() {
        List<WorkOrderStateTransition> expectedHistory = Collections.emptyList();
        when(transitionRepository.findByWorkOrderIdOrderByTransitionedAtDesc(1L))
                .thenReturn(expectedHistory);

        List<WorkOrderStateTransition> result = stateMachine.getTransitionHistory(1L);

        assertEquals(expectedHistory, result);
        verify(transitionRepository).findByWorkOrderIdOrderByTransitionedAtDesc(1L);
    }

    @Test
    void testGetSnapshotHistory() {
        List<WorkOrderSnapshot> expectedSnapshots = Collections.emptyList();
        when(snapshotRepository.findByWorkOrderIdOrderByCapturedAtDesc(1L))
                .thenReturn(expectedSnapshots);

        List<WorkOrderSnapshot> result = stateMachine.getSnapshotHistory(1L);

        assertEquals(expectedSnapshots, result);
        verify(snapshotRepository).findByWorkOrderIdOrderByCapturedAtDesc(1L);
    }

    @Test
    void testWorkOrderStatus_AllowedTransitions() {
        assertTrue(WorkOrderStatus.APPROVED.canTransitionTo(WorkOrderStatus.ASSIGNED));
        assertTrue(WorkOrderStatus.ASSIGNED.canTransitionTo(WorkOrderStatus.WORK_IN_PROGRESS));
        assertTrue(WorkOrderStatus.WORK_IN_PROGRESS.canTransitionTo(WorkOrderStatus.AWAITING_PARTS));
        assertTrue(WorkOrderStatus.WORK_IN_PROGRESS.canTransitionTo(WorkOrderStatus.AWAITING_APPROVAL));
        
        assertFalse(WorkOrderStatus.DRAFT.canTransitionTo(WorkOrderStatus.WORK_IN_PROGRESS));
        assertFalse(WorkOrderStatus.COMPLETED.canTransitionTo(WorkOrderStatus.WORK_IN_PROGRESS));
    }

    @Test
    void testWorkOrderStatus_StartEligibleStatuses() {
        assertTrue(WorkOrderStatus.getStartEligibleStatuses().contains(WorkOrderStatus.APPROVED));
        assertTrue(WorkOrderStatus.getStartEligibleStatuses().contains(WorkOrderStatus.ASSIGNED));
        assertFalse(WorkOrderStatus.getStartEligibleStatuses().contains(WorkOrderStatus.DRAFT));
    }

    @Test
    void testWorkOrderStatus_InProgressSubStatuses() {
        assertTrue(WorkOrderStatus.getInProgressSubStatuses().contains(WorkOrderStatus.WORK_IN_PROGRESS));
        assertTrue(WorkOrderStatus.getInProgressSubStatuses().contains(WorkOrderStatus.AWAITING_PARTS));
        assertTrue(WorkOrderStatus.getInProgressSubStatuses().contains(WorkOrderStatus.AWAITING_APPROVAL));
        assertFalse(WorkOrderStatus.getInProgressSubStatuses().contains(WorkOrderStatus.COMPLETED));
    }
}
