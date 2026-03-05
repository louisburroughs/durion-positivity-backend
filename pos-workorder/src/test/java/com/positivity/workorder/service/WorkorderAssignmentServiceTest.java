package com.positivity.workorder.service;

import java.time.ZoneOffset;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import com.positivity.workorder.internal.dto.AssignmentUpdatePayload;
import com.positivity.workorder.internal.dto.AssignmentUpdatedEvent;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.exception.WorkorderNotFoundException;
import com.positivity.workorder.internal.repository.AuditEventRepository;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import com.positivity.workorder.internal.service.WorkorderServiceImpl;
import com.positivity.workorder.internal.service.WorkorderStateMachine;

/**
 * Unit tests for CAP-140 Story #64: Propagate Assignment Context to Workorder.
 *
 * Validates that handleAssignmentUpdated correctly updates workorder location,
 * resource, and mechanic fields for mutable statuses (AC1/AC3), silently skips
 * locked/in-progress statuses without saving (AC2), throws
 * WorkorderNotFoundException
 * for unknown workorder IDs (AC4), throws IllegalArgumentException for null
 * events
 * (AC5), and records a correctly-populated AuditEvent on success (AC6).
 *
 * Issue: CAP-140
 */
@ExtendWith(MockitoExtension.class)
class WorkorderAssignmentServiceTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);


    @Mock
    private WorkorderRepository workorderRepository;

    @Mock
    private EstimateRepository estimateRepository;

    @Mock
    private EstimateItemRepository estimateItemRepository;

    @Mock
    private WorkorderServiceRepository workorderServiceRepository;

    @Mock
    private WorkorderPartRepository workorderPartRepository;

    @Mock
    private RestClient restClient;

    @Mock
    private WorkorderStateMachine stateMachine;

    @Mock
    private AuditEventRepository auditEventRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private PromotionValidationService promotionValidationService;

    @InjectMocks
    private WorkorderServiceImpl workorderService;

    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID RESOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID MECHANIC_ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID MECHANIC_ID_2 = UUID.fromString("00000000-0000-0000-0000-000000000005");

    private AssignmentUpdatedEvent validEvent() {
        return AssignmentUpdatedEvent.builder()
                .eventId(UUID.randomUUID())
                .timestamp(Instant.now(TEST_CLOCK))
                .workorderId(WORKORDER_ID)
                .payload(AssignmentUpdatePayload.builder()
                        .locationId(LOCATION_ID)
                        .resourceId(RESOURCE_ID)
                        .mechanicIds(List.of(MECHANIC_ID_1, MECHANIC_ID_2))
                        .build())
                .build();
    }

    private Workorder workorderWithStatus(WorkorderStatus status) {
        return Workorder.builder()
                .id(WORKORDER_ID)
                .status(status)
                .build();
    }

    // Issue CAP-140: AC1 — DRAFT workorder receives full assignment context update

    /**
     * AC1: A workorder in DRAFT status must have locationId, resourceId, and
     * mechanicIds updated and persisted when a valid AssignmentUpdatedEvent
     * arrives.
     */
    @Test
    void whenHandleAssignmentUpdated_withDraftWorkorder_thenUpdatesFieldsAndSaves() {
        Workorder workorder = workorderWithStatus(WorkorderStatus.DRAFT);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        workorderService.handleAssignmentUpdated(validEvent());

        assertThat(workorder.getLocationId()).isEqualTo(LOCATION_ID);
        assertThat(workorder.getResourceId()).isEqualTo(RESOURCE_ID);
        assertThat(workorder.getMechanicIds()).isNotNull()
                .contains(MECHANIC_ID_1.toString())
                .contains(MECHANIC_ID_2.toString());
        verify(workorderRepository).save(workorder);
    }

    // Issue CAP-140: AC1 — APPROVED workorder receives full assignment context
    // update

    /**
     * AC1: A workorder in APPROVED status must have its assignment fields updated
     * and saved.
     */
    @Test
    void whenHandleAssignmentUpdated_withApprovedWorkorder_thenUpdatesFieldsAndSaves() {
        Workorder workorder = workorderWithStatus(WorkorderStatus.APPROVED);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        workorderService.handleAssignmentUpdated(validEvent());

        assertThat(workorder.getLocationId()).isEqualTo(LOCATION_ID);
        assertThat(workorder.getResourceId()).isEqualTo(RESOURCE_ID);
        verify(workorderRepository).save(workorder);
    }

    // Issue CAP-140: AC1 — ASSIGNED workorder receives full assignment context
    // update

    /**
     * AC1: A workorder in ASSIGNED status must have its assignment fields updated
     * and saved.
     */
    @Test
    void whenHandleAssignmentUpdated_withAssignedWorkorder_thenUpdatesFieldsAndSaves() {
        Workorder workorder = workorderWithStatus(WorkorderStatus.ASSIGNED);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        workorderService.handleAssignmentUpdated(validEvent());

        assertThat(workorder.getLocationId()).isEqualTo(LOCATION_ID);
        assertThat(workorder.getResourceId()).isEqualTo(RESOURCE_ID);
        verify(workorderRepository).save(workorder);
    }

    // Issue CAP-140: AC2 — locked/in-progress statuses are silently skipped

    /**
     * AC2: A workorder in WORK_IN_PROGRESS status must not be updated or saved;
     * the call must complete without throwing an exception.
     */
    @Test
    void whenHandleAssignmentUpdated_withWorkInProgressWorkorder_thenSkipsUpdateAndDoesNotSave() {
        Workorder workorder = workorderWithStatus(WorkorderStatus.WORK_IN_PROGRESS);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        assertThatNoException().isThrownBy(
                () -> workorderService.handleAssignmentUpdated(validEvent()));

        verify(workorderRepository, never()).save(any());
    }

    /**
     * AC2: A workorder in AWAITING_PARTS status must not be updated or saved.
     */
    @Test
    void whenHandleAssignmentUpdated_withAwaitingPartsWorkorder_thenSkipsUpdateAndDoesNotSave() {
        Workorder workorder = workorderWithStatus(WorkorderStatus.AWAITING_PARTS);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        assertThatNoException().isThrownBy(
                () -> workorderService.handleAssignmentUpdated(validEvent()));

        verify(workorderRepository, never()).save(any());
    }

    /**
     * AC2: A workorder in COMPLETED status must not be updated or saved.
     */
    @Test
    void whenHandleAssignmentUpdated_withCompletedWorkorder_thenSkipsUpdateAndDoesNotSave() {
        Workorder workorder = workorderWithStatus(WorkorderStatus.COMPLETED);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        assertThatNoException().isThrownBy(
                () -> workorderService.handleAssignmentUpdated(validEvent()));

        verify(workorderRepository, never()).save(any());
    }

    /**
     * AC2: A workorder in CANCELLED status must not be updated or saved.
     */
    @Test
    void whenHandleAssignmentUpdated_withCancelledWorkorder_thenSkipsUpdateAndDoesNotSave() {
        Workorder workorder = workorderWithStatus(WorkorderStatus.CANCELLED);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        assertThatNoException().isThrownBy(
                () -> workorderService.handleAssignmentUpdated(validEvent()));

        verify(workorderRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC2: AWAITING_APPROVAL workorder skips update and does not save")
    void whenHandleAssignmentUpdated_withAwaitingApprovalWorkorder_thenSkipsUpdateAndDoesNotSave() {
        // Given
        Workorder workorder = Workorder.builder()
                .id(WORKORDER_ID)
                .status(WorkorderStatus.AWAITING_APPROVAL)
                .build();
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        // When
        workorderService.handleAssignmentUpdated(validEvent());

        // Then
        verify(workorderRepository, never()).save(any(Workorder.class));
        verify(auditEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC2: READY_FOR_PICKUP workorder skips update and does not save")
    void whenHandleAssignmentUpdated_withReadyForPickupWorkorder_thenSkipsUpdateAndDoesNotSave() {
        // Given
        Workorder workorder = Workorder.builder()
                .id(WORKORDER_ID)
                .status(WorkorderStatus.READY_FOR_PICKUP)
                .build();
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        // When
        workorderService.handleAssignmentUpdated(validEvent());

        // Then
        verify(workorderRepository, never()).save(any(Workorder.class));
        verify(auditEventRepository, never()).save(any());
    }

    // Issue CAP-140: AC3 — full replace semantics (prior value is discarded)

    /**
     * AC3: Given a workorder already has locationId A, when event carries
     * locationId B,
     * then the saved workorder.locationId must be B — the previous value is fully
     * replaced.
     */
    @Test
    void whenHandleAssignmentUpdated_withExistingLocationId_thenPreviousValueIsFullyReplaced() {
        UUID oldLocationId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        Workorder workorder = Workorder.builder()
                .id(WORKORDER_ID)
                .status(WorkorderStatus.DRAFT)
                .locationId(oldLocationId)
                .resourceId(UUID.fromString("00000000-0000-0000-0000-000000000098"))
                .mechanicIds("[\"00000000-0000-0000-0000-000000000097\"]")
                .build();
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        workorderService.handleAssignmentUpdated(validEvent());

        assertThat(workorder.getLocationId())
                .isEqualTo(LOCATION_ID)
                .isNotEqualTo(oldLocationId);
        assertThat(workorder.getResourceId()).isEqualTo(RESOURCE_ID);
        assertThat(workorder.getMechanicIds()).contains(MECHANIC_ID_1.toString());
    }

    // Issue CAP-140: AC4 — unknown workorderId triggers DLQ path via exception

    /**
     * AC4: When the workorderId in the event does not exist in the repository,
     * a WorkorderNotFoundException must be thrown (DLQ path).
     */
    @Test
    void whenHandleAssignmentUpdated_withUnknownWorkorderId_thenThrowsWorkorderNotFoundException() {
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> workorderService.handleAssignmentUpdated(validEvent()))
                .isInstanceOf(WorkorderNotFoundException.class);
    }

    // Issue CAP-140: AC5 — null event is rejected eagerly

    /**
     * AC5: When the event argument is null, an IllegalArgumentException must be
     * thrown
     * before any repository interaction.
     */
    @Test
    void whenHandleAssignmentUpdated_withNullEvent_thenThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> workorderService.handleAssignmentUpdated(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Issue CAP-140: AC6 — audit trail recorded on successful update

    /**
     * AC6: On a successful update of a DRAFT workorder, an AuditEvent must be saved
     * with
     * entityType="Workorder", eventType="AssignmentContextUpdated",
     * entityId=workorder UUID,
     * userId="System:ShopManagementService", and a non-null details field.
     */
    @Test
    void whenHandleAssignmentUpdated_withDraftWorkorder_thenAuditEventSavedWithRequiredFields() {
        Workorder workorder = workorderWithStatus(WorkorderStatus.DRAFT);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        workorderService.handleAssignmentUpdated(validEvent());

        verify(auditEventRepository).save(argThat(ae -> "Workorder".equals(ae.getEntityType())
                && "AssignmentContextUpdated".equals(ae.getEventType())
                && WORKORDER_ID.equals(ae.getEntityId())
                && "System:ShopManagementService".equals(ae.getUserId())
                && ae.getDetails() != null));
    }
}
