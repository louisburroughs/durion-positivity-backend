package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled report-only drift check for the stock summary read model (issue
 * #1024, A1): compares every {@code inventory_stock_summary} row against
 * {@code SUM(ledger)} per (stockItemId, locationId) and logs/counts
 * mismatches. NEVER mutates either table — repair is a manual
 * {@link StockSummaryRebuildService#rebuildFromLedger()}.
 */
@Component
@Slf4j
public class StockSummaryDriftVerifier {

    private final InventoryStockSummaryRepository summaryRepository;
    private final Counter driftCounter;

    public StockSummaryDriftVerifier(InventoryStockSummaryRepository summaryRepository, MeterRegistry meterRegistry) {
        this.summaryRepository = summaryRepository;
        this.driftCounter = meterRegistry.counter("inventory.stock_summary.drift.total");
    }

    @Scheduled(
            fixedDelayString = "${pos.inventory.stock-summary.verify-interval-ms:3600000}",
            initialDelayString = "${pos.inventory.stock-summary.verify-initial-delay-ms:600000}")
    public void verifyScheduled() {
        try {
            verify();
        } catch (Exception ex) {
            // Report-only job: never let a verification failure escalate.
            log.error("Stock summary drift verification failed", ex);
        }
    }

    /** Cap on per-key WARN lines per pass; the total always lands in the summary ERROR + counter. */
    private static final int MAX_DETAILED_DRIFT_LOGS = 50;

    /**
     * Runs one full comparison pass and returns the number of drifted keys.
     *
     * <p>The diff is computed set-based in the database
     * ({@link InventoryStockSummaryRepository#findDriftRows}) and returns only
     * mismatching keys, so this job holds no table-sized state in application
     * memory regardless of catalog × location cardinality.
     */
    @Transactional(readOnly = true)
    public int verify() {
        List<String> onHandTypes = InventoryLedgerEventType.onHandAffectingTypes().stream()
                .map(Enum::name)
                .toList();
        List<String> summaryTypes =
                StockSummaryEventSets.SUMMARY_TYPES.stream().map(Enum::name).toList();

        List<InventoryStockSummaryRepository.DriftRow> drifted = summaryRepository.findDriftRows(
                onHandTypes,
                InventoryLedgerEventType.ALLOCATION_CREATED.name(),
                InventoryLedgerEventType.ALLOCATION_RELEASED.name(),
                InventoryLedgerEventType.RESERVATION_CREATED.name(),
                InventoryLedgerEventType.RESERVATION_RELEASED.name(),
                InventoryLedgerEventType.TRANSFER_IN.name(),
                InventoryLedgerEventType.TRANSFER_OUT.name(),
                summaryTypes);

        int driftedKeys = drifted.size();
        for (int i = 0; i < driftedKeys && i < MAX_DETAILED_DRIFT_LOGS; i++) {
            logDrift(drifted.get(i));
        }

        if (driftedKeys > 0) {
            driftCounter.increment(driftedKeys);
            log.error(
                    "Stock summary drift detected: driftedKeys={} (detailed above, capped at {}) — summary is"
                            + " stale; run StockSummaryRebuildService.rebuildFromLedger() in a quiet window",
                    driftedKeys,
                    MAX_DETAILED_DRIFT_LOGS);
        } else {
            log.debug("Stock summary drift verification clean");
        }
        return driftedKeys;
    }

    private void logDrift(InventoryStockSummaryRepository.DriftRow row) {
        log.warn(
                "Stock summary drift: stockItemId={} locationId={} lotId={} ledgerOnHand={} ledgerAllocated={}"
                        + " ledgerReserved={} ledgerInTransit={} summaryOnHand={} summaryAllocated={}"
                        + " summaryReserved={} summaryInTransit={}",
                row.getStockItemId(),
                row.getLocationId(),
                row.getLotId(),
                row.getLedgerOnHand(),
                row.getLedgerAllocated(),
                row.getLedgerReserved(),
                row.getLedgerInTransit(),
                row.getSummaryOnHand(),
                row.getSummaryAllocated(),
                row.getSummaryReserved(),
                row.getSummaryInTransit());
    }
}
