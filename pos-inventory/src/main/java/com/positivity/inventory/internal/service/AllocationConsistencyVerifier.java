package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.AllocationRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled report-only reservation-consistency sweep (odoo-parity K3, issue
 * #1032; Odoo {@code _clean_reservations} analog — minus the "clean" part:
 * this job NEVER mutates anything). Corrections are new ledger entries per
 * DECISION-INVENTORY-005, applied by a human. Mirrors
 * {@link StockSummaryDriftVerifier}.
 *
 * <p><b>Invariant A — per allocation.</b> Allocation ledger events carry the
 * allocation id as {@code sourceTransactionId} ({@code ReservationServiceImpl.
 * writeAllocationLedgerEntry}, {@code ConsumptionServiceImpl.closeAllocations});
 * both {@code ALLOCATION_CREATED} and {@code ALLOCATION_RELEASED} are recorded
 * with positive {@code changeInQuantity}, so per-allocation outstanding is
 * {@code Σcreated − Σreleased}. Expected footprint, derived from the writers:
 *
 * <table border="1">
 *   <caption>Expected ledger footprint per allocation state</caption>
 *   <tr><th>allocationState / locationId / status</th><th>created</th><th>released</th><th>outstanding</th></tr>
 *   <tr><td>SOFT (any status) or locationId null</td><td>0</td><td>0</td><td>0 — only a located HARD
 *       promotion writes ALLOCATION_CREATED, and cancel/consume release only located HARD rows</td></tr>
 *   <tr><td>HARD, located, ALLOCATED or PICKED</td><td>allocatedQuantity (written exactly once at
 *       promote)</td><td>[0, created)</td><td>(0, allocatedQuantity] — partial consumption may release
 *       part while the row stays ALLOCATED; full release always flips the status to RELEASED</td></tr>
 *   <tr><td>HARD, located, RELEASED</td><td>allocatedQuantity</td><td>== created</td><td>0 —
 *       cancel releases the un-consumed remainder, consumption releases the rest</td></tr>
 *   <tr><td>orphan ledger key (no matching allocation row, or null key)</td>
 *       <td colspan="3">created must equal released; a non-zero outstanding is unaccounted stock</td></tr>
 * </table>
 *
 * <p>Note the spec's shorthand "outstanding ∈ {0, allocatedQuantity}" is
 * refined here: partial consumption (CAP-218 #662) legitimately leaves
 * outstanding strictly between 0 and allocatedQuantity while the allocation
 * is still open, so the verifier checks the per-state table above instead of
 * the two-point set.
 *
 * <p><b>Invariant B — per location.</b> The stock summary {@code allocated}
 * column (maintained by {@code LedgerPostingServiceImpl}) must equal ledger
 * outstanding allocations ({@code Σ ALLOCATION_CREATED − Σ
 * ALLOCATION_RELEASED}) per location — the set-based twin of
 * {@code InventoryLedgerEntryRepository#calculateOutstandingAllocationsByLocation}.
 *
 * <p>Both diffs run entirely in SQL and return only violating keys
 * ({@link AllocationRepository#findConsistencyViolations},
 * {@link InventoryStockSummaryRepository#findAllocatedDriftByLocation}), so
 * the job holds no table-sized state in application memory. Findings are
 * WARN-logged (capped) and counted on
 * {@code inventory.allocation.consistency.violations.total}.
 */
@Component
@Slf4j
public class AllocationConsistencyVerifier {

    /** Cap on per-key WARN lines per pass; the total always lands in the summary ERROR + counter. */
    private static final int MAX_DETAILED_VIOLATION_LOGS = 50;

    private final AllocationRepository allocationRepository;
    private final InventoryStockSummaryRepository summaryRepository;
    private final Counter violationCounter;

    public AllocationConsistencyVerifier(
            AllocationRepository allocationRepository,
            InventoryStockSummaryRepository summaryRepository,
            MeterRegistry meterRegistry) {
        this.allocationRepository = allocationRepository;
        this.summaryRepository = summaryRepository;
        this.violationCounter = meterRegistry.counter("inventory.allocation.consistency.violations.total");
    }

    @Scheduled(
            fixedDelayString = "${pos.inventory.allocation-verify.interval-ms:3600000}",
            initialDelayString = "${pos.inventory.allocation-verify.initial-delay-ms:600000}")
    public void verifyScheduled() {
        try {
            verify();
        } catch (Exception ex) {
            // Report-only job: never let a verification failure escalate.
            log.error("Allocation consistency verification failed", ex);
        }
    }

    /** Runs one full sweep and returns the number of violations (both invariants). */
    @Transactional(readOnly = true)
    public int verify() {
        String created = InventoryLedgerEventType.ALLOCATION_CREATED.name();
        String released = InventoryLedgerEventType.ALLOCATION_RELEASED.name();

        List<AllocationRepository.AllocationConsistencyRow> perAllocation =
                allocationRepository.findConsistencyViolations(created, released);
        List<InventoryStockSummaryRepository.AllocatedDriftRow> perLocation =
                summaryRepository.findAllocatedDriftByLocation(created, released);

        int logged = 0;
        for (AllocationRepository.AllocationConsistencyRow row : perAllocation) {
            if (logged++ >= MAX_DETAILED_VIOLATION_LOGS) {
                break;
            }
            log.warn(
                    "Allocation ledger inconsistency: allocationId={} state={} status={} allocatedQuantity={}"
                            + " ledgerCreated={} ledgerReleased={} outstanding={}",
                    row.getAllocationId(),
                    row.getAllocationState(),
                    row.getStatus(),
                    row.getAllocatedQuantity(),
                    row.getLedgerCreated(),
                    row.getLedgerReleased(),
                    row.getLedgerCreated() - row.getLedgerReleased());
        }
        for (InventoryStockSummaryRepository.AllocatedDriftRow row : perLocation) {
            if (logged++ >= MAX_DETAILED_VIOLATION_LOGS) {
                break;
            }
            log.warn(
                    "Summary allocated drift: locationId={} ledgerOutstanding={} summaryAllocated={}",
                    row.getLocationId(),
                    row.getLedgerOutstanding(),
                    row.getSummaryAllocated());
        }

        int violations = perAllocation.size() + perLocation.size();
        if (violations > 0) {
            violationCounter.increment(violations);
            log.error(
                    "Allocation consistency violations detected: perAllocation={} perLocation={} (detailed above,"
                            + " capped at {}) — investigate per docs/allocation-consistency.md; corrections are new"
                            + " ledger entries (DECISION-INVENTORY-005), never edits",
                    perAllocation.size(),
                    perLocation.size(),
                    MAX_DETAILED_VIOLATION_LOGS);
        } else {
            log.debug("Allocation consistency sweep clean");
        }
        return violations;
    }
}
