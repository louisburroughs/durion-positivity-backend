package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.dto.AssignmentUpdatePayload;
import com.positivity.workorder.internal.dto.AssignmentUpdatedEvent;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.ResourceType;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.exception.WorkorderNotFoundException;
import com.positivity.workorder.internal.repository.AuditEventRepository;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for CAP-140 Story #64: Propagate Assignment Context to Workorder.
 *
 * Validates that handleAssignmentUpdated correctly updates workorder location,
 * resource, and mechanic fields for every unlocked status (AC1/AC3; widened from
 * the original pre-execution-only set by #1656 so a mid-day reassignment is not
 * silently dropped), silently skips locked ones without saving (AC2), throws
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

    @Spy
    Clock clock = TEST_CLOCK;

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

    @org.mockito.Mock
    private com.positivity.workorder.internal.service.WorkorderFactPublisher workorderFactPublisher;

    @InjectMocks
    private WorkorderServiceImpl workorderService;

    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID RESOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID MECHANIC_ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID MECHANIC_ID_2 = UUID.fromString("00000000-0000-0000-0000-000000000005");

    private AssignmentUpdatedEvent validEvent() {
        return AssignmentUpdatedEvent.builder()
                .eventId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .timestamp(Instant.now(TEST_CLOCK))
                .workorderId(WORKORDER_ID)
                .payload(AssignmentUpdatePayload.builder()
                        .locationId(LOCATION_ID)
                        .resourceId(RESOURCE_ID)
                        .mechanicIds(List.of(MECHANIC_ID_1, MECHANIC_ID_2))
                        .build())
                .build();
    }

    // -----------------------------------------------------------------------
    // #1656: resourceType threading and the documented null fallback
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("#1656: an event carrying resourceType=MOBILE_UNIT is persisted as a mobile-unit assignment")
    void whenHandleAssignmentUpdated_withMobileUnitResourceType_thenPersistsType() {
        Workorder workorder = workorderWithStatus(WorkorderStatus.ASSIGNED);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        workorderService.handleAssignmentUpdated(eventWithResourceType(ResourceType.MOBILE_UNIT));

        assertThat(workorder.getResourceId()).isEqualTo(RESOURCE_ID);
        assertThat(workorder.getResourceType()).isEqualTo(ResourceType.MOBILE_UNIT);
        verify(workorderRepository).save(workorder);
    }

    @Test
    @DisplayName("#1656 fallback: an event with no resourceType is applied as BAY, preserving pre-#1656 behaviour")
    void whenHandleAssignmentUpdated_withoutResourceType_thenDefaultsToBay() {
        // pos-shop-manager does not publish resourceType yet and cannot be changed from this module.
        // Every untyped assignment that has ever reached here meant "bay", so that is what an absent
        // value must keep meaning — explicitly, not by accident.
        Workorder workorder = workorderWithStatus(WorkorderStatus.DRAFT);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        workorderService.handleAssignmentUpdated(eventWithResourceType(null));

        assertThat(workorder.getResourceType()).isEqualTo(ResourceType.BAY);
    }

    @Test
    @DisplayName("#1656: reassigning a bay-typed workorder to a mobile unit replaces id and type together")
    void whenHandleAssignmentUpdated_reassignsAcrossResourceTypes_thenBothFieldsMove() {
        // A half-applied change — new id, stale type — would file the workorder under the wrong
        // dispatch panel, so the two fields must move as one.
        Workorder workorder = workorderWithStatus(WorkorderStatus.ASSIGNED);
        workorder.setResourceId(UUID.fromString("00000000-0000-0000-0000-0000000000aa"));
        workorder.setResourceType(ResourceType.BAY);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        workorderService.handleAssignmentUpdated(eventWithResourceType(ResourceType.MOBILE_UNIT));

        assertThat(workorder.getResourceId()).isEqualTo(RESOURCE_ID);
        assertThat(workorder.getResourceType()).isEqualTo(ResourceType.MOBILE_UNIT);
    }

    private AssignmentUpdatedEvent eventWithResourceType(ResourceType resourceType) {
        return AssignmentUpdatedEvent.builder()
                .eventId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .timestamp(Instant.now(TEST_CLOCK))
                .workorderId(WORKORDER_ID)
                .payload(AssignmentUpdatePayload.builder()
                        .locationId(LOCATION_ID)
                        .resourceId(RESOURCE_ID)
                        .resourceType(resourceType)
                        .mechanicIds(List.of(MECHANIC_ID_1))
                        .build())
                .build();
    }

    private Workorder workorderWithStatus(WorkorderStatus status) {

        return Workorder.builder().id(WORKORDER_ID).status(status).build();
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
        assertThat(workorder.getMechanicIds())
                .isNotNull()
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

    // Issue CAP-140 / #1656: locked statuses are skipped; running work is reassignable

    /**
     * #1656: a running job is exactly the one that gets reassigned mid-day, so the event must be
     * applied rather than dropped.
     *
     * <p>This assertion is inverted from the CAP-140 original, which required WORK_IN_PROGRESS to
     * be skipped. That guard made #1656's mid-day-reassignment acceptance criterion unreachable: a
     * job moved from a bay to a mobile unit at 11am is WORK_IN_PROGRESS by definition, so the event
     * was logged and discarded, the old bay stayed OCCUPIED and the new unit stayed AVAILABLE on the
     * dispatch board with nothing anywhere for a dispatcher to see. The guard is now
     * {@link Workorder#isLocked()} — see the COMPLETED and CANCELLED cases below, which are
     * unchanged.
     */
    @Test
    @DisplayName("#1656: a WORK_IN_PROGRESS workorder accepts a mid-day reassignment")
    void whenHandleAssignmentUpdated_withWorkInProgressWorkorder_thenUpdatesFieldsAndSaves() {
        Workorder workorder = workorderWithStatus(WorkorderStatus.WORK_IN_PROGRESS);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        workorderService.handleAssignmentUpdated(validEvent());

        assertThat(workorder.getLocationId()).isEqualTo(LOCATION_ID);
        assertThat(workorder.getResourceId()).isEqualTo(RESOURCE_ID);
        verify(workorderRepository).save(workorder);
    }

    @Test
    @DisplayName("#1656: a mid-day BAY → MOBILE_UNIT reassignment frees the bay and holds the unit, typed")
    void whenHandleAssignmentUpdated_withRunningWorkorderMovedToMobileUnit_thenBothFieldsMove() {
        // #1656 AC: the same workorder moves from a bay to a mobile unit while the job is running.
        // The write path has to move id and type together — leaving the old bay id behind would keep
        // the bay OCCUPIED on the board, and leaving the old BAY type behind would file the van
        // under bays[] and go on advertising it as free.
        UUID oldBayId = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        Workorder workorder = workorderWithStatus(WorkorderStatus.WORK_IN_PROGRESS);
        workorder.setResourceId(oldBayId);
        workorder.setResourceType(ResourceType.BAY);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        workorderService.handleAssignmentUpdated(eventWithResourceType(ResourceType.MOBILE_UNIT));

        assertThat(workorder.getResourceId()).isEqualTo(RESOURCE_ID).isNotEqualTo(oldBayId);
        assertThat(workorder.getResourceType()).isEqualTo(ResourceType.MOBILE_UNIT);
        verify(workorderRepository).save(workorder);
    }

    /**
     * #1656: a job waiting on parts is still sitting in its bay and is just as reassignable — the
     * widened guard is "not locked", not "not yet started", so this is not a special case.
     */
    @Test
    void whenHandleAssignmentUpdated_withAwaitingPartsWorkorder_thenUpdatesFieldsAndSaves() {
        Workorder workorder = workorderWithStatus(WorkorderStatus.AWAITING_PARTS);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        workorderService.handleAssignmentUpdated(validEvent());

        assertThat(workorder.getResourceId()).isEqualTo(RESOURCE_ID);
        verify(workorderRepository).save(workorder);
    }

    /**
     * AC2 / #1656: a COMPLETED workorder that was never reopened is locked and must not be updated
     * or saved. This is the half of the old guard that survives the widening — a finished job must
     * not accept a reassignment.
     */
    @Test
    void whenHandleAssignmentUpdated_withCompletedWorkorder_thenSkipsUpdateAndDoesNotSave() {
        Workorder workorder = workorderWithStatus(WorkorderStatus.COMPLETED);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        assertThatNoException().isThrownBy(() -> workorderService.handleAssignmentUpdated(validEvent()));

        verify(workorderRepository, never()).save(any());
    }

    /**
     * AC2 / #1656: a CANCELLED workorder is locked and must not be updated or saved, and must not
     * hold a resource by accepting one either.
     */
    @Test
    void whenHandleAssignmentUpdated_withCancelledWorkorder_thenSkipsUpdateAndDoesNotSave() {
        UUID oldBayId = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        Workorder workorder = workorderWithStatus(WorkorderStatus.CANCELLED);
        workorder.setResourceId(oldBayId);
        workorder.setResourceType(ResourceType.BAY);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        assertThatNoException()
                .isThrownBy(() ->
                        workorderService.handleAssignmentUpdated(eventWithResourceType(ResourceType.MOBILE_UNIT)));

        assertThat(workorder.getResourceId()).isEqualTo(oldBayId);
        assertThat(workorder.getResourceType()).isEqualTo(ResourceType.BAY);
        verify(workorderRepository, never()).save(any());
        verify(auditEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("#1656: AWAITING_APPROVAL workorder accepts the assignment update")
    void whenHandleAssignmentUpdated_withAwaitingApprovalWorkorder_thenUpdatesFieldsAndSaves() {
        Workorder workorder = Workorder.builder()
                .id(WORKORDER_ID)
                .status(WorkorderStatus.AWAITING_APPROVAL)
                .build();
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        workorderService.handleAssignmentUpdated(validEvent());

        assertThat(workorder.getResourceId()).isEqualTo(RESOURCE_ID);
        verify(workorderRepository).save(any(Workorder.class));
        verify(auditEventRepository).save(any());
    }

    @Test
    @DisplayName("#1656: READY_FOR_PICKUP workorder accepts the assignment update")
    void whenHandleAssignmentUpdated_withReadyForPickupWorkorder_thenUpdatesFieldsAndSaves() {
        Workorder workorder = Workorder.builder()
                .id(WORKORDER_ID)
                .status(WorkorderStatus.READY_FOR_PICKUP)
                .build();
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        workorderService.handleAssignmentUpdated(validEvent());

        assertThat(workorder.getResourceId()).isEqualTo(RESOURCE_ID);
        verify(workorderRepository).save(any(Workorder.class));
        verify(auditEventRepository).save(any());
    }

    @Test
    @DisplayName("#1656: a reopened COMPLETED workorder is not locked, so it accepts the reassignment")
    void whenHandleAssignmentUpdated_withReopenedCompletedWorkorder_thenUpdatesFieldsAndSaves() {
        // isLocked() is the single authority, and it deliberately treats a reopened workorder as
        // live — reopening never changes the status, so a plain COMPLETED test would refuse an
        // assignment for a job somebody is actively redoing.
        Workorder workorder = Workorder.builder()
                .id(WORKORDER_ID)
                .status(WorkorderStatus.COMPLETED)
                .isReopened(true)
                .build();
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        workorderService.handleAssignmentUpdated(validEvent());

        assertThat(workorder.getResourceId()).isEqualTo(RESOURCE_ID);
        verify(workorderRepository).save(workorder);
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

        assertThat(workorder.getLocationId()).isEqualTo(LOCATION_ID).isNotEqualTo(oldLocationId);
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

        verify(auditEventRepository)
                .save(argThat(ae -> "Workorder".equals(ae.getEntityType())
                        && "AssignmentContextUpdated".equals(ae.getEventType())
                        && WORKORDER_ID.equals(ae.getEntityId())
                        && "System:ShopManagementService".equals(ae.getUserId())
                        && ae.getDetails() != null));
    }
}
