package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.replenishment.ReplenishmentScanResultResponse;
import com.positivity.inventory.service.ReplenishmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled driver for the batch replenishment scan (CAP-217 / odoo-parity F1,
 * issue #1025), mirroring the {@code pos.inventory.stock-summary.*} scheduling
 * convention used by {@link StockSummaryDriftVerifier}.
 *
 * <p>Disabled by default; enable per environment with
 * {@code pos.inventory.replenishment.scan.enabled=true}. The scan itself is
 * idempotent per (policy, day), so an aggressive interval cannot create
 * duplicate open tasks.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "pos.inventory.replenishment.scan", name = "enabled", havingValue = "true")
public class ReplenishmentScanScheduler {

    private final ReplenishmentService replenishmentService;

    @Scheduled(
            fixedDelayString = "${pos.inventory.replenishment.scan.interval-ms:3600000}",
            initialDelayString = "${pos.inventory.replenishment.scan.initial-delay-ms:600000}")
    public void runScheduledScan() {
        try {
            ReplenishmentScanResultResponse result = replenishmentService.runBatchReplenishmentScan();
            log.info(
                    "Scheduled replenishment scan: policiesEvaluated={} tasksCreated={} tasksRefreshed={}",
                    result.getPoliciesEvaluated(),
                    result.getTasksCreated(),
                    result.getTasksRefreshed());
        } catch (Exception ex) {
            // Scheduled job: never let one failed pass escalate; the next pass retries.
            log.error("Scheduled replenishment scan failed", ex);
        }
    }
}
