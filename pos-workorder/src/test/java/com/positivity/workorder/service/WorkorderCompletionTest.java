package com.positivity.workorder.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.workorder.internal.entity.AuditEvent;
import com.positivity.workorder.internal.entity.ChangeRequest;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderSnapshot;
import com.positivity.workorder.internal.entity.WorkorderStateTransition;
import com.positivity.workorder.internal.enums.WorkorderItemStatus;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.event.WorkCompletedEvent;
import com.positivity.workorder.internal.repository.AuditEventRepository;
import com.positivity.workorder.internal.repository.ChangeRequestRepository;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.ExtCustomerPartyReplicaRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import com.positivity.workorder.internal.repository.WorkorderSnapshotRepository;
import com.positivity.workorder.internal.repository.WorkorderStateTransitionRepository;
import com.positivity.workorder.internal.service.PeopleAvailabilityLocalService;
import com.positivity.workorder.internal.service.WorkorderServiceImpl;
import com.positivity.workorder.internal.service.WorkorderStateMachine;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class WorkorderCompletionTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final UUID AUTH_USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440034");

    @Spy
    Clock clock = TEST_CLOCK;

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
    private ExtCustomerPartyReplicaRepository extCustomerPartyReplicaRepository;

    @Mock
    private PeopleAvailabilityLocalService peopleAvailabilityLocalService;

    @Mock
    private com.positivity.workorder.internal.service.WorkorderFactPublisher workorderFactPublisher;

    @InjectMocks
    private WorkorderStateMachine stateMachine;

    @Mock
    private com.positivity.workorder.internal.repository.WorkorderLaborEntryRepository workorderLaborEntryRepository;

    @Mock
    private org.springframework.context.ApplicationEventPublisher applicationEventPublisher;

    private WorkorderServiceImpl workOrderService;

    private Workorder testWorkorder;
    private String userId;
    private UUID testWorkorderId;
    private UUID testShopId;
    private UUID testVehicleId;
    private UUID testCustomerId;

    @BeforeEach
    void setUp() {
        // Mock authenticated user with gateway-injected details so snapshot/audit flows
        // have an actor
        TestingAuthenticationToken authentication =
                new TestingAuthenticationToken("test-user", "password", "ROLE_USER");
        authentication.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USER_ID,
                AUTH_USER_ID,
                GatewaySecurityConstants.DETAIL_USERNAME,
                "test-user"));
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        testWorkorderId = UUID.fromString("550e8400-e29b-41d4-a716-446655440030");
        testShopId = UUID.fromString("550e8400-e29b-41d4-a716-446655440031");
        testVehicleId = UUID.fromString("550e8400-e29b-41d4-a716-446655440032");
        testCustomerId = UUID.fromString("550e8400-e29b-41d4-a716-446655440033");
        userId = "system";
        testWorkorder = Workorder.builder()
                .id(testWorkorderId)
                .shopId(testShopId)
                .vehicleId(testVehicleId)
                .customerId(testCustomerId)
                .status(WorkorderStatus.WORK_IN_PROGRESS)
                .build();

        // Re-inject mocks for WorkorderService since it has WorkorderStateMachine as
        // dependency
        workOrderService = new WorkorderServiceImpl(
                TEST_CLOCK,
                workOrderRepository,
                estimateRepository,
                estimateItemRepository,
                workorderServiceRepository,
                workorderPartRepository,
                extCustomerPartyReplicaRepository,
                org.mockito.Mockito.mock(
                        com.positivity.workorder.internal.service.WorkorderFactPublisher.class),
                stateMachine,
                workorderLaborEntryRepository,
                applicationEventPublisher,
                auditEventRepository,
                idempotencyService,
                promotionValidationService,
                peopleAvailabilityLocalService);
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
    void completeWorkorder_publishesJobTimeFactsPerFinalizedLaborEntry() throws Exception {
        when(workOrderRepository.findById(testWorkorderId)).thenReturn(Optional.of(testWorkorder));
        stubCompletionPreconditionsPass();
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        com.positivity.workorder.internal.entity.WorkorderLaborEntry finalized =
                com.positivity.workorder.internal.entity.WorkorderLaborEntry.builder()
                        .id(UUID.fromString("550e8400-e29b-41d4-a716-446655440040"))
                        .workorder(testWorkorder)
                        .workorderService(org.mockito.Mockito.mock(
                                com.positivity.workorder.internal.entity.WorkorderServiceLine.class))
                        .technicianId(UUID.fromString("550e8400-e29b-41d4-a716-446655440041"))
                        .startTime(java.time.LocalDateTime.parse("2026-02-16T13:30:00"))
                        .createdBy("test-user")
                        .endTime(java.time.LocalDateTime.parse("2026-02-16T15:00:00"))
                        .hoursWorked(new java.math.BigDecimal("1.5"))
                        .build();
        com.positivity.workorder.internal.entity.WorkorderLaborEntry open =
                com.positivity.workorder.internal.entity.WorkorderLaborEntry.builder()
                        .id(UUID.fromString("550e8400-e29b-41d4-a716-446655440042"))
                        .workorder(testWorkorder)
                        .workorderService(org.mockito.Mockito.mock(
                                com.positivity.workorder.internal.entity.WorkorderServiceLine.class))
                        .technicianId(UUID.fromString("550e8400-e29b-41d4-a716-446655440041"))
                        .startTime(java.time.LocalDateTime.parse("2026-02-16T16:00:00"))
                        .createdBy("test-user")
                        .hoursWorked(java.math.BigDecimal.ZERO)
                        .build();
        when(workorderLaborEntryRepository.findByWorkorder_IdOrderByStartTimeDesc(testWorkorderId))
                .thenReturn(java.util.List.of(finalized, open));

        workOrderService.completeWorkorder(testWorkorderId, userId, "Work completed successfully");

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(applicationEventPublisher).publishEvent(published.capture());
        com.positivity.domainevents.workorder.JobTimeRecordedV1 fact =
                (com.positivity.domainevents.workorder.JobTimeRecordedV1) published.getValue();
        assertEquals(finalized.getId(), fact.laborEntryId());
        assertEquals(testShopId, fact.locationId());
        assertEquals(90, fact.minutes());
        assertEquals(java.time.Instant.parse("2026-02-16T15:00:00Z"), fact.endAtUtc());
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
        assertEquals(userId, auditEvent.getUserId());
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
        com.positivity.workorder.internal.entity.WorkorderServiceLine billableService =
                mock(com.positivity.workorder.internal.entity.WorkorderServiceLine.class);
        when(billableService.getStatus()).thenReturn(WorkorderItemStatus.COMPLETED);
        when(billableService.getLineTotal()).thenReturn(BigDecimal.TEN);

        when(changeRequestRepository.findByWorkorder_IdAndStatus(
                        testWorkorderId, ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW))
                .thenReturn(List.of());
        when(workorderServiceRepository.findByWorkOrder_Id(testWorkorderId)).thenReturn(List.of(billableService));
        when(workorderPartRepository.findByWorkorderId(testWorkorderId)).thenReturn(List.of());
        when(workorderPartRepository.findByWorkOrderService_WorkOrder_Id(testWorkorderId))
                .thenReturn(List.of());
        when(changeRequestService.canCloseWorkorder(testWorkorderId)).thenReturn(true);
    }
}
