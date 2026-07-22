package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
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

    private final InventoryLedgerEntryRepository ledgerRepository;
    private final InventoryStockSummaryRepository summaryRepository;
    private final Counter driftCounter;

    public StockSummaryDriftVerifier(
            InventoryLedgerEntryRepository ledgerRepository,
            InventoryStockSummaryRepository summaryRepository,
            MeterRegistry meterRegistry) {
        this.ledgerRepository = ledgerRepository;
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

    /**
     * Runs one full comparison pass and returns the number of drifted keys.
     */
    @Transactional(readOnly = true)
    public int verify() {
        Map<Key, InventoryLedgerEntryRepository.SummaryAggregate> ledgerTruth = new HashMap<>();
        for (InventoryLedgerEntryRepository.SummaryAggregate aggregate : ledgerRepository.aggregateForStockSummary(
                InventoryLedgerEventType.onHandAffectingTypes(),
                InventoryLedgerEventType.ALLOCATION_CREATED,
                InventoryLedgerEventType.ALLOCATION_RELEASED,
                InventoryLedgerEventType.RESERVATION_CREATED,
                InventoryLedgerEventType.RESERVATION_RELEASED,
                StockSummaryEventSets.SUMMARY_TYPES)) {
            ledgerTruth.put(new Key(aggregate.getStockItemId(), aggregate.getLocationId()), aggregate);
        }

        int driftedKeys = 0;
        List<InventoryStockSummary> summaryRows = summaryRepository.findAll();
        for (InventoryStockSummary row : summaryRows) {
            Key key = new Key(row.getStockItemId(), row.getLocationId());
            InventoryLedgerEntryRepository.SummaryAggregate truth = ledgerTruth.remove(key);
            long expectedOnHand = truth == null ? 0L : truth.getOnHand();
            long expectedAllocated = truth == null ? 0L : truth.getAllocated();
            long expectedReserved = truth == null ? 0L : truth.getReserved();
            if (row.getOnHand() != expectedOnHand
                    || row.getAllocated() != expectedAllocated
                    || row.getReserved() != expectedReserved
                    || row.getAtp() != expectedOnHand - expectedAllocated) {
                driftedKeys++;
                logDrift(
                        key,
                        expectedOnHand,
                        expectedAllocated,
                        expectedReserved,
                        row.getOnHand(),
                        row.getAllocated(),
                        row.getReserved());
            }
        }

        // Remaining ledger keys have no summary row at all: drift unless they net to zero.
        for (Map.Entry<Key, InventoryLedgerEntryRepository.SummaryAggregate> missing : ledgerTruth.entrySet()) {
            InventoryLedgerEntryRepository.SummaryAggregate truth = missing.getValue();
            if (truth.getOnHand() != 0 || truth.getAllocated() != 0 || truth.getReserved() != 0) {
                driftedKeys++;
                logDrift(
                        missing.getKey(),
                        truth.getOnHand(),
                        truth.getAllocated(),
                        truth.getReserved(),
                        null,
                        null,
                        null);
            }
        }

        if (driftedKeys > 0) {
            driftCounter.increment(driftedKeys);
            log.error(
                    "Stock summary drift detected: driftedKeys={} summaryRows={} — summary is stale;"
                            + " run StockSummaryRebuildService.rebuildFromLedger() in a quiet window",
                    driftedKeys,
                    summaryRows.size());
        } else {
            log.debug("Stock summary drift verification clean: summaryRows={}", summaryRows.size());
        }
        return driftedKeys;
    }

    @SuppressWarnings("java:S107")
    private void logDrift(
            Key key,
            long expectedOnHand,
            long expectedAllocated,
            long expectedReserved,
            @Nullable Long actualOnHand,
            @Nullable Long actualAllocated,
            @Nullable Long actualReserved) {
        log.warn(
                "Stock summary drift: stockItemId={} locationId={} ledgerOnHand={} ledgerAllocated={}"
                        + " ledgerReserved={} summaryOnHand={} summaryAllocated={} summaryReserved={}",
                key.stockItemId(),
                key.locationId(),
                expectedOnHand,
                expectedAllocated,
                expectedReserved,
                actualOnHand,
                actualAllocated,
                actualReserved);
    }

    private record Key(String stockItemId, @Nullable UUID locationId) {
        private Key {
            Objects.requireNonNull(stockItemId, "stockItemId");
        }
    }
}
