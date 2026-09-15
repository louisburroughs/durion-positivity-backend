package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.testing.TenantTestSupport;
import com.positivity.workorder.internal.dto.AssignServicePositionRequest;
import com.positivity.workorder.internal.dto.ServicePositionResponse;
import com.positivity.workorder.internal.entity.ExtBayReplica;
import com.positivity.workorder.internal.entity.ExtMobileUnitReplica;
import com.positivity.workorder.internal.entity.ServicePositionAssignment;
import com.positivity.workorder.internal.entity.TechnicianAssignment;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.ResourceType;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.exception.ServicePositionInactiveException;
import com.positivity.workorder.internal.exception.ServicePositionInvalidException;
import com.positivity.workorder.internal.exception.ServicePositionOccupiedException;
import com.positivity.workorder.internal.exception.WorkorderClosedException;
import com.positivity.workorder.internal.exception.WorkorderNotFoundException;
import com.positivity.workorder.internal.repository.ExtBayReplicaRepository;
import com.positivity.workorder.internal.repository.ExtMobileUnitReplicaRepository;
import com.positivity.workorder.internal.repository.ServicePositionAssignmentRepository;
import com.positivity.workorder.internal.repository.TechnicianAssignmentRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Service-position assignment (#1983, #1984): assign, change and release, with history, and the
 * one-open-workorder rule on bays and mobile units that a hold position is exempt from.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ServicePositionServiceImpl")
class ServicePositionServiceImplTest {

    private static final UUID WORKORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3301");
    private static final UUID OTHER_WORKORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3302");
    private static final UUID SITE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3303");
    private static final UUID OTHER_SITE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3304");
    private static final UUID BAY_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3305");
    private static final UUID OTHER_BAY_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3306");
    private static final UUID MOBILE_UNIT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3307");
    private static final UUID TECHNICIAN_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3308");
    private static final Instant NOW = Instant.parse("2026-03-10T09:00:00Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final String ACTOR = "dispatch";

    @Mock
    private WorkorderRepository workorderRepository;

    @Mock
    private ServicePositionAssignmentRepository positionRepository;

    @Mock
    private TechnicianAssignmentRepository technicianAssignmentRepository;

    @Mock
    private ExtBayReplicaRepository extBayReplicaRepository;

    @Mock
    private ExtMobileUnitReplicaRepository extMobileUnitReplicaRepository;

    @Mock
    private WorkorderFactPublisher workorderFactPublisher;

    @Mock
    private WorkorderStateMachine stateMachine;

    private ServicePositionServiceImpl service;

    @BeforeEach
    void setUp() {
        // A pre-rollout token — authenticated, with no loc_* claims — so locationScope() answers the
        // unscoped scope that covers every site. These tests are about the assignment rules;
        // ADR-0061 location scoping on the very same permission is covered by
        // OperationalContextLocationScopeTest.
        TenantContext.bind(TenantTestSupport.TENANT_A);
        var token = new UsernamePasswordAuthenticationToken("dispatch", null, List.of());
        token.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "dispatch"));
        SecurityContextHolder.getContext().setAuthentication(token);
        service = new ServicePositionServiceImpl(
                Clock.fixed(NOW, ZoneOffset.UTC),
                workorderRepository,
                positionRepository,
                technicianAssignmentRepository,
                extBayReplicaRepository,
                extMobileUnitReplicaRepository,
                workorderFactPublisher);
        service.setStateMachine(stateMachine);

        when(workorderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workorderRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(positionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(positionRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(positionRepository.findByWorkorder_IdAndCurrentTrue(any())).thenReturn(Optional.empty());
        when(positionRepository.findByWorkorder_IdOrderByAssignedAtDescIdDesc(any()))
                .thenReturn(List.of());
        when(technicianAssignmentRepository.findByWorkorder_IdAndCurrentTrue(any()))
                .thenReturn(Optional.empty());
        when(workorderRepository.findOpenOccupantsOfPosition(any(), any(), any()))
                .thenReturn(List.of());

        givenBay(BAY_ID, SITE_ID);
        givenBay(OTHER_BAY_ID, SITE_ID);
        givenMobileUnit(MOBILE_UNIT_ID, SITE_ID);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private Workorder givenWorkorder(WorkorderStatus status) {
        Workorder workorder = new Workorder();
        workorder.setId(WORKORDER_ID);
        workorder.setStatus(status);
        workorder.setLocationId(SITE_ID);
        workorder.setShopId(SITE_ID);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));
        return workorder;
    }

    private void givenBay(UUID bayId, UUID locationId) {
        when(extBayReplicaRepository.findById(bayId))
                .thenReturn(Optional.of(ExtBayReplica.builder()
                        .bayId(bayId)
                        .locationId(locationId)
                        .active(true)
                        .aggregateVersion(1L)
                        .updatedAt(NOW)
                        .build()));
    }

    private void givenMobileUnit(UUID unitId, UUID baseLocationId) {
        when(extMobileUnitReplicaRepository.findById(unitId))
                .thenReturn(Optional.of(ExtMobileUnitReplica.builder()
                        .mobileUnitId(unitId)
                        .baseLocationId(baseLocationId)
                        .active(true)
                        .aggregateVersion(1L)
                        .updatedAt(NOW)
                        .build()));
    }

    /** #2001: a bay pos-location has marked out of service. */
    private void givenInactiveBay(UUID bayId, UUID locationId, String name) {
        when(extBayReplicaRepository.findById(bayId))
                .thenReturn(Optional.of(ExtBayReplica.builder()
                        .bayId(bayId)
                        .locationId(locationId)
                        .name(name)
                        .active(false)
                        .aggregateVersion(1L)
                        .updatedAt(NOW)
                        .build()));
    }

    /** #2001: a mobile unit pos-location has marked not deployed. */
    private void givenInactiveMobileUnit(UUID unitId, UUID baseLocationId, String name) {
        when(extMobileUnitReplicaRepository.findById(unitId))
                .thenReturn(Optional.of(ExtMobileUnitReplica.builder()
                        .mobileUnitId(unitId)
                        .baseLocationId(baseLocationId)
                        .name(name)
                        .active(false)
                        .aggregateVersion(1L)
                        .updatedAt(NOW)
                        .build()));
    }

    private void givenCurrentPlacement(ResourceType resourceType, UUID resourceId) {
        when(positionRepository.findByWorkorder_IdAndCurrentTrue(WORKORDER_ID))
                .thenReturn(Optional.of(ServicePositionAssignment.builder()
                        .id(7L)
                        .workorder(new Workorder(WORKORDER_ID))
                        .resourceType(resourceType)
                        .resourceId(resourceId)
                        .locationId(SITE_ID)
                        .assignedAt(NOW_LOCAL.minusHours(2))
                        .assignedBy("earlier-dispatch")
                        .current(true)
                        .build()));
    }

    private static AssignServicePositionRequest request(ResourceType resourceType, UUID resourceId, String reason) {
        return AssignServicePositionRequest.builder()
                .resourceType(resourceType)
                .resourceId(resourceId)
                .reason(reason)
                .build();
    }

    private ServicePositionAssignment savedPlacement() {
        ArgumentCaptor<ServicePositionAssignment> captor = ArgumentCaptor.forClass(ServicePositionAssignment.class);
        verify(positionRepository).save(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("assignPosition")
    class AssignPosition {

        @Test
        @DisplayName("places the workorder on a bay and opens a history row saying who, when and why")
        void assignsBay() {
            Workorder workorder = givenWorkorder(WorkorderStatus.APPROVED);

            ServicePositionResponse response =
                    service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, BAY_ID, "Alignment rack"), ACTOR);

            assertThat(workorder.getResourceType()).isEqualTo(ResourceType.BAY);
            assertThat(workorder.getResourceId()).isEqualTo(BAY_ID);
            assertThat(response.getResourceId()).isEqualTo(BAY_ID);

            ServicePositionAssignment placement = savedPlacement();
            assertThat(placement.getResourceType()).isEqualTo(ResourceType.BAY);
            assertThat(placement.getResourceId()).isEqualTo(BAY_ID);
            assertThat(placement.getLocationId()).isEqualTo(SITE_ID);
            assertThat(placement.getAssignedBy()).isEqualTo(ACTOR);
            assertThat(placement.getAssignedAt()).isEqualTo(NOW_LOCAL);
            assertThat(placement.getReason()).isEqualTo("Alignment rack");
            assertThat(placement.getCurrent()).isTrue();
            verify(workorderFactPublisher).markChanged(WORKORDER_ID);
        }

        @Test
        @DisplayName("#1983: a DRAFT workorder can be pre-slotted before it is approved")
        void assignsFromDraft() {
            givenWorkorder(WorkorderStatus.DRAFT);

            assertThatCode(() -> service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, BAY_ID, null), ACTOR))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("#1983: moving to another bay closes the previous placement and opens a new one")
        void changingPositionClosesThePrevious() {
            givenWorkorder(WorkorderStatus.WORK_IN_PROGRESS);
            givenCurrentPlacement(ResourceType.BAY, BAY_ID);

            service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, OTHER_BAY_ID, "Lift needed"), ACTOR);

            ArgumentCaptor<ServicePositionAssignment> closed = ArgumentCaptor.forClass(ServicePositionAssignment.class);
            verify(positionRepository).saveAndFlush(closed.capture());
            assertThat(closed.getValue().getCurrent()).isFalse();
            assertThat(closed.getValue().getReleasedAt()).isEqualTo(NOW_LOCAL);
            assertThat(closed.getValue().getReleasedBy()).isEqualTo(ACTOR);

            assertThat(savedPlacement().getResourceId()).isEqualTo(OTHER_BAY_ID);
        }

        @Test
        @DisplayName("#1983: changing the position leaves the technician alone")
        void changingPositionDoesNotTouchTheTechnician() {
            givenWorkorder(WorkorderStatus.WORK_IN_PROGRESS);
            when(technicianAssignmentRepository.findByWorkorder_IdAndCurrentTrue(WORKORDER_ID))
                    .thenReturn(Optional.of(TechnicianAssignment.builder()
                            .id(3L)
                            .workorder(new Workorder(WORKORDER_ID))
                            .technicianId(TECHNICIAN_ID)
                            .assignedBy("dispatch")
                            .assignedAt(NOW_LOCAL.minusHours(1))
                            .current(true)
                            .build()));

            ServicePositionResponse response =
                    service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, BAY_ID, null), ACTOR);

            // Position and technician are independent assignments; the read returns both together.
            assertThat(response.getTechnicianId()).isEqualTo(TECHNICIAN_ID);
            verify(technicianAssignmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("#1984: re-sending the position already in force writes nothing")
        void repeatIsANoOp() {
            givenWorkorder(WorkorderStatus.ASSIGNED);
            givenCurrentPlacement(ResourceType.BAY, BAY_ID);

            service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, BAY_ID, null), ACTOR);

            // Honouring a repeat literally would briefly release a bay that never came free, and fill
            // the history with pairs of rows recording that nothing happened.
            verify(positionRepository, never()).save(any());
            verify(positionRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("#1984: a bay another open workorder holds is refused, naming the occupant")
        void refusesAnOccupiedBay() {
            givenWorkorder(WorkorderStatus.APPROVED);
            Workorder occupant = new Workorder();
            occupant.setId(OTHER_WORKORDER_ID);
            when(workorderRepository.findOpenOccupantsOfPosition(ResourceType.BAY, BAY_ID, WORKORDER_ID))
                    .thenReturn(List.of(occupant));

            assertThatThrownBy(
                            () -> service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, BAY_ID, null), ACTOR))
                    .isInstanceOf(ServicePositionOccupiedException.class)
                    .hasMessageContaining(OTHER_WORKORDER_ID.toString())
                    .extracting(ex -> ((ServicePositionOccupiedException) ex).getOccupyingWorkorderId())
                    .isEqualTo(OTHER_WORKORDER_ID);

            verify(positionRepository, never()).save(any());
        }

        @Test
        @DisplayName("#1984: a mobile unit is exclusive on the same terms as a bay")
        void refusesAnOccupiedMobileUnit() {
            givenWorkorder(WorkorderStatus.APPROVED);
            Workorder occupant = new Workorder();
            occupant.setId(OTHER_WORKORDER_ID);
            when(workorderRepository.findOpenOccupantsOfPosition(
                            ResourceType.MOBILE_UNIT, MOBILE_UNIT_ID, WORKORDER_ID))
                    .thenReturn(List.of(occupant));

            assertThatThrownBy(() -> service.assignPosition(
                            WORKORDER_ID, request(ResourceType.MOBILE_UNIT, MOBILE_UNIT_ID, null), ACTOR))
                    .isInstanceOf(ServicePositionOccupiedException.class);
        }

        @Test
        @DisplayName("#1984: a hold position takes any number of workorders and is never checked for occupancy")
        void holdHasNoCapacityLimit() {
            givenWorkorder(WorkorderStatus.AWAITING_PARTS);

            ServicePositionResponse response =
                    service.assignPosition(WORKORDER_ID, request(ResourceType.HOLD, null, "Awaiting parts"), ACTOR);

            // The lot is identified by the site itself, which is what makes a hold site-scoped without
            // a table of its own.
            assertThat(response.getResourceType()).isEqualTo(ResourceType.HOLD);
            assertThat(response.getResourceId()).isEqualTo(SITE_ID);
            verify(workorderRepository, never()).findOpenOccupantsOfPosition(any(), any(), any());
        }

        @Test
        @DisplayName("#1984: a hold position at another site is refused")
        void holdMustBeTheWorkordersOwnSite() {
            givenWorkorder(WorkorderStatus.APPROVED);

            assertThatThrownBy(() -> service.assignPosition(
                            WORKORDER_ID, request(ResourceType.HOLD, OTHER_SITE_ID, null), ACTOR))
                    .isInstanceOf(ServicePositionInvalidException.class);
        }

        @Test
        @DisplayName("#1983: an unknown bay, and a bay at another site, are both refused")
        void refusesUnknownOrForeignPositions() {
            givenWorkorder(WorkorderStatus.APPROVED);

            UUID unknownBay = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3309");
            when(extBayReplicaRepository.findById(unknownBay)).thenReturn(Optional.empty());
            assertThatThrownBy(() ->
                            service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, unknownBay, null), ACTOR))
                    .isInstanceOf(ServicePositionInvalidException.class)
                    .hasMessageContaining("Unknown bay");

            UUID foreignBay = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f330a");
            givenBay(foreignBay, OTHER_SITE_ID);
            assertThatThrownBy(() ->
                            service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, foreignBay, null), ACTOR))
                    .isInstanceOf(ServicePositionInvalidException.class)
                    .hasMessageContaining("not to the workorder's site");
        }

        @Test
        @DisplayName("#1983: a bay assignment with no resourceId is refused")
        void bayNeedsAnId() {
            givenWorkorder(WorkorderStatus.APPROVED);

            assertThatThrownBy(() -> service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, null, null), ACTOR))
                    .isInstanceOf(ServicePositionInvalidException.class)
                    .hasMessageContaining("resourceId is required");
        }

        @Test
        @DisplayName("#1983: a COMPLETED or CANCELLED workorder cannot be moved, with a stable code")
        void refusesClosedWorkorders() {
            givenWorkorder(WorkorderStatus.COMPLETED);
            assertThatThrownBy(
                            () -> service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, BAY_ID, null), ACTOR))
                    .isInstanceOf(WorkorderClosedException.class);

            givenWorkorder(WorkorderStatus.CANCELLED);
            assertThatThrownBy(
                            () -> service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, BAY_ID, null), ACTOR))
                    .isInstanceOf(WorkorderClosedException.class);
        }

        @Test
        @DisplayName("#1983: a reopened COMPLETED workorder is open again and can be moved")
        void reopenedWorkorderIsNotClosed() {
            Workorder workorder = givenWorkorder(WorkorderStatus.COMPLETED);
            workorder.setIsReopened(true);

            // isLocked() is the single authority for "closed", and reopening never changes the status
            // — a plain status check would refuse a job somebody is actively working on again.
            assertThatCode(() -> service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, BAY_ID, null), ACTOR))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects an unknown workorder")
        void rejectsUnknownWorkorder() {
            when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(
                            () -> service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, BAY_ID, null), ACTOR))
                    .isInstanceOf(WorkorderNotFoundException.class);
        }

        @Test
        @DisplayName("#1984: a lost race against the unique index is the same 409, not a 500")
        void concurrentAssignBecomesConflict() {
            givenWorkorder(WorkorderStatus.APPROVED);
            org.mockito.Mockito.doThrow(new DataIntegrityViolationException(
                            "duplicate key value violates unique constraint \"workorder_open_position_uniq\""))
                    .when(workorderRepository)
                    .saveAndFlush(any());

            // Both dispatchers read the bay as free — neither sees the other's uncommitted row — so the
            // index decides, and the loser must hear what the pre-check would have said.
            assertThatThrownBy(
                            () -> service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, BAY_ID, null), ACTOR))
                    .isInstanceOf(ServicePositionOccupiedException.class)
                    .extracting(ex -> ((ServicePositionOccupiedException) ex).getOccupyingWorkorderId())
                    .isNull();
        }
    }

    @Nested
    @DisplayName("releasePosition")
    class ReleasePosition {

        @Test
        @DisplayName("#1983: unplaces the workorder and closes the history row with the reason")
        void releases() {
            Workorder workorder = givenWorkorder(WorkorderStatus.WORK_IN_PROGRESS);
            workorder.setResourceType(ResourceType.BAY);
            workorder.setResourceId(BAY_ID);
            givenCurrentPlacement(ResourceType.BAY, BAY_ID);

            ServicePositionResponse response = service.releasePosition(WORKORDER_ID, ACTOR, "Vehicle moved to the lot");

            assertThat(workorder.getResourceId()).isNull();
            assertThat(workorder.getResourceType()).isNull();
            assertThat(response.getResourceId()).isNull();

            ArgumentCaptor<ServicePositionAssignment> closed = ArgumentCaptor.forClass(ServicePositionAssignment.class);
            verify(positionRepository).saveAndFlush(closed.capture());
            assertThat(closed.getValue().getCurrent()).isFalse();
            assertThat(closed.getValue().getReason()).isEqualTo("Vehicle moved to the lot");
            verify(workorderFactPublisher).markChanged(WORKORDER_ID);
        }

        @Test
        @DisplayName("#1983: releasing a workorder that holds nothing succeeds and writes nothing")
        void releaseIsIdempotent() {
            givenWorkorder(WorkorderStatus.ASSIGNED);

            assertThatCode(() -> service.releasePosition(WORKORDER_ID, ACTOR, null))
                    .doesNotThrowAnyException();
            verify(positionRepository, never()).save(any());
            verify(positionRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("#1983: a closed workorder cannot be released by a client")
        void refusesClosedWorkorders() {
            givenWorkorder(WorkorderStatus.CANCELLED);

            assertThatThrownBy(() -> service.releasePosition(WORKORDER_ID, ACTOR, null))
                    .isInstanceOf(WorkorderClosedException.class);
        }
    }

    @Nested
    @DisplayName("releaseOnClose")
    class ReleaseOnClose {

        @Test
        @DisplayName("#1984: completing or cancelling frees the position, however the status changed")
        void freesThePosition() {
            Workorder workorder = givenWorkorder(WorkorderStatus.COMPLETED);
            workorder.setResourceType(ResourceType.BAY);
            workorder.setResourceId(BAY_ID);
            givenCurrentPlacement(ResourceType.BAY, BAY_ID);

            // No status check: this runs from inside the transition, and by then the workorder is
            // closed by definition — the very state releasePosition refuses.
            service.releaseOnClose(WORKORDER_ID, "advisor", "Workorder COMPLETED");

            assertThat(workorder.getResourceId()).isNull();
            assertThat(workorder.getResourceType()).isNull();

            ArgumentCaptor<ServicePositionAssignment> closed = ArgumentCaptor.forClass(ServicePositionAssignment.class);
            verify(positionRepository).saveAndFlush(closed.capture());
            assertThat(closed.getValue().getCurrent()).isFalse();
            assertThat(closed.getValue().getReason()).isEqualTo("Workorder COMPLETED");
        }

        @Test
        @DisplayName("a workorder that held no position is left alone")
        void noPositionIsANoOp() {
            givenWorkorder(WorkorderStatus.CANCELLED);

            service.releaseOnClose(WORKORDER_ID, "advisor", "Workorder CANCELLED");

            verify(workorderRepository, never()).save(any());
        }

        @Test
        @DisplayName("an unknown workorder is ignored rather than failing a status transition")
        void unknownWorkorderIsIgnored() {
            when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.empty());

            assertThatCode(() -> service.releaseOnClose(WORKORDER_ID, "advisor", "Workorder COMPLETED"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("getPosition")
    class GetPosition {

        @Test
        @DisplayName("#1983: returns the position and the technician together, with history newest first")
        void returnsBoth() {
            Workorder workorder = givenWorkorder(WorkorderStatus.WORK_IN_PROGRESS);
            workorder.setResourceType(ResourceType.BAY);
            workorder.setResourceId(BAY_ID);
            when(technicianAssignmentRepository.findByWorkorder_IdAndCurrentTrue(WORKORDER_ID))
                    .thenReturn(Optional.of(TechnicianAssignment.builder()
                            .id(3L)
                            .workorder(new Workorder(WORKORDER_ID))
                            .technicianId(TECHNICIAN_ID)
                            .assignedBy("dispatch")
                            .assignedAt(NOW_LOCAL.minusHours(1))
                            .current(true)
                            .build()));
            when(positionRepository.findByWorkorder_IdOrderByAssignedAtDescIdDesc(WORKORDER_ID))
                    .thenReturn(List.of(
                            ServicePositionAssignment.builder()
                                    .id(9L)
                                    .workorder(new Workorder(WORKORDER_ID))
                                    .resourceType(ResourceType.BAY)
                                    .resourceId(BAY_ID)
                                    .assignedAt(NOW_LOCAL)
                                    .assignedBy(ACTOR)
                                    .current(true)
                                    .build(),
                            ServicePositionAssignment.builder()
                                    .id(8L)
                                    .workorder(new Workorder(WORKORDER_ID))
                                    .resourceType(ResourceType.HOLD)
                                    .resourceId(SITE_ID)
                                    .assignedAt(NOW_LOCAL.minusHours(4))
                                    .assignedBy(ACTOR)
                                    .releasedAt(NOW_LOCAL)
                                    .current(false)
                                    .build()));

            ServicePositionResponse response = service.getPosition(WORKORDER_ID);

            assertThat(response.getResourceType()).isEqualTo(ResourceType.BAY);
            assertThat(response.getResourceId()).isEqualTo(BAY_ID);
            assertThat(response.getTechnicianId()).isEqualTo(TECHNICIAN_ID);
            assertThat(response.getWorkorderStatus()).isEqualTo("WORK_IN_PROGRESS");
            assertThat(response.getHistory()).hasSize(2);
            assertThat(response.getHistory().get(0).current()).isTrue();
            assertThat(response.getHistory().get(1).resourceType()).isEqualTo(ResourceType.HOLD);
        }

        @Test
        @DisplayName("an unplaced, unassigned workorder reads back with nulls rather than an error")
        void emptyIsNotAnError() {
            givenWorkorder(WorkorderStatus.DRAFT);

            ServicePositionResponse response = service.getPosition(WORKORDER_ID);

            assertThat(response.getResourceType()).isNull();
            assertThat(response.getResourceId()).isNull();
            assertThat(response.getTechnicianId()).isNull();
            assertThat(response.getHistory()).isEmpty();
        }

        @Test
        @DisplayName("rejects an unknown workorder")
        void rejectsUnknownWorkorder() {
            when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getPosition(WORKORDER_ID)).isInstanceOf(WorkorderNotFoundException.class);
        }
    }

    /**
     * #2002: a workorder holding a bay or a mobile unit is dispatch-board work, and the board selects
     * its roster on {@code scheduledDate}. Alpha was found with fourteen non-terminal workorders and
     * an empty board at every location because nothing on the placement path ever supplied one.
     */
    @Nested
    @DisplayName("scheduledDate invariant on placement")
    class ScheduledDateOnPlacement {

        private static final LocalDate TODAY = LocalDate.ofInstant(NOW, ZoneOffset.UTC);

        @Test
        @DisplayName("placing an undated workorder on a bay schedules it for today")
        void bayPlacementSchedulesUndatedWorkorder() {
            Workorder workorder = givenWorkorder(WorkorderStatus.APPROVED);
            assertThat(workorder.getScheduledDate()).isNull();

            service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, BAY_ID, null), ACTOR);

            assertThat(workorder.getScheduledDate()).isEqualTo(TODAY);
        }

        @Test
        @DisplayName("placing an undated workorder on a mobile unit schedules it for today")
        void mobileUnitPlacementSchedulesUndatedWorkorder() {
            Workorder workorder = givenWorkorder(WorkorderStatus.APPROVED);

            service.assignPosition(WORKORDER_ID, request(ResourceType.MOBILE_UNIT, MOBILE_UNIT_ID, null), ACTOR);

            assertThat(workorder.getScheduledDate()).isEqualTo(TODAY);
        }

        @Test
        @DisplayName("a date already set is left alone, including a past one: a multi-day job stays due when it was")
        void keepsAnExistingPastDate() {
            Workorder workorder = givenWorkorder(WorkorderStatus.WORK_IN_PROGRESS);
            LocalDate startedOn = TODAY.minusDays(3);
            workorder.setScheduledDate(startedOn);

            service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, OTHER_BAY_ID, "moved rack"), ACTOR);

            assertThat(workorder.getScheduledDate()).isEqualTo(startedOn);
        }

        @Test
        @DisplayName("a hold position schedules nothing: the lot is not dispatch work")
        void holdDoesNotSchedule() {
            Workorder workorder = givenWorkorder(WorkorderStatus.APPROVED);

            service.assignPosition(WORKORDER_ID, request(ResourceType.HOLD, SITE_ID, "waiting on parts"), ACTOR);

            assertThat(workorder.getResourceType()).isEqualTo(ResourceType.HOLD);
            assertThat(workorder.getScheduledDate()).isNull();
        }

        @Test
        @DisplayName("re-asserting the position a workorder already holds still supplies a missing date")
        void repeatedPlacementStillRepairsAMissingDate() {
            Workorder workorder = givenWorkorder(WorkorderStatus.ASSIGNED);
            workorder.setResourceType(ResourceType.BAY);
            workorder.setResourceId(BAY_ID);
            givenCurrentPlacement(ResourceType.BAY, BAY_ID);

            service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, BAY_ID, null), ACTOR);

            // The placement itself is a no-op — no second history row for a move that did not happen.
            verify(positionRepository, never()).save(any());
            assertThat(workorder.getScheduledDate()).isEqualTo(TODAY);
        }

        @Test
        @DisplayName("releasing a position schedules nothing")
        void releaseDoesNotSchedule() {
            Workorder workorder = givenWorkorder(WorkorderStatus.APPROVED);
            workorder.setResourceType(ResourceType.BAY);
            workorder.setResourceId(BAY_ID);
            givenCurrentPlacement(ResourceType.BAY, BAY_ID);

            service.releasePosition(WORKORDER_ID, ACTOR, "customer rescheduled");

            assertThat(workorder.getScheduledDate()).isNull();
        }
    }

    /**
     * #2001: a bay out of service or a mobile unit not deployed cannot be taken, checked after the
     * site check so a position at the wrong site is still reported as the wrong site.
     */
    @Nested
    @DisplayName("inactive positions are refused")
    class InactivePosition {

        @Test
        @DisplayName("an inactive bay is refused, naming the bay, and nothing is written")
        void refusesAnInactiveBay() {
            Workorder workorder = givenWorkorder(WorkorderStatus.APPROVED);
            givenInactiveBay(BAY_ID, SITE_ID, "Lift 3");

            assertThatThrownBy(() ->
                            service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, BAY_ID, null), ACTOR))
                    .isInstanceOf(ServicePositionInactiveException.class)
                    .hasMessageContaining("Lift 3")
                    .hasMessageContaining("INACTIVE");

            assertThat(workorder.getResourceType()).isNull();
            assertThat(workorder.getResourceId()).isNull();
            verify(positionRepository, never()).save(any());
        }

        @Test
        @DisplayName("an inactive mobile unit is refused, naming the unit, and nothing is written")
        void refusesAnInactiveMobileUnit() {
            Workorder workorder = givenWorkorder(WorkorderStatus.APPROVED);
            givenInactiveMobileUnit(MOBILE_UNIT_ID, SITE_ID, "Van 2");

            assertThatThrownBy(() -> service.assignPosition(
                            WORKORDER_ID, request(ResourceType.MOBILE_UNIT, MOBILE_UNIT_ID, null), ACTOR))
                    .isInstanceOf(ServicePositionInactiveException.class)
                    .hasMessageContaining("Van 2")
                    .hasMessageContaining("INACTIVE");

            assertThat(workorder.getResourceType()).isNull();
            assertThat(workorder.getResourceId()).isNull();
            verify(positionRepository, never()).save(any());
        }

        @Test
        @DisplayName("a bay at another site fails on site even when it is also inactive: the caller's "
                + "first mistake is the one worth naming")
        void siteCheckWinsOverInactive() {
            givenWorkorder(WorkorderStatus.APPROVED);
            givenInactiveBay(OTHER_BAY_ID, OTHER_SITE_ID, "Foreign lift");

            assertThatThrownBy(() -> service.assignPosition(
                            WORKORDER_ID, request(ResourceType.BAY, OTHER_BAY_ID, null), ACTOR))
                    .isInstanceOf(ServicePositionInvalidException.class)
                    .hasMessageContaining("not to the workorder's site");
        }
    }

    /**
     * #2001: the read-only companion to {@code resolvePosition}'s inactive check, for callers that
     * must not be unwound by an exception.
     */
    @Nested
    @DisplayName("isPositionActive")
    class IsPositionActive {

        @Test
        @DisplayName("an inactive bay is not active")
        void inactiveBayIsNotActive() {
            givenInactiveBay(BAY_ID, SITE_ID, "Lift 3");

            assertThat(service.isPositionActive(ResourceType.BAY, BAY_ID)).isFalse();
        }

        @Test
        @DisplayName("an inactive mobile unit is not active")
        void inactiveMobileUnitIsNotActive() {
            givenInactiveMobileUnit(MOBILE_UNIT_ID, SITE_ID, "Van 2");

            assertThat(service.isPositionActive(ResourceType.MOBILE_UNIT, MOBILE_UNIT_ID)).isFalse();
        }

        @Test
        @DisplayName("an active bay is active")
        void activeBayIsActive() {
            assertThat(service.isPositionActive(ResourceType.BAY, BAY_ID)).isTrue();
        }

        @Test
        @DisplayName("a HOLD position is always active: it is not exclusive")
        void holdIsAlwaysActive() {
            assertThat(service.isPositionActive(ResourceType.HOLD, SITE_ID)).isTrue();
        }

        @Test
        @DisplayName("a null resourceType or resourceId is active: there is nothing to be inactive")
        void nullResourceTypeOrIdIsActive() {
            assertThat(service.isPositionActive(null, BAY_ID)).isTrue();
            assertThat(service.isPositionActive(ResourceType.BAY, null)).isTrue();
        }

        @Test
        @DisplayName("a position whose replica row has not arrived yet is active: replica lag must not "
                + "drop a position that was really assigned")
        void missingReplicaRowIsActive() {
            UUID unreplicatedBay = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f330b");
            when(extBayReplicaRepository.findById(unreplicatedBay)).thenReturn(Optional.empty());

            assertThat(service.isPositionActive(ResourceType.BAY, unreplicatedBay)).isTrue();
        }
    }

    /**
     * #2011: a pair completed or broken by a position change is reconciled through the state
     * machine, inside the same transaction as the write.
     */
    @Nested
    @DisplayName("state-machine reconciliation")
    class StateMachineReconciliation {

        @Test
        @DisplayName("assignPosition asks the state machine to reconcile ASSIGNED")
        void assignReconciles() {
            givenWorkorder(WorkorderStatus.APPROVED);

            service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, BAY_ID, null), ACTOR);

            verify(stateMachine).reconcileAssigned(eq(WORKORDER_ID), eq(ACTOR), any());
        }

        @Test
        @DisplayName("releasePosition asks the state machine to reconcile ASSIGNED")
        void releaseReconciles() {
            Workorder workorder = givenWorkorder(WorkorderStatus.WORK_IN_PROGRESS);
            workorder.setResourceType(ResourceType.BAY);
            workorder.setResourceId(BAY_ID);
            givenCurrentPlacement(ResourceType.BAY, BAY_ID);

            service.releasePosition(WORKORDER_ID, ACTOR, "Vehicle moved to the lot");

            verify(stateMachine).reconcileAssigned(eq(WORKORDER_ID), eq(ACTOR), any());
        }
    }
}
