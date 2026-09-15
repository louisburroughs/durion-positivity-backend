package com.positivity.workorder.internal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.workorder.internal.entity.ChangeRequest;
import com.positivity.workorder.internal.entity.TechnicianAssignment;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderSnapshot;
import com.positivity.workorder.internal.entity.WorkorderStateTransition;
import com.positivity.workorder.internal.enums.ResourceType;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.ChangeRequestRepository;
import com.positivity.workorder.internal.repository.TechnicianAssignmentRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderSnapshotRepository;
import com.positivity.workorder.internal.repository.WorkorderStateTransitionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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
class WorkorderStateMachineTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final UUID AUTH_USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440045");

    @Spy
    Clock clock = TEST_CLOCK;

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

    @org.mockito.Mock
    private com.positivity.workorder.internal.service.WorkorderFactPublisher workorderFactPublisher;

    @org.mockito.Mock
    private com.positivity.workorder.internal.service.ServiceCompletionFactPublisher serviceCompletionFactPublisher;

    @Mock
    private com.positivity.workorder.internal.service.FleetAuthorizationService fleetAuthorizationService;

    @Mock
    private com.positivity.workorder.internal.repository.AuditEventRepository auditEventRepository;

    @Mock
    private TechnicianAssignmentRepository technicianAssignmentRepository;

    @InjectMocks
    private WorkorderStateMachine stateMachine;

    private Workorder testWorkorder;
    private String userId;
    private UUID testWorkorderId;
    private UUID testChangeRequestId;
    private UUID testShopId;
    private UUID testVehicleId;
    private UUID testCustomerId;

    @BeforeEach
    void setUp() {
        TestingAuthenticationToken authentication =
                new TestingAuthenticationToken("test-user", "password", "ROLE_USER");
        authentication.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USER_ID,
                AUTH_USER_ID,
                GatewaySecurityConstants.DETAIL_USERNAME,
                "test-user"));
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        testWorkorderId = UUID.fromString("550e8400-e29b-41d4-a716-446655440040");
        testChangeRequestId = UUID.fromString("550e8400-e29b-41d4-a716-446655440041");
        testShopId = UUID.fromString("550e8400-e29b-41d4-a716-446655440042");
        testVehicleId = UUID.fromString("550e8400-e29b-41d4-a716-446655440043");
        testCustomerId = UUID.fromString("550e8400-e29b-41d4-a716-446655440044");
        userId = "system";
        testWorkorder = Workorder.builder()
                .id(testWorkorderId)
                .shopId(testShopId)
                .vehicleId(testVehicleId)
                .customerId(testCustomerId)
                .status(WorkorderStatus.APPROVED)
                .build();
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    /** A minimal current technician assignment for {@code testWorkorderId} (#2011). */
    private TechnicianAssignment currentTechnicianAssignment() {
        return TechnicianAssignment.builder()
                .id(1L)
                .workorder(new Workorder(testWorkorderId))
                .technicianId(UUID.fromString("550e8400-e29b-41d4-a716-446655440061"))
                .assignedBy("dispatch")
                .assignedAt(LocalDateTime.now(TEST_CLOCK))
                .current(true)
                .build();
    }

    @Test
    @org.junit.jupiter.api.DisplayName(
            "reopenCompletedWorkorder records a COMPLETED->COMPLETED marker transition (#1594 E6)")
    void reopenCompletedWorkorder_recordsMarkerTransition() throws Exception {
        Workorder completedWorkorder = Workorder.builder()
                .id(testWorkorderId)
                .shopId(testShopId)
                .vehicleId(testVehicleId)
                .customerId(testCustomerId)
                .status(WorkorderStatus.COMPLETED)
                .build();
        when(workorderRepository.findById(testWorkorderId)).thenReturn(Optional.of(completedWorkorder));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        stateMachine.reopenCompletedWorkorder(testWorkorderId, userId, "Customer reported a rattle after pickup");

        // Status itself never leaves COMPLETED (isLocked() semantics) — only the isReopened flag
        // moves — so this cannot go through transitionWorkorder()/canTransitionTo(); it is recorded
        // directly, which is exactly what makes it distinguishable from nothing at all.
        assertEquals(WorkorderStatus.COMPLETED, completedWorkorder.getStatus());

        ArgumentCaptor<WorkorderStateTransition> captor = ArgumentCaptor.forClass(WorkorderStateTransition.class);
        verify(transitionRepository).save(captor.capture());
        WorkorderStateTransition recorded = captor.getValue();
        assertEquals(WorkorderStatus.COMPLETED, recorded.getFromStatus());
        assertEquals(WorkorderStatus.COMPLETED, recorded.getToStatus());
        assertEquals(userId, recorded.getTransitionedBy());
        assertTrue(recorded.getReason().startsWith("Reopened: "));
        assertTrue(recorded.getReason().contains("Customer reported a rattle after pickup"));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("A second reopen call records a second, independent marker transition")
    void reopenCompletedWorkorder_calledTwice_recordsTwoMarkerTransitions() throws Exception {
        Workorder completedWorkorder = Workorder.builder()
                .id(testWorkorderId)
                .shopId(testShopId)
                .vehicleId(testVehicleId)
                .customerId(testCustomerId)
                .status(WorkorderStatus.COMPLETED)
                .build();
        when(workorderRepository.findById(testWorkorderId)).thenReturn(Optional.of(completedWorkorder));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        stateMachine.reopenCompletedWorkorder(testWorkorderId, userId, "first reopen");
        stateMachine.reopenCompletedWorkorder(testWorkorderId, userId, "second reopen");

        verify(transitionRepository, org.mockito.Mockito.times(2)).save(any(WorkorderStateTransition.class));
    }

    @Test
    void testStartWorkorder_Success() throws Exception {
        // #2011: work starts only from ASSIGNED, and the gate checks the pair behind the status
        // rather than the status alone — so the fixture has to hold both halves, not just say so.
        givenReadyToBeWorked();
        when(workorderRepository.findById(testWorkorderId)).thenReturn(Optional.of(testWorkorder));
        when(changeRequestRepository.findByWorkorder_IdAndStatus(
                        testWorkorderId, ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW))
                .thenReturn(Collections.emptyList());
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        stateMachine.startWorkorder(testWorkorderId, userId, "Starting work");

        verify(workorderRepository, atLeastOnce()).save(any(Workorder.class));
        verify(snapshotRepository).save(any(WorkorderSnapshot.class));
        verify(transitionRepository).save(any(WorkorderStateTransition.class));
    }

    /**
     * #2011: ASSIGNED and actually holding both halves — a technician and a bay. The status alone is
     * not enough for the start gate, which is the point of {@link #testStartWorkorder_StaleAssignedRowWithoutThePairIsRefused()}.
     */
    private void givenReadyToBeWorked() {
        testWorkorder.setStatus(WorkorderStatus.ASSIGNED);
        testWorkorder.setResourceType(ResourceType.BAY);
        testWorkorder.setResourceId(UUID.fromString("550e8400-e29b-41d4-a716-446655440061"));
        when(technicianAssignmentRepository.findByWorkorder_IdAndCurrentTrue(testWorkorderId))
                .thenReturn(Optional.of(currentTechnicianAssignment()));
    }

    /**
     * #2011: the startup migration is disable-able by property, a tenant's pass can fail and a
     * restored snapshot can predate it, so a row saying ASSIGNED without a technician or a position
     * can still reach the start gate. It must not be able to begin work on the strength of the
     * status alone.
     */
    @Test
    void testStartWorkorder_StaleAssignedRowWithoutThePairIsRefused() {
        testWorkorder.setStatus(WorkorderStatus.ASSIGNED);
        testWorkorder.setResourceType(null);
        testWorkorder.setResourceId(null);
        when(technicianAssignmentRepository.findByWorkorder_IdAndCurrentTrue(testWorkorderId))
                .thenReturn(Optional.empty());
        when(workorderRepository.findById(testWorkorderId)).thenReturn(Optional.of(testWorkorder));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class, () -> stateMachine.startWorkorder(testWorkorderId, userId, "Starting"));

        assertTrue(exception.getMessage().contains("a technician"));
        assertTrue(exception.getMessage().contains("a bay or mobile unit"));
        verify(snapshotRepository, org.mockito.Mockito.never()).save(any(WorkorderSnapshot.class));
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
        // #2011: has to clear the ASSIGNED gate first to reach the pending-change-request check.
        givenReadyToBeWorked();
        when(workorderRepository.findById(testWorkorderId)).thenReturn(Optional.of(testWorkorder));
        ChangeRequest pendingRequest = ChangeRequest.builder()
                .id(testChangeRequestId)
                .workorder(testWorkorder)
                .status(ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW)
                .build();
        when(changeRequestRepository.findByWorkorder_IdAndStatus(
                        testWorkorderId, ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW))
                .thenReturn(List.of(pendingRequest));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            stateMachine.startWorkorder(testWorkorderId, userId, "Starting work");
        });

        assertTrue(exception.getMessage().contains("pending change request"));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("#2011: an APPROVED workorder missing both halves names them both")
    void testStartWorkorder_ApprovedMissingBothHalves_NamesBothInMessage() {
        testWorkorder.setStatus(WorkorderStatus.APPROVED);
        when(workorderRepository.findById(testWorkorderId)).thenReturn(Optional.of(testWorkorder));
        when(technicianAssignmentRepository.findByWorkorder_IdAndCurrentTrue(testWorkorderId))
                .thenReturn(Optional.empty());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> stateMachine.startWorkorder(testWorkorderId, userId, "Starting work"));

        assertTrue(exception.getMessage().contains("a technician"));
        assertTrue(exception.getMessage().contains("a bay or mobile unit"));
    }

    @Test
    @org.junit.jupiter.api.DisplayName(
            "#2011: an APPROVED workorder with a technician but no position names only the missing position")
    void testStartWorkorder_ApprovedWithTechnicianOnly_NamesOnlyPositionInMessage() {
        testWorkorder.setStatus(WorkorderStatus.APPROVED);
        when(workorderRepository.findById(testWorkorderId)).thenReturn(Optional.of(testWorkorder));
        when(technicianAssignmentRepository.findByWorkorder_IdAndCurrentTrue(testWorkorderId))
                .thenReturn(Optional.of(currentTechnicianAssignment()));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> stateMachine.startWorkorder(testWorkorderId, userId, "Starting work"));

        assertFalse(exception.getMessage().contains("missing a technician"));
        assertTrue(exception.getMessage().contains("a bay or mobile unit"));
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
            stateMachine.transitionWorkorder(
                    testWorkorderId, WorkorderStatus.WORK_IN_PROGRESS, userId, "Invalid transition");
        });

        assertTrue(exception.getMessage().contains("Invalid state transition"));
    }

    @Test
    void testCaptureSnapshot_Success() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"id\":1,\"status\":\"APPROVED\"}");

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
        when(transitionRepository.findByWorkorder_IdOrderByTransitionedAtDesc(testWorkorderId))
                .thenReturn(expectedHistory);

        List<WorkorderStateTransition> result = stateMachine.getTransitionHistory(testWorkorderId);

        assertEquals(expectedHistory, result);
        verify(transitionRepository).findByWorkorder_IdOrderByTransitionedAtDesc(testWorkorderId);
    }

    @Test
    void testGetSnapshotHistory() {
        List<WorkorderSnapshot> expectedSnapshots = Collections.emptyList();
        when(snapshotRepository.findByWorkorder_IdOrderByCapturedAtDesc(testWorkorderId))
                .thenReturn(expectedSnapshots);

        List<WorkorderSnapshot> result = stateMachine.getSnapshotHistory(testWorkorderId);

        assertEquals(expectedSnapshots, result);
        verify(snapshotRepository).findByWorkorder_IdOrderByCapturedAtDesc(testWorkorderId);
    }

    @Test
    void testWorkorderStatus_AllowedTransitions() {
        assertTrue(WorkorderStatus.APPROVED.canTransitionTo(WorkorderStatus.ASSIGNED));
        assertTrue(WorkorderStatus.ASSIGNED.canTransitionTo(WorkorderStatus.WORK_IN_PROGRESS));
        // #2010/#2011: releasing the technician, or giving up the bay, walks an ASSIGNED workorder
        // back to APPROVED — the reverse of the APPROVED -> ASSIGNED transition above.
        assertTrue(WorkorderStatus.ASSIGNED.canTransitionTo(WorkorderStatus.APPROVED));
        assertTrue(WorkorderStatus.WORK_IN_PROGRESS.canTransitionTo(WorkorderStatus.AWAITING_PARTS));
        assertTrue(WorkorderStatus.WORK_IN_PROGRESS.canTransitionTo(WorkorderStatus.AWAITING_APPROVAL));

        assertFalse(WorkorderStatus.DRAFT.canTransitionTo(WorkorderStatus.WORK_IN_PROGRESS));
        assertFalse(WorkorderStatus.COMPLETED.canTransitionTo(WorkorderStatus.WORK_IN_PROGRESS));
        // #2011: APPROVED no longer goes straight to WORK_IN_PROGRESS — a workorder must be ASSIGNED
        // (technician + bay/mobile unit) before work can start.
        assertFalse(WorkorderStatus.APPROVED.canTransitionTo(WorkorderStatus.WORK_IN_PROGRESS));
    }

    @Test
    void testWorkorderStatus_StartEligibleStatuses() {
        // #2011: ASSIGNED alone — an APPROVED workorder is not yet ready to be worked.
        assertEquals(Set.of(WorkorderStatus.ASSIGNED), WorkorderStatus.getStartEligibleStatuses());
        assertFalse(WorkorderStatus.getStartEligibleStatuses().contains(WorkorderStatus.APPROVED));
        assertFalse(WorkorderStatus.getStartEligibleStatuses().contains(WorkorderStatus.DRAFT));
    }

    @Test
    void testWorkorderStatus_InProgressSubStatuses() {
        assertTrue(WorkorderStatus.getInProgressSubStatuses().contains(WorkorderStatus.WORK_IN_PROGRESS));
        assertTrue(WorkorderStatus.getInProgressSubStatuses().contains(WorkorderStatus.AWAITING_PARTS));
        assertTrue(WorkorderStatus.getInProgressSubStatuses().contains(WorkorderStatus.AWAITING_APPROVAL));
        assertFalse(WorkorderStatus.getInProgressSubStatuses().contains(WorkorderStatus.COMPLETED));
    }

    // -----------------------------------------------------------------------
    // Odoo-parity E3 (#1084): finalize-back to workorder on order settlement
    // -----------------------------------------------------------------------

    @Test
    void finalizeFromOrderSettlement_unknownWorkorder_isNoOp() {
        UUID orderId = UUID.fromString("550e8400-e29b-41d4-a716-4466554400a1");
        when(workorderRepository.findById(testWorkorderId)).thenReturn(Optional.empty());

        stateMachine.finalizeFromOrderSettlement(testWorkorderId, orderId, "pos-order");

        verify(workorderRepository, org.mockito.Mockito.never()).save(any(Workorder.class));
    }

    @Test
    void finalizeFromOrderSettlement_alreadyCompleted_isNoOp() {
        UUID orderId = UUID.fromString("550e8400-e29b-41d4-a716-4466554400a2");
        testWorkorder.setStatus(WorkorderStatus.COMPLETED);
        when(workorderRepository.findById(testWorkorderId)).thenReturn(Optional.of(testWorkorder));

        stateMachine.finalizeFromOrderSettlement(testWorkorderId, orderId, "pos-order");

        verify(workorderRepository, org.mockito.Mockito.never()).save(any(Workorder.class));
        verify(transitionRepository, org.mockito.Mockito.never()).save(any(WorkorderStateTransition.class));
    }

    @Test
    void finalizeFromOrderSettlement_nonEligibleStatus_isNoOp() {
        UUID orderId = UUID.fromString("550e8400-e29b-41d4-a716-4466554400a3");
        testWorkorder.setStatus(WorkorderStatus.APPROVED); // not completion-eligible
        when(workorderRepository.findById(testWorkorderId)).thenReturn(Optional.of(testWorkorder));

        stateMachine.finalizeFromOrderSettlement(testWorkorderId, orderId, "pos-order");

        verify(workorderRepository, org.mockito.Mockito.never()).save(any(Workorder.class));
        verify(transitionRepository, org.mockito.Mockito.never()).save(any(WorkorderStateTransition.class));
    }

    // -----------------------------------------------------------------------
    // #2011: reconcileAssigned re-decides ASSIGNED vs APPROVED after either half of the
    // technician/position pair changes.
    // -----------------------------------------------------------------------

    @Nested
    @org.junit.jupiter.api.DisplayName("reconcileAssigned")
    class ReconcileAssigned {

        private static final UUID POSITION_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440060");

        private Workorder workorderWith(WorkorderStatus status, ResourceType resourceType, UUID resourceId) {
            return Workorder.builder()
                    .id(testWorkorderId)
                    .shopId(testShopId)
                    .vehicleId(testVehicleId)
                    .customerId(testCustomerId)
                    .status(status)
                    .resourceType(resourceType)
                    .resourceId(resourceId)
                    .build();
        }

        private void givenCurrentTechnician(boolean present) {
            Optional<TechnicianAssignment> assignment =
                    present ? Optional.of(currentTechnicianAssignment()) : Optional.empty();
            when(technicianAssignmentRepository.findByWorkorder_IdAndCurrentTrue(testWorkorderId))
                    .thenReturn(assignment);
        }

        @Test
        @org.junit.jupiter.api.DisplayName("APPROVED with a technician and a bay transitions to ASSIGNED")
        void approvedWithTechnicianAndBayTransitionsToAssigned() {
            Workorder workorder = workorderWith(WorkorderStatus.APPROVED, ResourceType.BAY, POSITION_ID);
            when(workorderRepository.findByIdForUpdate(testWorkorderId)).thenReturn(Optional.of(workorder));
            // Lenient: only a reconciliation that actually transitions reaches transitionWorkorder's
            // own read, and half of these cases deliberately decide to do nothing.
            org.mockito.Mockito.lenient()
                    .when(workorderRepository.findById(testWorkorderId))
                    .thenReturn(Optional.of(workorder));
            givenCurrentTechnician(true);

            stateMachine.reconcileAssigned(testWorkorderId, userId, "Position assigned");

            assertEquals(WorkorderStatus.ASSIGNED, workorder.getStatus());
            verify(workorderRepository).save(workorder);
        }

        @Test
        @org.junit.jupiter.api.DisplayName("APPROVED with a technician and a mobile unit transitions to ASSIGNED")
        void approvedWithTechnicianAndMobileUnitTransitionsToAssigned() {
            Workorder workorder = workorderWith(WorkorderStatus.APPROVED, ResourceType.MOBILE_UNIT, POSITION_ID);
            when(workorderRepository.findByIdForUpdate(testWorkorderId)).thenReturn(Optional.of(workorder));
            // Lenient: only a reconciliation that actually transitions reaches transitionWorkorder's
            // own read, and half of these cases deliberately decide to do nothing.
            org.mockito.Mockito.lenient()
                    .when(workorderRepository.findById(testWorkorderId))
                    .thenReturn(Optional.of(workorder));
            givenCurrentTechnician(true);

            stateMachine.reconcileAssigned(testWorkorderId, userId, "Position assigned");

            assertEquals(WorkorderStatus.ASSIGNED, workorder.getStatus());
        }

        @Test
        @org.junit.jupiter.api.DisplayName("APPROVED with a technician but no position does not transition")
        void approvedWithTechnicianNoPositionDoesNotTransition() {
            Workorder workorder = workorderWith(WorkorderStatus.APPROVED, null, null);
            when(workorderRepository.findByIdForUpdate(testWorkorderId)).thenReturn(Optional.of(workorder));
            // Lenient: only a reconciliation that actually transitions reaches transitionWorkorder's
            // own read, and half of these cases deliberately decide to do nothing.
            org.mockito.Mockito.lenient()
                    .when(workorderRepository.findById(testWorkorderId))
                    .thenReturn(Optional.of(workorder));
            givenCurrentTechnician(true);

            stateMachine.reconcileAssigned(testWorkorderId, userId, "Technician assigned");

            assertEquals(WorkorderStatus.APPROVED, workorder.getStatus());
            verify(workorderRepository, org.mockito.Mockito.never()).save(any(Workorder.class));
            verify(transitionRepository, org.mockito.Mockito.never()).save(any(WorkorderStateTransition.class));
        }

        @Test
        @org.junit.jupiter.api.DisplayName(
                "#1984: APPROVED with a technician on a HOLD does not transition — a hold is not a place work happens")
        void approvedWithTechnicianOnHoldDoesNotTransition() {
            Workorder workorder = workorderWith(WorkorderStatus.APPROVED, ResourceType.HOLD, POSITION_ID);
            when(workorderRepository.findByIdForUpdate(testWorkorderId)).thenReturn(Optional.of(workorder));
            // Lenient: only a reconciliation that actually transitions reaches transitionWorkorder's
            // own read, and half of these cases deliberately decide to do nothing.
            org.mockito.Mockito.lenient()
                    .when(workorderRepository.findById(testWorkorderId))
                    .thenReturn(Optional.of(workorder));
            givenCurrentTechnician(true);

            stateMachine.reconcileAssigned(testWorkorderId, userId, "Parked");

            assertEquals(WorkorderStatus.APPROVED, workorder.getStatus());
            verify(transitionRepository, org.mockito.Mockito.never()).save(any(WorkorderStateTransition.class));
        }

        @Test
        @org.junit.jupiter.api.DisplayName("APPROVED with a position but no technician does not transition")
        void approvedWithPositionNoTechnicianDoesNotTransition() {
            Workorder workorder = workorderWith(WorkorderStatus.APPROVED, ResourceType.BAY, POSITION_ID);
            when(workorderRepository.findByIdForUpdate(testWorkorderId)).thenReturn(Optional.of(workorder));
            // Lenient: only a reconciliation that actually transitions reaches transitionWorkorder's
            // own read, and half of these cases deliberately decide to do nothing.
            org.mockito.Mockito.lenient()
                    .when(workorderRepository.findById(testWorkorderId))
                    .thenReturn(Optional.of(workorder));
            givenCurrentTechnician(false);

            stateMachine.reconcileAssigned(testWorkorderId, userId, "Position assigned");

            assertEquals(WorkorderStatus.APPROVED, workorder.getStatus());
            verify(transitionRepository, org.mockito.Mockito.never()).save(any(WorkorderStateTransition.class));
        }

        @Test
        @org.junit.jupiter.api.DisplayName(
                "#2010: ASSIGNED reverts to APPROVED when the technician is released, and records history")
        void assignedRevertsToApprovedWhenTechnicianReleased() {
            Workorder workorder = workorderWith(WorkorderStatus.ASSIGNED, ResourceType.BAY, POSITION_ID);
            when(workorderRepository.findByIdForUpdate(testWorkorderId)).thenReturn(Optional.of(workorder));
            // Lenient: only a reconciliation that actually transitions reaches transitionWorkorder's
            // own read, and half of these cases deliberately decide to do nothing.
            org.mockito.Mockito.lenient()
                    .when(workorderRepository.findById(testWorkorderId))
                    .thenReturn(Optional.of(workorder));
            givenCurrentTechnician(false);

            stateMachine.reconcileAssigned(testWorkorderId, userId, "Technician released");

            assertEquals(WorkorderStatus.APPROVED, workorder.getStatus());
            ArgumentCaptor<WorkorderStateTransition> captor = ArgumentCaptor.forClass(WorkorderStateTransition.class);
            verify(transitionRepository).save(captor.capture());
            WorkorderStateTransition recorded = captor.getValue();
            assertEquals(WorkorderStatus.ASSIGNED, recorded.getFromStatus());
            assertEquals(WorkorderStatus.APPROVED, recorded.getToStatus());
            assertEquals("Technician released", recorded.getReason());
        }

        @Test
        @org.junit.jupiter.api.DisplayName("ASSIGNED reverts to APPROVED when the position is cleared")
        void assignedRevertsToApprovedWhenPositionCleared() {
            Workorder workorder = workorderWith(WorkorderStatus.ASSIGNED, null, null);
            when(workorderRepository.findByIdForUpdate(testWorkorderId)).thenReturn(Optional.of(workorder));
            // Lenient: only a reconciliation that actually transitions reaches transitionWorkorder's
            // own read, and half of these cases deliberately decide to do nothing.
            org.mockito.Mockito.lenient()
                    .when(workorderRepository.findById(testWorkorderId))
                    .thenReturn(Optional.of(workorder));
            givenCurrentTechnician(true);

            stateMachine.reconcileAssigned(testWorkorderId, userId, "Position released");

            assertEquals(WorkorderStatus.APPROVED, workorder.getStatus());
        }

        @Test
        @org.junit.jupiter.api.DisplayName("ASSIGNED with both halves is idempotent: no transition, no history row")
        void assignedWithBothHalvesIsIdempotent() {
            Workorder workorder = workorderWith(WorkorderStatus.ASSIGNED, ResourceType.BAY, POSITION_ID);
            when(workorderRepository.findByIdForUpdate(testWorkorderId)).thenReturn(Optional.of(workorder));
            // Lenient: only a reconciliation that actually transitions reaches transitionWorkorder's
            // own read, and half of these cases deliberately decide to do nothing.
            org.mockito.Mockito.lenient()
                    .when(workorderRepository.findById(testWorkorderId))
                    .thenReturn(Optional.of(workorder));
            givenCurrentTechnician(true);

            stateMachine.reconcileAssigned(testWorkorderId, userId, "Assignment re-asserted");

            assertEquals(WorkorderStatus.ASSIGNED, workorder.getStatus());
            verify(workorderRepository, org.mockito.Mockito.never()).save(any(Workorder.class));
            verify(transitionRepository, org.mockito.Mockito.never()).save(any(WorkorderStateTransition.class));
        }

        @Test
        @org.junit.jupiter.api.DisplayName("WORK_IN_PROGRESS is untouched whichever half is missing")
        void workInProgressIsUntouched() {
            Workorder workorder = workorderWith(WorkorderStatus.WORK_IN_PROGRESS, null, null);
            when(workorderRepository.findByIdForUpdate(testWorkorderId)).thenReturn(Optional.of(workorder));
            // Lenient: only a reconciliation that actually transitions reaches transitionWorkorder's
            // own read, and half of these cases deliberately decide to do nothing.
            org.mockito.Mockito.lenient()
                    .when(workorderRepository.findById(testWorkorderId))
                    .thenReturn(Optional.of(workorder));

            stateMachine.reconcileAssigned(testWorkorderId, userId, "Technician released");

            assertEquals(WorkorderStatus.WORK_IN_PROGRESS, workorder.getStatus());
            verify(workorderRepository, org.mockito.Mockito.never()).save(any(Workorder.class));
            verify(technicianAssignmentRepository, org.mockito.Mockito.never()).findByWorkorder_IdAndCurrentTrue(any());
        }

        @Test
        @org.junit.jupiter.api.DisplayName("an unknown workorder id is a no-op, not an exception")
        void unknownWorkorderIsNoOp() {
            when(workorderRepository.findByIdForUpdate(testWorkorderId)).thenReturn(Optional.empty());

            stateMachine.reconcileAssigned(testWorkorderId, userId, "irrelevant");

            verify(transitionRepository, org.mockito.Mockito.never()).save(any(WorkorderStateTransition.class));
        }
    }
}
