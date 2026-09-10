package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.enums.InventorySerialStatus;
import com.positivity.inventory.internal.repository.InventorySerialUnitRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.tenancy.TenantIterator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scheduled report-only reconciliation of serial-derived on-hand against the
 * ledger read model
 * (odoo-parity E4, issue #1050; spec §6 E4 verifier) — the serial analogue of
 * {@link StockSummaryDriftVerifier}. For every (stockItemId, locationId) with
 * serial units, it
 * compares the count of {@code IN_STOCK} units to the lot-agnostic
 * {@code inventory_stock_summary}
 * on-hand and logs/counts any mismatch. NEVER mutates either table.
 *
 * <p>
 * A SERIAL-tracked SKU maintains the serial=quantity invariant on every
 * posting, so a drift
 * here means a serial row was tampered with or a posting bypassed the funnel —
 * an alerting signal,
 * not an auto-repair trigger.
 */
@Component
@Slf4j
public class SerialUnitOnHandVerifier {

    private final InventorySerialUnitRepository serialRepository;
    private final InventoryStockSummaryRepository summaryRepository;
    private final Counter driftCounter;

    /** ADR-0062 section 3: verified once per active tenant, each pass in its own read-only transaction. */
    private final TenantIterator tenantIterator;

    private final TransactionTemplate readOnlyTransaction;

    public SerialUnitOnHandVerifier(
            InventorySerialUnitRepository serialRepository,
            InventoryStockSummaryRepository summaryRepository,
            MeterRegistry meterRegistry,
            TenantIterator tenantIterator,
            PlatformTransactionManager transactionManager) {
        this.serialRepository = serialRepository;
        this.tenantIterator = tenantIterator;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
        this.summaryRepository = summaryRepository;
        this.driftCounter = meterRegistry.counter("inventory.serial_unit.drift.total");
    }

    @Scheduled(
            fixedDelayString = "${pos.inventory.serial-unit.verify-interval-ms:3600000}",
            initialDelayString = "${pos.inventory.serial-unit.verify-initial-delay-ms:600000}")
    public void verifyScheduled() {
        // The transaction opens inside the tenant binding, so its connection carries the tenant.
        tenantIterator.forEachActiveTenant(
                tenantId -> readOnlyTransaction.executeWithoutResult(status -> verifyForTenant()));
    }

    private void verifyForTenant() {
        try {
            verify();
        } catch (Exception ex) {
            // Report-only job: never let a verification failure escalate.
            log.error("Serial unit on-hand verification failed", ex);
        }
    }

    /**
     * Cap on per-key WARN lines per pass; the total always lands in the summary
     * ERROR + counter.
     */
    private static final int MAX_DETAILED_DRIFT_LOGS = 50;

    /**
     * Runs one full comparison pass and returns the number of drifted (stockItemId,
     * locationId)
     * keys: keys where the count of {@code IN_STOCK} serial units disagrees with
     * the lot-agnostic
     * summary on-hand.
     *
     * @return number of drifted keys
     */
    public int verify() {
        List<InventorySerialUnitRepository.SerialOnHandRow> rows =
                serialRepository.serialOnHandByStockItemAndLocation(InventorySerialStatus.IN_STOCK);

        int driftedKeys = 0;
        for (InventorySerialUnitRepository.SerialOnHandRow row : rows) {
            if (row.getLocationId() == null) {
                continue;
            }
            BigDecimal summaryOnHand = summaryRepository
                    .findByStockItemIdAndLocationId(row.getStockItemId(), row.getLocationId())
                    .map(InventoryStockSummary::getOnHand)
                    .map(Quantities::nz)
                    .orElse(BigDecimal.ZERO);
            // compareTo, not equals: a summary of 4.0000 read back from numeric(19,4) is the same
            // quantity as a serial count of 4, and equals would call that drift.
            if (summaryOnHand.compareTo(BigDecimal.valueOf(row.getInStockCount())) != 0) {
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
