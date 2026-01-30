package com.positivity.workorder.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.workorder.internal.entity.*;
import com.positivity.workorder.internal.repository.ChangeRequestRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderSnapshotRepository;
import com.positivity.workorder.internal.repository.WorkorderStateTransitionRepository;
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
class WorkorderStateMachineTest {

    @Mock
    private WorkorderRepository workorderRepository;

    @Mock
    private WorkorderStateTransitionRepository transitionRepository;

    @Mock
    private WorkorderSnapshotRepository snapshotRepository;

    @Mock
    private ChangeRequestRepository changeRequestRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private WorkorderStateMachine stateMachine;

    private Workorder testWorkorder;
    private Long userId = 100L;

    @BeforeEach
    void setUp() {
        testWorkorder = Workorder.builder()
                .id(1L)
                .shopId(1L)
                .vehicleId(1L)
                .customerId(1L)
                .status(WorkorderStatus.APPROVED)
                .build();
    }

    @Test
    void testStartWorkorder_Success() throws Exception {
        when(workorderRepository.findById(1L)).thenReturn(Optional.of(testWorkorder));
        when(changeRequestRepository.findByWorkorderIdAndStatus(1L, ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW))
                .thenReturn(Collections.emptyList());
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        stateMachine.startWorkorder(1L, userId, "Starting work");

        verify(workorderRepository, atLeastOnce()).save(any(Workorder.class));
        verify(snapshotRepository).save(any(WorkorderSnapshot.class));
        verify(transitionRepository).save(any(WorkorderStateTransition.class));
    }

    @Test
    void testStartWorkorder_InvalidStatus_ThrowsException() {
        testWorkorder.setStatus(WorkorderStatus.DRAFT);
        when(workorderRepository.findById(1L)).thenReturn(Optional.of(testWorkorder));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            stateMachine.startWorkorder(1L, userId, "Starting work");
        });

        assertTrue(exception.getMessage().contains("cannot be started from status"));
    }

    @Test
    void testStartWorkorder_PendingChangeRequest_ThrowsException() {
        when(workorderRepository.findById(1L)).thenReturn(Optional.of(testWorkorder));
        ChangeRequest pendingRequest = ChangeRequest.builder()
                .id(1L)
                .workorderId(1L)
                .status(ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW)
                .build();
        when(changeRequestRepository.findByWorkorderIdAndStatus(1L, ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW))
                .thenReturn(List.of(pendingRequest));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            stateMachine.startWorkorder(1L, userId, "Starting work");
        });

        assertTrue(exception.getMessage().contains("pending change request"));
    }

    @Test
    void testTransitionWorkorder_ValidTransition() {
        testWorkorder.setStatus(WorkorderStatus.WORK_IN_PROGRESS);
        when(workorderRepository.findById(1L)).thenReturn(Optional.of(testWorkorder));

        stateMachine.transitionWorkorder(1L, WorkorderStatus.AWAITING_PARTS, userId, "Waiting for parts");

        ArgumentCaptor<Workorder> workorderCaptor = ArgumentCaptor.forClass(Workorder.class);
        verify(workorderRepository).save(workorderCaptor.capture());
        assertEquals(WorkorderStatus.AWAITING_PARTS, workorderCaptor.getValue().getStatus());

        verify(transitionRepository).save(any(WorkorderStateTransition.class));
    }

    @Test
    void testTransitionWorkorder_InvalidTransition_ThrowsException() {
        testWorkorder.setStatus(WorkorderStatus.DRAFT);
        when(workorderRepository.findById(1L)).thenReturn(Optional.of(testWorkorder));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            stateMachine.transitionWorkorder(1L, WorkorderStatus.WORK_IN_PROGRESS, userId, "Invalid transition");
        });

        assertTrue(exception.getMessage().contains("Invalid state transition"));
    }

    @Test
    void testCaptureSnapshot_Success() throws Exception {
        when(objectMapper.writeValueAsString(testWorkorder)).thenReturn("{\"id\":1,\"status\":\"APPROVED\"}");

        stateMachine.captureSnapshot(testWorkorder, userId, "TEST_SNAPSHOT", "Test reason");

        ArgumentCaptor<WorkorderSnapshot> snapshotCaptor = ArgumentCaptor.forClass(WorkorderSnapshot.class);
        verify(snapshotRepository).save(snapshotCaptor.capture());

        WorkorderSnapshot captured = snapshotCaptor.getValue();
        assertEquals(1L, captured.getWorkorderId());
        assertEquals(WorkorderStatus.APPROVED, captured.getStatus());
        assertEquals("TEST_SNAPSHOT", captured.getSnapshotType());
        assertEquals("Test reason", captured.getReason());
    }

    @Test
    void testGetTransitionHistory() {
        List<WorkorderStateTransition> expectedHistory = Collections.emptyList();
        when(transitionRepository.findByWorkorderIdOrderByTransitionedAtDesc(1L))
                .thenReturn(expectedHistory);

        List<WorkorderStateTransition> result = stateMachine.getTransitionHistory(1L);

        assertEquals(expectedHistory, result);
        verify(transitionRepository).findByWorkorderIdOrderByTransitionedAtDesc(1L);
    }

    @Test
    void testGetSnapshotHistory() {
        List<WorkorderSnapshot> expectedSnapshots = Collections.emptyList();
        when(snapshotRepository.findByWorkorderIdOrderByCapturedAtDesc(1L))
                .thenReturn(expectedSnapshots);

        List<WorkorderSnapshot> result = stateMachine.getSnapshotHistory(1L);

        assertEquals(expectedSnapshots, result);
        verify(snapshotRepository).findByWorkorderIdOrderByCapturedAtDesc(1L);
    }

    @Test
    void testWorkorderStatus_AllowedTransitions() {
        assertTrue(WorkorderStatus.APPROVED.canTransitionTo(WorkorderStatus.ASSIGNED));
        assertTrue(WorkorderStatus.ASSIGNED.canTransitionTo(WorkorderStatus.WORK_IN_PROGRESS));
        assertTrue(WorkorderStatus.WORK_IN_PROGRESS.canTransitionTo(WorkorderStatus.AWAITING_PARTS));
        assertTrue(WorkorderStatus.WORK_IN_PROGRESS.canTransitionTo(WorkorderStatus.AWAITING_APPROVAL));
        
        assertFalse(WorkorderStatus.DRAFT.canTransitionTo(WorkorderStatus.WORK_IN_PROGRESS));
        assertFalse(WorkorderStatus.COMPLETED.canTransitionTo(WorkorderStatus.WORK_IN_PROGRESS));
    }

    @Test
    void testWorkorderStatus_StartEligibleStatuses() {
        assertTrue(WorkorderStatus.getStartEligibleStatuses().contains(WorkorderStatus.APPROVED));
        assertTrue(WorkorderStatus.getStartEligibleStatuses().contains(WorkorderStatus.ASSIGNED));
        assertFalse(WorkorderStatus.getStartEligibleStatuses().contains(WorkorderStatus.DRAFT));
    }

    @Test
    void testWorkorderStatus_InProgressSubStatuses() {
        assertTrue(WorkorderStatus.getInProgressSubStatuses().contains(WorkorderStatus.WORK_IN_PROGRESS));
        assertTrue(WorkorderStatus.getInProgressSubStatuses().contains(WorkorderStatus.AWAITING_PARTS));
        assertTrue(WorkorderStatus.getInProgressSubStatuses().contains(WorkorderStatus.AWAITING_APPROVAL));
        assertFalse(WorkorderStatus.getInProgressSubStatuses().contains(WorkorderStatus.COMPLETED));
    }
}
