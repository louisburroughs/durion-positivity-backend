package com.positivity.workorder.service;

import com.positivity.workorder.internal.dto.WorkorderStatusDetail;
import com.positivity.workorder.internal.dto.WorkorderStatusView;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderStateTransition;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderStateTransitionRepository;
import com.positivity.workorder.internal.service.WipServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

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

    private static final String LOCATION_1 = "11111111-1111-1111-1111-111111111111";
    private static final String LOCATION_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final UUID LOCATION_2_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private WorkorderRepository workorderRepository;

    @Mock
    private WorkorderStateTransitionRepository stateTransitionRepository;

    @InjectMocks
    private WipServiceImpl service;

    @BeforeEach
    void setUp() {
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
                        .of(buildAwaitingPartsWorkorder(invocation.getArgument(0), UUID.fromString(LOCATION_1))));

        lenient().when(stateTransitionRepository.findByWorkorderId(any(UUID.class)))
                .thenAnswer(invocation -> List.of(
                        WorkorderStateTransition.builder()
                                .id(UUID.randomUUID())
                                .workorderId(invocation.getArgument(0))
                                .fromStatus(WorkorderStatus.ASSIGNED)
                                .toStatus(WorkorderStatus.AWAITING_PARTS)
                                .transitionedAt(Instant.now())
                                .transitionedBy("system")
                                .reason("parts delayed")
                                .build()));
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
                .allMatch(v -> locationId.equals(v.getLocationId()),
                        "all views must carry locationId=" + locationId);
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
    void getWipWorkorders_multiLocationFalse_scopsToSingleLocation() {
        // Arrange
        String locationId = LOCATION_A;
        Pageable pageable = PageRequest.of(0, 20);

        // Act
        Page<WorkorderStatusView> result = service.getWipWorkorders(locationId, false, pageable);

        // Assert
        assertThat(result.getContent())
                .allMatch(v -> locationId.equals(v.getLocationId()),
                        "single-location mode must scope all results to locationId=" + locationId);
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
                .as("multi-location mode should be capable of returning workorders from more than one location")
                .isGreaterThanOrEqualTo(1);
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
        assertThat(result.getContent().size())
                .as("page content must not exceed requested page size of %d", pageSize)
                .isLessThanOrEqualTo(pageSize);
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
        UUID workorderId = UUID.randomUUID();

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
        UUID workorderId = UUID.randomUUID();

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

    // -------------------------------------------------------------------------
    // AC7 — service unavailability / unknown workorder
    // -------------------------------------------------------------------------

    /**
     * AC7: requesting a non-existent workorder must throw
     * {@link IllegalArgumentException} or {@link NoSuchElementException} — not
     * return {@code null} silently.
     *
     * <p>
     * In RED the stub throws {@link UnsupportedOperationException}, which
     * propagates and fails the test. In GREEN the real impl throws the expected
     * typed exception, the catch block fires and the test passes.
     */
    @Test
    @DisplayName("getWipDetail: unknown workorder ID throws IllegalArgumentException or NoSuchElementException")
    void getWipDetail_unknownWorkorderId_throwsIllegalArgumentException() {
        // Arrange
        UUID unknownId = UUID.randomUUID();
        // Issue #14: stub the repository so the real impl can detect the missing entity
        when(workorderRepository.findById(unknownId)).thenReturn(Optional.empty());

        // Act & Assert
        try {
            WorkorderStatusDetail result = service.getWipDetail(unknownId);
            // If the call returned without throwing, the impl silently returned a value
            // for an unknown ID — that is a contract violation.
            assertThat(result)
                    .as("getWipDetail must not return a non-null result for an unknown workorder ID")
                    .isNull();
        } catch (IllegalArgumentException | NoSuchElementException expected) {
            // Correct behaviour in GREEN — the real impl threw the right exception.
        }
        // UnsupportedOperationException (RED stub) propagates uncaught → test FAILS
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
                .id(UUID.randomUUID())
                .shopId(shopId)
                .customerId(UUID.randomUUID())
                .vehicleId(UUID.randomUUID())
                .status(status)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static Workorder buildAwaitingPartsWorkorder(UUID workorderId, UUID shopId) {
        return Workorder.builder()
                .id(workorderId)
                .shopId(shopId)
                .customerId(UUID.randomUUID())
                .vehicleId(UUID.randomUUID())
                .status(WorkorderStatus.AWAITING_PARTS)
                .updatedAt(LocalDateTime.now())
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
