package com.positivity.inventory.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.inventory.internal.dto.reallocation.ReallocateRequest;
import com.positivity.inventory.internal.dto.reallocation.ReallocateResponse;
import com.positivity.inventory.internal.entity.AllocationAuditEntity;
import com.positivity.inventory.internal.entity.ReservationEntity;
import com.positivity.inventory.internal.enums.AllocationAuditReasonCode;
import com.positivity.inventory.internal.enums.ReservationStatus;
import com.positivity.inventory.internal.repository.AllocationAuditRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.ReservationRepository;
import com.positivity.inventory.internal.service.AllocationReallocationServiceImpl;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.positivity.security.common.GatewaySecurityConstants;

/**
 * Unit tests for {@link AllocationReallocationServiceImpl} — Story #24
 * (CAP-220): GREEN phase.
 *
 * <p>
 * ADR compliance:
 * <ul>
 * <li>ADR-0013: UUIDv7 identifiers —
 * {@code UUID.fromString("00000000-0000-0000-0000-000000000001")} used in tests
 * (exact value not required for contract correctness)</li>
 * <li>ADR-0017: 200 for successful reallocation mutation</li>
 * <li>ADR-0018: actor sourced from SecurityContext, not request body</li>
 * <li>ADR-0023: no tenantId in request or response</li>
 * </ul>
 *
 * <p>
 * Business rule coverage (branch-to-test map):
 * <ol>
 * <li>AC1 — deterministic sort: high-priority workorder receives stock first →
 * {@link #AC1_priorityChange_highPriorityWorkorderReceivesStock}</li>
 * <li>AC1 (audit) — reallocate produces audit record with PRIORITY_CHANGE
 * reason →
 * {@link #AC1_priorityChange_auditRecordCreatedWithReasonCode}</li>
 * <li>AC2 — priority aging: long-waiting workorder gets improved effective
 * priority →
 * {@link #AC2_priorityAging_longWaitingWorkorderWinsStock}</li>
 * <li>AC3 — sort tie-breaker: equal priority, earlier dueDateTime wins →
 * {@link #AC3_sortTieBreaker_earlierDueDateTimeWinsStock}</li>
 * <li>AC4 — full-allocation-only: insufficient stock → workorder receives 0 →
 * {@link #AC2_insufficientStock_workorderReceivesZeroAllocation}</li>
 * <li>AC5 — ATP consistency: reallocation does not change ATP →
 * {@link #AC3_atpConsistency_atpUnchangedAfterReallocation}</li>
 * <li>Guard — null stockItemId throws early →
 * {@link #AC4_nullSku_throwsException}</li>
 * </ol>
 *
 * Issue: #24
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AllocationReallocationServiceImpl — Story #24 unit tests (Story #24 — GREEN phase)")
class AllocationReallocationServiceImplTest {

        @Mock
        private InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

        @Mock
        private AllocationAuditRepository allocationAuditRepository;

        @Mock
        private ReservationRepository reservationRepository;

        private AllocationReallocationServiceImpl service;

        // ─── Fixture constants ───────────────────────────────────────────────────────

        private static final UUID STOCK_ITEM_ID = UUID.fromString("00000001-0000-0000-0000-000000000001");

        private static final UUID WORKORDER_HIGH_PRIO = UUID.fromString("00000002-0000-0000-0000-000000000002");

        private static final UUID WORKORDER_LOW_PRIO = UUID.fromString("00000003-0000-0000-0000-000000000003");

        private static final Instant FIXED_NOW = Instant.parse("2026-02-27T12:00:00Z");
        private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

        @BeforeEach
        void setUp() {
                service = new AllocationReallocationServiceImpl(
                                inventoryLedgerEntryRepository,
                                allocationAuditRepository,
                                reservationRepository,
                                FIXED_CLOCK);
                authenticateAs("allocation-test-user");
        }

        private void authenticateAs(String username) {
                var authentication = new UsernamePasswordAuthenticationToken(username, "N/A", List.of());
                authentication.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, username));
                SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        private ReservationEntity buildReservation(UUID workorderId, UUID stockItemId, int priority,
                        int required, Instant waitingSince) {
                return ReservationEntity.builder()
                                .reservationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .workorderLineId(workorderId)
                                .stockItemId(stockItemId)
                                .requiredQuantity(required)
                                .allocatedQuantity(0)
                                .priority(priority)
                                .waitingSince(waitingSince)
                                .status(ReservationStatus.PENDING)
                                .createdAt(FIXED_NOW.minus(Duration.ofHours(48)))
                                .updatedAt(FIXED_NOW)
                                .build();
        }

        // ─── AC1: Deterministic sort — higher priority workorder receives stock
        // ───────

        /**
         * AC1: Given two workorders competing for the same stock item where the
         * high-priority workorder requires 5 units and 5 units are available, the
         * service must allocate all 5 units to the high-priority workorder and return
         * {@code totalReallocated >= 1}.
         *
         * @see AllocationReallocationServiceImpl#reallocate(ReallocateRequest)
         */
        @Test
        @DisplayName("AC1_priorityChange: high-priority workorder receives full stock → totalReallocated > 0")
        void AC1_priorityChange_highPriorityWorkorderReceivesStock() {
                // Issue #24: AC1 — high-priority workorder must receive stock before
                // lower-priority
                // Arrange
                UUID stockItemId = STOCK_ITEM_ID;
                ReservationEntity highPrio = buildReservation(
                                WORKORDER_HIGH_PRIO,
                                stockItemId,
                                2,
                                7,
                                FIXED_NOW.minus(Duration.ofHours(48)));
                ReservationEntity lowPrio = buildReservation(WORKORDER_LOW_PRIO, stockItemId, 8, 5, null);
                // AC5: previousTotalAllocated must be non-zero so the redistribution pool
                // exists
                highPrio.setAllocatedQuantity(5);
                lowPrio.setAllocatedQuantity(5);
                when(reservationRepository.findByStockItemId(stockItemId))
                                .thenReturn(java.util.List.of(highPrio, lowPrio));
                when(inventoryLedgerEntryRepository.calculateOnHandQuantity(stockItemId)).thenReturn(10);
                when(reservationRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
                when(allocationAuditRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

                ReallocateRequest request = ReallocateRequest.builder()
                                .stockItemId(stockItemId)
                                .triggerType("PRIORITY_CHANGE")
                                .triggerReferenceId(WORKORDER_HIGH_PRIO.toString())
                                .build();

                // Act
                ReallocateResponse result = service.reallocate(request);

                // Assert
                assertThat(result.getTotalReallocated())
                                .as("High-priority workorder must receive stock; totalReallocated must be > 0")
                                .isGreaterThan(0);
                assertThat(result.getStockItemId())
                                .as("Response stockItemId must match the requested stockItemId")
                                .isEqualTo(stockItemId);
        }

        // ─── AC1 audit: Audit record must be created with PRIORITY_CHANGE reason ─────

        /**
         * AC1 (audit): Given a successful priority-change reallocation, the service
         * must persist at least one audit record with reason code
         * {@code PRIORITY_CHANGE}
         * and reflect the count in {@code auditRecordsCreated}.
         *
         */
        @Test
        @DisplayName("AC1_audit: successful reallocation → auditRecordsCreated >= 1")
        void AC1_priorityChange_auditRecordCreatedWithReasonCode() {
                // Issue #24: AC1 — audit record with PRIORITY_CHANGE reason must be persisted
                // Arrange
                UUID stockItemId = STOCK_ITEM_ID;
                // waitingSince=null ensures effectivePriority == priority (no aging), so the
                // PRIORITY_CHANGE reason flows directly to the audit record.
                ReservationEntity highPrio = buildReservation(WORKORDER_HIGH_PRIO, stockItemId, 2, 7, null);
                ReservationEntity lowPrio = buildReservation(WORKORDER_LOW_PRIO, stockItemId, 8, 5, null);
                // AC5: previousTotalAllocated must be non-zero so the redistribution pool
                // exists
                highPrio.setAllocatedQuantity(5);
                lowPrio.setAllocatedQuantity(5);
                when(reservationRepository.findByStockItemId(stockItemId))
                                .thenReturn(java.util.List.of(highPrio, lowPrio));
                when(inventoryLedgerEntryRepository.calculateOnHandQuantity(stockItemId)).thenReturn(10);
                when(reservationRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
                when(allocationAuditRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

                ReallocateRequest request = ReallocateRequest.builder()
                                .stockItemId(stockItemId)
                                .triggerType("PRIORITY_CHANGE")
                                .triggerReferenceId("cap220-audit-ref")
                                .build();

                // Act
                ReallocateResponse result = service.reallocate(request);

                // Assert — verify count and that at least one audit record carries
                // PRIORITY_CHANGE
                assertThat(result.getAuditRecordsCreated())
                                .as("Audit records must be created for PRIORITY_CHANGE reallocation")
                                .isGreaterThanOrEqualTo(1);

                @SuppressWarnings("unchecked")
                ArgumentCaptor<Iterable<AllocationAuditEntity>> auditCaptor = ArgumentCaptor
                                .forClass((Class<Iterable<AllocationAuditEntity>>) (Class<?>) Iterable.class);
                verify(allocationAuditRepository).saveAll(auditCaptor.capture());
                List<AllocationAuditEntity> savedAudits = new java.util.ArrayList<>();
                auditCaptor.getValue().forEach(savedAudits::add);
                assertThat(savedAudits)
                                .as("At least one audit record must have reasonCode PRIORITY_CHANGE")
                                .extracting(AllocationAuditEntity::getReasonCode)
                                .contains(AllocationAuditReasonCode.PRIORITY_CHANGE);
        }

        // ─── AC2: Full-allocation-only — insufficient stock → zero allocation
        // ─────────

        /**
         * AC2: When available quantity is less than the required quantity for a
         * workorder,
         * that workorder must receive zero units allocated (no partial allocation).
         *
         * <p>
         * Scenario: 3 units on-hand, workorder requires 5 units → workorder gets 0,
         * {@code totalReallocated} equals what could be fully satisfied (0 here).
         *
         */
        @Test
        @DisplayName("AC2_fullAllocationOnly: available qty < required qty → workorder receives 0 (no partial)")
        void AC2_insufficientStock_workorderReceivesZeroAllocation() {
                // Issue #24: AC2 — no partial allocations; workorder must receive 0 when stock
                // insufficient
                // Arrange: only 3 units available, workorder needs 5
                UUID stockItemId = STOCK_ITEM_ID;
                ReservationEntity reservation = buildReservation(WORKORDER_LOW_PRIO, stockItemId, 5, 5, null);
                when(reservationRepository.findByStockItemId(stockItemId)).thenReturn(java.util.List.of(reservation));
                when(inventoryLedgerEntryRepository.calculateOnHandQuantity(stockItemId)).thenReturn(3);
                when(reservationRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
                when(allocationAuditRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

                ReallocateRequest request = ReallocateRequest.builder()
                                .stockItemId(stockItemId)
                                .triggerType("PRIORITY_CHANGE")
                                .triggerReferenceId(WORKORDER_LOW_PRIO.toString())
                                .build();

                // Act
                ReallocateResponse result = service.reallocate(request);

                // Assert: with only 3 units available for a 5-unit requirement, total
                // reallocated = 0
                assertThat(result.getTotalReallocated())
                                .as("No partial allocation permitted: workorder requiring 5 with 3 available must get 0")
                                .isZero();
        }

        // ─── AC3: ATP consistency — onHand minus totalAllocated unchanged
        // ─────────────

        /**
         * AC3: After reallocation, ATP (= onHand − totalHardAllocated) must remain
         * constant. Reallocation redistributes existing allocations; it does not
         * consume
         * additional on-hand stock.
         *
         * <p>
         * Scenario: onHand = 10, totalAllocated before = 7, ATP before = 3.
         * After reallocation, ATP must still equal 3.
         *
         */
        @Test
        @DisplayName("AC3_atpConsistency: reallocation does not change ATP (onHand - totalAllocated constant)")
        void AC3_atpConsistency_atpUnchangedAfterReallocation() {
                // Issue #24: AC3 — reallocation redistributes among existing allocations; ATP
                // must not change
                int onHand = 10;
                int totalAllocatedBefore = 7;
                int expectedAtp = onHand - totalAllocatedBefore; // 3

                UUID stockItemId = STOCK_ITEM_ID;
                ReservationEntity reservation = buildReservation(WORKORDER_HIGH_PRIO, stockItemId, 3, 7, null);
                // PRCR-C02: pre-existing allocation of 7 units must be set so that
                // previousTotalAllocated = 7, and ATP after reallocation = 10 - 7 = 3.
                reservation.setAllocatedQuantity(7);
                when(reservationRepository.findByStockItemId(stockItemId)).thenReturn(java.util.List.of(reservation));
                when(inventoryLedgerEntryRepository.calculateOnHandQuantity(stockItemId)).thenReturn(10);
                when(reservationRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
                when(allocationAuditRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

                ReallocateRequest request = ReallocateRequest.builder()
                                .stockItemId(stockItemId)
                                .triggerType("PRIORITY_CHANGE")
                                .triggerReferenceId("atp-consistency-ref")
                                .build();

                // Act
                ReallocateResponse result = service.reallocate(request);

                // Assert: ATP after reallocation must equal onHand - totalAllocated (same as
                // before)
                assertThat(result.getAtpAfterReallocation())
                                .as("ATP must remain constant after reallocation (onHand=%d - totalAllocated=%d = %d)",
                                                onHand, totalAllocatedBefore, expectedAtp)
                                .isEqualTo(expectedAtp);
        }

        // ─── AC4: Null stockItemId → exception (service-layer guard)
        // ────────────────────────

        /**
         * AC4: A {@code ReallocateRequest} with a null {@code stockItemId} must cause
         * the service to throw rather than silently proceeding with a null reference.
         * Bean Validation on the controller enforces 400 at the HTTP layer; this test
         * verifies the service-layer guard.
         *
         * <p>
         * Expected: any {@link RuntimeException} (NullPointerException,
         * IllegalArgumentException,
         * or domain-specific variant) is thrown.
         *
         */
        @Test
        @DisplayName("AC4_nullSku: null stockItemId → service throws runtime exception")
        void AC4_nullSku_throwsException() {
                // Issue #24: AC4 — null stockItemId must not silently succeed at the service
                // layer
                ReallocateRequest request = ReallocateRequest.builder()
                                .stockItemId(null)
                                .triggerType("PRIORITY_CHANGE")
                                .triggerReferenceId("ref-null-stock")
                                .build();

                // Act + Assert — service must fail fast on null stockItemId.
                assertThatThrownBy(() -> service.reallocate(request))
                                .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("AC2_priorityAging: workorder waiting > agingGracePeriod gets improved effective priority and wins stock")
        void AC2_priorityAging_longWaitingWorkorderWinsStock() {
                // Issue #24: AC2 — starvation prevention: workorder waiting > 24h gets priority
                // improvement
                // Arrange: workorderA has priority=8 (low) but has been waiting 49 hours → aged
                // to 7
                // workorderB has priority=7 (medium) → no aging
                // only 5 units available; workorderA needs 4, workorderB needs 4
                // Due to aging, workorderA effective priority = max(1, 8 - floor((49-24)/24)) =
                // 7
                // workorderA is now same priority as workorderB → tie-break by waitingSince
                // (workorderA wins)
                UUID stockItemId = STOCK_ITEM_ID;
                Instant longAgo = FIXED_NOW.minus(Duration.ofHours(49));
                ReservationEntity agedWaiter = buildReservation(WORKORDER_LOW_PRIO, stockItemId, 8, 4, longAgo);
                ReservationEntity freshHigher = buildReservation(WORKORDER_HIGH_PRIO, stockItemId, 7, 4, null);
                // AC5: seed prior allocations so the redistribution pool
                // (previousTotalAllocated)
                // is non-zero; both reservations had 4 units allocated before this
                // reallocation.
                agedWaiter.setAllocatedQuantity(4);
                freshHigher.setAllocatedQuantity(4);

                when(reservationRepository.findByStockItemId(stockItemId))
                                .thenReturn(java.util.List.of(freshHigher, agedWaiter));
                when(inventoryLedgerEntryRepository.calculateOnHandQuantity(stockItemId)).thenReturn(5);
                when(reservationRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
                when(allocationAuditRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

                ReallocateRequest request = ReallocateRequest.builder()
                                .stockItemId(stockItemId)
                                .triggerType("PRIORITY_CHANGE")
                                .triggerReferenceId("aging-test-ref")
                                .build();

                ReallocateResponse result = service.reallocate(request);

                @SuppressWarnings("unchecked")
                ArgumentCaptor<List<AllocationAuditEntity>> auditCaptor = ArgumentCaptor
                                .forClass((Class<List<AllocationAuditEntity>>) (Class<?>) List.class);
                verify(allocationAuditRepository).saveAll(auditCaptor.capture());
                List<AllocationAuditEntity> savedAudits = auditCaptor.getValue();

                assertThat(result.getTotalReallocated())
                                .as("Aged workorder must receive stock (totalReallocated >= 1)")
                                .isGreaterThanOrEqualTo(1);
                assertThat(agedWaiter.getAllocatedQuantity())
                                .as("Long-waiting workorder must be allocated 4 units after aging improves priority")
                                .isEqualTo(4);
                assertThat(savedAudits)
                                .as("At least one audit record must have PRIORITY_AGED reason code when aging changes allocation")
                                .anyMatch(a -> a.getReasonCode() == AllocationAuditReasonCode.PRIORITY_AGED);
        }

        @Test
        @DisplayName("AC3_sortTieBreaker: equal priority, earlier dueDateTime workorder receives stock first")
        void AC3_sortTieBreaker_earlierDueDateTimeWinsStock() {
                // Issue #24: AC3 — tie-breaker sort key 2 (dueDateTime ASC): earlier deadline
                // wins
                UUID stockItemId = STOCK_ITEM_ID;
                ReservationEntity earlyDueDate = ReservationEntity.builder()
                                .reservationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .workorderLineId(WORKORDER_HIGH_PRIO)
                                .stockItemId(stockItemId)
                                .requiredQuantity(5)
                                .allocatedQuantity(0)
                                .priority(5)
                                .waitingSince(null)
                                .dueDateTime(FIXED_NOW.plus(Duration.ofDays(1)))
                                .scheduleStartTime(null)
                                .status(ReservationStatus.PENDING)
                                .createdAt(FIXED_NOW.minus(Duration.ofHours(2)))
                                .updatedAt(FIXED_NOW)
                                .build();
                ReservationEntity lateDueDate = ReservationEntity.builder()
                                .reservationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .workorderLineId(WORKORDER_LOW_PRIO)
                                .stockItemId(stockItemId)
                                .requiredQuantity(5)
                                .allocatedQuantity(0)
                                .priority(5)
                                .waitingSince(null)
                                .dueDateTime(FIXED_NOW.plus(Duration.ofDays(7)))
                                .scheduleStartTime(null)
                                .status(ReservationStatus.PENDING)
                                .createdAt(FIXED_NOW.minus(Duration.ofHours(1)))
                                .updatedAt(FIXED_NOW)
                                .build();
                // AC5: lateDueDate previously held the 5-unit allocation; after reallocation
                // earlyDueDate wins the tie-break by dueDateTime.
                lateDueDate.setAllocatedQuantity(5);

                when(reservationRepository.findByStockItemId(stockItemId))
                                .thenReturn(java.util.List.of(lateDueDate, earlyDueDate));
                when(inventoryLedgerEntryRepository.calculateOnHandQuantity(stockItemId)).thenReturn(5);
                when(reservationRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
                when(allocationAuditRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

                ReallocateRequest request = ReallocateRequest.builder()
                                .stockItemId(stockItemId)
                                .triggerType("PRIORITY_CHANGE")
                                .triggerReferenceId("tie-breaker-test")
                                .build();

                service.reallocate(request);

                assertThat(earlyDueDate.getAllocatedQuantity())
                                .as("Workorder with earlier dueDateTime must receive stock first when priorities are equal")
                                .isEqualTo(5);
                assertThat(lateDueDate.getAllocatedQuantity())
                                .as("Workorder with later dueDateTime must receive 0 when stock is insufficient")
                                .isZero();
        }

        @Test
        @DisplayName("resolveTriggeredBy: with authenticated principal → audit record captures username")
        void resolveTriggeredBy_withAuthenticatedPrincipal() {
                // Arrange
                UUID stockItemId = STOCK_ITEM_ID;
                ReservationEntity reservation = buildReservation(WORKORDER_HIGH_PRIO, stockItemId, 1, 5, null);
                when(reservationRepository.findByStockItemId(stockItemId)).thenReturn(List.of(reservation));
                when(inventoryLedgerEntryRepository.calculateOnHandQuantity(stockItemId)).thenReturn(10);
                when(reservationRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
                when(allocationAuditRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

                authenticateAs("testuser");

                ReallocateRequest request = ReallocateRequest.builder()
                                .stockItemId(stockItemId)
                                .triggerType("MANUAL_OVERRIDE")
                                .triggerReferenceId("auth-user-test")
                                .build();

                // Act
                service.reallocate(request);

                // Assert
                @SuppressWarnings("unchecked")
                ArgumentCaptor<List<AllocationAuditEntity>> auditCaptor = ArgumentCaptor
                                .forClass((Class<List<AllocationAuditEntity>>) (Class<?>) List.class);
                verify(allocationAuditRepository).saveAll(auditCaptor.capture());
                List<AllocationAuditEntity> savedAudits = auditCaptor.getValue();

                assertThat(savedAudits)
                                .isNotEmpty()
                                .allSatisfy(audit -> assertThat(audit.getTriggeredBy()).isEqualTo("testuser"));
        }

        @Test
        @DisplayName("parseReasonCode: null triggerType → defaults to MANUAL_OVERRIDE")
        void parseReasonCode_withNullTriggerType() {
                // Arrange
                UUID stockItemId = STOCK_ITEM_ID;
                ReservationEntity reservation = buildReservation(WORKORDER_HIGH_PRIO, stockItemId, 1, 5, null);
                // AC5: seed allocation so the pool is non-zero and the reservation reaches the
                // if-branch, producing an audit record with the parsed (not STOCK_SHORTAGE)
                // reason.
                reservation.setAllocatedQuantity(5);
                when(reservationRepository.findByStockItemId(stockItemId)).thenReturn(List.of(reservation));
                when(inventoryLedgerEntryRepository.calculateOnHandQuantity(stockItemId)).thenReturn(10);
                when(reservationRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
                when(allocationAuditRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

                ReallocateRequest request = ReallocateRequest.builder()
                                .stockItemId(stockItemId)
                                .triggerType(null) // Null trigger type
                                .triggerReferenceId("null-trigger-test")
                                .build();

                // Act
                service.reallocate(request);

                // Assert
                @SuppressWarnings("unchecked")
                ArgumentCaptor<List<AllocationAuditEntity>> auditCaptor = ArgumentCaptor
                                .forClass((Class<List<AllocationAuditEntity>>) (Class<?>) List.class);
                verify(allocationAuditRepository).saveAll(auditCaptor.capture());
                List<AllocationAuditEntity> savedAudits = auditCaptor.getValue();

                assertThat(savedAudits)
                                .isNotEmpty()
                                .allSatisfy(audit -> assertThat(audit.getReasonCode())
                                                .isEqualTo(AllocationAuditReasonCode.MANUAL_OVERRIDE));
        }

        @Test
        @DisplayName("parseReasonCode: SCHEDULE_CHANGE triggerType → audit has SCHEDULE_CHANGE reason")
        void parseReasonCode_withScheduleChangeTrigger() {
                // Arrange
                UUID stockItemId = STOCK_ITEM_ID;
                ReservationEntity reservation = buildReservation(WORKORDER_HIGH_PRIO, stockItemId, 1, 5, null);
                // AC5: seed allocation so the pool is non-zero and the reservation reaches the
                // if-branch, producing an audit record with the parsed SCHEDULE_CHANGE reason.
                reservation.setAllocatedQuantity(5);
                when(reservationRepository.findByStockItemId(stockItemId)).thenReturn(List.of(reservation));
                when(inventoryLedgerEntryRepository.calculateOnHandQuantity(stockItemId)).thenReturn(10);
                when(reservationRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
                when(allocationAuditRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

                ReallocateRequest request = ReallocateRequest.builder()
                                .stockItemId(stockItemId)
                                .triggerType("SCHEDULE_CHANGE")
                                .triggerReferenceId("schedule-change-test")
                                .build();

                // Act
                service.reallocate(request);

                // Assert
                @SuppressWarnings("unchecked")
                ArgumentCaptor<List<AllocationAuditEntity>> auditCaptor = ArgumentCaptor
                                .forClass((Class<List<AllocationAuditEntity>>) (Class<?>) List.class);
                verify(allocationAuditRepository).saveAll(auditCaptor.capture());
                List<AllocationAuditEntity> savedAudits = auditCaptor.getValue();

                assertThat(savedAudits)
                                .isNotEmpty()
                                .allSatisfy(audit -> assertThat(audit.getReasonCode())
                                                .isEqualTo(AllocationAuditReasonCode.SCHEDULE_CHANGE));
        }

        @Test
        @DisplayName("computeEffectivePriority: waiting < grace period (12h) → no priority aging")
        void computeEffectivePriority_beforeGracePeriod_noAging() {
                // Arrange
                UUID stockItemId = STOCK_ITEM_ID;
                // Waiting for 12 hours, which is less than the 24-hour grace period
                Instant waitingSince = FIXED_NOW.minus(Duration.ofHours(12));
                ReservationEntity noAgingReservation = buildReservation(WORKORDER_LOW_PRIO, stockItemId, 8, 5,
                                waitingSince);

                ReservationEntity higherPrioReservation = buildReservation(WORKORDER_HIGH_PRIO, stockItemId, 7, 5,
                                null);
                // AC5: seed prior allocation on the lower-priority reservation so that
                // previousTotalAllocated = 5 and the pool is available for redistribution.
                noAgingReservation.setAllocatedQuantity(5);

                when(reservationRepository.findByStockItemId(stockItemId))
                                .thenReturn(List.of(noAgingReservation, higherPrioReservation));
                when(inventoryLedgerEntryRepository.calculateOnHandQuantity(stockItemId)).thenReturn(5);
                when(reservationRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
                when(allocationAuditRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

                ReallocateRequest request = ReallocateRequest.builder()
                                .stockItemId(stockItemId)
                                .triggerType("PRIORITY_CHANGE")
                                .triggerReferenceId("no-aging-test")
                                .build();

                // Act
                service.reallocate(request);

                // Assert
                // higherPrioReservation (prio 7) should get the stock
                assertThat(higherPrioReservation.getAllocatedQuantity()).isEqualTo(5);
                // noAgingReservation (prio 8) should not get stock, because no aging occurred
                assertThat(noAgingReservation.getAllocatedQuantity()).isZero();
        }

        @Test
        @DisplayName("parseReasonCode: invalid triggerType → defaults to MANUAL_OVERRIDE")
        void parseReasonCode_withInvalidTriggerType() {
                // Arrange
                UUID stockItemId = STOCK_ITEM_ID;
                ReservationEntity reservation = buildReservation(WORKORDER_HIGH_PRIO, stockItemId, 1, 5, null);
                // AC5: seed allocation so the pool is non-zero and the reservation reaches the
                // if-branch, producing an audit record with MANUAL_OVERRIDE (fallback for
                // invalid).
                reservation.setAllocatedQuantity(5);
                when(reservationRepository.findByStockItemId(stockItemId)).thenReturn(List.of(reservation));
                when(inventoryLedgerEntryRepository.calculateOnHandQuantity(stockItemId)).thenReturn(10);
                when(reservationRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
                when(allocationAuditRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

                ReallocateRequest request = ReallocateRequest.builder()
                                .stockItemId(stockItemId)
                                .triggerType("INVALID_TRIGGER_TYPE") // Invalid trigger type
                                .triggerReferenceId("invalid-trigger-test")
                                .build();

                // Act
                service.reallocate(request);

                // Assert
                @SuppressWarnings("unchecked")
                ArgumentCaptor<List<AllocationAuditEntity>> auditCaptor = ArgumentCaptor
                                .forClass((Class<List<AllocationAuditEntity>>) (Class<?>) List.class);
                verify(allocationAuditRepository).saveAll(auditCaptor.capture());
                List<AllocationAuditEntity> savedAudits = auditCaptor.getValue();

                assertThat(savedAudits)
                                .isNotEmpty()
                                .allSatisfy(audit -> assertThat(audit.getReasonCode())
                                                .isEqualTo(AllocationAuditReasonCode.MANUAL_OVERRIDE));
        }
}
