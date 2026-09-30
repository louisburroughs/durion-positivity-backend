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
import com.positivity.web.common.ReplicationPendingException;
import com.positivity.workorder.internal.dto.AssignServicePositionRequest;
import com.positivity.workorder.internal.dto.ServicePositionResponse;
import com.positivity.workorder.internal.entity.ExtBayReplica;
import com.positivity.workorder.internal.entity.ExtMobileUnitReplica;
import com.positivity.workorder.internal.entity.ExtVehicleReplica;
import com.positivity.workorder.internal.entity.ServicePositionAssignment;
import com.positivity.workorder.internal.entity.TechnicianAssignment;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.ResourceType;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.exception.ServicePositionDutyClassExceededException;
import com.positivity.workorder.internal.exception.ServicePositionInactiveException;
import com.positivity.workorder.internal.exception.ServicePositionInvalidException;
import com.positivity.workorder.internal.exception.ServicePositionOccupiedException;
import com.positivity.workorder.internal.exception.WorkorderClosedException;
import com.positivity.workorder.internal.exception.WorkorderNotFoundException;
import com.positivity.workorder.internal.repository.ExtBayReplicaRepository;
import com.positivity.workorder.internal.repository.ExtMobileUnitReplicaRepository;
import com.positivity.workorder.internal.repository.ExtVehicleReplicaRepository;
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
    private static final UUID VEHICLE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f330c");
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
    private ExtVehicleReplicaRepository extVehicleReplicaRepository;

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
                extVehicleReplicaRepository,
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
        givenBay(bayId, locationId, null, null);
    }

    /** #2269: a bay with a duty-class ceiling and, optionally, a specialty capability that never gates placement. */
    private void givenBay(UUID bayId, UUID locationId, Integer maxDutyClass, List<String> serviceCapabilityCodes) {
        when(extBayReplicaRepository.findById(bayId))
                .thenReturn(Optional.of(ExtBayReplica.builder()
                        .bayId(bayId)
                        .locationId(locationId)
                        .active(true)
                        .maxDutyClass(maxDutyClass)
                        .serviceCapabilityCodes(serviceCapabilityCodes)
                        .aggregateVersion(1L)
                        .updatedAt(NOW)
                        .build()));
    }

    private void givenMobileUnit(UUID unitId, UUID baseLocationId) {
        givenMobileUnit(unitId, baseLocationId, null);
    }

    /** #2269: a mobile unit with a duty-class ceiling. */
    private void givenMobileUnit(UUID unitId, UUID baseLocationId, Integer maxDutyClass) {
        when(extMobileUnitReplicaRepository.findById(unitId))
                .thenReturn(Optional.of(ExtMobileUnitReplica.builder()
                        .mobileUnitId(unitId)
                        .baseLocationId(baseLocationId)
                        .active(true)
                        .maxDutyClass(maxDutyClass)
                        .aggregateVersion(1L)
                        .updatedAt(NOW)
                        .build()));
    }

    /** #2269: the vehicle's replicated GVWR class, resolved through the workorder's vehicleId. */
    private void givenVehicle(UUID vehicleId, Integer gvwrClass) {
        when(extVehicleReplicaRepository.findById(vehicleId))
                .thenReturn(Optional.of(ExtVehicleReplica.builder()
                        .vehicleId(vehicleId)
                        .gvwrClass(gvwrClass)
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
        @DisplayName("#1994: a bay neither location replica holds yet is 503 LOCATION_REPLICATION_PENDING")
        void unreplicatedBayIsReplicationPending() {
            givenWorkorder(WorkorderStatus.APPROVED);

            UUID unknownBay = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3309");
            when(extBayReplicaRepository.findById(unknownBay)).thenReturn(Optional.empty());
            when(extMobileUnitReplicaRepository.existsById(unknownBay)).thenReturn(false);
            assertThatThrownBy(() ->
                            service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, unknownBay, null), ACTOR))
                    .isInstanceOfSatisfying(ReplicationPendingException.class, e -> {
                        assertThat(e.getCode()).isEqualTo("LOCATION_REPLICATION_PENDING");
                        assertThat(e.getReferenceId()).isEqualTo(unknownBay);
                        assertThat(e.getMessage()).doesNotContain(unknownBay.toString());
                    });
        }

        @Test
        @DisplayName("#1994: a mobile unit neither location replica holds yet is 503 LOCATION_REPLICATION_PENDING")
        void unreplicatedMobileUnitIsReplicationPending() {
            givenWorkorder(WorkorderStatus.APPROVED);

            UUID unknownUnit = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3319");
            when(extMobileUnitReplicaRepository.findById(unknownUnit)).thenReturn(Optional.empty());
            when(extBayReplicaRepository.existsById(unknownUnit)).thenReturn(false);
            assertThatThrownBy(() -> service.assignPosition(
                            WORKORDER_ID, request(ResourceType.MOBILE_UNIT, unknownUnit, null), ACTOR))
                    .isInstanceOfSatisfying(ReplicationPendingException.class, e -> {
                        assertThat(e.getCode()).isEqualTo("LOCATION_REPLICATION_PENDING");
                        assertThat(e.getReferenceId()).isEqualTo(unknownUnit);
                    });
        }

        @Test
        @DisplayName("#1994: an id held by the other kind's replica is positively the wrong kind: 422, not 503")
        void positionOfTheOtherKindStays422() {
            givenWorkorder(WorkorderStatus.APPROVED);

            UUID mobileUnitId = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3329");
            when(extBayReplicaRepository.findById(mobileUnitId)).thenReturn(Optional.empty());
            when(extMobileUnitReplicaRepository.existsById(mobileUnitId)).thenReturn(true);
            assertThatThrownBy(() ->
                            service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, mobileUnitId, null), ACTOR))
                    .isInstanceOf(ServicePositionInvalidException.class)
                    .hasMessageContaining("mobile unit, not a bay");

            UUID bayId = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3339");
            when(extMobileUnitReplicaRepository.findById(bayId)).thenReturn(Optional.empty());
            when(extBayReplicaRepository.existsById(bayId)).thenReturn(true);
            assertThatThrownBy(() ->
                            service.assignPosition(WORKORDER_ID, request(ResourceType.MOBILE_UNIT, bayId, null), ACTOR))
                    .isInstanceOf(ServicePositionInvalidException.class)
                    .hasMessageContaining("bay, not a mobile unit");
        }

        @Test
        @DisplayName("#1983: a bay at another site is refused")
        void refusesForeignPositions() {
            givenWorkorder(WorkorderStatus.APPROVED);

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
            // #1990: releasing a position stays independent of who holds the workorder — the
            // technician assignment is neither touched nor cleared by freeing a bay or mobile unit.
            verify(technicianAssignmentRepository, never()).save(any());
            verify(technicianAssignmentRepository, never()).saveAndFlush(any());
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

            assertThatThrownBy(
                            () -> service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, BAY_ID, null), ACTOR))
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

            assertThatThrownBy(() ->
                            service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, OTHER_BAY_ID, null), ACTOR))
                    .isInstanceOf(ServicePositionInvalidException.class)
                    .hasMessageContaining("not to the workorder's site");
        }
    }

    /**
     * #2269: DECISION-SHOPMGMT-021 rule 3 — placement keeps its site and active checks and adds duty
     * class only, never specialty capability. Checked after site and active, same ordering as the
     * inactive check.
     */
    @Nested
    @DisplayName("duty class is checked on assignPosition; specialty never is")
    class DutyClassOnAssign {

        @Test
        @DisplayName("#2269: a class 7 vehicle on a bay with ceiling 3 is refused")
        void refusesAClass7VehicleOnACeiling3Bay() {
            Workorder workorder = givenWorkorder(WorkorderStatus.APPROVED);
            workorder.setVehicleId(VEHICLE_ID);
            givenBay(BAY_ID, SITE_ID, 3, null);
            givenVehicle(VEHICLE_ID, 7);

            assertThatThrownBy(
                            () -> service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, BAY_ID, null), ACTOR))
                    .isInstanceOf(ServicePositionDutyClassExceededException.class)
                    .hasMessageContaining("7")
                    .hasMessageContaining("3");

            assertThat(workorder.getResourceType()).isNull();
            assertThat(workorder.getResourceId()).isNull();
            verify(positionRepository, never()).save(any());
        }

        @Test
        @DisplayName("#2269: a class 7 vehicle on a mobile unit with ceiling 3 is refused the same way")
        void refusesAClass7VehicleOnACeiling3MobileUnit() {
            givenWorkorder(WorkorderStatus.APPROVED).setVehicleId(VEHICLE_ID);
            givenMobileUnit(MOBILE_UNIT_ID, SITE_ID, 3);
            givenVehicle(VEHICLE_ID, 7);

            assertThatThrownBy(() -> service.assignPosition(
                            WORKORDER_ID, request(ResourceType.MOBILE_UNIT, MOBILE_UNIT_ID, null), ACTOR))
                    .isInstanceOf(ServicePositionDutyClassExceededException.class);
        }

        @Test
        @DisplayName("#2269: an unknown vehicle class skips the check and places normally")
        void unknownVehicleClassSkipsTheCheck() {
            Workorder workorder = givenWorkorder(WorkorderStatus.APPROVED);
            workorder.setVehicleId(VEHICLE_ID);
            givenBay(BAY_ID, SITE_ID, 3, null);
            // No ext_vehicle row at all: the replica has not arrived, or the publisher never sent a
            // GVWR class. Either way it is undetermined, not zero.
            when(extVehicleReplicaRepository.findById(VEHICLE_ID)).thenReturn(Optional.empty());

            assertThatCode(() -> service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, BAY_ID, null), ACTOR))
                    .doesNotThrowAnyException();
            assertThat(workorder.getResourceId()).isEqualTo(BAY_ID);
        }

        @Test
        @DisplayName("#2269: a null gvwrClass on the replica row is the same as unknown")
        void nullGvwrClassOnTheReplicaSkipsTheCheck() {
            Workorder workorder = givenWorkorder(WorkorderStatus.APPROVED);
            workorder.setVehicleId(VEHICLE_ID);
            givenBay(BAY_ID, SITE_ID, 3, null);
            givenVehicle(VEHICLE_ID, null);

            assertThatCode(() -> service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, BAY_ID, null), ACTOR))
                    .doesNotThrowAnyException();
            assertThat(workorder.getResourceId()).isEqualTo(BAY_ID);
        }

        @Test
        @DisplayName("#2269: a null maxDutyClass ceiling skips the check and places normally")
        void nullCeilingSkipsTheCheck() {
            Workorder workorder = givenWorkorder(WorkorderStatus.APPROVED);
            workorder.setVehicleId(VEHICLE_ID);
            givenBay(BAY_ID, SITE_ID, null, null);
            givenVehicle(VEHICLE_ID, 8);

            assertThatCode(() -> service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, BAY_ID, null), ACTOR))
                    .doesNotThrowAnyException();
            assertThat(workorder.getResourceId()).isEqualTo(BAY_ID);
        }

        @Test
        @DisplayName("#2269: a vehicle class within the ceiling places normally")
        void vehicleClassWithinCeilingPlaces() {
            Workorder workorder = givenWorkorder(WorkorderStatus.APPROVED);
            workorder.setVehicleId(VEHICLE_ID);
            givenBay(BAY_ID, SITE_ID, 5, null);
            givenVehicle(VEHICLE_ID, 3);

            assertThatCode(() -> service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, BAY_ID, null), ACTOR))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("#2269: no workorder vehicle at all skips the check")
        void noWorkorderVehicleSkipsTheCheck() {
            Workorder workorder = givenWorkorder(WorkorderStatus.APPROVED);
            workorder.setVehicleId(null);
            givenBay(BAY_ID, SITE_ID, 3, null);

            assertThatCode(() -> service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, BAY_ID, null), ACTOR))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("#2269: a specialty-capability mismatch alone never refuses placement")
        void specialtyCapabilityMismatchAloneNeverRefuses() {
            // A WASH_DETAIL-style bay claiming a narrow specialty list, with a duty class the vehicle
            // is well within. Nothing on this path ever asks what operation the workorder carries, so
            // a bay claiming no general work still takes the placement.
            Workorder workorder = givenWorkorder(WorkorderStatus.APPROVED);
            workorder.setVehicleId(VEHICLE_ID);
            givenBay(BAY_ID, SITE_ID, 8, List.of("WHEEL-ALIGNMENT-4-WHEEL"));
            givenVehicle(VEHICLE_ID, 2);

            assertThatCode(() -> service.assignPosition(WORKORDER_ID, request(ResourceType.BAY, BAY_ID, null), ACTOR))
                    .doesNotThrowAnyException();
            assertThat(workorder.getResourceId()).isEqualTo(BAY_ID);
        }
    }

    /**
     * #2269: the override path (recordPositionChange, reached directly by
     * overrideOperationalContext) enforces duty class even though it skips site and active — a lift's
     * rated capacity is a physical limit an override cannot waive.
     */
    @Nested
    @DisplayName("recordPositionChange refuses an over-class position (override path)")
    class RecordPositionChangeDutyClass {

        @Test
        @DisplayName("#2269: an over-class bay is refused, giving the override the same outcome as the API")
        void refusesAnOverClassBay() {
            Workorder workorder = givenWorkorder(WorkorderStatus.APPROVED);
            workorder.setVehicleId(VEHICLE_ID);
            givenBay(BAY_ID, SITE_ID, 3, null);
            givenVehicle(VEHICLE_ID, 7);

            assertThatThrownBy(
                            () -> service.recordPositionChange(workorder, ResourceType.BAY, BAY_ID, ACTOR, "Override"))
                    .isInstanceOf(ServicePositionDutyClassExceededException.class);

            verify(positionRepository, never()).save(any());
        }

        @Test
        @DisplayName("#2269: an over-class mobile unit is refused the same way")
        void refusesAnOverClassMobileUnit() {
            Workorder workorder = givenWorkorder(WorkorderStatus.APPROVED);
            workorder.setVehicleId(VEHICLE_ID);
            givenMobileUnit(MOBILE_UNIT_ID, SITE_ID, 3);
            givenVehicle(VEHICLE_ID, 7);

            assertThatThrownBy(() -> service.recordPositionChange(
                            workorder, ResourceType.MOBILE_UNIT, MOBILE_UNIT_ID, ACTOR, "Override"))
                    .isInstanceOf(ServicePositionDutyClassExceededException.class);
        }

        @Test
        @DisplayName("#2269: a HOLD position is never duty-checked, even with a set vehicle")
        void holdIsNeverDutyChecked() {
            Workorder workorder = givenWorkorder(WorkorderStatus.APPROVED);
            workorder.setVehicleId(VEHICLE_ID);
            givenVehicle(VEHICLE_ID, 8);

            assertThatCode(() -> service.recordPositionChange(workorder, ResourceType.HOLD, SITE_ID, ACTOR, "Parked"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("#2269: within-ceiling override still applies the position")
        void withinCeilingOverrideApplies() {
            Workorder workorder = givenWorkorder(WorkorderStatus.APPROVED);
            workorder.setVehicleId(VEHICLE_ID);
            givenBay(BAY_ID, SITE_ID, 5, null);
            givenVehicle(VEHICLE_ID, 3);

            assertThatCode(() -> service.recordPositionChange(workorder, ResourceType.BAY, BAY_ID, ACTOR, "Override"))
                    .doesNotThrowAnyException();
            assertThat(savedPlacement().getResourceId()).isEqualTo(BAY_ID);
        }

        @Test
        @DisplayName("#2280 F7: override naming the workorder's own over-class position is still refused, not"
                + " let through by the unchanged-placement short-circuit")
        void overrideNamingTheCurrentOverClassPositionIsRefused() {
            Workorder workorder = givenWorkorder(WorkorderStatus.APPROVED);
            workorder.setVehicleId(VEHICLE_ID);
            givenBay(BAY_ID, SITE_ID, 3, null);
            givenVehicle(VEHICLE_ID, 7);
            givenCurrentPlacement(ResourceType.BAY, BAY_ID);

            assertThatThrownBy(
                            () -> service.recordPositionChange(workorder, ResourceType.BAY, BAY_ID, ACTOR, "Override"))
                    .isInstanceOf(ServicePositionDutyClassExceededException.class);

            verify(positionRepository, never()).save(any());
        }

        @Test
        @DisplayName("#2280 F7: naming the workorder's own within-class position is still a no-op — no history row")
        void unchangedPlacementWithinClassStillReturnsEarlyWithNoHistoryRow() {
            Workorder workorder = givenWorkorder(WorkorderStatus.APPROVED);
            workorder.setVehicleId(VEHICLE_ID);
            givenBay(BAY_ID, SITE_ID, 5, null);
            givenVehicle(VEHICLE_ID, 3);
            givenCurrentPlacement(ResourceType.BAY, BAY_ID);

            assertThatCode(() -> service.recordPositionChange(workorder, ResourceType.BAY, BAY_ID, ACTOR, "Override"))
                    .doesNotThrowAnyException();

            verify(positionRepository, never()).save(any());
            verify(positionRepository, never()).saveAndFlush(any());
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

            assertThat(service.isPositionActive(ResourceType.MOBILE_UNIT, MOBILE_UNIT_ID))
                    .isFalse();
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

            assertThat(service.isPositionActive(ResourceType.BAY, unreplicatedBay))
                    .isTrue();
        }
    }

    /**
     * #2269: the read-only companion to {@code resolvePosition}'s duty-class check, used by
     * {@code handleAssignmentUpdated} so an over-class inbound position is dropped rather than
     * thrown, the same reasoning as {@link IsPositionActive}.
     */
    @Nested
    @DisplayName("isWithinDutyClass")
    class IsWithinDutyClass {

        @Test
        @DisplayName("a class 7 vehicle exceeds a bay with ceiling 3")
        void class7ExceedsCeiling3Bay() {
            givenBay(BAY_ID, SITE_ID, 3, null);
            givenVehicle(VEHICLE_ID, 7);

            assertThat(service.isWithinDutyClass(ResourceType.BAY, BAY_ID, VEHICLE_ID))
                    .isFalse();
        }

        @Test
        @DisplayName("a class 7 vehicle exceeds a mobile unit with ceiling 3")
        void class7ExceedsCeiling3MobileUnit() {
            givenMobileUnit(MOBILE_UNIT_ID, SITE_ID, 3);
            givenVehicle(VEHICLE_ID, 7);

            assertThat(service.isWithinDutyClass(ResourceType.MOBILE_UNIT, MOBILE_UNIT_ID, VEHICLE_ID))
                    .isFalse();
        }

        @Test
        @DisplayName("a vehicle within the ceiling is admitted")
        void withinCeilingIsAdmitted() {
            givenBay(BAY_ID, SITE_ID, 5, null);
            givenVehicle(VEHICLE_ID, 3);

            assertThat(service.isWithinDutyClass(ResourceType.BAY, BAY_ID, VEHICLE_ID))
                    .isTrue();
        }

        @Test
        @DisplayName("an unknown vehicle class is admitted")
        void unknownVehicleClassIsAdmitted() {
            givenBay(BAY_ID, SITE_ID, 3, null);
            when(extVehicleReplicaRepository.findById(VEHICLE_ID)).thenReturn(Optional.empty());

            assertThat(service.isWithinDutyClass(ResourceType.BAY, BAY_ID, VEHICLE_ID))
                    .isTrue();
        }

        @Test
        @DisplayName("a null ceiling is admitted")
        void nullCeilingIsAdmitted() {
            givenBay(BAY_ID, SITE_ID, null, null);
            givenVehicle(VEHICLE_ID, 8);

            assertThat(service.isWithinDutyClass(ResourceType.BAY, BAY_ID, VEHICLE_ID))
                    .isTrue();
        }

        @Test
        @DisplayName("a HOLD position is always within duty class: it is not exclusive")
        void holdIsAlwaysWithinDutyClass() {
            assertThat(service.isWithinDutyClass(ResourceType.HOLD, SITE_ID, VEHICLE_ID))
                    .isTrue();
        }

        @Test
        @DisplayName("a null resourceType, resourceId or vehicleId is within duty class")
        void nullsAreWithinDutyClass() {
            assertThat(service.isWithinDutyClass(null, BAY_ID, VEHICLE_ID)).isTrue();
            assertThat(service.isWithinDutyClass(ResourceType.BAY, null, VEHICLE_ID))
                    .isTrue();
            givenBay(BAY_ID, SITE_ID, 3, null);
            assertThat(service.isWithinDutyClass(ResourceType.BAY, BAY_ID, null))
                    .isTrue();
        }

        @Test
        @DisplayName("a position whose replica row has not arrived yet is within duty class")
        void missingReplicaRowIsWithinDutyClass() {
            UUID unreplicatedBay = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f330d");
            when(extBayReplicaRepository.findById(unreplicatedBay)).thenReturn(Optional.empty());
            givenVehicle(VEHICLE_ID, 8);

            assertThat(service.isWithinDutyClass(ResourceType.BAY, unreplicatedBay, VEHICLE_ID))
                    .isTrue();
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
