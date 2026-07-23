package com.positivity.order.internal.client;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Advisory {@link InventoryPort} (ADR-0044 domain wall, PR #1089 resolution): pos-inventory is a
 * domain module, so pos-order may not query it synchronously. Until an event-fed availability
 * replica exists (parity story H2 delivers the order→inventory consumption feed; an
 * availability-snapshot feed is its natural follow-up), availability reports sufficient — which
 * matches the WARN_AND_BACKORDER policy's spirit: availability was always advisory, never a
 * blocker, and stock truth reconciles at consumption time.
 */
@Component
@Slf4j
public class AdvisoryInventoryPortAdapter implements InventoryPort {

    @Override
    public @NonNull InventoryResult checkAvailability(@NonNull String itemSku, int quantity) {
        log.debug(
                "Advisory availability for sku {} qty {}: no replica feed yet, reporting sufficient",
                itemSku,
                quantity);
        return new InventoryResult(true, quantity);
    }
}
