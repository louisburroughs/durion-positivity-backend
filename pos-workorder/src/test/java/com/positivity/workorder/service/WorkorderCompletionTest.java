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
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.positivity.security.common.GatewaySecurityConstants;

@ExtendWith(MockitoExtension.class)
class WorkorderCompletionTest {

    @Mock
    private WorkorderRepository workOrderRepository;

    @Mock
    private EstimateRepository estimateRepository;

    @Mock
    private EstimateItemRepository estimateItemRepository;

    @Mock
    private WorkorderServiceRepository workorderServiceRepository;

    @Mock
    private WorkorderPartRepository workorderPartRepository;

    @Mock
    private WorkorderStateTransitionRepository transitionRepository;

    @Mock
    private WorkorderSnapshotRepository snapshotRepository;

    @Mock
    private ChangeRequestRepository changeRequestRepository;

    @Mock
    private AuditEventRepository auditEventRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private ChangeRequestService changeRequestService;

    @Mock
    private PromotionValidationService promotionValidationService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private org.springframework.web.client.RestClient restClient;

    @InjectMocks
    private WorkorderStateMachine stateMachine;

    @InjectMocks
    private WorkorderService workOrderService;

    private Workorder testWorkorder;
    private UUID userId;
    private UUID testWorkorderId;
    private UUID testShopId;
    private UUID testVehicleId;
    private UUID testCustomerId;

    @BeforeEach
    void setUp() {
        // Mock authenticated user with gateway-injected details so snapshot/audit flows have an actor
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(
                "test-user",
                "password",
                "ROLE_USER");
        authentication.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USER_ID, "550e8400-e29b-41d4-a716-446655440034",
                GatewaySecurityConstants.DETAIL_USERNAME, "test-user"));
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        testWorkorderId = UUID.fromString("550e8400-e29b-41d4-a716-446655440030");
        testShopId = UUID.fromString("550e8400-e29b-41d4-a716-446655440031");
        testVehicleId = UUID.fromString("550e8400-e29b-41d4-a716-446655440032");
        testCustomerId = UUID.fromString("550e8400-e29b-41d4-a716-446655440033");
        userId = UUID.fromString("550e8400-e29b-41d4-a716-446655440034");
        testWorkorder = Workorder.builder()
                .id(testWorkorderId)
                .shopId(testShopId)
                .vehicleId(testVehicleId)
                .customerId(testCustomerId)
                .status(WorkorderStatus.WORK_IN_PROGRESS)
                .build();

        // Re-inject mocks for WorkorderService since it has WorkorderStateMachine as
        // dependency
        workOrderService = new WorkorderService(workOrderRepository, estimateRepository, estimateItemRepository,
                workorderServiceRepository, workorderPartRepository, restClient, stateMachine,
                auditEventRepository, idempotencyService, promotionValidationService);
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testCompleteWorkorder_Success_FromWorkInProgress() throws Exception {
        when(workOrderRepository.findById(testWorkorderId)).thenReturn(Optional.of(testWorkorder));
        stubCompletionPreconditionsPass();
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        stateMachine.completeWorkorder(testWorkorderId, userId, "Work completed successfully");

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
        when(workOrderRepository.findById(testWorkorderId)).thenReturn(Optional.of(testWorkorder));
        stubCompletionPreconditionsPass();
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        stateMachine.completeWorkorder(testWorkorderId, userId, "Parts arrived, work completed");

        ArgumentCaptor<Workorder> workOrderCaptor = ArgumentCaptor.forClass(Workorder.class);
        verify(workOrderRepository, atLeastOnce()).save(workOrderCaptor.capture());

        Workorder savedWorkorder = workOrderCaptor.getValue();
        assertEquals(WorkorderStatus.COMPLETED, savedWorkorder.getStatus());
        assertNotNull(savedWorkorder.getCompletedAt());
    }

    @Test
    void testCompleteWorkorder_Success_FromReadyForPickup() throws Exception {
        testWorkorder.setStatus(WorkorderStatus.READY_FOR_PICKUP);
        when(workOrderRepository.findById(testWorkorderId)).thenReturn(Optional.of(testWorkorder));
        stubCompletionPreconditionsPass();
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        stateMachine.completeWorkorder(testWorkorderId, userId, "Customer picked up");

        ArgumentCaptor<Workorder> workOrderCaptor = ArgumentCaptor.forClass(Workorder.class);
        verify(workOrderRepository, atLeastOnce()).save(workOrderCaptor.capture());

        assertEquals(WorkorderStatus.COMPLETED, workOrderCaptor.getValue().getStatus());
    }

    @Test
    void testCompleteWorkorder_AlreadyCompleted_ThrowsException() {
        testWorkorder.setStatus(WorkorderStatus.COMPLETED);
        when(workOrderRepository.findById(testWorkorderId)).thenReturn(Optional.of(testWorkorder));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            stateMachine.completeWorkorder(testWorkorderId, userId, "Trying to complete again");
        });

        assertTrue(exception.getMessage().contains("already completed"));
        verify(workOrderRepository, never()).save(any(Workorder.class));
        verify(auditEventRepository, never()).save(any(AuditEvent.class));
    }

    @Test
    void testCompleteWorkorder_CancelledStatus_ThrowsException() {
        testWorkorder.setStatus(WorkorderStatus.CANCELLED);
        when(workOrderRepository.findById(testWorkorderId)).thenReturn(Optional.of(testWorkorder));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            stateMachine.completeWorkorder(testWorkorderId, userId, "Trying to complete cancelled order");
        });

        assertTrue(exception.getMessage().contains("cancelled"));
        verify(workOrderRepository, never()).save(any(Workorder.class));
    }

    @Test
    void testCompleteWorkorder_InvalidStatus_ThrowsException() {
        testWorkorder.setStatus(WorkorderStatus.DRAFT);
        when(workOrderRepository.findById(testWorkorderId)).thenReturn(Optional.of(testWorkorder));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            stateMachine.completeWorkorder(testWorkorderId, userId, "Invalid completion attempt");
        });

        assertTrue(exception.getMessage().contains("cannot be completed from status"));
        verify(workOrderRepository, never()).save(any(Workorder.class));
    }

    @Test
    void testCompleteWorkorder_WorkorderNotFound_ThrowsException() {
        UUID nonExistentId = UUID.fromString("550e8400-e29b-41d4-a716-446655440999");
        when(workOrderRepository.findById(nonExistentId)).thenReturn(Optional.empty());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            stateMachine.completeWorkorder(nonExistentId, userId, "Work order not found");
        });

        assertTrue(exception.getMessage().contains("Workorder not found"));
    }

    @Test
    void testCompleteWorkorder_AuditEventCreated() throws Exception {
        when(workOrderRepository.findById(testWorkorderId)).thenReturn(Optional.of(testWorkorder));
        stubCompletionPreconditionsPass();
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        stateMachine.completeWorkorder(testWorkorderId, userId, "Completion notes");

        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventRepository).save(auditCaptor.capture());

        AuditEvent auditEvent = auditCaptor.getValue();
        assertEquals("Workorder", auditEvent.getEntityType());
        assertEquals(testWorkorderId, auditEvent.getEntityId());
        assertEquals("StateTransition", auditEvent.getEventType());
        assertEquals(userId.toString(), auditEvent.getUserId());
        assertTrue(auditEvent.getDetails().contains("WORK_IN_PROGRESS"));
        assertTrue(auditEvent.getDetails().contains("COMPLETED"));
    }

    @Test
    void testCompleteWorkorder_EventEmission() throws Exception {
        testWorkorder.setStatus(WorkorderStatus.WORK_IN_PROGRESS);
        when(workOrderRepository.findById(testWorkorderId))
                .thenReturn(Optional.of(testWorkorder)) // First call by completeWorkorder in service
                .thenReturn(Optional.of(testWorkorder)); // Second call after completion
        stubCompletionPreconditionsPass();
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        // Simulate what happens when completeWorkorder is called
        WorkCompletedEvent event = workOrderService.completeWorkorder(testWorkorderId, userId, "Work done");

        // Verify the event was created correctly
        assertNotNull(event);
        assertEquals("WorkCompleted", event.getEventType());
        assertEquals("workexec", event.getSourceDomain());
        assertNotNull(event.getEventId());
        assertNotNull(event.getIdempotencyKey());
        assertTrue(event.getIdempotencyKey().contains(testWorkorderId.toString()));

        WorkCompletedEvent.WorkCompletedPayload payload = event.getPayload();
        assertEquals(testWorkorderId, payload.getWorkorderId());
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

    private void stubCompletionPreconditionsPass() {
        com.positivity.workorder.internal.entity.WorkorderService billableService = mock(
                com.positivity.workorder.internal.entity.WorkorderService.class);
        when(billableService.getStatus()).thenReturn(WorkorderItemStatus.COMPLETED);
        when(billableService.getLineTotal()).thenReturn(BigDecimal.TEN);

        when(changeRequestRepository.findByWorkorderIdAndStatus(testWorkorderId,
                ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW)).thenReturn(List.of());
        when(workorderServiceRepository.findByWorkOrder_Id(testWorkorderId)).thenReturn(List.of(billableService));
        when(workorderPartRepository.findByWorkorderId(testWorkorderId)).thenReturn(List.of());
        when(workorderPartRepository.findByWorkOrderService_WorkOrder_Id(testWorkorderId)).thenReturn(List.of());
        when(changeRequestService.canCloseWorkorder(testWorkorderId)).thenReturn(true);
    }
}
