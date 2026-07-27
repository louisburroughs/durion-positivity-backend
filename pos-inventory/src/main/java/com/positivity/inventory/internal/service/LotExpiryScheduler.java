package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.service.LotExpiryScanService.LotExpiryScanResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled driver for the daily lot-expiry scan (odoo-parity E3, issue #1047; spec §6 E3),
 * mirroring the {@code pos.inventory.replenishment.scan.*} scheduling convention used by
 * {@link ReplenishmentScanScheduler}.
 *
 * <p>Disabled by default; enable per environment with
 * {@code pos.inventory.lot.expiry-scan.enabled=true}. The scan is idempotent (emit-once via the
 * lot's {@code expired_alerted_at}/{@code expiring_alerted_at} stamps), so an aggressive interval
 * cannot re-emit an already-alerted transition. Default interval is daily.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "pos.inventory.lot.expiry-scan", name = "enabled", havingValue = "true")
public class LotExpiryScheduler {

    private final LotExpiryScanService lotExpiryScanService;

    @Scheduled(
            fixedDelayString = "${pos.inventory.lot.expiry-scan.interval-ms:86400000}",
            initialDelayString = "${pos.inventory.lot.expiry-scan.initial-delay-ms:600000}")
    public void runScheduledScan() {
        try {
            LotExpiryScanResult result = lotExpiryScanService.runExpiryScan();
            log.info(
                    "Scheduled lot expiry scan: expiredAlerted={} expiringAlerted={}",
                    result.expiredAlerted(),
                    result.expiringAlerted());
        } catch (Exception ex) {
            // Scheduled job: never let one failed pass escalate; the next pass retries.
            log.error("Scheduled lot expiry scan failed", ex);
        }
    }
}
