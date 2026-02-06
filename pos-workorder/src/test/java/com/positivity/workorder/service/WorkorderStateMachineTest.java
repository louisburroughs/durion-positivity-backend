package com.positivity.workorder.service;

import tools.jackson.databind.ObjectMapper;
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
import java.util.UUID;

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
    private UUID userId;
    private UUID testWorkorderId;
    private UUID testChangeRequestId;
    private UUID testShopId;
    private UUID testVehicleId;
    private UUID testCustomerId;

    @BeforeEach
    void setUp() {
        testWorkorderId = UUID.fromString("550e8400-e29b-41d4-a716-446655440040");
        testChangeRequestId = UUID.fromString("550e8400-e29b-41d4-a716-446655440041");
        testShopId = UUID.fromString("550e8400-e29b-41d4-a716-446655440042");
        testVehicleId = UUID.fromString("550e8400-e29b-41d4-a716-446655440043");
        testCustomerId = UUID.fromString("550e8400-e29b-41d4-a716-446655440044");
        userId = UUID.fromString("550e8400-e29b-41d4-a716-446655440045");
        testWorkorder = Workorder.builder()
                .id(testWorkorderId)
                .shopId(testShopId)
                .vehicleId(testVehicleId)
                .customerId(testCustomerId)
                .status(WorkorderStatus.APPROVED)
                .build();
    }

    @Test
    void testStartWorkorder_Success() throws Exception {
        when(workorderRepository.findById(testWorkorderId)).thenReturn(Optional.of(testWorkorder));
        when(changeRequestRepository.findByWorkorderIdAndStatus(testWorkorderId,
                ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW))
                .thenReturn(Collections.emptyList());
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        stateMachine.startWorkorder(testWorkorderId, userId, "Starting work");

        verify(workorderRepository, atLeastOnce()).save(any(Workorder.class));
        verify(snapshotRepository).save(any(WorkorderSnapshot.class));
        verify(transitionRepository).save(any(WorkorderStateTransition.class));
    }

    @Test
    void testStartWorkorder_InvalidStatus_ThrowsException() {
        testWorkorder.setStatus(WorkorderStatus.DRAFT);
        when(workorderRepository.findById(testWorkorderId)).thenReturn(Optional.of(testWorkorder));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            stateMachine.startWorkorder(testWorkorderId, userId, "Starting work");
        });

        assertTrue(exception.getMessage().contains("cannot be started from status"));
    }

    @Test
    void testStartWorkorder_PendingChangeRequest_ThrowsException() {
        when(workorderRepository.findById(testWorkorderId)).thenReturn(Optional.of(testWorkorder));
        ChangeRequest pendingRequest = ChangeRequest.builder()
                .id(testChangeRequestId)
                .workorderId(testWorkorderId)
                .status(ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW)
                .build();
        when(changeRequestRepository.findByWorkorderIdAndStatus(testWorkorderId,
                ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW))
                .thenReturn(List.of(pendingRequest));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            stateMachine.startWorkorder(testWorkorderId, userId, "Starting work");
        });

        assertTrue(exception.getMessage().contains("pending change request"));
    }

    @Test
    void testTransitionWorkorder_ValidTransition() {
        testWorkorder.setStatus(WorkorderStatus.WORK_IN_PROGRESS);
        when(workorderRepository.findById(testWorkorderId)).thenReturn(Optional.of(testWorkorder));

        stateMachine.transitionWorkorder(testWorkorderId, WorkorderStatus.AWAITING_PARTS, userId, "Waiting for parts");

        ArgumentCaptor<Workorder> workorderCaptor = ArgumentCaptor.forClass(Workorder.class);
        verify(workorderRepository).save(workorderCaptor.capture());
        assertEquals(WorkorderStatus.AWAITING_PARTS, workorderCaptor.getValue().getStatus());

        verify(transitionRepository).save(any(WorkorderStateTransition.class));
    }

    @Test
    void testTransitionWorkorder_InvalidTransition_ThrowsException() {
        testWorkorder.setStatus(WorkorderStatus.DRAFT);
        when(workorderRepository.findById(testWorkorderId)).thenReturn(Optional.of(testWorkorder));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            stateMachine.transitionWorkorder(testWorkorderId, WorkorderStatus.WORK_IN_PROGRESS, userId,
                    "Invalid transition");
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
        assertEquals(testWorkorderId, captured.getWorkorderId());
        assertEquals(WorkorderStatus.APPROVED, captured.getStatus());
        assertEquals("TEST_SNAPSHOT", captured.getSnapshotType());
        assertEquals("Test reason", captured.getReason());
    }

    @Test
    void testGetTransitionHistory() {
        List<WorkorderStateTransition> expectedHistory = Collections.emptyList();
        when(transitionRepository.findByWorkorderIdOrderByTransitionedAtDesc(testWorkorderId))
                .thenReturn(expectedHistory);

        List<WorkorderStateTransition> result = stateMachine.getTransitionHistory(testWorkorderId);

        assertEquals(expectedHistory, result);
        verify(transitionRepository).findByWorkorderIdOrderByTransitionedAtDesc(testWorkorderId);
    }

    @Test
    void testGetSnapshotHistory() {
        List<WorkorderSnapshot> expectedSnapshots = Collections.emptyList();
        when(snapshotRepository.findByWorkorderIdOrderByCapturedAtDesc(testWorkorderId))
                .thenReturn(expectedSnapshots);

        List<WorkorderSnapshot> result = stateMachine.getSnapshotHistory(testWorkorderId);

        assertEquals(expectedSnapshots, result);
        verify(snapshotRepository).findByWorkorderIdOrderByCapturedAtDesc(testWorkorderId);
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
