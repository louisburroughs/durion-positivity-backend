package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.enums.InventorySerialStatus;
import com.positivity.inventory.internal.repository.InventorySerialUnitRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled report-only reconciliation of serial-derived on-hand against the ledger read model
 * (odoo-parity E4, issue #1050; spec §6 E4 verifier) — the serial analogue of
 * {@link StockSummaryDriftVerifier}. For every (stockItemId, locationId) with serial units, it
 * compares the count of {@code IN_STOCK} units to the lot-agnostic {@code inventory_stock_summary}
 * on-hand and logs/counts any mismatch. NEVER mutates either table.
 *
 * <p>A SERIAL-tracked SKU maintains the serial=quantity invariant on every posting, so a drift
 * here means a serial row was tampered with or a posting bypassed the funnel — an alerting signal,
 * not an auto-repair trigger.
 */
@Component
@Slf4j
public class SerialUnitOnHandVerifier {

    private final InventorySerialUnitRepository serialRepository;
    private final InventoryStockSummaryRepository summaryRepository;
    private final Counter driftCounter;

    public SerialUnitOnHandVerifier(
            InventorySerialUnitRepository serialRepository,
            InventoryStockSummaryRepository summaryRepository,
            MeterRegistry meterRegistry) {
        this.serialRepository = serialRepository;
        this.summaryRepository = summaryRepository;
        this.driftCounter = meterRegistry.counter("inventory.serial_unit.drift.total");
    }

    @Scheduled(
            fixedDelayString = "${pos.inventory.serial-unit.verify-interval-ms:3600000}",
            initialDelayString = "${pos.inventory.serial-unit.verify-initial-delay-ms:600000}")
    public void verifyScheduled() {
        try {
            verify();
        } catch (Exception ex) {
            // Report-only job: never let a verification failure escalate.
            log.error("Serial unit on-hand verification failed", ex);
        }
    }

    /** Cap on per-key WARN lines per pass; the total always lands in the summary ERROR + counter. */
    private static final int MAX_DETAILED_DRIFT_LOGS = 50;

    /**
     * Runs one full comparison pass and returns the number of drifted (stockItemId, locationId)
     * keys: keys where the count of {@code IN_STOCK} serial units disagrees with the lot-agnostic
     * summary on-hand.
     *
     * @return number of drifted keys
     */
    @Transactional(readOnly = true)
    public int verify() {
        List<InventorySerialUnitRepository.SerialOnHandRow> rows =
                serialRepository.serialOnHandByStockItemAndLocation(InventorySerialStatus.IN_STOCK);

        int driftedKeys = 0;
        for (InventorySerialUnitRepository.SerialOnHandRow row : rows) {
            if (row.getLocationId() == null) {
                continue;
            }
            long summaryOnHand = summaryRepository
                    .findByStockItemIdAndLocationId(row.getStockItemId(), row.getLocationId())
                    .map(summary -> summary.getOnHand())
                    .orElse(0L);
            if (summaryOnHand != row.getInStockCount()) {
                if (driftedKeys < MAX_DETAILED_DRIFT_LOGS) {
                    log.warn(
                            "Serial on-hand drift: stockItemId={} locationId={} serialInStock={} summaryOnHand={}",
                            row.getStockItemId(),
                            row.getLocationId(),
                            row.getInStockCount(),
                            summaryOnHand);
                }
                driftedKeys++;
            }
        }

        if (driftedKeys > 0) {
            driftCounter.increment(driftedKeys);
            log.error(
                    "Serial unit on-hand drift detected: driftedKeys={} (detailed above, capped at {}) — a serial row"
                            + " was tampered with or a posting bypassed the funnel",
                    driftedKeys,
                    MAX_DETAILED_DRIFT_LOGS);
        } else {
            log.debug("Serial unit on-hand verification clean");
        }
        return driftedKeys;
    }
}
