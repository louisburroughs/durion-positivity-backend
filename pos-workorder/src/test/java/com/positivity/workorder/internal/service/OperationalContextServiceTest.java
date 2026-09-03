package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.dto.OperationalContextOverrideRequest;
import com.positivity.workorder.internal.dto.OperationalContextResponse;
import com.positivity.workorder.internal.dto.WorkorderStartResponse;
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
 * Service-layer tests for CAP-140 Story #59 operational context behavior.
 */
@ExtendWith(MockitoExtension.class)
class OperationalContextServiceTest {
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

    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000059");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000060");
    private static final UUID MECHANIC_ID = UUID.fromString("00000000-0000-0000-0000-000000000061");
    private static final UUID RESOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000062");

    // -----------------------------------------------------------------------
    // AC7 — getOperationalContext serves the workorder's own assignment state (#898)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC7: getOperationalContext returns the workorder's local assignment state")
    void whenGetOperationalContext_thenReturnsLocalAssignmentState() {
        var existingWorkorder = Workorder.builder()
                .id(WORKORDER_ID)
                .status(WorkorderStatus.ASSIGNED)
                .operationalContextVersion("ctx-v1")
                .locationId(LOCATION_ID)
                .resourceId(RESOURCE_ID)
                .resourceType(ResourceType.MOBILE_UNIT)
                .mechanicIds("[\"" + MECHANIC_ID + "\"]")
                .workStartedAt(null)
                .build();
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(existingWorkorder));

        OperationalContextResponse result = workorderService.getOperationalContext(WORKORDER_ID);

        assertThat(result.getVersion()).isEqualTo("ctx-v1");
        assertThat(result.getLocationId()).isEqualTo(LOCATION_ID);
        // #1656: the id is reported type-neutrally with its type beside it — the retired bayId key
        // labelled every assignment a bay, including this mobile-unit one.
        assertThat(result.getResourceId()).isEqualTo(RESOURCE_ID.toString());
        assertThat(result.getResourceType()).isEqualTo(ResourceType.MOBILE_UNIT);
        assertThat(result.getAssignedMechanics()).containsExactly(MECHANIC_ID);
        assertThat(result.getAssignedResources()).containsExactly(RESOURCE_ID);
        assertThat(result.isLocked()).isFalse();
    }

    @Test
    @DisplayName("AC2: getOperationalContext throws WorkorderNotFoundException for unknown workorderId")
    void whenGetOperationalContext_withUnknownWorkorderId_thenThrowsWorkorderNotFoundException() {
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> workorderService.getOperationalContext(WORKORDER_ID))
                .isInstanceOf(WorkorderNotFoundException.class);
    }

    // -----------------------------------------------------------------------
    // AC8 — startWork with unknown workorderId throws WorkorderNotFoundException
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC8: startWork throws WorkorderNotFoundException for unknown workorderId")
    void whenStartWork_withUnknownWorkorderId_thenThrowsWorkorderNotFoundException() {
        // Issue CAP-140: AC8 — not-found guard
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> workorderService.startWork(WORKORDER_ID))
                .isInstanceOf(WorkorderNotFoundException.class);
    }

    // -----------------------------------------------------------------------
    // AC5 — startWork happy-path sets context fields and saves
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC5: startWork on ASSIGNED workorder locks context and returns WorkorderStartResponse")
    void whenStartWork_withAssignedWorkorder_thenLocksContextAndReturnsResponse() {
        // Issue CAP-140: AC5 — happy-path context locking
        var workorder = Workorder.builder()
                .id(WORKORDER_ID)
                .status(WorkorderStatus.ASSIGNED)
                .build();
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));
        when(workorderRepository.save(any(Workorder.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkorderStartResponse result = workorderService.startWork(WORKORDER_ID);

        assertThat(result.getWorkorderId()).isEqualTo(WORKORDER_ID);
        assertThat(result.getWorkStartedAt()).isNotNull();
        assertThat(result.getOperationalContextVersion()).isNotNull();
        assertThat(workorder.getWorkStartedAt()).isNotNull();
        assertThat(workorder.getOperationalContextVersion()).isNotNull();
        verify(workorderRepository).save(workorder);
    }

    // -----------------------------------------------------------------------
    // AC6 — startWork when already started throws IllegalStateException
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC6: startWork throws IllegalStateException when work already started")
    void whenStartWork_withWorkAlreadyStarted_thenThrowsIllegalStateException() {
        // Issue CAP-140: AC6 — duplicate-start guard
        var workorder = Workorder.builder()
                .id(WORKORDER_ID)
                .status(WorkorderStatus.WORK_IN_PROGRESS)
                .workStartedAt(Instant.now(TEST_CLOCK))
                .build();
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        assertThatThrownBy(() -> workorderService.startWork(WORKORDER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already started");
    }

    // -----------------------------------------------------------------------
    // AC4 — overrideOperationalContext when work started throws
    // IllegalStateException
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC4: overrideOperationalContext throws IllegalStateException when work already started")
    void whenOverrideOperationalContext_withWorkAlreadyStarted_thenThrowsIllegalStateException() {
        // Issue CAP-140: AC4 — override guard when context is locked
        var workorder = Workorder.builder()
                .id(WORKORDER_ID)
                .status(WorkorderStatus.WORK_IN_PROGRESS)
                .workStartedAt(Instant.now(TEST_CLOCK))
                .build();
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        var overrideRequest = OperationalContextOverrideRequest.builder()
                .locationId(LOCATION_ID)
                .assignedMechanics(List.of())
                .build();

        assertThatThrownBy(() -> workorderService.overrideOperationalContext(WORKORDER_ID, overrideRequest))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("overrideOperationalContext with valid request returns mapped response")
    void whenOverrideOperationalContext_withValidRequest_thenReturnsMappedResponse() {
        var workorder = Workorder.builder()
                .id(WORKORDER_ID)
                .status(WorkorderStatus.ASSIGNED)
                .operationalContextVersion("ctx-v1")
                .workStartedAt(null)
                .build();
        var overrideRequest = OperationalContextOverrideRequest.builder()
                .locationId(LOCATION_ID)
                .assignedMechanics(List.of(MECHANIC_ID))
                .assignedResources(List.of(RESOURCE_ID))
                .constraints(List.of("LIFT_REQUIRED"))
                .build();
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));
        when(workorderRepository.save(any(Workorder.class))).thenAnswer(inv -> inv.getArgument(0));

        OperationalContextResponse response =
                workorderService.overrideOperationalContext(WORKORDER_ID, overrideRequest);

        assertThat(response).isNotNull();
        assertThat(response.getVersion()).isEqualTo("ctx-v1");
        assertThat(response.getLocationId()).isEqualTo(LOCATION_ID);
        assertThat(response.getResourceId()).isEqualTo(RESOURCE_ID.toString());
        // No resourceType on the request: the one documented fallback applies, as on the event path.
        assertThat(response.getResourceType()).isEqualTo(ResourceType.BAY);
        assertThat(workorder.getResourceType()).isEqualTo(ResourceType.BAY);
        assertThat(response.getAssignedMechanics()).containsExactly(MECHANIC_ID);
        assertThat(response.getAssignedResources()).containsExactly(RESOURCE_ID);
        assertThat(response.getConstraints()).containsExactly("LIFT_REQUIRED");
        assertThat(response.isLocked()).isFalse();
        verify(workorderRepository).save(any(Workorder.class));
    }

    // -----------------------------------------------------------------------
    // #1656 review finding 1: the override writes resource id and type together
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("#1656: overriding a bay assignment onto a mobile unit moves the type with the id")
    void whenOverrideOperationalContext_toMobileUnit_thenTypeMovesWithId() {
        // Writing the id alone left the workorder pointing at a van while still typed BAY: the
        // dispatch board would then file it under bays[] and, in the same response, keep reporting
        // the van as available. Exactly the half-applied state the assignment-event path prevents.
        UUID unitId = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
        var workorder = Workorder.builder()
                .id(WORKORDER_ID)
                .status(WorkorderStatus.ASSIGNED)
                .operationalContextVersion("ctx-v1")
                .resourceId(RESOURCE_ID)
                .resourceType(ResourceType.BAY)
                .workStartedAt(null)
                .build();
        var overrideRequest = OperationalContextOverrideRequest.builder()
                .locationId(LOCATION_ID)
                .resourceType(ResourceType.MOBILE_UNIT)
                .assignedResources(List.of(unitId))
                .build();
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));
        when(workorderRepository.save(any(Workorder.class))).thenAnswer(inv -> inv.getArgument(0));

        OperationalContextResponse response =
                workorderService.overrideOperationalContext(WORKORDER_ID, overrideRequest);

        assertThat(workorder.getResourceId()).isEqualTo(unitId);
        assertThat(workorder.getResourceType()).isEqualTo(ResourceType.MOBILE_UNIT);
        assertThat(response.getResourceId()).isEqualTo(unitId.toString());
        assertThat(response.getResourceType()).isEqualTo(ResourceType.MOBILE_UNIT);
    }

    @Test
    @DisplayName("#1656: an override that clears the resource clears the type with it")
    void whenOverrideOperationalContext_withNoResources_thenTypeIsCleared() {
        var workorder = Workorder.builder()
                .id(WORKORDER_ID)
                .status(WorkorderStatus.ASSIGNED)
                .resourceId(RESOURCE_ID)
                .resourceType(ResourceType.MOBILE_UNIT)
                .workStartedAt(null)
                .build();
        var overrideRequest = OperationalContextOverrideRequest.builder()
                .locationId(LOCATION_ID)
                .assignedResources(List.of())
                .build();
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));
        when(workorderRepository.save(any(Workorder.class))).thenAnswer(inv -> inv.getArgument(0));

        OperationalContextResponse response =
                workorderService.overrideOperationalContext(WORKORDER_ID, overrideRequest);

        // A type with no id would be a dangling discriminator; the pair is always written together.
        assertThat(workorder.getResourceId()).isNull();
        assertThat(workorder.getResourceType()).isNull();
        assertThat(response.getResourceId()).isNull();
        assertThat(response.getResourceType()).isNull();
    }
}
