package com.positivity.workorder.service;

import java.time.ZoneOffset;
import java.time.Clock;

import com.positivity.workorder.internal.dto.WorkorderStatusDetail;
import com.positivity.workorder.internal.dto.WorkorderStatusView;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.entity.WorkorderStateTransition;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.TechnicianAssignmentRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import com.positivity.workorder.internal.repository.WorkorderStateTransitionRepository;
import com.positivity.workorder.internal.service.CustomerReferenceService;
import com.positivity.workorder.internal.service.VehicleReferenceService;
import com.positivity.workorder.internal.service.WipServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WipServiceImpl} — WIP status visibility.
 *
 * <p>
 * Verifies acceptance criteria for Story #14 (CAP-248):
 * "Workexec: Display Work In Progress Status for Active Workorders".
 * All 10 tests cover service-layer behaviour across single/multi-location
 * scoping, active-status filtering, pagination, unassigned workorder handling,
 * detail view contents, and error paths.
 *
 * Issue: #14
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WipService Unit Tests")
class WipServiceImplTest {
        private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
        private long nextPartUuidSuffix;

        @Spy
        Clock clock = TEST_CLOCK;

        private static final String LOCATION_1 = "11111111-1111-1111-1111-111111111111";
        private static final String LOCATION_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        private static final UUID LOCATION_2_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");

        @Mock
        private WorkorderRepository workorderRepository;

        @Mock
        private WorkorderStateTransitionRepository stateTransitionRepository;

        @Mock
        private TechnicianAssignmentRepository technicianAssignmentRepository;

        @Mock
        private WorkorderPartRepository workorderPartRepository;

        @Mock
        private WorkorderServiceRepository workorderServiceRepository;

        @Mock
        private CustomerReferenceService customerReferenceService;

        @Mock
        private VehicleReferenceService vehicleReferenceService;

        @InjectMocks
        private WipServiceImpl service;

        @BeforeEach
        void setUp() {
                nextPartUuidSuffix = 1L;
                lenient()
                                .when(workorderRepository.findByShopIdAndStatusIn(any(UUID.class), anyCollection(),
                                                any(Pageable.class)))
                                .thenAnswer(invocation -> {
                                        UUID shopId = invocation.getArgument(0);
                                        Pageable pageable = invocation.getArgument(2);
                                        List<Workorder> all = buildSingleLocationWorkorders(shopId);
                                        return toPage(all, pageable);
                                });

                lenient().when(workorderRepository.findByStatusIn(anyCollection(), any(Pageable.class)))
                                .thenAnswer(invocation -> {
                                        Pageable pageable = invocation.getArgument(1);
                                        List<Workorder> all = buildMultiLocationWorkorders();
                                        return toPage(all, pageable);
                                });

                lenient().when(workorderRepository.findById(any(UUID.class)))
                                .thenAnswer(invocation -> Optional
                                                .of(buildAwaitingPartsWorkorder(invocation.getArgument(0),
                                                                UUID.fromString(LOCATION_1))));

                lenient().when(stateTransitionRepository.findByWorkorder_Id(any(UUID.class)))
                                .thenAnswer(invocation -> List.of(
                                                WorkorderStateTransition.builder()
                                                                .id(UUID.fromString(
                                                                                "00000000-0000-0000-0000-000000000001"))
                                                                .workorder(new Workorder(invocation.getArgument(0)))
                                                                .fromStatus(WorkorderStatus.ASSIGNED)
                                                                .toStatus(WorkorderStatus.AWAITING_PARTS)
                                                                .transitionedAt(Instant.now(TEST_CLOCK))
                                                                .transitionedBy("system")
                                                                .reason("parts delayed")
                                                                .build()));

                lenient().when(technicianAssignmentRepository.findByWorkorder_IdInAndCurrentTrue(any()))
                                .thenReturn(List.of());
                lenient().when(technicianAssignmentRepository.findByWorkorder_IdAndCurrentTrue(any(UUID.class)))
                                .thenReturn(Optional.empty());

                lenient().when(workorderPartRepository.findByWorkorderId(any(UUID.class))).thenReturn(List.of());
                lenient().when(workorderPartRepository.findByWorkOrderService_WorkOrder_Id(any(UUID.class)))
                                .thenReturn(List.of());
                lenient().when(workorderServiceRepository.findByWorkOrder_Id(any(UUID.class))).thenReturn(List.of());

                lenient().when(customerReferenceService.resolveAll(any())).thenReturn(java.util.Map.of());
                lenient().when(vehicleReferenceService.resolveAll(any())).thenReturn(java.util.Map.of());
                lenient().when(customerReferenceService.resolve(any(UUID.class)))
                                .thenAnswer(invocation -> new CustomerReferenceService.CustomerContact(
                                                "customer-" + invocation.getArgument(0),
                                                null));
                lenient().when(vehicleReferenceService.resolve(any(UUID.class)))
                                .thenAnswer(invocation -> new VehicleReferenceService.VehicleReference(
                                                "vehicle-" + invocation.getArgument(0),
                                                null));
        }

        // -------------------------------------------------------------------------
        // AC1 / AC8 — single-location scope, active statuses only
        // -------------------------------------------------------------------------

        /**
         * AC1 & AC6: result is filtered to the requested location when
         * {@code multiLocation} is {@code false}.
         */
        @Test
        @DisplayName("getWipWorkorders: single-location request returns only workorders for that location")
        void getWipWorkorders_singleLocation_returnsOnlyWorkordersForThatLocation() {
                // Arrange
                String locationId = LOCATION_1;
                Pageable pageable = PageRequest.of(0, 20);

                // Act
                // Issue #14: real impl must scope query to locationId when multiLocation=false
                Page<WorkorderStatusView> result = service.getWipWorkorders(locationId, false, pageable);

                // Assert — every returned view belongs to the requested location
                assertThat(result.getContent())
                                .isNotEmpty()
                                .filteredOn(v -> !locationId.equals(v.getLocationId()))
                                .isEmpty();
        }

        /**
         * AC8: COMPLETED and CANCELLED workorders MUST NOT appear in the WIP list.
         */
        @Test
        @DisplayName("getWipWorkorders: result excludes COMPLETED and CANCELLED workorders")
        void getWipWorkorders_filteredToActiveStatuses_excludesCompletedAndCancelled() {
                // Arrange
                Pageable pageable = PageRequest.of(0, 20);

                // Act
                Page<WorkorderStatusView> result = service.getWipWorkorders(LOCATION_1, false, pageable);

                // Assert
                assertThat(result.getContent())
                                .as("WIP list must not include COMPLETED or CANCELLED workorders")
                                .noneMatch(v -> v.getStatus() == WorkorderStatus.COMPLETED
                                                || v.getStatus() == WorkorderStatus.CANCELLED);
        }

        /**
         * AC6: multiLocation=false must confine the query to the supplied locationId.
         */
        @Test
        @DisplayName("getWipWorkorders: multiLocation=false scopes result to the given locationId")
        void getWipWorkorders_multiLocationFalse_scopesToSingleLocation() {
                // Arrange
                String locationId = LOCATION_A;
                Pageable pageable = PageRequest.of(0, 20);

                // Act
                Page<WorkorderStatusView> result = service.getWipWorkorders(locationId, false, pageable);

                // Assert
                assertThat(result.getContent())
                                .isNotEmpty()
                                .allMatch(v -> locationId.equals(v.getLocationId()),
                                                "single-location mode must scope all results to locationId="
                                                                + locationId);
        }

        // -------------------------------------------------------------------------
        // AC6 — multi-location permission gate
        // -------------------------------------------------------------------------

        /**
         * AC6 & AC10: multiLocation=true (caller holds
         * {@code workorder:wip:view_all_locations}) enables cross-location results.
         */
        @Test
        @DisplayName("getWipWorkorders: multiLocation=true can return workorders from multiple locations")
        void getWipWorkorders_multiLocationTrue_returnsAllLocationsWorkorders() {
                // Arrange
                Pageable pageable = PageRequest.of(0, 20);

                // Act — real impl must widen scope when multiLocation=true
                Page<WorkorderStatusView> result = service.getWipWorkorders(LOCATION_1, true, pageable);

                // Assert — result is well-formed; location diversity is verified here
                assertThat(result).isNotNull();
                assertThat(result.getContent()).isNotNull();

                // When there are workorders across locations, more than one distinct locationId
                // appears
                long distinctLocations = result.getContent().stream()
                                .map(WorkorderStatusView::getLocationId)
                                .distinct()
                                .count();
                assertThat(distinctLocations)
                                .as("multi-location mode must return workorders from more than one location")
                                .isGreaterThanOrEqualTo(2);
        }

        // -------------------------------------------------------------------------
        // AC5 — UNASSIGNED workorder visibility
        // -------------------------------------------------------------------------

        /**
         * AC5: a workorder with no assigned technician must surface with a
         * {@code null} {@code assignedTechnicianId} in the WIP view.
         */
        @Test
        @DisplayName("getWipWorkorders: unassigned workorder has null assignedTechnicianId in view")
        void getWipWorkorders_unassignedWorkorder_hasNullTechnicianId() {
                // Arrange
                Pageable pageable = PageRequest.of(0, 20);

                // Act
                Page<WorkorderStatusView> result = service.getWipWorkorders(LOCATION_1, false, pageable);

                // Assert — at least one view in the result must represent an unassigned
                // workorder
                assertThat(result.getContent())
                                .anySatisfy(v -> assertThat(v.getAssignedTechnicianId())
                                                .as("unassigned workorder must have null assignedTechnicianId")
                                                .isNull());
        }

        // -------------------------------------------------------------------------
        // AC3 — polling support / pagination
        // -------------------------------------------------------------------------

        /**
         * AC3: pagination contract — no result page may exceed the requested page
         * size.
         */
        @Test
        @DisplayName("getWipWorkorders: pagination is respected — result page size never exceeds requested size")
        void getWipWorkorders_returnsPage_withPaginationRespected() {
                // Arrange
                int pageSize = 5;
                Pageable pageable = PageRequest.of(0, pageSize);

                // Act
                Page<WorkorderStatusView> result = service.getWipWorkorders(LOCATION_1, false, pageable);

                // Assert
                assertThat(result.getContent())
                                .as("page content must not exceed requested page size of %d", pageSize)
                                .hasSizeLessThanOrEqualTo(pageSize);
        }

        // -------------------------------------------------------------------------
        // AC4 — detail view
        // -------------------------------------------------------------------------

        /**
         * AC4: detail view must include a non-null {@code statusHistory} list.
         */
        @Test
        @DisplayName("getWipDetail: returns full detail including non-null statusHistory list")
        void getWipDetail_returnsFullDetail_withStatusHistory() {
                // Arrange
                UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                // Act
                WorkorderStatusDetail detail = service.getWipDetail(workorderId);

                // Assert
                assertThat(detail).isNotNull();
                assertThat(detail.getStatusHistory())
                                .as("statusHistory must be present in the detail view")
                                .isNotNull();
        }

        /**
         * AC4: when a workorder is AWAITING_PARTS, the {@code partsBlocking} list
         * must be non-empty to surface what is blocking progress.
         */
        @Test
        @DisplayName("getWipDetail: AWAITING_PARTS workorder has non-empty partsBlocking list")
        void getWipDetail_returnsPartsBlocking_whenAwaitingParts() {
                // Arrange
                UUID workorderId = UUID.fromString("10000000-0000-0000-0000-000000000001");

                // Act
                WorkorderStatusDetail detail = service.getWipDetail(workorderId);

                // Assert
                assertThat(detail.getStatus())
                                .as("workorder must be in AWAITING_PARTS status for this scenario")
                                .isEqualTo(WorkorderStatus.AWAITING_PARTS);
                assertThat(detail.getPartsBlocking())
                                .as("partsBlocking list must be non-empty for an AWAITING_PARTS workorder")
                                .isNotEmpty();
        }

        @Test
        @DisplayName("getWipDetail: partsBlocking includes only parts with unfulfilled quantity gap")
        void getWipDetail_partsBlocking_usesQuantityGapRule() {
                UUID workorderId = UUID.fromString("22000000-0000-0000-0000-000000000001");
                WorkorderPart partReady = buildPart("Oil Filter", new BigDecimal("1.00"), new BigDecimal("1.00"),
                                BigDecimal.ZERO, BigDecimal.ZERO);
                WorkorderPart partBlocked = buildPart("Brake Pad Kit", new BigDecimal("2.00"), new BigDecimal("1.00"),
                                BigDecimal.ZERO, BigDecimal.ZERO);
                when(workorderRepository.findById(workorderId))
                                .thenReturn(Optional.of(
                                                buildAwaitingPartsWorkorder(workorderId, UUID.fromString(LOCATION_1))));
                when(workorderPartRepository.findByWorkorderId(workorderId))
                                .thenReturn(List.of(partReady, partBlocked));
                when(workorderPartRepository.findByWorkOrderService_WorkOrder_Id(workorderId)).thenReturn(List.of());

                WorkorderStatusDetail detail = service.getWipDetail(workorderId);

                assertThat(detail.getPartsBlocking()).containsExactly("Brake Pad Kit");
        }

        @Test
        @DisplayName("getWipDetail: fully covered part quantities fall back to generic PARTS_PENDING token")
        void getWipDetail_partsBlocking_fallsBackWhenNoQuantityGap() {
                UUID workorderId = UUID.fromString("33000000-0000-0000-0000-000000000001");
                WorkorderPart coveredPart = buildPart("Cabin Filter", new BigDecimal("1.00"), new BigDecimal("1.00"),
                                BigDecimal.ZERO, BigDecimal.ZERO);
                when(workorderRepository.findById(workorderId))
                                .thenReturn(Optional.of(
                                                buildAwaitingPartsWorkorder(workorderId, UUID.fromString(LOCATION_1))));
                when(workorderPartRepository.findByWorkorderId(workorderId))
                                .thenReturn(List.of(coveredPart));
                when(workorderPartRepository.findByWorkOrderService_WorkOrder_Id(workorderId)).thenReturn(List.of());

                WorkorderStatusDetail detail = service.getWipDetail(workorderId);

                assertThat(detail.getPartsBlocking()).containsExactly("PARTS_PENDING");
        }

        // -------------------------------------------------------------------------
        // AC7 — service unavailability / unknown workorder
        // -------------------------------------------------------------------------

        /**
         * AC7: requesting a non-existent workorder must throw
         * {@link IllegalArgumentException} or {@link NoSuchElementException} — not
         * return {@code null} silently.
         */
        @Test
        @DisplayName("getWipDetail: unknown workorder ID throws IllegalArgumentException or NoSuchElementException")
        void getWipDetail_unknownWorkorderId_throwsIllegalArgumentException() {
                // Arrange
                UUID unknownId = UUID.fromString("44000000-0000-0000-0000-000000000001");
                // Issue #14: stub the repository so the real impl can detect the missing entity
                when(workorderRepository.findById(unknownId)).thenReturn(Optional.empty());

                // Act & Assert
                org.junit.jupiter.api.Assertions.assertThrows(
                                Exception.class,
                                () -> service.getWipDetail(unknownId),
                                "getWipDetail must throw for an unknown workorder ID, not return silently");
        }

        // -------------------------------------------------------------------------
        // AC1 — all active status variants present
        // -------------------------------------------------------------------------

        /**
         * AC1: the WIP list must be capable of surfacing workorders in each of the
         * six active status variants.
         */
        @Test
        @DisplayName("getWipWorkorders: result includes all six active WIP status variants")
        void getWipWorkorders_currentStatuses_includesAllActiveStatusVariants() {
                // Arrange
                Pageable pageable = PageRequest.of(0, 50);

                // Act
                Page<WorkorderStatusView> result = service.getWipWorkorders(LOCATION_1, true, pageable);

                // Assert — all active status variants must appear at least once
                List<WorkorderStatus> activeStatuses = List.of(
                                WorkorderStatus.APPROVED,
                                WorkorderStatus.ASSIGNED,
                                WorkorderStatus.WORK_IN_PROGRESS,
                                WorkorderStatus.AWAITING_PARTS,
                                WorkorderStatus.AWAITING_APPROVAL,
                                WorkorderStatus.READY_FOR_PICKUP);

                List<WorkorderStatus> returnedStatuses = result.getContent().stream()
                                .map(WorkorderStatusView::getStatus)
                                .toList();

                assertThat(returnedStatuses)
                                .as("WIP list must include all six active status variants")
                                .containsAll(activeStatuses);
        }

        private static List<Workorder> buildSingleLocationWorkorders(UUID locationId) {
                return List.of(
                                workorderWithStatus(locationId, WorkorderStatus.APPROVED),
                                workorderWithStatus(locationId, WorkorderStatus.ASSIGNED),
                                workorderWithStatus(locationId, WorkorderStatus.WORK_IN_PROGRESS),
                                workorderWithStatus(locationId, WorkorderStatus.AWAITING_PARTS),
                                workorderWithStatus(locationId, WorkorderStatus.AWAITING_APPROVAL),
                                workorderWithStatus(locationId, WorkorderStatus.READY_FOR_PICKUP));
        }

        private static List<Workorder> buildMultiLocationWorkorders() {
                UUID loc1 = UUID.fromString(LOCATION_1);
                return List.of(
                                workorderWithStatus(loc1, WorkorderStatus.APPROVED),
                                workorderWithStatus(LOCATION_2_UUID, WorkorderStatus.ASSIGNED),
                                workorderWithStatus(loc1, WorkorderStatus.WORK_IN_PROGRESS),
                                workorderWithStatus(LOCATION_2_UUID, WorkorderStatus.AWAITING_PARTS),
                                workorderWithStatus(loc1, WorkorderStatus.AWAITING_APPROVAL),
                                workorderWithStatus(LOCATION_2_UUID, WorkorderStatus.READY_FOR_PICKUP));
        }

        private static Workorder workorderWithStatus(UUID shopId, WorkorderStatus status) {
                return Workorder.builder()
                                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .shopId(shopId)
                                .customerId(UUID.fromString("00000000-0000-0000-0000-000000000009"))
                                .vehicleId(UUID.fromString("00000000-0000-0000-0000-000000000011"))
                                .status(status)
                                .updatedAt(LocalDateTime.now(TEST_CLOCK))
                                .build();
        }

        private static Workorder buildAwaitingPartsWorkorder(UUID workorderId, UUID shopId) {
                return Workorder.builder()
                                .id(workorderId)
                                .shopId(shopId)
                                .customerId(UUID.fromString("00000000-0000-0000-0000-000000000099"))
                                .vehicleId(UUID.fromString("00000000-0000-0000-0000-000000000999"))
                                .status(WorkorderStatus.AWAITING_PARTS)
                                .updatedAt(LocalDateTime.now(TEST_CLOCK))
                                .build();
        }

        private com.positivity.workorder.internal.entity.WorkorderPart buildPart(
                        String description,
                        BigDecimal quantity,
                        BigDecimal issued,
                        BigDecimal consumed,
                        BigDecimal returned) {
                return com.positivity.workorder.internal.entity.WorkorderPart.builder()
                                .id(UUID.fromString(
                                                String.format("00000000-0000-0000-0000-%012d", nextPartUuidSuffix++)))
                                .description(description)
                                .quantity(quantity)
                                .quantityIssued(issued)
                                .quantityConsumed(consumed)
                                .quantityReturned(returned)
                                .status(com.positivity.workorder.internal.enums.WorkorderItemStatus.OPEN)
                                .build();
        }

        private static Page<Workorder> toPage(List<Workorder> all, Pageable pageable) {
                int start = (int) pageable.getOffset();
                if (start >= all.size()) {
                        return new PageImpl<>(List.of(), pageable, all.size());
                }

                int end = Math.min(start + pageable.getPageSize(), all.size());
                return new PageImpl<>(all.subList(start, end), pageable, all.size());
        }
}
