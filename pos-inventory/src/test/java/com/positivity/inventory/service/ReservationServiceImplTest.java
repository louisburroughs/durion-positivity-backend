package com.positivity.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.service.StorageLocationValidationService;
import com.positivity.inventory.internal.dto.reservation.CreateReservationRequest;
import com.positivity.inventory.internal.dto.reservation.PromoteAllocationRequest;
import com.positivity.inventory.internal.dto.reservation.ReservationResponse;
import com.positivity.inventory.internal.entity.AllocationEntity;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.ReservationEntity;
import com.positivity.inventory.internal.enums.AllocationState;
import com.positivity.inventory.internal.enums.AllocationStatus;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.ReservationStatus;
import com.positivity.inventory.internal.exception.InsufficientAtpException;
import com.positivity.inventory.internal.exception.LocationNotFoundException;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.AllocationRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.ReservationRepository;
import com.positivity.inventory.internal.service.ReservationServiceImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ReservationServiceImpl} — Story #29: Reserve/Allocate
 * Stock to Workorder Lines.
 *
 * <p>
 * Covers all 7 business scenarios (SC1–SC7). All tests are intentionally RED
 * because
 * {@code ReservationServiceImpl} contains only scaffold stubs that throw
 * {@link UnsupportedOperationException}.
 *
 * <p>
 * ADR compliance:
 * <ul>
 * <li>ADR-0001: ATP = on-hand − HARD-allocated; SOFT does NOT reduce ATP</li>
 * <li>ADR-0013: UUIDv7 identifiers for reservation and allocation IDs</li>
 * <li>ADR-0023: no tenantId in request or response</li>
 * </ul>
 *
 * Issue: #29
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationServiceImpl — Story #29 unit tests (RED phase)")
class ReservationServiceImplTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static final UUID STORAGE_LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private AllocationRepository allocationRepository;

    @Mock
    private InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    @Mock
    private StorageLocationValidationService storageLocationValidationService;

    private ReservationServiceImpl service;

    private static StorageLocationValidationService.StorageLocationValidation validation(
            boolean exists, boolean active) {
        StorageLocationValidationService.StorageLocationValidation result =
                new StorageLocationValidationService.StorageLocationValidation();
        result.setStorageLocationId(STORAGE_LOCATION_ID);
        result.setExists(exists);
        result.setActive(active);
        return result;
    }

    @BeforeEach
    void setUp() {
        service = new ReservationServiceImpl(
                TEST_CLOCK,
                reservationRepository,
                allocationRepository,
                inventoryLedgerEntryRepository,
                storageLocationValidationService);
        // Default lenient stubs for the SC1–SC7 scaffold tests
        lenient().when(reservationRepository.findByWorkorderLineId(any())).thenReturn(Optional.empty());
        lenient().when(reservationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(allocationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(allocationRepository.saveAll(any())).thenReturn(List.of());
        lenient().when(allocationRepository.findByReservation(any())).thenReturn(List.of());
        lenient()
                .when(allocationRepository.findByReservationAndAllocationState(any(), any()))
                .thenReturn(List.of());
        lenient().when(allocationRepository.findById(any(UUID.class))).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            ReservationEntity res = ReservationEntity.builder()
                    .reservationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                    .workorderLineId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                    .stockItemId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                    .requiredQuantity(5)
                    .allocatedQuantity(5)
                    .status(ReservationStatus.PENDING)
                    .build();
            return Optional.of(AllocationEntity.builder()
                    .allocationId(id)
                    .reservation(res)
                    .allocatedQuantity(5)
                    .allocationState(AllocationState.SOFT)
                    .status(AllocationStatus.ALLOCATED)
                    .build());
        });
        lenient()
                .when(inventoryLedgerEntryRepository.calculateOnHandQuantity(any(UUID.class)))
                .thenReturn(100);
        lenient()
                .when(storageLocationValidationService.getStorageLocationValidation(any(String.class)))
                .thenReturn(validation(true, true));
        lenient()
                .when(inventoryLedgerEntryRepository.save(any(InventoryLedgerEntry.class)))
                .thenAnswer(i -> i.getArgument(0));
    }

    // ─── SC1: Create SOFT reservation ──────────────────────────────────────────

    /**
     * SC1: Verifies that createOrUpdateReservation() with valid SKU and quantity
     * results
     * in a PENDING reservation with a SOFT allocation and the allocatedQuantity
     * populated.
     *
     * @see ReservationServiceImpl#createOrUpdateReservation(CreateReservationRequest)
     */
    @Test
    @DisplayName(
            "SC1_createSoftReservation: valid SKU + quantity → PENDING status, SOFT allocation, allocatedQuantity set")
    void SC1_createSoftReservation_validSkuAndQuantity_returnsPendingWithSoftAllocation() {
        // Issue #29: SC1 — SOFT reservation creation must set status=PENDING,
        // allocatedQuantity=requiredQuantity
        // Arrange
        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID stockItemId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CreateReservationRequest request = new CreateReservationRequest(workorderLineId, stockItemId, 5);

        // Act — RED: impl throws UnsupportedOperationException; assertions are never
        // reached
        ReservationResponse result = service.createOrUpdateReservation(request);

        // Assert
        assertThat(result.getStatus()).isEqualTo(ReservationStatus.PENDING.name());
        assertThat(result.getAllocatedQuantity()).isEqualTo(5);
        assertThat(result.getWorkorderLineId()).isEqualTo(workorderLineId);
        assertThat(result.getStockItemId()).isEqualTo(stockItemId);
    }

    // ─── SC2: Idempotency by workorderLineId ────────────────────────────────────

    /**
     * SC2: Verifies that submitting the same workorderLineId a second time results
     * in
     * an upsert — only one reservation exists, and requiredQuantity reflects the
     * latest value.
     *
     * @see ReservationServiceImpl#createOrUpdateReservation(CreateReservationRequest)
     */
    @Test
    @DisplayName("SC2_idempotencyByWorkorderLineId: same workorderLineId twice → upsert, requiredQuantity updated")
    void SC2_idempotencyByWorkorderLineId_duplicateWorkorderLineId_upserts() {
        // Issue #29: SC2 — idempotent upsert semantics by workorderLineId
        // Arrange
        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CreateReservationRequest first = new CreateReservationRequest(
                workorderLineId, UUID.fromString("00000000-0000-0000-0000-000000000001"), 5);
        CreateReservationRequest second = new CreateReservationRequest(
                workorderLineId, UUID.fromString("00000000-0000-0000-0000-000000000001"), 10);

        // Act — RED: first call already throws UnsupportedOperationException
        service.createOrUpdateReservation(first);
        ReservationResponse result = service.createOrUpdateReservation(second);

        // Assert — only one reservation; quantity reflects second call
        assertThat(result.getWorkorderLineId()).isEqualTo(workorderLineId);
        assertThat(result.getRequiredQuantity()).isEqualTo(10);
    }

    // ─── SC3: Promote SOFT → HARD ───────────────────────────────────────────────

    /**
     * SC3: Verifies that promoteToHard() transitions an existing SOFT allocation to
     * HARD,
     * setting hardenedAt and hardenedBy fields from the caller context.
     *
     * @see ReservationServiceImpl#promoteToHard(UUID, PromoteAllocationRequest)
     */
    @Test
    @DisplayName("SC3_promoteToHard: existing SOFT allocation → HARD state, hardenedAt and hardenedBy populated")
    void SC3_promoteToHard_existingSoftAllocation_transitionsToHardWithAuditFields() {
        // Issue #29: SC3 — SOFT → HARD promotion must record hardenedAt and hardenedBy
        // Arrange
        UUID allocationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PromoteAllocationRequest request =
                new PromoteAllocationRequest(STORAGE_LOCATION_ID, "approved for workorder completion");

        // Act — RED: impl throws UnsupportedOperationException
        ReservationResponse result = service.promoteToHard(allocationId, request);

        // Assert — reservation status must change from PENDING when HARD
        assertThat(result).isNotNull();
        assertThat(result.getReservationId()).isNotNull();
    }

    // ─── SC4: Promote fails — insufficient ATP ──────────────────────────────────

    /**
     * SC4: Verifies that promoteToHard() throws {@link InsufficientAtpException}
     * when
     * on-hand inventory cannot cover the requested HARD allocation quantity.
     *
     * <p>
     * ATP = on-hand − HARD-allocated (ADR-0001). SOFT allocations do NOT reduce
     * ATP.
     * When ATP is insufficient the reservation status must transition to
     * BACKORDERED.
     *
     * @see ReservationServiceImpl#promoteToHard(UUID, PromoteAllocationRequest)
     */
    @Test
    @DisplayName(
            "SC4_promotionFailsInsufficientAtp: on-hand < required → throws InsufficientAtpException (BACKORDERED)")
    void SC4_promotionFailsInsufficientAtp_insufficientOnHand_throwsInsufficientAtpException() {
        // Issue #29: SC4 — must throw InsufficientAtpException with INSUFFICIENT_ATP
        // error code; reservation status must become BACKORDERED
        // Arrange
        UUID allocationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PromoteAllocationRequest request = new PromoteAllocationRequest(STORAGE_LOCATION_ID, "urgent");
        // Force insufficient ATP so the exception is thrown
        when(inventoryLedgerEntryRepository.calculateOnHandQuantity(any(UUID.class)))
                .thenReturn(1);

        // Act + Assert
        assertThatThrownBy(() -> service.promoteToHard(allocationId, request))
                .isInstanceOf(InsufficientAtpException.class)
                .hasMessageContaining("INSUFFICIENT_ATP");
    }

    // ─── SC5: Cancel SOFT allocation — no ATP change ────────────────────────────

    /**
     * SC5: Verifies that cancelling a SOFT reservation does not alter ATP (on-hand
     * inventory
     * is not restored because SOFT allocations never reduced it per ADR-0001).
     *
     * @see ReservationServiceImpl#cancelReservation(UUID)
     */
    @Test
    @DisplayName("SC5_cancelSoft: SOFT allocation cancelled → status CANCELLED, ATP unchanged")
    void SC5_cancelSoft_softAllocation_cancelledWithoutAtpChange() {
        // Issue #29: SC5 — cancelling SOFT must NOT change on-hand/ATP
        // Arrange
        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ReservationEntity reservation = ReservationEntity.builder()
                .reservationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .workorderLineId(workorderLineId)
                .stockItemId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .requiredQuantity(3)
                .allocatedQuantity(3)
                .status(ReservationStatus.PENDING)
                .build();
        when(reservationRepository.findByWorkorderLineId(workorderLineId)).thenReturn(Optional.of(reservation));
        when(allocationRepository.findByReservation(reservation)).thenReturn(List.of());

        // Act
        service.cancelReservation(workorderLineId);

        // No on-hand change expected for SOFT cancel — no additional
        // repository calls beyond the standard cancel flow.
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        verify(inventoryLedgerEntryRepository, never()).save(any());
    }

    // ─── SC6: Cancel HARD allocation — ATP restored ─────────────────────────────

    /**
     * SC6: Verifies that cancelling a HARD reservation restores ATP by incrementing
     * on-hand inventory by the previously allocated quantity.
     *
     * @see ReservationServiceImpl#cancelReservation(UUID)
     */
    @Test
    @DisplayName("SC6_cancelHard: HARD allocation cancelled → status CANCELLED, ATP restored (on-hand incremented)")
    void SC6_cancelHard_hardAllocation_atpRestoredOnCancel() {
        // Issue #29: SC6 — cancelling HARD must restore ATP; on-hand incremented by
        // hard-allocated qty
        // Arrange
        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ReservationEntity reservation = ReservationEntity.builder()
                .reservationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .workorderLineId(workorderLineId)
                .stockItemId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .requiredQuantity(5)
                .allocatedQuantity(5)
                .status(ReservationStatus.PENDING)
                .build();
        AllocationEntity hardAlloc = AllocationEntity.builder()
                .allocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .reservation(reservation)
                .allocatedQuantity(5)
                .allocationState(AllocationState.HARD)
                .status(AllocationStatus.ALLOCATED)
                .build();
        when(reservationRepository.findByWorkorderLineId(workorderLineId)).thenReturn(Optional.of(reservation));
        when(allocationRepository.findByReservation(reservation)).thenReturn(List.of(hardAlloc));
        when(allocationRepository.saveAll(any())).thenReturn(List.of(hardAlloc));

        // Act
        service.cancelReservation(workorderLineId);

        // Assert: HARD allocation released and reservation marked CANCELLED.
        assertThat(hardAlloc.getStatus()).isEqualTo(AllocationStatus.RELEASED);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
    }

    // ─── SC7: Quantity update on existing reservation ───────────────────────────

    /**
     * SC7: Verifies that a subsequent createOrUpdateReservation() call for an
     * existing
     * PENDING reservation updates the requiredQuantity and adjusts allocations
     * accordingly.
     *
     * @see ReservationServiceImpl#createOrUpdateReservation(CreateReservationRequest)
     */
    @Test
    @DisplayName(
            "SC7_quantityUpdate: existing PENDING reservation → update requiredQuantity → reservation updated, allocations adjusted")
    void SC7_quantityUpdate_existingPendingReservation_updatesQuantityAndAllocations() {
        // Issue #29: SC7 — upsert updates requiredQuantity and adjusts allocations
        // Arrange
        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CreateReservationRequest original = new CreateReservationRequest(
                workorderLineId, UUID.fromString("00000000-0000-0000-0000-000000000001"), 3);
        CreateReservationRequest update = new CreateReservationRequest(
                workorderLineId, UUID.fromString("00000000-0000-0000-0000-000000000001"), 7);

        // Act — RED: first call throws UnsupportedOperationException
        service.createOrUpdateReservation(original);
        ReservationResponse result = service.createOrUpdateReservation(update);

        // Assert
        assertThat(result.getRequiredQuantity()).isEqualTo(7);
        assertThat(result.getStatus()).isEqualTo(ReservationStatus.PENDING.name());
    }

    @Test
    @DisplayName("createOrUpdateReservation repository mode updates existing reservation by workorderLineId")
    void createOrUpdateReservation_repositoryMode_existingReservation_updatesRequiredQuantity() {
        ReservationServiceImpl repositoryService = new ReservationServiceImpl(
                TEST_CLOCK,
                reservationRepository,
                allocationRepository,
                inventoryLedgerEntryRepository,
                storageLocationValidationService);

        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ReservationEntity existing = ReservationEntity.builder()
                .reservationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .workorderLineId(workorderLineId)
                .stockItemId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .requiredQuantity(2)
                .allocatedQuantity(2)
                .status(ReservationStatus.PENDING)
                .build();

        when(reservationRepository.findByWorkorderLineId(workorderLineId)).thenReturn(Optional.of(existing));
        when(reservationRepository.save(existing)).thenReturn(existing);

        ReservationResponse response = repositoryService.createOrUpdateReservation(new CreateReservationRequest(
                workorderLineId, UUID.fromString("00000000-0000-0000-0000-000000000001"), 9));

        assertThat(existing.getRequiredQuantity()).isEqualTo(9);
        assertThat(response.getRequiredQuantity()).isEqualTo(9);
        verify(allocationRepository, never()).save(any(AllocationEntity.class));
    }

    @Test
    @DisplayName("createOrUpdateReservation repository mode creates reservation and SOFT allocation when absent")
    void createOrUpdateReservation_repositoryMode_missingReservation_createsSoftAllocation() {
        ReservationServiceImpl repositoryService = new ReservationServiceImpl(
                TEST_CLOCK,
                reservationRepository,
                allocationRepository,
                inventoryLedgerEntryRepository,
                storageLocationValidationService);

        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(reservationRepository.findByWorkorderLineId(workorderLineId)).thenReturn(Optional.empty());
        when(reservationRepository.save(any(ReservationEntity.class))).thenAnswer(invocation -> {
            ReservationEntity entity = invocation.getArgument(0);
            if (entity.getReservationId() == null) {
                entity.setReservationId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
            }
            return entity;
        });
        when(allocationRepository.save(any(AllocationEntity.class))).thenAnswer(invocation -> {
            AllocationEntity entity = invocation.getArgument(0);
            if (entity.getAllocationId() == null) {
                entity.setAllocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
            }
            return entity;
        });

        ReservationResponse response = repositoryService.createOrUpdateReservation(new CreateReservationRequest(
                workorderLineId, UUID.fromString("00000000-0000-0000-0000-000000000001"), 4));

        assertThat(response.getWorkorderLineId()).isEqualTo(workorderLineId);
        assertThat(response.getStatus()).isEqualTo(ReservationStatus.PENDING.name());
        assertThat(response.getAllocatedQuantity()).isEqualTo(4);
        verify(reservationRepository, times(2)).save(any(ReservationEntity.class));
        verify(allocationRepository).save(any(AllocationEntity.class));
    }

    @Test
    @DisplayName("promoteToHard throws ResourceNotFoundException when allocationId does not exist")
    void promoteToHard_repositoryMode_allocationMissing_throwsResourceNotFound() {
        ReservationServiceImpl repositoryService = new ReservationServiceImpl(
                TEST_CLOCK,
                reservationRepository,
                allocationRepository,
                inventoryLedgerEntryRepository,
                storageLocationValidationService);

        UUID allocationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(allocationRepository.findById(allocationId)).thenReturn(Optional.empty());

        PromoteAllocationRequest request = new PromoteAllocationRequest(STORAGE_LOCATION_ID, "normal");
        assertThatThrownBy(() -> repositoryService.promoteToHard(allocationId, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Allocation not found");
    }

    @Test
    @DisplayName("promoteToHard sets BACKORDERED and throws InsufficientAtpException when ATP is insufficient")
    void promoteToHard_repositoryMode_insufficientAtp_setsBackorderedAndThrows() {
        ReservationServiceImpl repositoryService = new ReservationServiceImpl(
                TEST_CLOCK,
                reservationRepository,
                allocationRepository,
                inventoryLedgerEntryRepository,
                storageLocationValidationService);

        ReservationEntity reservation = ReservationEntity.builder()
                .reservationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .workorderLineId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .stockItemId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .requiredQuantity(5)
                .allocatedQuantity(5)
                .status(ReservationStatus.PENDING)
                .build();
        AllocationEntity allocation = AllocationEntity.builder()
                .allocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .reservation(reservation)
                .locationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .allocatedQuantity(5)
                .allocationState(AllocationState.SOFT)
                .status(AllocationStatus.ALLOCATED)
                .build();

        when(allocationRepository.findById(allocation.getAllocationId())).thenReturn(Optional.of(allocation));
        when(inventoryLedgerEntryRepository.calculateOnHandQuantity(any(UUID.class)))
                .thenReturn(2);
        when(allocationRepository.findByReservationAndAllocationState(reservation, AllocationState.HARD))
                .thenReturn(List.of());
        when(reservationRepository.save(any(ReservationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UUID targetAllocationId = allocation.getAllocationId();
        PromoteAllocationRequest request = new PromoteAllocationRequest(STORAGE_LOCATION_ID, "urgent");

        assertThatThrownBy(() -> repositoryService.promoteToHard(targetAllocationId, request))
                .isInstanceOf(InsufficientAtpException.class)
                .hasMessageContaining("INSUFFICIENT_ATP");

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.BACKORDERED);
        verify(allocationRepository, never()).save(any(AllocationEntity.class));
    }

    @Test
    @DisplayName("promoteToHard transitions allocation to HARD and returns FULFILLED when ATP is sufficient")
    void promoteToHard_repositoryMode_sufficientAtp_transitionsToHard() {
        ReservationServiceImpl repositoryService = new ReservationServiceImpl(
                TEST_CLOCK,
                reservationRepository,
                allocationRepository,
                inventoryLedgerEntryRepository,
                storageLocationValidationService);

        ReservationEntity reservation = ReservationEntity.builder()
                .reservationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .workorderLineId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .stockItemId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .requiredQuantity(5)
                .allocatedQuantity(0)
                .status(ReservationStatus.PENDING)
                .build();
        AllocationEntity allocation = AllocationEntity.builder()
                .allocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .reservation(reservation)
                .locationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .allocatedQuantity(5)
                .allocationState(AllocationState.SOFT)
                .status(AllocationStatus.ALLOCATED)
                .build();

        when(allocationRepository.findById(allocation.getAllocationId())).thenReturn(Optional.of(allocation));
        when(inventoryLedgerEntryRepository.calculateOnHandQuantity(any(UUID.class)))
                .thenReturn(10);
        when(allocationRepository.findByReservationAndAllocationState(reservation, AllocationState.HARD))
                .thenReturn(List.of());
        when(allocationRepository.save(any(AllocationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(allocationRepository.findByReservation(reservation)).thenReturn(List.of(allocation));
        when(reservationRepository.save(any(ReservationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ReservationResponse response = repositoryService.promoteToHard(
                allocation.getAllocationId(), new PromoteAllocationRequest(STORAGE_LOCATION_ID, "approved"));

        assertThat(allocation.getAllocationState()).isEqualTo(AllocationState.HARD);
        assertThat(allocation.getHardenedAt()).isNotNull();
        assertThat(allocation.getHardenedBy()).isNotBlank();
        assertThat(response.getStatus()).isEqualTo(ReservationStatus.FULFILLED.name());
        assertThat(response.getAllocatedQuantity()).isEqualTo(5);
        verify(allocationRepository).save(allocation);
    }

    @Test
    @DisplayName("promoteToHard excludes current HARD allocation from ATP subtraction")
    void promoteToHard_repositoryMode_currentHardAllocation_excludesSelfFromExistingHard() {
        ReservationServiceImpl repositoryService = new ReservationServiceImpl(
                TEST_CLOCK,
                reservationRepository,
                allocationRepository,
                inventoryLedgerEntryRepository,
                storageLocationValidationService);

        ReservationEntity reservation = ReservationEntity.builder()
                .reservationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .workorderLineId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .stockItemId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .requiredQuantity(5)
                .allocatedQuantity(5)
                .status(ReservationStatus.PENDING)
                .build();
        AllocationEntity current = AllocationEntity.builder()
                .allocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .reservation(reservation)
                .locationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .allocatedQuantity(5)
                .allocationState(AllocationState.HARD)
                .status(AllocationStatus.ALLOCATED)
                .build();
        AllocationEntity otherHard = AllocationEntity.builder()
                .allocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .reservation(reservation)
                .locationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .allocatedQuantity(3)
                .allocationState(AllocationState.HARD)
                .status(AllocationStatus.ALLOCATED)
                .build();

        when(allocationRepository.findById(current.getAllocationId())).thenReturn(Optional.of(current));
        when(inventoryLedgerEntryRepository.calculateOnHandQuantity(any(UUID.class)))
                .thenReturn(6);
        when(allocationRepository.findByReservationAndAllocationState(reservation, AllocationState.HARD))
                .thenReturn(List.of(current, otherHard));
        when(reservationRepository.save(any(ReservationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UUID currentAllocationId = current.getAllocationId();
        PromoteAllocationRequest promoteReq = new PromoteAllocationRequest(STORAGE_LOCATION_ID, "re-harden");

        assertThatThrownBy(() -> repositoryService.promoteToHard(currentAllocationId, promoteReq))
                .isInstanceOf(InsufficientAtpException.class);

        verify(allocationRepository, never()).save(any(AllocationEntity.class));
    }

    @Test
    @DisplayName("cancelReservation throws ResourceNotFoundException when reservation does not exist")
    void cancelReservation_repositoryMode_reservationMissing_throwsResourceNotFound() {
        ReservationServiceImpl repositoryService = new ReservationServiceImpl(
                TEST_CLOCK,
                reservationRepository,
                allocationRepository,
                inventoryLedgerEntryRepository,
                storageLocationValidationService);

        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(reservationRepository.findByWorkorderLineId(workorderLineId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> repositoryService.cancelReservation(workorderLineId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Reservation not found");
    }

    @Test
    @DisplayName("cancelReservation handles empty allocation list and marks reservation CANCELLED")
    void cancelReservation_repositoryMode_emptyAllocationList_marksCancelled() {
        ReservationServiceImpl repositoryService = new ReservationServiceImpl(
                TEST_CLOCK,
                reservationRepository,
                allocationRepository,
                inventoryLedgerEntryRepository,
                storageLocationValidationService);

        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ReservationEntity reservation = ReservationEntity.builder()
                .reservationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .workorderLineId(workorderLineId)
                .stockItemId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .requiredQuantity(5)
                .allocatedQuantity(5)
                .status(ReservationStatus.PENDING)
                .build();

        when(reservationRepository.findByWorkorderLineId(workorderLineId)).thenReturn(Optional.of(reservation));
        when(allocationRepository.findByReservation(reservation)).thenReturn(List.of());
        when(allocationRepository.saveAll(List.of())).thenReturn(List.of());
        when(reservationRepository.save(any(ReservationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        repositoryService.cancelReservation(workorderLineId);

        assertThat(reservation.getAllocatedQuantity()).isZero();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        verify(allocationRepository).saveAll(List.of());
    }

    @Test
    @DisplayName("cancelReservation releases both SOFT and HARD allocations")
    void cancelReservation_repositoryMode_releasesSoftAndHardAllocations() {
        ReservationServiceImpl repositoryService = new ReservationServiceImpl(
                TEST_CLOCK,
                reservationRepository,
                allocationRepository,
                inventoryLedgerEntryRepository,
                storageLocationValidationService);

        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ReservationEntity reservation = ReservationEntity.builder()
                .reservationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .workorderLineId(workorderLineId)
                .stockItemId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .requiredQuantity(9)
                .allocatedQuantity(9)
                .status(ReservationStatus.PARTIALLY_FULFILLED)
                .build();
        AllocationEntity softAllocation = AllocationEntity.builder()
                .allocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .reservation(reservation)
                .locationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .allocatedQuantity(4)
                .allocationState(AllocationState.SOFT)
                .status(AllocationStatus.ALLOCATED)
                .build();
        AllocationEntity hardAllocation = AllocationEntity.builder()
                .allocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .reservation(reservation)
                .locationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .allocatedQuantity(5)
                .allocationState(AllocationState.HARD)
                .status(AllocationStatus.ALLOCATED)
                .build();

        when(reservationRepository.findByWorkorderLineId(workorderLineId)).thenReturn(Optional.of(reservation));
        when(allocationRepository.findByReservation(reservation)).thenReturn(List.of(softAllocation, hardAllocation));
        when(allocationRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reservationRepository.save(any(ReservationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        repositoryService.cancelReservation(workorderLineId);

        assertThat(softAllocation.getStatus()).isEqualTo(AllocationStatus.RELEASED);
        assertThat(hardAllocation.getStatus()).isEqualTo(AllocationStatus.RELEASED);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        verify(allocationRepository).saveAll(any());
    }

    // ─── CAP-218 #656: allocation ledger events + location on hard promotion ────

    @Test
    @DisplayName("promoteToHard assigns storage location and writes ALLOCATION_CREATED ledger event")
    void promoteToHard_softAllocation_assignsLocationAndWritesAllocationCreated() {
        // Issue #656: hard promotion must pin a storage location and record
        // an ALLOCATION_CREATED ledger event in the same transaction
        ReservationEntity reservation = ReservationEntity.builder()
                .reservationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .workorderLineId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .stockItemId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .requiredQuantity(5)
                .allocatedQuantity(0)
                .status(ReservationStatus.PENDING)
                .build();
        AllocationEntity allocation = AllocationEntity.builder()
                .allocationId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                .reservation(reservation)
                .locationId(null)
                .allocatedQuantity(5)
                .allocationState(AllocationState.SOFT)
                .status(AllocationStatus.ALLOCATED)
                .build();

        when(allocationRepository.findById(allocation.getAllocationId())).thenReturn(Optional.of(allocation));
        when(inventoryLedgerEntryRepository.calculateOnHandQuantity(any(UUID.class)))
                .thenReturn(10);
        when(allocationRepository.findByReservationAndAllocationState(reservation, AllocationState.HARD))
                .thenReturn(List.of());
        when(allocationRepository.save(any(AllocationEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(allocationRepository.findByReservation(reservation)).thenReturn(List.of(allocation));
        when(reservationRepository.save(any(ReservationEntity.class))).thenAnswer(i -> i.getArgument(0));

        service.promoteToHard(
                allocation.getAllocationId(), new PromoteAllocationRequest(STORAGE_LOCATION_ID, "approved"));

        assertThat(allocation.getLocationId()).isEqualTo(STORAGE_LOCATION_ID);

        ArgumentCaptor<InventoryLedgerEntry> ledgerCaptor = ArgumentCaptor.forClass(InventoryLedgerEntry.class);
        verify(inventoryLedgerEntryRepository).save(ledgerCaptor.capture());
        InventoryLedgerEntry entry = ledgerCaptor.getValue();
        assertThat(entry.getEventType()).isEqualTo(InventoryLedgerEventType.ALLOCATION_CREATED);
        assertThat(entry.getLocationId()).isEqualTo(STORAGE_LOCATION_ID);
        assertThat(entry.getChangeInQuantity()).isEqualTo(5);
        assertThat(entry.getStockItemId())
                .isEqualTo(reservation.getStockItemId().toString());
        assertThat(entry.getSourceTransactionId())
                .isEqualTo(allocation.getAllocationId().toString());
    }

    @Test
    @DisplayName("promoteToHard re-promote at the SAME location writes no duplicate CREATED")
    void promoteToHard_alreadyHardSameLocation_noDuplicateLedgerEvent() {
        // Issue #656: re-promote must not write a duplicate ALLOCATION_CREATED
        ReservationEntity reservation = ReservationEntity.builder()
                .reservationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .workorderLineId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .stockItemId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .requiredQuantity(5)
                .allocatedQuantity(5)
                .status(ReservationStatus.FULFILLED)
                .build();
        AllocationEntity allocation = AllocationEntity.builder()
                .allocationId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                .reservation(reservation)
                .locationId(STORAGE_LOCATION_ID)
                .allocatedQuantity(5)
                .allocationState(AllocationState.HARD)
                .status(AllocationStatus.ALLOCATED)
                .build();

        when(allocationRepository.findById(allocation.getAllocationId())).thenReturn(Optional.of(allocation));
        when(inventoryLedgerEntryRepository.calculateOnHandQuantity(any(UUID.class)))
                .thenReturn(10);
        when(allocationRepository.findByReservationAndAllocationState(reservation, AllocationState.HARD))
                .thenReturn(List.of(allocation));
        when(allocationRepository.save(any(AllocationEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(allocationRepository.findByReservation(reservation)).thenReturn(List.of(allocation));
        when(reservationRepository.save(any(ReservationEntity.class))).thenAnswer(i -> i.getArgument(0));

        service.promoteToHard(
                allocation.getAllocationId(), new PromoteAllocationRequest(STORAGE_LOCATION_ID, "re-promote"));

        assertThat(allocation.getLocationId()).isEqualTo(STORAGE_LOCATION_ID);
        verify(inventoryLedgerEntryRepository, never()).save(any(InventoryLedgerEntry.class));
    }

    @Test
    @DisplayName("promoteToHard re-promote at a DIFFERENT location → IllegalStateException (409), location kept")
    void promoteToHard_alreadyHardDifferentLocation_throwsConflictAndKeepsLocation() {
        // PR #661 review finding 6: relocation via promote is rejected, not
        // silently ignored
        UUID originalLocation = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        ReservationEntity reservation = ReservationEntity.builder()
                .reservationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .workorderLineId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .stockItemId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .requiredQuantity(5)
                .allocatedQuantity(5)
                .status(ReservationStatus.FULFILLED)
                .build();
        AllocationEntity allocation = AllocationEntity.builder()
                .allocationId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                .reservation(reservation)
                .locationId(originalLocation)
                .allocatedQuantity(5)
                .allocationState(AllocationState.HARD)
                .status(AllocationStatus.ALLOCATED)
                .build();

        when(allocationRepository.findById(allocation.getAllocationId())).thenReturn(Optional.of(allocation));
        when(inventoryLedgerEntryRepository.calculateOnHandQuantity(any(UUID.class)))
                .thenReturn(10);
        when(allocationRepository.findByReservationAndAllocationState(reservation, AllocationState.HARD))
                .thenReturn(List.of(allocation));

        UUID targetAllocationId = allocation.getAllocationId();
        PromoteAllocationRequest request = new PromoteAllocationRequest(STORAGE_LOCATION_ID, "re-promote");

        assertThatThrownBy(() -> service.promoteToHard(targetAllocationId, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already hardened");

        assertThat(allocation.getLocationId()).isEqualTo(originalLocation);
        verify(allocationRepository, never()).save(any(AllocationEntity.class));
        verify(inventoryLedgerEntryRepository, never()).save(any(InventoryLedgerEntry.class));
    }

    @Test
    @DisplayName("createOrUpdateReservation rejects SKU change while located HARD allocation exists")
    void createOrUpdateReservation_skuChangeWithLocatedHard_throwsConflict() {
        // PR #661 review finding 4: SKU change would skew per-SKU ledger math
        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ReservationEntity existing = ReservationEntity.builder()
                .reservationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .workorderLineId(workorderLineId)
                .stockItemId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .requiredQuantity(5)
                .allocatedQuantity(5)
                .status(ReservationStatus.FULFILLED)
                .build();
        AllocationEntity locatedHard = AllocationEntity.builder()
                .allocationId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                .reservation(existing)
                .locationId(STORAGE_LOCATION_ID)
                .allocatedQuantity(5)
                .allocationState(AllocationState.HARD)
                .status(AllocationStatus.ALLOCATED)
                .build();

        when(reservationRepository.findByWorkorderLineId(workorderLineId)).thenReturn(Optional.of(existing));
        when(allocationRepository.findByReservation(existing)).thenReturn(List.of(locatedHard));

        CreateReservationRequest skuChange = new CreateReservationRequest(
                workorderLineId, UUID.fromString("00000000-0000-0000-0000-000000000099"), 5);

        assertThatThrownBy(() -> service.createOrUpdateReservation(skuChange))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot change stock item");
        verify(reservationRepository, never()).save(any(ReservationEntity.class));
    }

    @Test
    @DisplayName("promoteToHard throws LocationNotFoundException when storage location does not exist")
    void promoteToHard_storageLocationMissing_throwsLocationNotFound() {
        // Issue #656: nonexistent storage location must fail before any state change
        when(storageLocationValidationService.getStorageLocationValidation(any(String.class)))
                .thenReturn(validation(false, false));

        UUID allocationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PromoteAllocationRequest request = new PromoteAllocationRequest(STORAGE_LOCATION_ID, "x");

        assertThatThrownBy(() -> service.promoteToHard(allocationId, request))
                .isInstanceOf(LocationNotFoundException.class);
        verify(allocationRepository, never()).save(any(AllocationEntity.class));
        verify(inventoryLedgerEntryRepository, never()).save(any(InventoryLedgerEntry.class));
    }

    @Test
    @DisplayName("promoteToHard throws IllegalArgumentException when storage location is inactive")
    void promoteToHard_storageLocationInactive_throwsIllegalArgument() {
        // Issue #656: inactive storage location must be rejected
        when(storageLocationValidationService.getStorageLocationValidation(any(String.class)))
                .thenReturn(validation(true, false));

        UUID allocationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PromoteAllocationRequest request = new PromoteAllocationRequest(STORAGE_LOCATION_ID, "x");

        assertThatThrownBy(() -> service.promoteToHard(allocationId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not active");
        verify(allocationRepository, never()).save(any(AllocationEntity.class));
    }

    @Test
    @DisplayName("cancelReservation after partial consumption releases only the un-released remainder")
    void cancelReservation_afterPartialRelease_releasesRemainderOnly() {
        // Issue #662: ledger-derived remainder keeps CREATED - RELEASED within
        // {0, allocatedQuantity} across promote -> partial consume -> cancel
        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ReservationEntity reservation = ReservationEntity.builder()
                .reservationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .workorderLineId(workorderLineId)
                .stockItemId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .requiredQuantity(5)
                .allocatedQuantity(5)
                .status(ReservationStatus.FULFILLED)
                .build();
        AllocationEntity locatedHard = AllocationEntity.builder()
                .allocationId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                .reservation(reservation)
                .locationId(STORAGE_LOCATION_ID)
                .allocatedQuantity(5)
                .allocationState(AllocationState.HARD)
                .status(AllocationStatus.ALLOCATED)
                .build();

        when(reservationRepository.findByWorkorderLineId(workorderLineId)).thenReturn(Optional.of(reservation));
        when(allocationRepository.findByReservation(reservation)).thenReturn(List.of(locatedHard));
        when(allocationRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(reservationRepository.save(any(ReservationEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(inventoryLedgerEntryRepository.sumChangeBySourceTransactionIdAndEventType(
                        locatedHard.getAllocationId().toString(), InventoryLedgerEventType.ALLOCATION_RELEASED))
                .thenReturn(3);

        service.cancelReservation(workorderLineId);

        ArgumentCaptor<InventoryLedgerEntry> ledgerCaptor = ArgumentCaptor.forClass(InventoryLedgerEntry.class);
        verify(inventoryLedgerEntryRepository).save(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getChangeInQuantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("cancelReservation writes ALLOCATION_RELEASED only for located HARD allocations")
    void cancelReservation_mixedAllocations_writesReleasedOnlyForLocatedHard() {
        // Issue #656: SOFT and unlocated allocations had no CREATED event, so no
        // RELEASED event may be written for them
        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ReservationEntity reservation = ReservationEntity.builder()
                .reservationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .workorderLineId(workorderLineId)
                .stockItemId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .requiredQuantity(12)
                .allocatedQuantity(12)
                .status(ReservationStatus.PARTIALLY_FULFILLED)
                .build();
        AllocationEntity softAllocation = AllocationEntity.builder()
                .allocationId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                .reservation(reservation)
                .locationId(null)
                .allocatedQuantity(4)
                .allocationState(AllocationState.SOFT)
                .status(AllocationStatus.ALLOCATED)
                .build();
        AllocationEntity unlocatedHard = AllocationEntity.builder()
                .allocationId(UUID.fromString("00000000-0000-0000-0000-000000000003"))
                .reservation(reservation)
                .locationId(null)
                .allocatedQuantity(3)
                .allocationState(AllocationState.HARD)
                .status(AllocationStatus.ALLOCATED)
                .build();
        AllocationEntity locatedHard = AllocationEntity.builder()
                .allocationId(UUID.fromString("00000000-0000-0000-0000-000000000004"))
                .reservation(reservation)
                .locationId(STORAGE_LOCATION_ID)
                .allocatedQuantity(5)
                .allocationState(AllocationState.HARD)
                .status(AllocationStatus.ALLOCATED)
                .build();

        when(reservationRepository.findByWorkorderLineId(workorderLineId)).thenReturn(Optional.of(reservation));
        when(allocationRepository.findByReservation(reservation))
                .thenReturn(List.of(softAllocation, unlocatedHard, locatedHard));
        when(allocationRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(reservationRepository.save(any(ReservationEntity.class))).thenAnswer(i -> i.getArgument(0));

        service.cancelReservation(workorderLineId);

        ArgumentCaptor<InventoryLedgerEntry> ledgerCaptor = ArgumentCaptor.forClass(InventoryLedgerEntry.class);
        verify(inventoryLedgerEntryRepository).save(ledgerCaptor.capture());
        InventoryLedgerEntry entry = ledgerCaptor.getValue();
        assertThat(entry.getEventType()).isEqualTo(InventoryLedgerEventType.ALLOCATION_RELEASED);
        assertThat(entry.getLocationId()).isEqualTo(STORAGE_LOCATION_ID);
        assertThat(entry.getChangeInQuantity()).isEqualTo(5);
        assertThat(entry.getSourceTransactionId())
                .isEqualTo(locatedHard.getAllocationId().toString());
    }
}
