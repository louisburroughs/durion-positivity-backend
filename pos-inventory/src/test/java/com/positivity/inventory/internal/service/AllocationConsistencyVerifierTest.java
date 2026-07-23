package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.inventory.internal.entity.AllocationEntity;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.entity.ReservationEntity;
import com.positivity.inventory.internal.enums.AllocationState;
import com.positivity.inventory.internal.enums.AllocationStatus;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.ReservationStatus;
import com.positivity.inventory.internal.repository.AllocationRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.ReservationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Reservation-consistency sweep tests (odoo-parity K3, issue #1032): the
 * verifier detects per-allocation ledger drift (invariant A) and per-location
 * summary {@code allocated} drift (invariant B), increments the violation
 * counter, and never mutates anything. Fixtures seed inconsistency via direct
 * repository access — allowed in tests; the ArchUnit posting-funnel rule only
 * covers main classes ({@code ImportOption.DoNotIncludeTests}).
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Allocation consistency sweep")
class AllocationConsistencyVerifierTest {

    @Autowired
    private AllocationConsistencyVerifier verifier;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private AllocationRepository allocationRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private InventoryLedgerEntryRepository ledgerRepository;

    @Autowired
    private InventoryStockSummaryRepository summaryRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void cleanSlate() {
        // The sweep asserts whole-table invariants; start from empty tables.
        allocationRepository.deleteAll();
        reservationRepository.deleteAll();
        summaryRepository.deleteAll();
        ledgerRepository.deleteAll();
    }

    private double violationCount() {
        return meterRegistry
                .counter("inventory.allocation.consistency.violations.total")
                .count();
    }

    private AllocationEntity persistAllocation(
            UUID stockItemId, UUID locationId, int quantity, AllocationState state, AllocationStatus status) {
        ReservationEntity reservation = reservationRepository.save(ReservationEntity.builder()
                .workorderLineId(UUID.randomUUID())
                .stockItemId(stockItemId)
                .requiredQuantity(quantity)
                .allocatedQuantity(quantity)
                .status(ReservationStatus.PENDING)
                .build());
        return allocationRepository.save(AllocationEntity.builder()
                .reservation(reservation)
                .locationId(locationId)
                .allocatedQuantity(quantity)
                .allocationState(state)
                .status(status)
                .build());
    }

    private void postAllocationEvent(
            InventoryLedgerEventType type, UUID stockItemId, UUID locationId, String sourceTransactionId, int qty) {
        ledgerPostingService.post(InventoryLedgerEntry.builder()
                .stockItemId(stockItemId.toString())
                .locationId(locationId)
                .eventType(type)
                .changeInQuantity(qty)
                .quantityAfter(0)
                .transactionUserId("consistency-test")
                .sourceTransactionId(sourceTransactionId)
                .build());
    }

    // ─── Clean state ──────────────────────────────────────────────────────────

    @Test
    void consistentWorld_reportsZeroFindings() {
        UUID sku = UUID.randomUUID();
        UUID location = UUID.randomUUID();

        // SOFT allocation: no ledger footprint expected.
        persistAllocation(sku, null, 4, AllocationState.SOFT, AllocationStatus.ALLOCATED);

        // Outstanding located HARD allocation: CREATED == quantity.
        AllocationEntity outstanding =
                persistAllocation(sku, location, 5, AllocationState.HARD, AllocationStatus.ALLOCATED);
        postAllocationEvent(
                InventoryLedgerEventType.ALLOCATION_CREATED,
                sku,
                location,
                outstanding.getAllocationId().toString(),
                5);

        // Partially consumed located HARD allocation: 0 < released < created.
        AllocationEntity partial =
                persistAllocation(sku, location, 6, AllocationState.HARD, AllocationStatus.ALLOCATED);
        postAllocationEvent(
                InventoryLedgerEventType.ALLOCATION_CREATED,
                sku,
                location,
                partial.getAllocationId().toString(),
                6);
        postAllocationEvent(
                InventoryLedgerEventType.ALLOCATION_RELEASED,
                sku,
                location,
                partial.getAllocationId().toString(),
                2);

        // Fully released located HARD allocation: released == created.
        AllocationEntity released =
                persistAllocation(sku, location, 3, AllocationState.HARD, AllocationStatus.RELEASED);
        postAllocationEvent(
                InventoryLedgerEventType.ALLOCATION_CREATED,
                sku,
                location,
                released.getAllocationId().toString(),
                3);
        postAllocationEvent(
                InventoryLedgerEventType.ALLOCATION_RELEASED,
                sku,
                location,
                released.getAllocationId().toString(),
                3);

        double before = violationCount();

        assertThat(verifier.verify()).isZero();
        assertThat(violationCount()).isEqualTo(before);
    }

    // ─── Invariant A ──────────────────────────────────────────────────────────

    @Test
    void hardLocatedAllocationWithoutLedgerFootprint_isDetected_withoutMutation() {
        UUID sku = UUID.randomUUID();
        AllocationEntity broken =
                persistAllocation(sku, UUID.randomUUID(), 5, AllocationState.HARD, AllocationStatus.ALLOCATED);

        double before = violationCount();
        long ledgerRowsBefore = ledgerRepository.count();

        assertThat(verifier.verify()).isEqualTo(1);
        assertThat(violationCount()).isEqualTo(before + 1);

        // Report-only: nothing was repaired or written.
        AllocationEntity untouched =
                allocationRepository.findById(broken.getAllocationId()).orElseThrow();
        assertThat(untouched.getAllocationState()).isEqualTo(AllocationState.HARD);
        assertThat(untouched.getStatus()).isEqualTo(AllocationStatus.ALLOCATED);
        assertThat(untouched.getAllocatedQuantity()).isEqualTo(5);
        assertThat(ledgerRepository.count()).isEqualTo(ledgerRowsBefore);
        assertThat(summaryRepository.findAll()).isEmpty();

        // Still inconsistent on the next pass — detection is repeatable.
        assertThat(verifier.verify()).isEqualTo(1);
    }

    @Test
    void fullyReleasedLedgerWithStaleAllocatedStatus_isDetected() {
        UUID sku = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        AllocationEntity stale = persistAllocation(sku, location, 5, AllocationState.HARD, AllocationStatus.ALLOCATED);
        postAllocationEvent(
                InventoryLedgerEventType.ALLOCATION_CREATED,
                sku,
                location,
                stale.getAllocationId().toString(),
                5);
        postAllocationEvent(
                InventoryLedgerEventType.ALLOCATION_RELEASED,
                sku,
                location,
                stale.getAllocationId().toString(),
                5);

        // Ledger outstanding is 0 and summary agrees, so this is purely an
        // invariant-A (status lag) finding.
        assertThat(verifier.verify()).isEqualTo(1);
    }

    @Test
    void orphanAllocationEventsWithOutstanding_areDetected() {
        UUID sku = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        // ALLOCATION_CREATED pointing at an id with no allocation row: the
        // funnel keeps summary.allocated in sync, so invariant B stays clean
        // and only the orphan branch fires.
        postAllocationEvent(
                InventoryLedgerEventType.ALLOCATION_CREATED,
                sku,
                location,
                UUID.randomUUID().toString(),
                3);

        assertThat(verifier.verify()).isEqualTo(1);
    }

    // ─── Invariant B ──────────────────────────────────────────────────────────

    @Test
    void summaryAllocatedDrift_isDetected_withoutMutation() {
        UUID sku = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        AllocationEntity allocation =
                persistAllocation(sku, location, 5, AllocationState.HARD, AllocationStatus.ALLOCATED);
        postAllocationEvent(
                InventoryLedgerEventType.ALLOCATION_CREATED,
                sku,
                location,
                allocation.getAllocationId().toString(),
                5);

        assertThat(verifier.verify()).isZero();

        // Corrupt the summary out-of-band (simulating a bypassing write).
        InventoryStockSummary row = summaryRepository
                .findByStockItemIdAndLocationId(sku.toString(), location)
                .orElseThrow();
        row.setAllocated(999);
        summaryRepository.save(row);

        double before = violationCount();
        assertThat(verifier.verify()).isEqualTo(1);
        assertThat(violationCount()).isEqualTo(before + 1);

        // Report-only: the corrupted value must still be there.
        assertThat(summaryRepository
                        .findByStockItemIdAndLocationId(sku.toString(), location)
                        .orElseThrow()
                        .getAllocated())
                .isEqualTo(999);
    }
}
